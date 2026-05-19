package com.nonstop.proxy.util;

import com.nonstop.proxy.model.DdlDefinition;
import com.nonstop.proxy.model.DdlField;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Carga y cachea definiciones DDL desde el filesystem OSS del NonStop.
 *
 * Cada DDL es un archivo JSON en PROXY_DDL_PATH con nombre <ddl_name>.json.
 * Las DDLs se cargan una sola vez por proceso Pathway y se retienen en memoria
 * para el resto del ciclo de vida del server (cache perpetuo por diseño —
 * si se modifica un .json, reiniciar el SERVERCLASS para recargar).
 *
 * Variable de entorno:
 *   PROXY_DDL_PATH   Directorio base de los archivos DDL JSON
 *                    Default en NonStop: /home/proxyuser/ddl
 *                    Default en WSL2 sim: ./ddl
 */
public class DdlLoader {

    private static final String DEFAULT_DDL_PATH_NONSTOP = "/home/proxyuser/ddl";
    private static final String DEFAULT_DDL_PATH_LOCAL   = "ddl";

    private final Path   ddlBasePath;
    private final Logger logger;
    private final ConcurrentHashMap<String, DdlDefinition> cache = new ConcurrentHashMap<>();

    public DdlLoader() {
        this.logger = new Logger("DdlLoader");

        String envPath = System.getenv("PROXY_DDL_PATH");
        if (envPath != null && !envPath.isEmpty()) {
            this.ddlBasePath = Paths.get(envPath);
        } else {
            // Preferir el path NonStop; si no existe, usar ./ddl para desarrollo/WSL2
            Path nonstopPath = Paths.get(DEFAULT_DDL_PATH_NONSTOP);
            this.ddlBasePath = Files.isDirectory(nonstopPath)
                ? nonstopPath
                : Paths.get(DEFAULT_DDL_PATH_LOCAL);
        }

        logger.info("DdlLoader inicializado. DDL path: " + ddlBasePath.toAbsolutePath());
    }

    /**
     * Carga la DDL desde cache o disco. Thread-safe.
     *
     * @param ddlName  Nombre de la DDL (sin extension .json)
     * @return         DdlDefinition cargada
     * @throws DdlNotFoundException si el archivo no existe o no se puede parsear
     */
    public DdlDefinition load(String ddlName) {
        return cache.computeIfAbsent(ddlName, this::loadFromDisk);
    }

    /** Invalida la cache de una DDL (util para recarga en caliente durante pruebas). */
    public void invalidate(String ddlName) {
        cache.remove(ddlName);
    }

    // ----------------------------------------------------------
    //  Carga desde disco
    // ----------------------------------------------------------

    private DdlDefinition loadFromDisk(String ddlName) {
        Path file = ddlBasePath.resolve(ddlName + ".json");
        if (!Files.exists(file)) {
            throw new DdlNotFoundException(
                "DDL no encontrada: " + file.toAbsolutePath()
                + " — verificar PROXY_DDL_PATH y que el archivo exista");
        }

        try {
            byte[] bytes = Files.readAllBytes(file);
            String json  = new String(bytes, StandardCharsets.UTF_8);
            DdlDefinition ddl = parseJson(json, ddlName);
            logger.info("DDL cargada: " + ddl);
            return ddl;

        } catch (IOException e) {
            throw new DdlNotFoundException(
                "Error leyendo DDL " + file + ": " + e.getMessage());
        }
    }

    // ----------------------------------------------------------
    //  Mini parser JSON para la estructura DDL controlada
    //
    //  Soporta el formato exacto generado por esta aplicación:
    //   { "ddl_name":"x", "request":[{...},{...}], "response":[{...}] }
    //  No es un parser JSON genérico.
    // ----------------------------------------------------------

    private DdlDefinition parseJson(String json, String ddlName) {
        List<DdlField> requestFields  = parseFieldArray(json, "request");
        List<DdlField> responseFields = parseFieldArray(json, "response");
        return new DdlDefinition(ddlName, requestFields, responseFields);
    }

    /**
     * Extrae el array JSON de una sección ("request" o "response")
     * y parsea cada objeto campo dentro de él.
     */
    private List<DdlField> parseFieldArray(String json, String section) {
        Pattern sectionPat = Pattern.compile("\"" + section + "\"\\s*:\\s*\\[");
        Matcher m = sectionPat.matcher(json);
        if (!m.find()) {
            return Collections.emptyList();
        }

        // Encontrar el cierre del array usando conteo de brackets
        int arrayStart = m.end(); // justo después del '['
        int depth = 1;
        int arrayEnd = arrayStart;
        for (int i = arrayStart; i < json.length() && depth > 0; i++) {
            char c = json.charAt(i);
            if      (c == '[') depth++;
            else if (c == ']') { depth--; arrayEnd = i; }
        }

        String arrayContent = json.substring(arrayStart, arrayEnd);
        return parseFieldObjects(arrayContent);
    }

    /**
     * Parsea los objetos campo individuales dentro del contenido de un array.
     * Cada objeto tiene la forma: { "field":"x", "length":N, "type":"y", "json_path":"z" }
     */
    private List<DdlField> parseFieldObjects(String arrayContent) {
        List<DdlField> fields = new ArrayList<>();
        int depth    = 0;
        int objStart = -1;

        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '{') {
                depth++;
                if (depth == 1) objStart = i;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String objJson = arrayContent.substring(objStart, i + 1);
                    DdlField field = parseFieldObject(objJson);
                    if (field != null) fields.add(field);
                    objStart = -1;
                }
            }
        }

        return fields;
    }

    /** Parsea un objeto campo individual y construye un DdlField. */
    private DdlField parseFieldObject(String objJson) {
        String name     = extractString(objJson, "field");
        String type     = extractString(objJson, "type");
        String jsonPath = extractString(objJson, "json_path");
        int    length   = extractInt(objJson,    "length");

        if (name == null || name.isEmpty()) {
            logger.warn("Campo DDL sin 'field' — ignorado: " + objJson);
            return null;
        }
        if (length <= 0) {
            logger.warn("Campo DDL '" + name + "' sin 'length' válido — usando 1");
            length = 1;
        }
        return new DdlField(name, length,
            type != null ? type : "string",
            jsonPath);
    }

    // ----------------------------------------------------------
    //  Extracción de valores JSON por clave (para objetos simples)
    // ----------------------------------------------------------

    private String extractString(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private int extractInt(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    // ----------------------------------------------------------
    //  Excepción
    // ----------------------------------------------------------

    public static class DdlNotFoundException extends RuntimeException {
        public DdlNotFoundException(String message) { super(message); }
    }
}
