import com.nonstop.proxy.PathwayProxyServer;
import com.tandem.ext.guardian.Receive;

import java.nio.charset.StandardCharsets;

/**
 * Harness de simulación del PathwayProxyServer en WSL2.
 *
 * Simula el flujo completo:
 *   XPNET → buffer texto plano → $RECEIVE → MessageSerializer(DDL) → ApiGateway → REPLY
 *
 * Los buffers de entrada se construyen como texto key=value,
 * igual a como los enviaría un proceso XPNET real en NonStop.
 * Los buffers de REPLY se imprimen campo por campo usando la DDL correspondiente.
 *
 * Casos de prueba:
 *   Test 1 — GET  consulta_saldo   : consulta saldo de cuenta
 *   Test 2 — POST alta_cliente     : alta de nuevo cliente (con json_path anidado)
 *   Test 3 — POST transferencia    : transferencia entre cuentas (json_path multi-nivel)
 *   Test 4 — GET  ddl inexistente  : error esperado E002
 *   Test 5 — Buffer mal formado    : error esperado E001
 */
public class SimRunner {

    private static final int API_PORT = 18080;

    public static void main(String[] args) throws Exception {
        printBanner();

        // ── Variables de entorno (en producción se configuran en PATHCOM) ──
        setEnv("PROXY_API_BASE_URL",         "http://localhost:" + API_PORT);
        setEnv("PROXY_API_KEY",              "sim-api-key-12345");
        setEnv("PROXY_CONNECT_TIMEOUT_MS",   "3000");
        setEnv("PROXY_REQUEST_TIMEOUT_MS",   "10000");
        setEnv("PROXY_HEALTH_PATH",          "/health");
        setEnv("PROXY_KEEPALIVE_INTERVAL_S", "60");    // cada 60s — poco frecuente en sim
        setEnv("PROXY_KEEPALIVE_TIMEOUT_MS", "3000");
        setEnv("PROXY_DEBUG",                "true");
        // DDL path: relativo al directorio de ejecución (wsl2-sim/) → ../ddl
        setEnv("PROXY_DDL_PATH",             "../ddl");

        // ── Levantar MockApiServer con respuestas simuladas por DDL ────────
        MockApiServer apiServer = new MockApiServer(API_PORT);
        apiServer.start();
        Thread.sleep(200);

        // ── Arrancar PathwayProxyServer en hilo daemon ─────────────────────
        Thread serverThread = new Thread(() -> PathwayProxyServer.main(new String[0]));
        serverThread.setName("pathway-proxy-server");
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(600); // esperar inicialización del DdlLoader + KeepAlive

        // ── Tests ──────────────────────────────────────────────────────────
        System.out.println("\n══════════════════════════════════════════════════════════");
        System.out.println("  Tests de integración PathwayProxyServer (DDL-driven)");
        System.out.println("══════════════════════════════════════════════════════════\n");

        // Test 1 — Consulta de saldo (GET, DDL: consulta_saldo)
        runTest("Test 1 — GET consulta_saldo",
            "metodo=GET\n"
            + "endpoint=/api/v1/cuentas/saldo\n"
            + "ddl_json=consulta_saldo\n"
            + "cuenta=1234567890123456\n"
            + "moneda=ARS\n"
            + "tipo=CC");

        // Test 2 — Alta de cliente (POST, DDL: alta_cliente, json_path anidado)
        runTest("Test 2 — POST alta_cliente (json_path anidado)",
            "metodo=POST\n"
            + "endpoint=/api/v1/clientes\n"
            + "ddl_json=alta_cliente\n"
            + "nombre=Juan\n"
            + "apellido=Pérez\n"
            + "dni=20345678901\n"
            + "email=juan.perez@banco.com\n"
            + "telefono=+54911234567");

        // Test 3 — Transferencia (POST, DDL: transferencia, json_path multi-nivel)
        runTest("Test 3 — POST transferencia (json_path multi-nivel)",
            "metodo=POST\n"
            + "endpoint=/api/v1/transferencias\n"
            + "ddl_json=transferencia\n"
            + "cta_origen=1234567890123456\n"
            + "cta_destino=9876543210987654\n"
            + "importe=150000\n"
            + "moneda=ARS\n"
            + "referencia=TRF-20260518-001");

        // Test 4 — DDL inexistente (error E002 esperado)
        runTest("Test 4 — DDL no encontrada (error E002 esperado)",
            "metodo=GET\n"
            + "endpoint=/api/v1/test\n"
            + "ddl_json=no_existe_esta_ddl\n"
            + "campo1=valor1");

        // Test 5 — Buffer mal formado, sin campo 'endpoint' (error E001 esperado)
        runTest("Test 5 — Buffer sin endpoint (error E001 esperado)",
            "metodo=GET\n"
            + "ddl_json=consulta_saldo\n"
            + "cuenta=123");

        // ── Cierre ────────────────────────────────────────────────────────
        System.out.println("\n══════════════════════════════════════════════════════════");
        System.out.println("  Todos los tests completados. Deteniendo servidor...");
        System.out.println("══════════════════════════════════════════════════════════\n");

        Receive.sendStop();
        serverThread.join(3000);
        apiServer.stop();
        System.out.println("[SimRunner] FIN");
    }

    // ----------------------------------------------------------
    //  Ejecuta un caso de prueba: envía buffer IPC y decodifica REPLY
    // ----------------------------------------------------------
    private static void runTest(String label, String ipcBufferText) throws Exception {
        System.out.println("┌─ " + label);

        // Mostrar el buffer de entrada que recibiría $RECEIVE
        System.out.println("│  [XPNET→Proxy] buffer IPC:");
        for (String line : ipcBufferText.split("\n")) {
            System.out.println("│    " + line);
        }

        byte[] buffer = ipcBufferText.getBytes(StandardCharsets.UTF_8);
        Receive.sendMessage(buffer);

        byte[] reply = Receive.waitForReply();

        System.out.println("│  [Proxy→XPNET] reply (" + (reply != null ? reply.length : 0) + " bytes):");
        if (reply == null) {
            System.out.println("│    ERROR: Sin respuesta (timeout 15s)");
        } else {
            // Imprimir el buffer de respuesta como texto (es texto plano con padding)
            String replyText = new String(reply, StandardCharsets.UTF_8);
            System.out.println("│    RAW: [" + replyText + "]");
        }

        System.out.println("└──────────────────────────────────────────────────────────\n");
    }

    // ----------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void setEnv(String key, String value) {
        try {
            java.util.Map<String, String> env = System.getenv();
            java.lang.reflect.Field field = env.getClass().getDeclaredField("m");
            field.setAccessible(true);
            ((java.util.Map<String, String>) field.get(env)).put(key, value);
        } catch (Exception e) {
            System.err.println("[SimRunner] Advertencia: setEnv falló para " + key
                + " — usar run.sh que exporta las variables. Error: " + e.getMessage());
        }
    }

    private static void printBanner() {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  PathwayProxyServer — Simulación WSL2 (DDL-driven)       ║");
        System.out.println("║  Flujo: XPNET key=value → DDL → JSON → API → fixed buf  ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
