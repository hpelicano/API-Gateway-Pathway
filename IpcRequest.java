package com.nonstop.proxy.model;

/**
 * Mensaje IPC recibido desde un proceso XPNET a través de $RECEIVE.
 *
 * Layout del buffer binario (big-endian):
 *  Offset  Bytes  Campo
 *  0       2      version       (short, = 1)
 *  2       2      msgType       (1=GET 2=POST 3=PUT 4=DELETE)
 *  4       16     correlationId (ASCII, padded)
 *  20      32     operation     (path del API, ASCII, padded)
 *  52      2      payloadLength (short)
 *  54      var    payload       (UTF-8 JSON)
 */
public class IpcRequest {

    public static final short MSG_TYPE_HTTP_GET    = 0x01;
    public static final short MSG_TYPE_HTTP_POST   = 0x02;
    public static final short MSG_TYPE_HTTP_PUT    = 0x03;
    public static final short MSG_TYPE_HTTP_DELETE = 0x04;

    private final short  version;
    private final short  msgType;
    private final String correlationId;
    private final String operation;
    private final String payload;

    public IpcRequest(short version, short msgType,
                      String correlationId, String operation, String payload) {
        this.version       = version;
        this.msgType       = msgType;
        this.correlationId = correlationId;
        this.operation     = operation;
        this.payload       = payload;
    }

    public short  getVersion()       { return version; }
    public short  getMsgType()       { return msgType; }
    public String getCorrelationId() { return correlationId; }
    public String getOperation()     { return operation; }
    public String getPayload()       { return payload; }

    public boolean isGet()    { return msgType == MSG_TYPE_HTTP_GET; }
    public boolean isPost()   { return msgType == MSG_TYPE_HTTP_POST; }
    public boolean isPut()    { return msgType == MSG_TYPE_HTTP_PUT; }
    public boolean isDelete() { return msgType == MSG_TYPE_HTTP_DELETE; }

    /** Devuelve el método HTTP correspondiente al msgType. Compatible JDK 11. */
    public String getHttpMethod() {
        if (msgType == MSG_TYPE_HTTP_GET)    return "GET";
        if (msgType == MSG_TYPE_HTTP_POST)   return "POST";
        if (msgType == MSG_TYPE_HTTP_PUT)    return "PUT";
        if (msgType == MSG_TYPE_HTTP_DELETE) return "DELETE";
        throw new IllegalStateException("msgType desconocido: " + msgType);
    }

    @Override
    public String toString() {
        return String.format(
            "IpcRequest{version=%d, msgType=%d, correlationId='%s', operation='%s', payloadLen=%d}",
            version, msgType, correlationId, operation,
            payload != null ? payload.length() : 0);
    }
}
