package com.networkalert.simulator;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trạm giả lập TCP Server phục vụ demo trực tiếp kịch bản sập kết nối / khôi phục kết nối
 */
public class MockTcpServer {
    private final int port;
    private ServerSocket serverSocket;
    private Thread listenerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int artificialDelayMs = 0;

    public MockTcpServer(int port) {
        this.port = port;
    }

    public synchronized boolean start() {
        if (running.get()) return true;
        try {
            serverSocket = new ServerSocket(port);
            running.set(true);
            listenerThread = new Thread(this::listenLoop, "MockTcpServer-" + port);
            listenerThread.setDaemon(true);
            listenerThread.start();
            System.out.println("[MockTcpServer] Đang chạy trên cổng TCP " + port);
            return true;
        } catch (IOException e) {
            System.err.println("[MockTcpServer] Lỗi khởi động trên cổng " + port + ": " + e.getMessage());
            return false;
        }
    }

    private void listenLoop() {
        while (running.get() && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                handleClient(clientSocket);
            } catch (IOException e) {
                if (!running.get()) break;
            }
        }
    }

    private void handleClient(Socket socket) {
        Thread worker = new Thread(() -> {
            try (socket) {
                if (artificialDelayMs > 0) {
                    Thread.sleep(artificialDelayMs);
                }
                OutputStream os = socket.getOutputStream();
                String response = "TCP_MOCK_SERVER_READY_PORT_" + port + "\n";
                os.write(response.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (Exception ignored) {
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        running.set(false);
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        System.out.println("[MockTcpServer] Đã dừng trên cổng " + port);
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getPort() {
        return port;
    }

    public void setArtificialDelayMs(int delayMs) {
        this.artificialDelayMs = delayMs;
    }

    public int getArtificialDelayMs() {
        return artificialDelayMs;
    }
}
