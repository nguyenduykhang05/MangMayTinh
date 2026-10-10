package com.networkalert.simulator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Trạm giả lập HTTP Web Server phục vụ demo kiểm tra giao thức tầng Ứng dụng
 */
public class MockHttpServer {
    private final int port;
    private HttpServer server;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger responseCode = new AtomicInteger(200);
    private final AtomicInteger artificialDelayMs = new AtomicInteger(0);

    public MockHttpServer(int port) {
        this.port = port;
    }

    public synchronized boolean start() {
        if (running.get()) return true;
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new MockHandler());
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            running.set(true);
            System.out.println("[MockHttpServer] Đang chạy trên cổng HTTP " + port);
            return true;
        } catch (IOException e) {
            System.err.println("[MockHttpServer] Lỗi khởi động HTTP Server trên cổng " + port + ": " + e.getMessage());
            return false;
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (server != null) {
            server.stop(0);
        }
        System.out.println("[MockHttpServer] Đã dừng trên cổng HTTP " + port);
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getPort() {
        return port;
    }

    public int getResponseCode() {
        return responseCode.get();
    }

    public void setResponseCode(int code) {
        this.responseCode.set(code);
    }

    public int getArtificialDelayMs() {
        return artificialDelayMs.get();
    }

    public void setArtificialDelayMs(int delayMs) {
        this.artificialDelayMs.set(delayMs);
    }

    private class MockHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            int delay = artificialDelayMs.get();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ignored) {}
            }

            int code = responseCode.get();
            String responseBody = "{\"status\": " + code + ", \"message\": \"Mock HTTP Server Response\", \"time\": " + System.currentTimeMillis() + "}";
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(code, bytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
