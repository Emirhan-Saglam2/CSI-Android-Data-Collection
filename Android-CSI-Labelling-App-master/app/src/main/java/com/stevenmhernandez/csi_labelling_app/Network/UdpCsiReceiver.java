package com.stevenmhernandez.csi_labelling_app.Network;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class UdpCsiReceiver {

    /*
     * Tek UDP soketi farklı IP adreslerindeki tüm ESP32 cihazlarından
     * paket kabul edebilir.
     */
    private static final int MAX_UDP_PACKET_BYTES = 65_507;
    private static final int REQUESTED_RECEIVE_BUFFER_BYTES = 1_048_576;

    public interface Listener {
        void onPacket(String message, String sourceIp);
        void onError(Exception error);
    }

    private final int port;
    private final Listener listener;

    private volatile boolean running = false;
    private volatile DatagramSocket socket;
    private volatile Thread receiverThread;
    private volatile int actualReceiveBufferBytes = 0;

    public UdpCsiReceiver(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) {
            return;
        }

        running = true;

        receiverThread = new Thread(() -> {
            DatagramSocket localSocket = null;

            try {
                /*
                 * Soketi önce bağlantısız oluşturuyoruz. Böylece bind işleminden
                 * önce işletim sisteminin UDP alma tamponunu büyütebiliyoruz.
                 */
                localSocket = new DatagramSocket(null);
                localSocket.setReceiveBufferSize(
                        REQUESTED_RECEIVE_BUFFER_BYTES
                );
                localSocket.bind(new InetSocketAddress(port));

                socket = localSocket;
                actualReceiveBufferBytes =
                        localSocket.getReceiveBufferSize();

                /*
                 * Bir UDP datagramının taşıyabileceği en büyük güvenli veri alanı.
                 * Böylece uzun CSI satırları sessizce kesilmez.
                 */
                byte[] buffer = new byte[MAX_UDP_PACKET_BYTES];

                while (running) {
                    DatagramPacket packet =
                            new DatagramPacket(buffer, buffer.length);

                    localSocket.receive(packet);

                    if (!running) {
                        break;
                    }

                    String message = new String(
                            packet.getData(),
                            packet.getOffset(),
                            packet.getLength(),
                            StandardCharsets.UTF_8
                    );

                    String sourceIp =
                            packet.getAddress().getHostAddress();

                    /*
                     * Paket ana arayüz iş parçacığına gönderilmiyor.
                     * Doğrulama ve dosya kuyruğuna ekleme daha sonra burada,
                     * UDP alıcı iş parçacığında yapılacak.
                     */
                    try {
                        listener.onPacket(message, sourceIp);
                    } catch (Exception callbackError) {
                        listener.onError(callbackError);
                    }
                }

            } catch (Exception error) {
                if (running) {
                    listener.onError(error);
                }
            } finally {
                if (localSocket != null && !localSocket.isClosed()) {
                    localSocket.close();
                }

                if (socket == localSocket) {
                    socket = null;
                }

                running = false;
            }
        }, "UdpCsiReceiver");

        receiverThread.start();
    }

    public synchronized void stop() {
        running = false;

        DatagramSocket currentSocket = socket;

        if (currentSocket != null && !currentSocket.isClosed()) {
            currentSocket.close();
        }

        Thread currentThread = receiverThread;

        if (currentThread != null) {
            currentThread.interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getActualReceiveBufferBytes() {
        return actualReceiveBufferBytes;
    }
}