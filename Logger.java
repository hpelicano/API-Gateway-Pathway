package com.nonstop.proxy.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * ============================================================
 *  Logger
 * ============================================================
 *  Logger simple para el Pathway Proxy Server.
 *
 *  En NonStop, stdout/stderr de un proceso Pathway son capturados
 *  por PATHMON y pueden redirigirse al sistema EMS (Event Management
 *  System). Por eso se loggea a stdout con un formato estructurado
 *  que EMS puede parsear.
 *
 *  Formato de log:
 *    [TIMESTAMP] [LEVEL] [CLASS] mensaje
 *
 *  Para integración completa con EMS se puede extender usando
 *  las clases de JToolkit com.tandem.ext.guardian.Ems*.
 * ============================================================
 */
public class Logger {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                             .withZone(ZoneOffset.UTC);

    private final String className;

    public Logger(String className) {
        this.className = className;
    }

    public void info(String message) {
        log("INFO ", message);
    }

    public void warn(String message) {
        log("WARN ", message);
    }

    public void error(String message) {
        log("ERROR", message);
    }

    public void debug(String message) {
        // Activar con variable de entorno PROXY_DEBUG=true
        if ("true".equalsIgnoreCase(System.getenv("PROXY_DEBUG"))) {
            log("DEBUG", message);
        }
    }

    private void log(String level, String message) {
        String timestamp = FORMATTER.format(Instant.now());
        // EMS en NonStop parsea líneas stdout del proceso Pathway
        System.out.printf("[%s] [%s] [%s] %s%n", timestamp, level, className, message);
        System.out.flush(); // Flush explícito: crítico en procesos NonStop sin TTY
    }
}
