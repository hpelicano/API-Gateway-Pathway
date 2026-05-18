package com.nonstop.proxy.util;

import com.nonstop.proxy.model.IpcRequest;
import com.nonstop.proxy.model.IpcResponse;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * ============================================================
 *  MessageSerializer
 * ============================================================
 *  Serializa y deserializa los buffers binarios IPC que viajan
 *  entre el proceso XPNET (caller) y el Pathway Proxy Server.
 *
 *  IMPORTANTE: El layout binario debe coincidir exactamente con
 *  el que define el proceso XPNET en TAL/COBOL/C.
 *  Se usa big-endian (network byte order) por convención NonStop.
 * ============================================================
 */
public class MessageSerializer {

    // Offsets del buffer de REQUEST (ver IpcRequest)
    private static final int REQ_OFFSET_VERSION       = 0;   // 2 bytes
    private static final int REQ_OFFSET_MSGTYPE       = 2;   // 2 bytes
    private static final int REQ_OFFSET_CORRELATION   = 4;   // 16 bytes
    private static final int REQ_OFFSET_OPERATION     = 20;  // 32 bytes
    private static final int REQ_OFFSET_PAYLOAD_LEN   = 52;  // 2 bytes
    private static final int REQ_OFFSET_PAYLOAD       = 54;  // variable

    private static final int REQ_CORRELATION_LEN      = 16;
    private static final int REQ_OPERATION_LEN        = 32;

    // Offsets del buffer de RESPONSE (ver IpcResponse)
    private static final int RES_OFFSET_VERSION       = 0;   // 2 bytes
    private static final int RES_OFFSET_STATUSCODE    = 2;   // 2 bytes
    private static final int RES_OFFSET_ERRORCODE     = 4;   // 2 bytes
    private static final int RES_OFFSET_CORRELATION   = 6;   // 16 bytes
    private static final int RES_OFFSET_PAYLOAD_LEN   = 22;  // 2 bytes
    private static final int RES_OFFSET_PAYLOAD       = 24;  // variable

    private static final int RES_CORRELATION_LEN      = 16;
    private static final int RES_HEADER_SIZE          = 24;

    // ----------------------------------------------------------
    //  Deserialización: buffer binario → IpcRequest
    // ----------------------------------------------------------
    /**
     * Convierte el buffer binario recibido de $RECEIVE en un IpcRequest.
     *
     * @param buffer    Raw bytes leídos desde $RECEIVE
     * @param length    Cantidad de bytes válidos en el buffer
     * @return          IpcRequest deserializado
     * @throws DeserializationException si el buffer es inválido
     */
    public static IpcRequest deserialize(byte[] buffer, int length) throws DeserializationException {
        if (length < REQ_OFFSET_PAYLOAD) {
            throw new DeserializationException(
                "Buffer demasiado corto: " + length + " bytes (mínimo " + REQ_OFFSET_PAYLOAD + ")");
        }

        ByteBuffer buf = ByteBuffer.wrap(buffer, 0, length)
                                   .order(ByteOrder.BIG_ENDIAN);

        // Leer campos de header
        short version = buf.getShort(REQ_OFFSET_VERSION);
        if (version != 1) {
            throw new DeserializationException("Versión de protocolo no soportada: " + version);
        }

        short msgType = buf.getShort(REQ_OFFSET_MSGTYPE);

        // Leer correlationId (ASCII fijo, strip padding de espacios/nulls)
        String correlationId = readFixedString(buffer, REQ_OFFSET_CORRELATION, REQ_CORRELATION_LEN);

        // Leer operation path (ASCII fijo)
        String operation = readFixedString(buffer, REQ_OFFSET_OPERATION, REQ_OPERATION_LEN);

        // Leer payload (longitud + bytes UTF-8)
        short payloadLen = buf.getShort(REQ_OFFSET_PAYLOAD_LEN);
        String payload = "";

        if (payloadLen > 0) {
            if (REQ_OFFSET_PAYLOAD + payloadLen > length) {
                throw new DeserializationException(
                    "payloadLen=" + payloadLen + " excede el tamaño del buffer recibido=" + length);
            }
            payload = new String(buffer, REQ_OFFSET_PAYLOAD, payloadLen, StandardCharsets.UTF_8);
        }

        return new IpcRequest(version, msgType, correlationId, operation, payload);
    }

    // ----------------------------------------------------------
    //  Serialización: IpcResponse → buffer binario
    // ----------------------------------------------------------
    /**
     * Convierte un IpcResponse en el buffer binario que se envía
     * de vuelta al proceso XPNET via Guardian REPLY.
     *
     * @param response  La respuesta a serializar
     * @return          Buffer binario listo para REPLY
     */
    public static byte[] serialize(IpcResponse response) {
        byte[] correlationBytes = padOrTruncate(
                response.getCorrelationId(), RES_CORRELATION_LEN);

        byte[] payloadBytes = response.getPayload() != null
                ? response.getPayload().getBytes(StandardCharsets.UTF_8)
                : new byte[0];

        int totalSize = RES_HEADER_SIZE + payloadBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(totalSize)
                                   .order(ByteOrder.BIG_ENDIAN);

        buf.putShort(response.getVersion());                    // [0..1]  version
        buf.putShort((short) response.getHttpStatusCode());     // [2..3]  httpStatusCode
        buf.putShort(response.getErrorCode());                  // [4..5]  errorCode
        buf.put(correlationBytes);                              // [6..21] correlationId
        buf.putShort((short) payloadBytes.length);              // [22..23] payloadLength
        buf.put(payloadBytes);                                  // [24...] payload

        return buf.array();
    }

    // ----------------------------------------------------------
    //  Utilidades internas
    // ----------------------------------------------------------

    /**
     * Lee una cadena ASCII de longitud fija desde un offset del buffer,
     * eliminando caracteres de padding (null bytes y espacios).
     */
    private static String readFixedString(byte[] buffer, int offset, int length) {
        byte[] fieldBytes = Arrays.copyOfRange(buffer, offset, offset + length);
        // Trim null bytes y espacios (padding NonStop típico)
        int end = length;
        while (end > 0 && (fieldBytes[end - 1] == 0x00 || fieldBytes[end - 1] == 0x20)) {
            end--;
        }
        return new String(fieldBytes, 0, end, StandardCharsets.US_ASCII);
    }

    /**
     * Convierte un string a bytes de longitud fija, con padding de null bytes
     * si es más corto, o truncado si es más largo.
     */
    private static byte[] padOrTruncate(String value, int length) {
        byte[] result = new byte[length]; // inicializado en 0x00
        if (value != null) {
            byte[] src = value.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(src, 0, result, 0, Math.min(src.length, length));
        }
        return result;
    }

    // ----------------------------------------------------------
    //  Excepción de deserialización
    // ----------------------------------------------------------
    public static class DeserializationException extends Exception {
        public DeserializationException(String message) {
            super(message);
        }
    }
}
