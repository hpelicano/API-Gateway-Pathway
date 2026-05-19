package com.nonstop.proxy.model;

/**
 * Define un campo dentro de una DDL (sección request o response).
 *
 * Cada campo corresponde a un elemento del COBOL DDL original:
 *   name      → nombre del campo en el buffer IPC (key=value)
 *   length    → longitud máxima en bytes (igual que PIC X(n) en COBOL)
 *   type      → "string"  → alineado izquierda, padded con espacios
 *               "numeric" → alineado derecha, padded con ceros
 *   jsonPath  → path dot-notation en el JSON de la API
 *               En request: la clave que se usará en el JSON de envío
 *               En response: de dónde extraer el valor del JSON de respuesta
 *               Ejemplos: "cuenta", "data.saldo", "header.codigo_ret"
 */
public class DdlField {

    private final String name;
    private final int    length;
    private final String type;
    private final String jsonPath;

    public DdlField(String name, int length, String type, String jsonPath) {
        this.name     = name;
        this.length   = length;
        this.type     = (type != null && !type.isEmpty()) ? type : "string";
        this.jsonPath = (jsonPath != null && !jsonPath.isEmpty()) ? jsonPath : name;
    }

    public String getName()     { return name; }
    public int    getLength()   { return length; }
    public String getType()     { return type; }
    public String getJsonPath() { return jsonPath; }

    public boolean isNumeric()  { return "numeric".equalsIgnoreCase(type); }

    @Override
    public String toString() {
        return String.format("DdlField{name='%s', length=%d, type='%s', jsonPath='%s'}",
            name, length, type, jsonPath);
    }
}
