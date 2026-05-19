package com.nonstop.proxy.model;

import java.util.Collections;
import java.util.Map;

/**
 * Mensaje IPC recibido desde un proceso XPNET, ya parseado desde el buffer de texto plano.
 *
 * El buffer de entrada desde $RECEIVE es texto plano key=value, un par por línea:
 *
 *   metodo=POST
 *   endpoint=/service/alta_cliente
 *   ddl_json=alta_cliente
 *   nombre=Juan
 *   apellido=Pérez
 *   dni=20345678901
 *   email=juan@banco.com
 *
 * Los tres primeros campos (metodo, endpoint, ddl_json) son de control del servidor.
 * El resto son datos de negocio que se mapean al JSON según la DDL correspondiente.
 *
 * MessageSerializer.parseBuffer() construye esta instancia y adjunta:
 *   - La DDL cargada desde disco
 *   - El JSON de payload ya armado para enviar a la API externa
 */
public class IpcRequest {

    private final String              method;         // GET, POST, PUT, DELETE
    private final String              endpoint;       // path del API Gateway
    private final String              ddlName;        // nombre del archivo DDL (sin .json)
    private final String              correlationId;  // generado o tomado del buffer
    private final Map<String, String> dataFields;     // campos de negocio del buffer
    private final String              jsonPayload;    // JSON armado con dataFields + DDL request
    private final DdlDefinition       ddl;            // DDL cargada desde disco

    public IpcRequest(String method, String endpoint, String ddlName,
                      String correlationId, Map<String, String> dataFields,
                      String jsonPayload, DdlDefinition ddl) {
        this.method        = method;
        this.endpoint      = endpoint;
        this.ddlName       = ddlName;
        this.correlationId = correlationId;
        this.dataFields    = Collections.unmodifiableMap(dataFields);
        this.jsonPayload   = jsonPayload;
        this.ddl           = ddl;
    }

    public String              getMethod()        { return method; }
    public String              getEndpoint()      { return endpoint; }
    public String              getDdlName()       { return ddlName; }
    public String              getCorrelationId() { return correlationId; }
    public Map<String, String> getDataFields()    { return dataFields; }
    public String              getJsonPayload()   { return jsonPayload; }
    public DdlDefinition       getDdl()           { return ddl; }

    // Mantiene compatibilidad con ApiGatewayClient que usa getHttpMethod() y getPayload()
    public String getHttpMethod() { return method; }
    public String getPayload()    { return jsonPayload; }
    public String getOperation()  { return endpoint; }

    @Override
    public String toString() {
        return String.format(
            "IpcRequest{method='%s', endpoint='%s', ddl='%s', corrId='%s', fields=%d, payloadLen=%d}",
            method, endpoint, ddlName, correlationId,
            dataFields != null ? dataFields.size() : 0,
            jsonPayload != null ? jsonPayload.length() : 0);
    }
}
