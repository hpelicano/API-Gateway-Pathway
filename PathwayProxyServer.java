package com.nonstop.proxy;

import com.tandem.ext.guardian.Guardian;
import com.tandem.ext.guardian.GuardianException;
import com.tandem.ext.guardian.Receive;
import com.tandem.ext.guardian.ReceiveInfo;

import com.nonstop.proxy.handler.ApiGatewayClient;
import com.nonstop.proxy.handler.KeepAliveService;
import com.nonstop.proxy.model.IpcRequest;
import com.nonstop.proxy.model.IpcResponse;
import com.nonstop.proxy.util.DdlLoader;
import com.nonstop.proxy.util.Logger;
import com.nonstop.proxy.util.MessageSerializer;

/**
 * Servidor Pathway (NSJ 11+) — proxy entre procesos XPNET y un API Gateway externo.
 *
 * Flujo completo por mensaje:
 *
 *  [XPNET] buffer texto plano (key=value)
 *      ↓  $RECEIVE (JToolkit)
 *  [1] MessageSerializer.parseBuffer()
 *      → lee metodo / endpoint / ddl_json del buffer
 *      → carga DdlDefinition desde /home/proxyuser/ddl/<ddl_json>.json
 *      → mapea campos de negocio → JSON según sección "request" de la DDL
 *      → IpcRequest{method, endpoint, jsonPayload, ddl}
 *      ↓
 *  [2] ApiGatewayClient.call()
 *      → HTTPS POST/GET/PUT/DELETE con el JSON construido
 *      → IpcResponse{httpStatus, payload=jsonRespuesta}
 *      ↓
 *  [3] MessageSerializer.buildReplyBuffer()
 *      → extrae valores del JSON de respuesta usando json_path de la DDL "response"
 *      → arma buffer de longitud fija (PIC-like) con padding de cada campo
 *      ↓
 *  [XPNET] buffer respuesta de longitud fija vía Guardian REPLY
 *
 * Configuración PATHCOM (actualizada para JDK 11 + DDL):
 *   SET SERVERCLASS PROXYSRV ENV "JREHOME=/usr/tandem/java11"
 *   SET SERVERCLASS PROXYSRV ENV "PROXY_DDL_PATH=/home/proxyuser/ddl"
 *   SET SERVERCLASS PROXYSRV ARGLIST "-cp /usr/tandem/java11/lib/tdmext.jar:/home/proxyuser/proxy.jar com.nonstop.proxy.PathwayProxyServer"
 */
public class PathwayProxyServer {

    private static final int MAX_MSG_SIZE    = 32768; // 32KB — suficiente para mensajes COBOL/texto
    private static final int MAX_API_RETRIES = 3;

    private final ApiGatewayClient apiClient;
    private final KeepAliveService keepAlive;
    private final DdlLoader        ddlLoader;
    private final Logger           logger;
    private volatile boolean       running = true;

    public PathwayProxyServer() {
        this.logger    = new Logger("PathwayProxyServer");
        this.ddlLoader = new DdlLoader();
        this.apiClient = new ApiGatewayClient();
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
        logger.info("PathwayProxyServer iniciado (JDK 11, TLS 1.2/1.3, DDL-driven). "
            + "DDL path: " + System.getenv().getOrDefault("PROXY_DDL_PATH", "ddl"));

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
                logger.error("Error fatal en $RECEIVE: " + e.getMessage());
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
        IpcRequest  request   = null;

        try {
            // ── PASO 1: Leer buffer de texto plano desde $RECEIVE ─────────────
            int bytesRead = receive.read(rawBuffer, MAX_MSG_SIZE, info);

            if (bytesRead <= 0) {
                logger.warn("Mensaje vacío o de sistema. Ignorando.");
                sendErrorReply(receive, null, "E000", "Mensaje IPC vacío");
                return;
            }

            logger.info(String.format("Mensaje recibido: %d bytes, filenum=%d",
                bytesRead, info.getFileNumber()));

            // ── PASO 2: Parsear buffer key=value + cargar DDL + armar JSON ────
            //   MessageSerializer lee metodo/endpoint/ddl_json del buffer,
            //   carga la DdlDefinition y construye el JSON para la API.
            request = MessageSerializer.parseBuffer(rawBuffer, bytesRead, ddlLoader);

            logger.info("Request: " + request);

            // ── PASO 3: Llamar API Gateway con reintentos ─────────────────────
            IpcResponse apiResponse = callWithRetry(request);

            // ── PASO 4: Convertir JSON de respuesta → buffer de longitud fija ─
            //   MessageSerializer usa la sección "response" de la DDL para
            //   extraer cada campo del JSON y armar el buffer con padding COBOL.
            byte[] replyBuffer = MessageSerializer.buildReplyBuffer(
                apiResponse.getPayload(), request);

            // ── PASO 5: Guardian REPLY al proceso XPNET ───────────────────────
            receive.reply(replyBuffer, replyBuffer.length, (short) 0);

            logger.info(String.format("Reply enviado. corrId=%s, httpStatus=%d, replyBytes=%d",
                request.getCorrelationId(), apiResponse.getHttpStatusCode(), replyBuffer.length));

        } catch (MessageSerializer.ParseException e) {
            logger.error("Error parseando buffer IPC: " + e.getMessage());
            sendErrorReply(receive, request, "E001", e.getMessage());

        } catch (DdlLoader.DdlNotFoundException e) {
            logger.error("DDL no encontrada: " + e.getMessage());
            sendErrorReply(receive, request, "E002", "DDL no encontrada: "
                + (request != null ? request.getDdlName() : "desconocida"));

        } catch (ApiGatewayClient.ApiException e) {
            logger.error("Error API externa: " + e.getMessage()
                + " HTTP=" + e.getHttpStatus());
            String code = e.getHttpStatus() == 0 ? "E003" : "E" + e.getHttpStatus();
            sendErrorReply(receive, request, code, e.getMessage());

        } catch (GuardianException e) {
            if (Thread.currentThread().isInterrupted()) {
                running = false;
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
                logger.info("API call intento " + attempt + "/" + MAX_API_RETRIES
                    + " → " + request.getMethod() + " " + request.getEndpoint());
                return apiClient.call(request);
            } catch (ApiGatewayClient.ApiException e) {
                last = e;
                if (!e.isRetryable()) throw e;
                logger.warn("Intento " + attempt + " falló: " + e.getMessage());
                sleep(Math.min((long) Math.pow(2, attempt) * 200L, 5000L));
            }
        }
        throw last;
    }

    // ----------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------
    private void sendErrorReply(Receive receive, IpcRequest request,
                                 String errorCode, String errorMsg) {
        try {
            byte[] buf = MessageSerializer.buildErrorBuffer(errorCode, errorMsg, request);
            receive.reply(buf, buf.length, (short) -1);
        } catch (Exception ex) {
            logger.error("No se pudo enviar error reply: " + ex.getMessage());
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown recibido. Deteniendo PathwayProxyServer...");
            running = false;
        }, "shutdown-hook"));
    }
}
