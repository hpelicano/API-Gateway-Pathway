package com.nonstop.proxy.model;

/**
 * Respuesta que el Pathway Proxy Server envía al proceso XPNET via Guardian REPLY.
 *
 * Layout del buffer binario de respuesta (big-endian):
 *  Offset  Bytes  Campo
 *  0       2      version        (short, = 1)
 *  2       2      httpStatusCode (código HTTP de la API externa)
 *  4       2      errorCode      (0 = OK, >0 = error interno del proxy)
 *  6       16     correlationId  (ASCII, padded — echo del request)
 *  22      2      payloadLength  (short)
 *  24      var    payload        (UTF-8 JSON)
 */
public class IpcResponse {

    public static final short ERR_OK             = 0;
    public static final short ERR_DESERIALIZE    = 1;
    public static final short ERR_API_ERROR      = 2;
    public static final short ERR_API_TIMEOUT    = 3;
    public static final short ERR_API_UNAVAILABLE= 4;
    public static final short ERR_EMPTY_MSG      = 5;
    public static final short ERR_INTERNAL       = 99;

    private final short  version;
    private final int    httpStatusCode;
    private final short  errorCode;
    private final String correlationId;
    private final String payload;

    public IpcResponse(short version, int httpStatusCode, short errorCode,
                       String correlationId, String payload) {
        this.version        = version;
        this.httpStatusCode = httpStatusCode;
        this.errorCode      = errorCode;
        this.correlationId  = correlationId;
        this.payload        = payload;
    }

    /** Factory: respuesta exitosa con payload de la API externa. */
    public static IpcResponse success(String correlationId, int httpStatus, String responseBody) {
        return new IpcResponse((short) 1, httpStatus, ERR_OK, correlationId, responseBody);
    }

    /** Factory: respuesta de error estructurado. Compatible JDK 11. */
    public static IpcResponse error(String errorKey) {
        short code;
        switch (errorKey) {
            case "DESERIALIZE_ERROR": code = ERR_DESERIALIZE;     break;
            case "API_TIMEOUT":       code = ERR_API_TIMEOUT;      break;
            case "API_UNAVAILABLE":   code = ERR_API_UNAVAILABLE;  break;
            case "EMPTY_MSG":         code = ERR_EMPTY_MSG;        break;
            default:
                code = errorKey.startsWith("API_ERROR:") ? ERR_API_ERROR : ERR_INTERNAL;
        }
        String payload = "{\"error\":\"" + errorKey + "\"}";
        return new IpcResponse((short) 1, 0, code, "ERROR", payload);
    }

    public short  getVersion()        { return version; }
    public int    getHttpStatusCode() { return httpStatusCode; }
    public short  getErrorCode()      { return errorCode; }
    public String getCorrelationId()  { return correlationId; }
    public String getPayload()        { return payload; }
    public boolean isSuccess()        { return errorCode == ERR_OK; }

    @Override
    public String toString() {
        return String.format(
            "IpcResponse{httpStatus=%d, errorCode=%d, correlationId='%s', payloadLen=%d}",
            httpStatusCode, errorCode, correlationId,
            payload != null ? payload.length() : 0);
    }
}
