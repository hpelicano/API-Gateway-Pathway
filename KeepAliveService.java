package com.nonstop.proxy.handler;

import com.nonstop.proxy.util.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Envía pings periódicos al endpoint de salud del API Gateway externo.
 *
 * Propósitos:
 *  - Detectar tempranamente si el API Gateway está caído antes de recibir
 *    un mensaje XPNET real (fail-fast con log proactivo).
 *  - Mantener activas las conexiones HTTP keep-alive del HttpClient pool.
 *  - Proveer métricas de disponibilidad del API Gateway en los logs EMS.
 *
 * Variables de entorno:
 *   PROXY_HEALTH_PATH           Path del endpoint de salud (default: /health)
 *   PROXY_KEEPALIVE_INTERVAL_S  Intervalo en segundos (default: 30)
 *   PROXY_KEEPALIVE_TIMEOUT_MS  Timeout del ping en ms (default: 5000)
 *   PROXY_KEEPALIVE_MAX_FAILS   Fallos consecutivos antes de log ERROR (default: 3)
 */
public class KeepAliveService {

    private static final int DEFAULT_INTERVAL_S   = 30;
    private static final int DEFAULT_TIMEOUT_MS   = 5000;
    private static final int DEFAULT_MAX_FAILS    = 3;

    private final HttpClient               httpClient;
    private final String                   healthUrl;
    private final String                   apiKey;
    private final Duration                 pingTimeout;
    private final int                      maxConsecutiveFails;
    private final Logger                   logger;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger            consecutiveFails;

    /**
     * @param httpClient  El mismo cliente HTTP compartido con ApiGatewayClient
     *                    (reutiliza el pool de conexiones TLS).
     * @param baseUrl     URL base del API Gateway (ej: https://api.mygateway.com/v1)
     * @param apiKey      API Key para el header X-API-Key
     */
    public KeepAliveService(HttpClient httpClient, String baseUrl, String apiKey) {
        this.httpClient  = httpClient;
        this.apiKey      = apiKey;
        this.logger      = new Logger("KeepAliveService");
        this.scheduler   = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "keepalive-thread");
            t.setDaemon(true); // no bloquea el shutdown del proceso Pathway
            return t;
        });
        this.consecutiveFails = new AtomicInteger(0);

        String healthPath = System.getenv().getOrDefault("PROXY_HEALTH_PATH", "/health");
        if (!healthPath.startsWith("/")) healthPath = "/" + healthPath;
        this.healthUrl = baseUrl + healthPath;

        long timeoutMs = parseLong("PROXY_KEEPALIVE_TIMEOUT_MS", DEFAULT_TIMEOUT_MS);
        this.pingTimeout = Duration.ofMillis(timeoutMs);

        this.maxConsecutiveFails = (int) parseLong("PROXY_KEEPALIVE_MAX_FAILS", DEFAULT_MAX_FAILS);
    }

    /**
     * Inicia el scheduler. Llamar una sola vez al arrancar el servidor.
     */
    public void start() {
        long intervalS = parseLong("PROXY_KEEPALIVE_INTERVAL_S", DEFAULT_INTERVAL_S);
        scheduler.scheduleAtFixedRate(
            this::ping,
            intervalS,    // initial delay: esperar que el servidor esté listo
            intervalS,
            TimeUnit.SECONDS
        );
        logger.info(String.format(
            "KeepAliveService iniciado. healthUrl=%s, interval=%ds, timeout=%dms",
            healthUrl, intervalS, pingTimeout.toMillis()));
    }

    /**
     * Detiene el scheduler ordenadamente. Llamar en el shutdown hook del servidor.
     */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("KeepAliveService detenido.");
    }

    // ----------------------------------------------------------
    //  Ping al endpoint de salud
    // ----------------------------------------------------------
    private void ping() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(healthUrl))
                .timeout(pingTimeout)
                .header("X-API-Key",      apiKey)
                .header("X-Source-System","NONSTOP-XPNET")
                .GET()
                .build();

            HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();

            if (status >= 200 && status < 300) {
                int prev = consecutiveFails.getAndSet(0);
                if (prev > 0) {
                    logger.info("API Gateway recuperado. healthUrl=" + healthUrl
                        + " HTTP " + status + " (fallos previos=" + prev + ")");
                } else {
                    logger.debug("Keep-alive OK. HTTP " + status);
                }
            } else {
                handleFail("HTTP " + status + " desde " + healthUrl);
            }

        } catch (java.net.http.HttpTimeoutException e) {
            handleFail("Timeout (" + pingTimeout.toMillis() + "ms) al conectar con " + healthUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            handleFail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void handleFail(String reason) {
        int fails = consecutiveFails.incrementAndGet();
        if (fails >= maxConsecutiveFails) {
            logger.error(String.format(
                "API Gateway NO DISPONIBLE — %d fallos consecutivos. Causa: %s", fails, reason));
        } else {
            logger.warn(String.format(
                "Keep-alive falló (%d/%d). Causa: %s", fails, maxConsecutiveFails, reason));
        }
    }

    private static long parseLong(String envVar, long defaultVal) {
        String val = System.getenv(envVar);
        if (val != null && !val.isEmpty()) {
            try { return Long.parseLong(val); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }
}
