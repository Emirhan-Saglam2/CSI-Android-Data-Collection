package com.stevenmhernandez.csi_labelling_app.Network;

import android.os.Handler;
import android.os.Looper;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;

public class UdpCsiReceiver {

    public interface Listener {
        void onPacket(String message, String sourceIp);
        void onError(Exception error);
    }

    private final int port;
    private final Listener listener;
    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private DatagramSocket socket;
    private Thread receiverThread;

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
            try {
                socket = new DatagramSocket(port);
                byte[] buffer = new byte[8192];

                while (running) {
                    DatagramPacket packet =
                            new DatagramPacket(buffer, buffer.length);

                    socket.receive(packet);

                    String message = new String(
                            packet.getData(),
                            packet.getOffset(),
                            packet.getLength(),
                            StandardCharsets.UTF_8
                    );

                    String sourceIp =
                            packet.getAddress().getHostAddress();

                    mainHandler.post(() ->
                            listener.onPacket(message, sourceIp)
                    );
                }

            } catch (Exception error) {
                if (running) {
                    mainHandler.post(() ->
                            listener.onError(error)
                    );
                }
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }

                socket = null;
                running = false;
            }
        }, "UdpCsiReceiver");

        receiverThread.start();
    }

    public synchronized void stop() {
        running = false;

        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}