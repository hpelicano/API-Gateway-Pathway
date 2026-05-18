import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Servidor HTTP local que simula el API Gateway externo en WSL2.
 *
 * Responde a cualquier método (GET/POST/PUT/DELETE) en cualquier path
 * con una respuesta JSON que refleja el request recibido.
 *
 * Endpoint especial:
 *   GET /health  →  {"status":"UP","service":"MockApiServer"}
 *                   (usado por KeepAliveService)
 */
public class MockApiServer {

    private final HttpServer server;
    private final int        port;

    public MockApiServer(int port) throws IOException {
        this.port   = port;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
    }

    public void start() {
        server.start();
        System.out.printf("[MockApiServer] Escuchando en http://localhost:%d%n", port);
    }

    public void stop() {
        server.stop(0);
        System.out.println("[MockApiServer] Detenido.");
    }

    // ----------------------------------------------------------
    //  Handler principal
    // ----------------------------------------------------------
    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path   = exchange.getRequestURI().getPath();
        String corrId = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
        String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");

        // Leer body si lo hay
        String requestBody = "";
        try (InputStream is = exchange.getRequestBody()) {
            requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        System.out.printf("[MockApiServer] %s %s  corrId=%s  bodyLen=%d%n",
            method, path, corrId, requestBody.length());

        // Validación básica de API Key
        if (apiKey == null || apiKey.isEmpty()) {
            sendJson(exchange, 401,
                "{\"error\":\"Unauthorized\",\"message\":\"X-API-Key requerida\"}");
            return;
        }

        // Endpoint de salud para KeepAliveService
        if ("/health".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200,
                "{\"status\":\"UP\",\"service\":\"MockApiServer\",\"port\":" + port + "}");
            return;
        }

        // Respuesta simulada: echo del request con datos ficticios de negocio
        String responseBody = buildMockResponse(method, path, corrId, requestBody);
        int    status       = "POST".equals(method) ? 201 : 200;

        sendJson(exchange, status, responseBody);
    }

    private String buildMockResponse(String method, String path,
                                     String corrId, String requestBody) {
        // Escapar caracteres problemáticos en JSON
        String safeBody = requestBody.isEmpty() ? "{}" : requestBody
            .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "");

        return "{"
            + "\"mock\":true,"
            + "\"method\":\"" + method + "\","
            + "\"path\":\"" + path + "\","
            + "\"correlationId\":\"" + (corrId != null ? corrId : "") + "\","
            + "\"requestBody\":" + safeBody + ","
            + "\"result\":{\"code\":\"SIM-OK\",\"timestamp\":" + System.currentTimeMillis() + "}"
            + "}";
    }

    private void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
