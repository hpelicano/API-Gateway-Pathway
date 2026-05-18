package com.tandem.ext.guardian;

import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stub de com.tandem.ext.guardian.Receive para simulación en WSL2.
 *
 * Reemplaza la implementación real de JToolkit que envuelve Guardian $RECEIVE.
 * Usa dos colas bloqueantes:
 *   IN_QUEUE   — SimRunner deposita buffers IPC de entrada
 *   REPLY_QUEUE — el servidor deposita los buffers de respuesta
 *
 * SimRunner llama a sendMessage() / waitForReply() para controlar la simulación.
 */
public class Receive implements AutoCloseable {

    private static final BlockingQueue<byte[]> IN_QUEUE    = new LinkedBlockingQueue<>();
    private static final BlockingQueue<byte[]> REPLY_QUEUE = new LinkedBlockingQueue<>();
    private static final AtomicInteger MSG_COUNTER         = new AtomicInteger(0);

    // Sentinel que indica al servidor que debe detenerse
    private static final byte[] POISON_PILL = new byte[0];

    private static final int READ_TIMEOUT_SECONDS = 30;

    private Receive() {}

    /** Equivalente a Receive.getInstance() de JToolkit. */
    public static Receive getInstance() {
        return new Receive();
    }

    /** No-op en simulación (JToolkit usa esto para filtrar system messages). */
    public void setSystemMessageMask(short mask) {}

    /** Simula la apertura de $RECEIVE en Guardian. */
    public void open() throws GuardianException {
        System.out.println("[MockReceive] $RECEIVE abierto (modo simulacion WSL2)");
    }

    /**
     * Bloquea hasta que SimRunner deposita un mensaje en IN_QUEUE.
     * Equivale a READUPDATEX en Guardian.
     */
    public int read(byte[] buffer, int maxLen, ReceiveInfo info) throws GuardianException {
        try {
            byte[] msg = IN_QUEUE.poll(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (msg == null) {
                throw new GuardianException("Timeout esperando mensaje en $RECEIVE ("
                    + READ_TIMEOUT_SECONDS + "s)");
            }
            // Poison pill: señal de stop desde SimRunner
            if (msg == POISON_PILL) {
                Thread.currentThread().interrupt();
                throw new GuardianException("SIMULATION_STOP");
            }

            int len = Math.min(msg.length, maxLen);
            System.arraycopy(msg, 0, buffer, 0, len);

            int counter = MSG_COUNTER.incrementAndGet();
            info.setFileNumber(counter);
            info.setSyncId(counter * 100);

            return len;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GuardianException("$RECEIVE interrumpido", e);
        }
    }

    /**
     * Equivale a Guardian REPLY. Deposita el buffer de respuesta en REPLY_QUEUE
     * para que SimRunner lo recupere y lo decodifique.
     */
    public void reply(byte[] buffer, int len, short errorNum) throws GuardianException {
        byte[] copy = Arrays.copyOf(buffer, len);
        REPLY_QUEUE.offer(copy);
        System.out.printf("[MockReceive] REPLY: %d bytes, errorNum=%d%n", len, errorNum);
    }

    @Override
    public void close() {
        System.out.println("[MockReceive] $RECEIVE cerrado");
    }

    // ============================================================
    //  Métodos de control para SimRunner
    // ============================================================

    /** Deposita un buffer IPC en la cola de entrada del servidor. */
    public static void sendMessage(byte[] msg) {
        IN_QUEUE.offer(msg);
    }

    /**
     * Espera la respuesta del servidor (bloqueante, timeout 15s).
     * Retorna null si no llega respuesta a tiempo.
     */
    public static byte[] waitForReply() throws InterruptedException {
        return REPLY_QUEUE.poll(15, TimeUnit.SECONDS);
    }

    /** Envía la señal de stop al servidor para terminar el bucle $RECEIVE. */
    public static void sendStop() {
        IN_QUEUE.offer(POISON_PILL);
    }
}
