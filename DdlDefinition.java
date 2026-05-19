package com.nonstop.proxy.model;

import java.util.Collections;
import java.util.List;

/**
 * Definición completa de una DDL: nombre + campos de request + campos de response.
 *
 * Corresponde a un archivo JSON en PROXY_DDL_PATH con la estructura:
 * {
 *   "ddl_name":    "consulta_saldo",
 *   "description": "Consulta de saldo de cuenta corriente",
 *   "request":  [ { "field": "cuenta", "length": 16, "type": "string", "json_path": "cuenta" }, ... ],
 *   "response": [ { "field": "cod_ret","length": 4,  "type": "numeric","json_path": "codigo" }, ... ]
 * }
 *
 * La sección request define qué campos del buffer IPC se mapean al JSON de la API.
 * La sección response define cómo convertir el JSON de respuesta de la API a un
 * buffer de longitud fija para enviar de vuelta al proceso XPNET.
 */
public class DdlDefinition {

    private final String         ddlName;
    private final List<DdlField> requestFields;
    private final List<DdlField> responseFields;

    public DdlDefinition(String ddlName,
                         List<DdlField> requestFields,
                         List<DdlField> responseFields) {
        this.ddlName        = ddlName;
        this.requestFields  = Collections.unmodifiableList(requestFields);
        this.responseFields = Collections.unmodifiableList(responseFields);
    }

    public String         getDdlName()        { return ddlName; }
    public List<DdlField> getRequestFields()  { return requestFields; }
    public List<DdlField> getResponseFields() { return responseFields; }

    /** Longitud total del buffer de respuesta (suma de lengths de todos los campos response). */
    public int totalResponseLength() {
        int total = 0;
        for (DdlField f : responseFields) total += f.getLength();
        return total;
    }

    @Override
    public String toString() {
        return String.format("DdlDefinition{name='%s', reqFields=%d, resFields=%d, responseLen=%d}",
            ddlName, requestFields.size(), responseFields.size(), totalResponseLength());
    }
}
