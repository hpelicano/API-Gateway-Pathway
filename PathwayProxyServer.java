package com.nonstop.proxy;

import com.tandem.ext.guardian.Guardian;
import com.tandem.ext.guardian.GuardianException;
import com.tandem.ext.guardian.Receive;
import com.tandem.ext.guardian.ReceiveInfo;

import com.nonstop.proxy.handler.ApiGatewayClient;
import com.nonstop.proxy.handler.KeepAliveService;
import com.nonstop.proxy.model.IpcRequest;
import com.nonstop.proxy.model.IpcResponse;
import com.nonstop.proxy.util.Logger;
import com.nonstop.proxy.util.MessageSerializer;

/**
 * Servidor Pathway (NSJ 11+) que actúa de proxy entre procesos XPNET
 * y un API Gateway externo via HTTPS.
 *
 * Flujo por mensaje:
 *  1. Lee buffer binario desde $RECEIVE (JToolkit)
 *  2. Deserializa buffer → IpcRequest (MessageSerializer)
 *  3. Convierte IpcRequest → JSON → llamada HTTPS (ApiGatewayClient)
 *  4. Convierte respuesta JSON → IpcResponse → buffer binario (MessageSerializer)
 *  5. Envía buffer al proceso XPNET caller via Guardian REPLY
 *
 * Configuración en PATHCOM:
 *   ADD SERVERCLASS PROXYSRV, PROCESSTYPE JAVA, MAXSERVERS 10, MINSERVERS 2, TIMEOUT 120
 *   SET SERVERCLASS PROXYSRV ENV "JREHOME=/usr/tandem/java11"
 *   SET SERVERCLASS PROXYSRV ARGLIST "-cp /usr/tandem/java11/lib/tdmext.jar:/home/proxyuser/proxy.jar com.nonstop.proxy.PathwayProxyServer"
 */
public class PathwayProxyServer {

    private static final int MAX_MSG_SIZE   = 4096;
    private static final int MAX_API_RETRIES = 3;

    private final ApiGatewayClient apiClient;
    private final KeepAliveService keepAlive;
    private final Logger           logger;
    private volatile boolean       running = true;

    public PathwayProxyServer() {
        this.logger    = new Logger("PathwayProxyServer");
        this.apiClient = new ApiGatewayClient();
        // KeepAlive reutiliza el HttpClient del apiClient (pool TLS compartido)
        this.keepAlive = new KeepAliveService(
            apiClient.getHttpClient(),
            apiClient.getBaseUrl(),
            apiClient.getApiKey());
    }

    public static void main(String[] args) {
        PathwayProxyServer server = new PathwayProxyServer();
        server.registerShutdownHook();
        server.run();
    }

    // ----------------------------------------------------------
    //  Loop principal del servidor
    // ----------------------------------------------------------
    public void run() {
        logger.info("PathwayProxyServer iniciado (JDK 11, TLS 1.2/1.3). Esperando mensajes...");

        keepAlive.start();

        try (Receive receive = Receive.getInstance()) {
            receive.setSystemMessageMask((short) 0);
            receive.open();

            while (running && !Thread.currentThread().isInterrupted()) {
                processNextMessage(receive);
            }

        } catch (GuardianException e) {
            if (Thread.currentThread().isInterrupted()) {
                logger.info("PathwayProxyServer interrumpido. Cerrando...");
            } else {
                logger.error("Error fatal al operar $RECEIVE: " + e.getMessage());
                System.exit(1);
            }
        } finally {
            keepAlive.stop();
        }

        logger.info("PathwayProxyServer detenido.");
    }

    // ----------------------------------------------------------
    //  Procesamiento de un mensaje IPC
    // ----------------------------------------------------------
    private void processNextMessage(Receive receive) {
        byte[]      rawBuffer = new byte[MAX_MSG_SIZE];
        ReceiveInfo info      = new ReceiveInfo();

        try {
            // PASO 1: Leer buffer binario desde $RECEIVE (bloqueante)
            int bytesRead = receive.read(rawBuffer, MAX_MSG_SIZE, info);

            if (bytesRead <= 0) {
                logger.warn("Mensaje vacío o de sistema. Ignorando.");
                sendErrorReply(receive, IpcResponse.ERR_EMPTY_MSG, "EMPTY_MSG");
                return;
            }

            logger.info(String.format("Mensaje recibido: %d bytes, filenum=%d, syncId=%d",
                bytesRead, info.getFileNumber(), info.getSyncId()));

            // PASO 2: Deserializar buffer binario → IpcRequest (big-endian)
            //         El payload del buffer XPNET ya viene como JSON UTF-8
            IpcRequest request = MessageSerializer.deserialize(rawBuffer, bytesRead);
            logger.info("Request: op=" + request.getOperation()
                + ", method=" + request.getHttpMethod()
                + ", corrId=" + request.getCorrelationId());

            // PASO 3 + 4: Llamar API externa (con reintentos) y obtener IpcResponse
            //             ApiGatewayClient convierte IpcRequest → JSON → HTTPS
            //             y la respuesta JSON de la API → IpcResponse
            IpcResponse apiResponse = callWithRetry(request);

            // PASO 5: Serializar IpcResponse → buffer binario → Guardian REPLY al XPNET
            byte[] replyBuffer = MessageSerializer.serialize(apiResponse);
            receive.reply(replyBuffer, replyBuffer.length, (short) 0);

            logger.info("Reply enviado. corrId=" + request.getCorrelationId()
                + ", httpStatus=" + apiResponse.getHttpStatusCode()
                + ", replyBytes=" + replyBuffer.length);

        } catch (MessageSerializer.DeserializationException e) {
            logger.error("Error deserializando buffer IPC: " + e.getMessage());
            sendErrorReply(receive, IpcResponse.ERR_DESERIALIZE, "DESERIALIZE_ERROR");

        } catch (ApiGatewayClient.ApiException e) {
            logger.error("Error llamando API externa: " + e.getMessage()
                + " (HTTP " + e.getHttpStatus() + ")");
            String key = e.getHttpStatus() == 0 ? "API_TIMEOUT" : "API_ERROR:" + e.getHttpStatus();
            sendErrorReply(receive, IpcResponse.ERR_API_ERROR, key);

        } catch (GuardianException e) {
            // Error en la propia operación Guardian (read o reply)
            if (Thread.currentThread().isInterrupted()) {
                running = false; // salir del while loop
            } else {
                logger.error("Error Guardian: " + e.getMessage());
            }
        }
    }

    // ----------------------------------------------------------
    //  Reintentos con backoff exponencial
    // ----------------------------------------------------------
    private IpcResponse callWithRetry(IpcRequest request)
            throws ApiGatewayClient.ApiException {

        ApiGatewayClient.ApiException last = null;

        for (int attempt = 1; attempt <= MAX_API_RETRIES; attempt++) {
            try {
                logger.info("API call intento " + attempt + "/" + MAX_API_RETRIES);
                return apiClient.call(request);

            } catch (ApiGatewayClient.ApiException e) {
                last = e;
                if (!e.isRetryable()) throw e;
                logger.warn("Intento " + attempt + " falló: " + e.getMessage() + ". Reintentando...");
                sleep(exponentialBackoffMs(attempt));
            }
        }

        throw last;
    }

    // ----------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------
    private void sendErrorReply(Receive receive, short errorCode, String errorKey) {
        try {
            IpcResponse err = IpcResponse.error(errorKey);
            byte[] buf = MessageSerializer.serialize(err);
            receive.reply(buf, buf.length, (short) -1);
        } catch (Exception ex) {
            logger.error("No se pudo enviar error reply: " + ex.getMessage());
        }
    }

    private static long exponentialBackoffMs(int attempt) {
        return Math.min((long) Math.pow(2, attempt) * 200L, 5000L);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Señal de shutdown recibida. Deteniendo PathwayProxyServer...");
            running = false;
        }, "shutdown-hook"));
    }
}
