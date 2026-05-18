import com.nonstop.proxy.PathwayProxyServer;
import com.tandem.ext.guardian.Receive;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Harness de simulación del PathwayProxyServer en WSL2.
 *
 * Pasos que ejecuta:
 *  1. Levanta MockApiServer en localhost:18080 (simula API Gateway externo)
 *  2. Arranca PathwayProxyServer en un hilo daemon
 *  3. Construye buffers IPC binarios tal como los enviaría un proceso XPNET real
 *  4. Los deposita en MockReceive y espera la respuesta de REPLY
 *  5. Decodifica e imprime cada respuesta
 *  6. Detiene todo ordenadamente
 *
 * Las variables de entorno son las mismas que en producción NonStop,
 * definidas al inicio de main() para facilitar la ejecución en WSL2.
 */
public class SimRunner {

    private static final int API_PORT = 18080;

    public static void main(String[] args) throws Exception {
        printBanner();

        // ── Variables de entorno (en producción se configuran en PATHCOM) ──────
        System.setProperty("java.util.logging.SimpleFormatter.format", "");
        setEnv("PROXY_API_BASE_URL",         "http://localhost:" + API_PORT);
        setEnv("PROXY_API_KEY",              "sim-api-key-12345");
        setEnv("PROXY_CONNECT_TIMEOUT_MS",   "3000");
        setEnv("PROXY_REQUEST_TIMEOUT_MS",   "10000");
        setEnv("PROXY_HEALTH_PATH",          "/health");
        setEnv("PROXY_KEEPALIVE_INTERVAL_S", "15");
        setEnv("PROXY_KEEPALIVE_TIMEOUT_MS", "3000");
        setEnv("PROXY_DEBUG",                "true");

        // ── Levantar MockApiServer ─────────────────────────────────────────────
        MockApiServer apiServer = new MockApiServer(API_PORT);
        apiServer.start();
        Thread.sleep(200);

        // ── Arrancar PathwayProxyServer en hilo daemon ─────────────────────────
        Thread serverThread = new Thread(() -> PathwayProxyServer.main(new String[0]));
        serverThread.setName("pathway-proxy-server");
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(500); // esperar inicialización

        // ── Enviar mensajes IPC de prueba ──────────────────────────────────────
        System.out.println("\n══════════════════════════════════════════════");
        System.out.println("  Enviando mensajes IPC de prueba al servidor");
        System.out.println("══════════════════════════════════════════════\n");

        runTest(1, "GET",    "TXN-001-GET-ACCT",  "/api/v1/accounts/ACC-001",  null);
        runTest(2, "POST",   "TXN-002-POST-PAY",  "/api/v1/payments",
            "{\"amount\":1500.00,\"currency\":\"USD\",\"from\":\"ACC-001\",\"to\":\"ACC-999\"}");
        runTest(3, "PUT",    "TXN-003-PUT-ACCT",  "/api/v1/accounts/ACC-001",
            "{\"status\":\"ACTIVE\",\"limit\":5000,\"currency\":\"USD\"}");
        runTest(4, "DELETE", "TXN-004-DEL-SES",   "/api/v1/sessions/SES-789",  null);

        // ── Test: error 4xx (recurso inexistente) ──────────────────────────────
        // El MockApiServer siempre devuelve 200/201, pero podemos probar
        // un path especial que generamos para el ejemplo
        runTest(5, "GET",    "TXN-005-NOT-FOUND", "/api/v1/unknown/resource",  null);

        // ── Cierre ────────────────────────────────────────────────────────────
        System.out.println("\n══════════════════════════════════════════════");
        System.out.println("  Simulación completada. Deteniendo servidor...");
        System.out.println("══════════════════════════════════════════════\n");

        Receive.sendStop();
        serverThread.join(3000);
        apiServer.stop();

        System.out.println("[SimRunner] FIN");
    }

    // ----------------------------------------------------------
    //  Ejecuta un caso de prueba: construye buffer IPC, lo envía
    //  y decodifica la respuesta de REPLY
    // ----------------------------------------------------------
    private static void runTest(int num, String method, String correlationId,
                                 String operation, String payload) throws Exception {
        System.out.printf("── Test %d: %s %s ──%n", num, method, operation);

        byte[] ipcBuffer = buildIpcBuffer(method, correlationId, operation, payload);
        Receive.sendMessage(ipcBuffer);

        byte[] reply = Receive.waitForReply();
        if (reply == null) {
            System.out.println("  ERROR: No llegó respuesta (timeout)");
        } else {
            decodeAndPrint(reply);
        }
        System.out.println();
    }

    // ----------------------------------------------------------
    //  Construye un buffer IPC binario simulando el XPNET caller
    //
    //  Layout (big-endian):
    //   Offset  Bytes  Campo
    //    0       2     version (= 1)
    //    2       2     msgType (1=GET 2=POST 3=PUT 4=DELETE)
    //    4      16     correlationId (ASCII, padded)
    //   20      32     operation (ASCII, padded)
    //   52       2     payloadLength
    //   54      var    payload (UTF-8 JSON)
    // ----------------------------------------------------------
    private static byte[] buildIpcBuffer(String method, String correlationId,
                                          String operation, String payload) {
        short msgType;
        switch (method) {
            case "GET":    msgType = 1; break;
            case "POST":   msgType = 2; break;
            case "PUT":    msgType = 3; break;
            case "DELETE": msgType = 4; break;
            default: throw new IllegalArgumentException("Método no soportado: " + method);
        }

        byte[] corrBytes    = padOrTruncate(correlationId, 16);
        byte[] opBytes      = padOrTruncate(operation, 32);
        byte[] payloadBytes = payload != null
            ? payload.getBytes(StandardCharsets.UTF_8)
            : new byte[0];

        ByteBuffer buf = ByteBuffer.allocate(54 + payloadBytes.length)
                                   .order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) 1);                    // version
        buf.putShort(msgType);                      // msgType
        buf.put(corrBytes);                         // correlationId [16]
        buf.put(opBytes);                           // operation [32]
        buf.putShort((short) payloadBytes.length);  // payloadLength
        if (payloadBytes.length > 0) buf.put(payloadBytes);

        System.out.printf("  [XPNET→Proxy] buffer=%d bytes  payload=%s%n",
            buf.capacity(), payload != null ? payload : "(vacío)");

        return buf.array();
    }

    // ----------------------------------------------------------
    //  Decodifica el buffer de REPLY y lo imprime
    //
    //  Layout de respuesta (big-endian):
    //   Offset  Bytes  Campo
    //    0       2     version
    //    2       2     httpStatusCode
    //    4       2     errorCode (0=OK)
    //    6      16     correlationId
    //   22       2     payloadLength
    //   24      var    payload (UTF-8 JSON)
    // ----------------------------------------------------------
    private static void decodeAndPrint(byte[] reply) {
        ByteBuffer buf = ByteBuffer.wrap(reply).order(ByteOrder.BIG_ENDIAN);

        short  version    = buf.getShort();
        short  httpStatus = buf.getShort();
        short  errorCode  = buf.getShort();
        byte[] corrBytes  = new byte[16];
        buf.get(corrBytes);
        String corrId     = new String(corrBytes, StandardCharsets.US_ASCII).trim()
                                .replace("\0", "");
        short  payloadLen = buf.getShort();
        String payload    = "";
        if (payloadLen > 0) {
            byte[] pb = new byte[payloadLen];
            buf.get(pb);
            payload = new String(pb, StandardCharsets.UTF_8);
        }

        String status = errorCode == 0 ? "OK" : "ERROR";
        System.out.printf("  [Proxy→XPNET] corrId=%-20s  httpStatus=%d  errorCode=%d  (%s)%n",
            corrId, httpStatus, errorCode, status);
        System.out.printf("  payload: %s%n", payload.length() > 200
            ? payload.substring(0, 200) + "..." : payload);
    }

    // ----------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------
    private static byte[] padOrTruncate(String value, int length) {
        byte[] result = new byte[length];
        if (value != null) {
            byte[] src = value.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(src, 0, result, 0, Math.min(src.length, length));
        }
        return result;
    }

    /** Establece una variable de entorno en el proceso actual via reflexión. */
    @SuppressWarnings("unchecked")
    private static void setEnv(String key, String value) {
        try {
            java.util.Map<String, String> env = System.getenv();
            java.lang.reflect.Field field = env.getClass().getDeclaredField("m");
            field.setAccessible(true);
            ((java.util.Map<String, String>) field.get(env)).put(key, value);
        } catch (Exception e) {
            // En algunos JDKs el hack de reflexión no funciona; en ese caso
            // usar run.sh que exporta las variables antes de invocar java
            System.err.println("[SimRunner] Advertencia: no se pudo setear " + key
                + " via reflexión. Usar run.sh en su lugar. Error: " + e.getMessage());
        }
    }

    private static void printBanner() {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  PathwayProxyServer — Simulación WSL2                ║");
        System.out.println("║  Simula: XPNET → $RECEIVE → Proxy → API → REPLY     ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
