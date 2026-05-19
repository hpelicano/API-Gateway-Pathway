package com.nonstop.proxy.util;

import com.nonstop.proxy.model.DdlDefinition;
import com.nonstop.proxy.model.DdlField;
import com.nonstop.proxy.model.IpcRequest;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Responsable de la traducción completa entre el buffer IPC de texto plano
 * y los mensajes JSON que viajan hacia/desde el API Gateway externo.
 *
 * ─── Flujo Request (buffer IPC → JSON) ───────────────────────────────────
 *
 *  Buffer de entrada (texto plano, key=value por línea):
 *    metodo=POST
 *    endpoint=/service/alta_cliente
 *    ddl_json=alta_cliente
 *    nombre=Juan
 *    apellido=Pérez
 *    dni=20345678901
 *
 *  Usando la sección "request" de la DDL, produce JSON para la API:
 *    { "nombre":"Juan", "apellido":"Pérez", "documento":{"numero":"20345678901"} }
 *    (el json_path "documento.numero" indica anidamiento en el JSON de salida)
 *
 * ─── Flujo Response (JSON API → buffer IPC) ──────────────────────────────
 *
 *  Respuesta JSON de la API externa:
 *    { "codigo": 0, "mensaje": "OK", "data": { "id_cliente": 99, "estado": "AC" } }
 *
 *  Usando la sección "response" de la DDL, produce buffer de longitud fija:
 *    0000ALTA OK                                                              ...0000000099AC
 *    ├──┤├───────────────────────────────────────────────────────────────────┤├─────────┤├──┤
 *    4    100 chars (mensaje)                                                 10          2
 *
 * ─── Padding ─────────────────────────────────────────────────────────────
 *  type="string"  → alineado izquierda, padded con espacios a la derecha
 *  type="numeric" → alineado derecha, padded con ceros a la izquierda
 */
public class MessageSerializer {

    // ----------------------------------------------------------
    //  API pública
    // ----------------------------------------------------------

    /**
     * Parsea el buffer de texto plano key=value recibido desde $RECEIVE,
     * carga la DDL correspondiente, y arma el IpcRequest con el JSON de payload.
     *
     * @param buffer     Bytes leídos de $RECEIVE
     * @param len        Cantidad de bytes válidos
     * @param ddlLoader  Loader de DDLs (con cache)
     * @return           IpcRequest listo para pasarle a ApiGatewayClient
     * @throws ParseException si faltan campos obligatorios (metodo/endpoint/ddl_json)
     */
    public static IpcRequest parseBuffer(byte[] buffer, int len, DdlLoader ddlLoader)
            throws ParseException {

        String text = new String(buffer, 0, len, StandardCharsets.UTF_8);
        Map<String, String> all = parseKeyValue(text);

        // Los tres primeros campos son de control del servidor
        String method   = requireField(all, "metodo").toUpperCase();
        String endpoint = requireField(all, "endpoint");
        String ddlName  = requireField(all, "ddl_json");
        String corrId   = all.getOrDefault("correlation_id", generateCorrelationId());

        // Campos de datos de negocio (todo lo que no es control)
        Map<String, String> dataFields = new LinkedHashMap<>(all);
        dataFields.remove("metodo");
        dataFields.remove("endpoint");
        dataFields.remove("ddl_json");
        dataFields.remove("correlation_id");

        // Cargar DDL y construir el JSON de payload para la API
        DdlDefinition ddl         = ddlLoader.load(ddlName);
        String        jsonPayload = buildRequestJson(dataFields, ddl);

        return new IpcRequest(method, endpoint, ddlName, corrId, dataFields, jsonPayload, ddl);
    }

    /**
     * Convierte el JSON de respuesta de la API externa a un buffer de longitud fija
     * usando la sección "response" de la DDL del request original.
     *
     * El buffer resultante tiene los campos concatenados, cada uno padded a su longitud
     * definida en el DDL (igual que una estructura COBOL de longitud fija).
     *
     * @param apiJsonResponse  Body JSON de la respuesta HTTP de la API
     * @param request          IpcRequest original (contiene la DdlDefinition cargada)
     * @return                 Buffer de bytes de longitud fija para enviar via REPLY
     */
    public static byte[] buildReplyBuffer(String apiJsonResponse, IpcRequest request) {
        DdlDefinition ddl = request.getDdl();
        StringBuilder sb  = new StringBuilder();

        for (DdlField field : ddl.getResponseFields()) {
            String rawValue = extractByJsonPath(apiJsonResponse, field.getJsonPath());
            sb.append(padField(rawValue, field.getLength(), field.isNumeric()));
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Construye un buffer de error usando la DDL si está disponible,
     * o un formato mínimo de texto si la DDL no pudo cargarse.
     *
     * Convención de errores:
     *   - Si la DDL tiene campo "cod_ret" → se llena con errorCode
     *   - Si la DDL tiene campo "mensaje"  → se llena con errorMsg
     *   - El resto de campos se ponen en blanco/cero
     *
     * @param errorCode  Código de error (ej: "9001", "ERR1")
     * @param errorMsg   Descripción del error
     * @param request    IpcRequest original (puede ser null si el error ocurrió antes del parseo)
     */
    public static byte[] buildErrorBuffer(String errorCode, String errorMsg, IpcRequest request) {
        if (request == null || request.getDdl() == null) {
            // Fallback sin DDL: buffer de texto libre
            String text = padField(errorCode, 4, true) + padField(errorMsg, 200, false);
            return text.getBytes(StandardCharsets.UTF_8);
        }

        // Construir un JSON de error mínimo que el buildReplyBuffer pueda procesar
        String safeMsg = errorMsg != null ? errorMsg.replace("\"", "'") : "";
        String errorJson = "{\"codigo\":" + toNumericOrZero(errorCode)
            + ",\"codigo_str\":\"" + errorCode + "\""
            + ",\"mensaje\":\"" + safeMsg + "\""
            + ",\"data\":{}"
            + ",\"cod_ret\":\"" + errorCode + "\""
            + "}";

        return buildReplyBuffer(errorJson, request);
    }

    // ----------------------------------------------------------
    //  Parseo del buffer de texto plano
    // ----------------------------------------------------------

    /**
     * Convierte el texto key=value del buffer IPC en un mapa ordenado.
     * Tolerante a espacios alrededor del '=' y a líneas vacías.
     * Las claves se normalizan a minúsculas con underscores.
     */
    static Map<String, String> parseKeyValue(String text) throws ParseException {
        if (text == null || text.isBlank()) {
            throw new ParseException("Buffer IPC vacío");
        }
        Map<String, String> map = new LinkedHashMap<>();
        String[] lines = text.split("\\r?\\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;

            String key = line.substring(0, eq).trim().toLowerCase().replace(" ", "_");
            String val = line.substring(eq + 1).trim();
            map.put(key, val);
        }
        return map;
    }

    // ----------------------------------------------------------
    //  Construcción del JSON de request para la API
    // ----------------------------------------------------------

    /**
     * Mapea los campos del buffer IPC (key=value) al JSON de la API usando
     * el json_path de cada campo en la sección "request" de la DDL.
     *
     * Soporta json_path simples ("cuenta") y anidados ("documento.numero").
     * Para anidados construye objetos JSON nested.
     *
     * Ejemplo:
     *   DDL request: { field:"dni", json_path:"documento.numero" }
     *   Buffer:      dni=20345678901
     *   JSON:        { "documento": { "numero": "20345678901" } }
     */
    static String buildRequestJson(Map<String, String> dataFields, DdlDefinition ddl) {
        // Árbol de valores: puede tener ramas para paths anidados
        // Usamos un mapa de mapas para construir el JSON sin librería externa
        Map<String, Object> root = new LinkedHashMap<>();

        for (DdlField field : ddl.getRequestFields()) {
            String rawValue = dataFields.getOrDefault(field.getName().toLowerCase(), "");
            String jsonPath = field.getJsonPath();

            if (jsonPath.contains(".")) {
                setNestedValue(root, jsonPath.split("\\."), rawValue, field.isNumeric());
            } else {
                root.put(jsonPath, field.isNumeric()
                    ? toNumericOrZero(rawValue)
                    : rawValue);
            }
        }

        return mapToJson(root);
    }

    // ----------------------------------------------------------
    //  Extracción de valores del JSON de respuesta de la API
    // ----------------------------------------------------------

    /**
     * Extrae el valor de un campo JSON usando notación dot-path.
     * Soporta objetos anidados: "data.saldo", "header.codigo".
     * Para arrays no hay soporte (las DDLs actuales no los usan).
     *
     * Retorna string vacío si el path no existe en el JSON.
     */
    static String extractByJsonPath(String json, String path) {
        if (json == null || json.isEmpty() || path == null || path.isEmpty()) return "";

        String[] parts = path.split("\\.");
        String scope = json;

        // Navegar hasta el penúltimo segmento del path
        for (int i = 0; i < parts.length - 1; i++) {
            scope = extractJsonObject(scope, parts[i]);
            if (scope == null) return "";
        }

        // Extraer el valor en el último segmento
        return extractJsonScalar(scope, parts[parts.length - 1]);
    }

    // ----------------------------------------------------------
    //  Padding de campos para el buffer de respuesta
    // ----------------------------------------------------------

    /**
     * Aplica el padding de longitud fija al valor de un campo.
     *
     * string:  "Juan      " — alineado izquierda, espacios a la derecha
     * numeric: "0000001500" — alineado derecha, ceros a la izquierda
     *
     * Si el valor es más largo que length, se trunca desde la derecha.
     */
    static String padField(String value, int length, boolean numeric) {
        if (value == null) value = "";
        // Truncar si excede la longitud del campo DDL
        if (value.length() > length) value = value.substring(0, length);

        int padding = length - value.length();

        if (numeric) {
            // Alineado a la derecha con ceros
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < padding; i++) sb.append('0');
            sb.append(value);
            return sb.toString();
        } else {
            // Alineado a la izquierda con espacios
            StringBuilder sb = new StringBuilder(value);
            for (int i = 0; i < padding; i++) sb.append(' ');
            return sb.toString();
        }
    }

    // ----------------------------------------------------------
    //  Helpers internos
    // ----------------------------------------------------------

    private static String requireField(Map<String, String> fields, String name)
            throws ParseException {
        String value = fields.get(name);
        if (value == null || value.isEmpty()) {
            throw new ParseException(
                "Campo obligatorio ausente en buffer IPC: '" + name + "'. "
                + "El buffer debe comenzar con: metodo=, endpoint=, ddl_json=");
        }
        return value;
    }

    private static String generateCorrelationId() {
        return "CID" + Long.toHexString(System.currentTimeMillis()).toUpperCase();
    }

    /** Extrae el valor escalar (string o número) de una clave en el scope JSON dado. */
    private static String extractJsonScalar(String json, String key) {
        // Primero intentar valor string: "key": "value"
        Pattern strPat = Pattern.compile(
            "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = strPat.matcher(json);
        if (m.find()) return m.group(1);

        // Luego intentar valor numérico/boolean: "key": 123 | true | false | null
        Pattern numPat = Pattern.compile(
            "\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?|true|false|null)");
        m = numPat.matcher(json);
        if (m.find()) {
            String val = m.group(1);
            return "null".equals(val) ? "" : val;
        }

        return "";
    }

    /** Extrae el contenido del objeto JSON identificado por key en el scope. */
    private static String extractJsonObject(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\{)");
        Matcher m = p.matcher(json);
        if (!m.find()) return null;

        int start = m.start(1);
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if      (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return json.substring(start, i + 1); }
        }
        return null;
    }

    /**
     * Inserta un valor en un mapa anidado usando un path de segmentos.
     * Crea los nodos intermedios (mapas) si no existen.
     */
    @SuppressWarnings("unchecked")
    private static void setNestedValue(Map<String, Object> node, String[] pathSegments,
                                        String value, boolean numeric) {
        for (int i = 0; i < pathSegments.length - 1; i++) {
            node = (Map<String, Object>) node.computeIfAbsent(
                pathSegments[i], k -> new LinkedHashMap<String, Object>());
        }
        String leafKey = pathSegments[pathSegments.length - 1];
        node.put(leafKey, numeric ? toNumericOrZero(value) : value);
    }

    /** Convierte un mapa (potencialmente anidado) a JSON string sin librería externa. */
    @SuppressWarnings("unchecked")
    private static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");

            Object val = entry.getValue();
            if (val instanceof Map) {
                sb.append(mapToJson((Map<String, Object>) val));
            } else if (val instanceof String) {
                sb.append("\"").append(((String) val).replace("\\", "\\\\")
                    .replace("\"", "\\\"")).append("\"");
            } else {
                sb.append(val); // numeric value stored as String of digits
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /** Convierte un string a valor numérico o "0" si no es válido. */
    private static String toNumericOrZero(String value) {
        if (value == null || value.isBlank()) return "0";
        String stripped = value.trim().replaceAll("[^0-9.\\-]", "");
        return stripped.isEmpty() ? "0" : stripped;
    }

    // ----------------------------------------------------------
    //  Excepción de parseo del buffer IPC
    // ----------------------------------------------------------

    public static class ParseException extends Exception {
        public ParseException(String message) { super(message); }
    }
}
