package com.networkalert.simulator;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trạm giả lập UDP Server phục vụ demo kiểm tra giao thức phi kết nối
 */
public class MockUdpServer {
    private final int port;
    private DatagramSocket socket;
    private Thread listenerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MockUdpServer(int port) {
        this.port = port;
    }

    public synchronized boolean start() {
        if (running.get()) return true;
        try {
            socket = new DatagramSocket(port);
            running.set(true);
            listenerThread = new Thread(this::listenLoop, "MockUdpServer-" + port);
            listenerThread.setDaemon(true);
            listenerThread.start();
            System.out.println("[MockUdpServer] Đang chạy trên cổng UDP " + port);
            return true;
        } catch (IOException e) {
            System.err.println("[MockUdpServer] Lỗi khởi động trên cổng " + port + ": " + e.getMessage());
            return false;
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[1024];
        while (running.get() && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                // Gửi lại phản hồi Echo
                byte[] responseData = ("PONG_UDP_ECHO_FROM_PORT_" + port).getBytes(StandardCharsets.UTF_8);
                DatagramPacket reply = new DatagramPacket(
                        responseData, responseData.length, packet.getAddress(), packet.getPort()
                );
                socket.send(reply);
            } catch (IOException e) {
                if (!running.get()) break;
            }
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        System.out.println("[MockUdpServer] Đã dừng trên cổng " + port);
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getPort() {
        return port;
    }
}
