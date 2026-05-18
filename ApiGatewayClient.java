package com.nonstop.proxy.handler;

import com.nonstop.proxy.model.IpcRequest;
import com.nonstop.proxy.model.IpcResponse;
import com.nonstop.proxy.util.Logger;
import com.nonstop.proxy.util.SslContextFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Cliente HTTP que traduce IpcRequest → llamada HTTPS → IpcResponse.
 *
 * Características:
 *  - TLS 1.2 + TLS 1.3 via SslContextFactory
 *  - Timeouts configurables por variable de entorno
 *  - Errores HTTP 5xx y timeouts son retryable; 4xx no lo son
 *  - Cabeceras de trazabilidad end-to-end (X-Correlation-Id)
 *  - Compatible JDK 11
 *
 * Variables de entorno (definir en PATHCOM):
 *   PROXY_API_BASE_URL          URL base del API Gateway (obligatoria)
 *   PROXY_API_KEY               API Key de autenticación (obligatoria)
 *   PROXY_CONNECT_TIMEOUT_MS    Timeout TCP (default: 5000)
 *   PROXY_REQUEST_TIMEOUT_MS    Timeout HTTP request (default: 30000)
 *   PROXY_TRUSTSTORE_PATH       JKS con CA privada (opcional)
 *   PROXY_TRUSTSTORE_PASSWORD   Contraseña del truststore (opcional)
 *   PROXY_KEYSTORE_PATH         JKS cliente para mTLS (opcional)
 *   PROXY_KEYSTORE_PASSWORD     Contraseña del keystore (opcional)
 */
public class ApiGatewayClient {

    private static final Set<Integer> RETRYABLE_HTTP_CODES =
        new HashSet<>(Arrays.asList(429, 500, 502, 503, 504));

    private final HttpClient httpClient;
    private final String     baseUrl;
    private final String     apiKey;
    private final Logger     logger;
    private final Duration   requestTimeout;

    public ApiGatewayClient() {
        this.logger = new Logger("ApiGatewayClient");

        this.baseUrl = getEnvOrFail("PROXY_API_BASE_URL");
        this.apiKey  = getEnvOrFail("PROXY_API_KEY");

        long connectMs = parseLong("PROXY_CONNECT_TIMEOUT_MS", 5000);
        long requestMs = parseLong("PROXY_REQUEST_TIMEOUT_MS", 30000);
        this.requestTimeout = Duration.ofMillis(requestMs);

        SSLContext    sslContext = SslContextFactory.createFromEnv();
        SSLParameters sslParams  = SslContextFactory.tlsParameters();

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectMs))
            .version(HttpClient.Version.HTTP_1_1)
            .sslContext(sslContext)
            .sslParameters(sslParams)
            .build();

        logger.info(String.format(
            "ApiGatewayClient inicializado. baseUrl=%s, connectTimeout=%dms, requestTimeout=%dms",
            baseUrl, connectMs, requestMs));
    }

    /**
     * Expone el HttpClient para que KeepAliveService reutilice el pool de conexiones TLS.
     */
    public HttpClient getHttpClient() {
        return httpClient;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    // ----------------------------------------------------------
    //  Conversión IpcRequest → HTTP → IpcResponse
    // ----------------------------------------------------------

    /**
     * Convierte el buffer IPC (ya deserializado en IpcRequest) en una llamada HTTPS
     * y empaqueta el resultado en IpcResponse listo para serializar al buffer de REPLY.
     */
    public IpcResponse call(IpcRequest request) throws ApiException {
        String fullUrl = buildUrl(request);
        logger.info(String.format("HTTP %s %s (correlationId=%s)",
            request.getHttpMethod(), fullUrl, request.getCorrelationId()));

        HttpRequest httpRequest = buildHttpRequest(request, fullUrl);

        try {
            HttpResponse<String> httpResponse =
                httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            int    statusCode = httpResponse.statusCode();
            String body       = httpResponse.body();

            logger.info(String.format("Respuesta API: HTTP %d, bodyLen=%d (correlationId=%s)",
                statusCode, body != null ? body.length() : 0, request.getCorrelationId()));

            // 4xx: error del caller XPNET, no reintentar
            if (statusCode >= 400 && statusCode < 500) {
                throw new ApiException(
                    "API retornó error de cliente: " + statusCode, statusCode, false);
            }

            // 5xx: error del servidor, potencialmente retryable
            if (statusCode >= 500) {
                throw new ApiException(
                    "API retornó error de servidor: " + statusCode,
                    statusCode,
                    RETRYABLE_HTTP_CODES.contains(statusCode));
            }

            // 2xx: éxito — el body JSON se cargará en el buffer de respuesta IPC
            return IpcResponse.success(request.getCorrelationId(), statusCode, body);

        } catch (HttpTimeoutException e) {
            throw new ApiException(
                "Timeout al llamar API (" + requestTimeout.toMillis() + "ms): " + fullUrl,
                0, true);

        } catch (IOException e) {
            throw new ApiException(
                "Error de red/IO: " + e.getMessage(), 0, true);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Llamada HTTP interrumpida", 0, false);
        }
    }

    // ----------------------------------------------------------
    //  Helpers privados
    // ----------------------------------------------------------

    private String buildUrl(IpcRequest request) {
        String path = request.getOperation();
        if (!path.startsWith("/")) path = "/" + path;
        return baseUrl + path;
    }

    /** Construye el HttpRequest según el msgType del buffer IPC. Compatible JDK 11. */
    private HttpRequest buildHttpRequest(IpcRequest request, String url) throws ApiException {
        String method  = request.getHttpMethod();
        String payload = request.getPayload() != null ? request.getPayload() : "{}";

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(requestTimeout)
            .header("Content-Type",     "application/json")
            .header("Accept",           "application/json")
            .header("X-API-Key",        apiKey)
            .header("X-Correlation-Id", request.getCorrelationId())
            .header("X-Source-System",  "NONSTOP-XPNET");

        switch (method) {
            case "GET":
                builder.GET();
                break;
            case "POST":
                builder.POST(HttpRequest.BodyPublishers.ofString(payload));
                break;
            case "PUT":
                builder.PUT(HttpRequest.BodyPublishers.ofString(payload));
                break;
            case "DELETE":
                builder.DELETE();
                break;
            default:
                throw new ApiException("Método HTTP no soportado: " + method, 0, false);
        }

        return builder.build();
    }

    private String getEnvOrFail(String varName) {
        String value = System.getenv(varName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Variable de entorno requerida no definida: " + varName
                + ". Configurar en PATHCOM: SET SERVERCLASS PROXYSRV ENV \""
                + varName + "=...\"");
        }
        return value;
    }

    private static long parseLong(String envVar, long defaultVal) {
        String val = System.getenv(envVar);
        if (val != null && !val.isEmpty()) {
            try { return Long.parseLong(val); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    // ----------------------------------------------------------
    //  Excepción tipada para diferenciar errores retryable
    // ----------------------------------------------------------
    public static class ApiException extends Exception {
        private final int     httpStatus;
        private final boolean retryable;

        public ApiException(String message, int httpStatus, boolean retryable) {
            super(message);
            this.httpStatus = httpStatus;
            this.retryable  = retryable;
        }

        public int     getHttpStatus() { return httpStatus; }
        public boolean isRetryable()   { return retryable; }
    }
}
