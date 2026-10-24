package com.networkalert.chaos;

import com.networkalert.util.LoggerUtil;
import com.networkalert.util.NamedThreadFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proxy TCP hỗ trợ Inject lỗi có chủ đích (TCP Fault Proxy) theo ERRATA §E14.2 & §E14.3.
 * <p>
 * Nằm ở giữa Server và Agent để mô phỏng môi trường mạng khắc nghiệt:
 * <ul>
 *   <li><b>Cắt kết nối đột ngột (drop):</b> Ngắt socket 2 chiều ngay tức khắc</li>
 *   <li><b>Độ trễ mạng (latency injection):</b> Chèn độ trễ biến thiên trước khi chuyển tiếp dữ liệu</li>
 *   <li><b>Băm nhỏ byte (fragmentation):</b> Cắt nhỏ khung truyền thành từng mảnh 1-5 bytes</li>
 *   <li><b>Half-close:</b> Đóng một chiều (Client -> Server hoặc Server -> Client)</li>
 *   <li><b>Blackhole (lỗ đen):</b> Nuốt chửng dữ liệu mà không chuyển tiếp và không đóng kết nối</li>
 *   <li><b>Bóp băng thông (bandwidth throttle):</b> Giới hạn tốc độ truyền byte/giây</li>
 * </ul>
 * </p>
 */
public class TcpFaultProxy {

    public enum Direction {
        CLIENT_TO_SERVER,
        SERVER_TO_CLIENT
    }

    private final String bindHost;
    private final int bindPort;
    private volatile String targetHost;
    private volatile int targetPort;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    private final List<ProxyConnection> activeConnections = new CopyOnWriteArrayList<>();
    private final ExecutorService threadPool = Executors.newCachedThreadPool(
            new NamedThreadFactory("TcpFaultProxy", true));

    // Các cờ cấu hình hành vi lỗi (Fault parameters)
    private final AtomicLong delayMs = new AtomicLong(0);
    private final AtomicInteger fragmentChunkSize = new AtomicInteger(0);
    private final AtomicBoolean blackhole = new AtomicBoolean(false);
    private final AtomicBoolean halfCloseClientToServer = new AtomicBoolean(false);
    private final AtomicBoolean halfCloseServerToClient = new AtomicBoolean(false);
    private final AtomicLong bandwidthLimitBps = new AtomicLong(0);

    // Thống kê proxy
    private final AtomicLong connectionsAccepted = new AtomicLong(0);
    private final AtomicLong connectionsDropped = new AtomicLong(0);
    private final AtomicLong bytesClientToServer = new AtomicLong(0);
    private final AtomicLong bytesServerToClient = new AtomicLong(0);

    public TcpFaultProxy(String targetHost, int targetPort) {
        this("127.0.0.1", 0, targetHost, targetPort);
    }

    public TcpFaultProxy(String bindHost, int bindPort, String targetHost, int targetPort) {
        this.bindHost = bindHost != null ? bindHost : "0.0.0.0";
        this.bindPort = bindPort;
        this.targetHost = targetHost != null ? targetHost : "127.0.0.1";
        this.targetPort = targetPort;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(bindHost, bindPort));
        this.running = true;

        threadPool.execute(this::acceptLoop);
        LoggerUtil.info(String.format("🔌 [TcpFaultProxy] Đã khởi chạy tại %s:%d -> đích %s:%d",
                bindHost, getPort(), targetHost, targetPort));
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}

        dropConnections();
        threadPool.shutdownNow();
        LoggerUtil.info("🔌 [TcpFaultProxy] Đã dừng hoàn toàn.");
    }

    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                connectionsAccepted.incrementAndGet();

                Socket upstreamSocket = new Socket();
                upstreamSocket.setTcpNoDelay(true);
                upstreamSocket.connect(new InetSocketAddress(targetHost, targetPort), 3000);

                ProxyConnection conn = new ProxyConnection(clientSocket, upstreamSocket);
                activeConnections.add(conn);

                threadPool.execute(() -> forwardStream(conn, clientSocket, upstreamSocket, Direction.CLIENT_TO_SERVER));
                threadPool.execute(() -> forwardStream(conn, upstreamSocket, clientSocket, Direction.SERVER_TO_CLIENT));

            } catch (Exception e) {
                if (running) {
                    LoggerUtil.warn("[TcpFaultProxy] Lỗi accept kết nối: " + e.getMessage());
                }
            }
        }
    }

    private void forwardStream(ProxyConnection conn, Socket source, Socket destination, Direction dir) {
        try (InputStream in = source.getInputStream();
             OutputStream out = destination.getOutputStream()) {

            byte[] buffer = new byte[8192];
            int read;

            while (running && !conn.isClosed() && !source.isClosed() && !destination.isClosed() &&
                    (read = in.read(buffer)) != -1) {

                // 1. Kiểm tra Blackhole: nuốt byte, không chuyển tiếp
                if (blackhole.get()) {
                    continue;
                }

                // 2. Kiểm tra Half-Close: đóng 1 chiều luồng dữ liệu
                if (dir == Direction.CLIENT_TO_SERVER && halfCloseClientToServer.get()) {
                    try { source.shutdownInput(); } catch (Exception ignored) {}
                    try { destination.shutdownOutput(); } catch (Exception ignored) {}
                    break;
                }
                if (dir == Direction.SERVER_TO_CLIENT && halfCloseServerToClient.get()) {
                    try { source.shutdownInput(); } catch (Exception ignored) {}
                    try { destination.shutdownOutput(); } catch (Exception ignored) {}
                    break;
                }

                // 3. Inject Độ trễ (Delay)
                long delay = delayMs.get();
                if (delay > 0) {
                    pause(delay);
                }

                // 4. Inject Băm nhỏ byte (Fragmentation)
                int fragSize = fragmentChunkSize.get();
                if (fragSize > 0) {
                    int offset = 0;
                    while (offset < read) {
                        int chunk = Math.min(fragSize, read - offset);
                        out.write(buffer, offset, chunk);
                        out.flush();
                        offset += chunk;
                        if (fragSize <= 5 && offset < read) {
                            pause(1);
                        }
                    }
                } else {
                    // 5. Bóp băng thông (Bandwidth throttle)
                    long limit = bandwidthLimitBps.get();
                    if (limit > 0) {
                        long expectedWait = (read * 1000L) / limit;
                        if (expectedWait > 0) {
                            pause(expectedWait);
                        }
                    }
                    out.write(buffer, 0, read);
                    out.flush();
                }

                // Cập nhật thống kê byte
                if (dir == Direction.CLIENT_TO_SERVER) {
                    bytesClientToServer.addAndGet(read);
                } else {
                    bytesServerToClient.addAndGet(read);
                }
            }

        } catch (Exception ignored) {
        } finally {
            conn.close();
            activeConnections.remove(conn);
        }
    }

    private void pause(long ms) {
        if (ms <= 0) return;
        try {
            new CountDownLatch(1).await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Cắt đứt toàn bộ các kết nối socket đang hoạt động ngay lập tức
     */
    public void dropConnections() {
        for (ProxyConnection conn : activeConnections) {
            conn.close();
            connectionsDropped.incrementAndGet();
        }
        activeConnections.clear();
    }

    public void setTarget(String host, int port) {
        this.targetHost = host;
        this.targetPort = port;
    }

    public void setDelayMs(long delay) {
        this.delayMs.set(Math.max(0, delay));
    }

    public void setFragmentChunkSize(int chunkSize) {
        this.fragmentChunkSize.set(Math.max(0, chunkSize));
    }

    public void setBlackhole(boolean blackhole) {
        this.blackhole.set(blackhole);
    }

    public void setHalfCloseClientToServer(boolean enable) {
        this.halfCloseClientToServer.set(enable);
    }

    public void setHalfCloseServerToClient(boolean enable) {
        this.halfCloseServerToClient.set(enable);
    }

    public void setBandwidthThrottle(long bytesPerSec) {
        this.bandwidthLimitBps.set(Math.max(0, bytesPerSec));
    }

    public void resetFaults() {
        delayMs.set(0);
        fragmentChunkSize.set(0);
        blackhole.set(false);
        halfCloseClientToServer.set(false);
        halfCloseServerToClient.set(false);
        bandwidthLimitBps.set(0);
    }

    public int getPort() {
        return (serverSocket != null && serverSocket.isBound()) ? serverSocket.getLocalPort() : 0;
    }

    public int getActiveConnectionCount() {
        return activeConnections.size();
    }

    public long getConnectionsAccepted() { return connectionsAccepted.get(); }
    public long getConnectionsDropped() { return connectionsDropped.get(); }
    public long getBytesClientToServer() { return bytesClientToServer.get(); }
    public long getBytesServerToClient() { return bytesServerToClient.get(); }

    public static class ProxyConnection {
        private final Socket clientSocket;
        private final Socket serverSocket;
        private volatile boolean closed = false;

        public ProxyConnection(Socket clientSocket, Socket serverSocket) {
            this.clientSocket = clientSocket;
            this.serverSocket = serverSocket;
        }

        public void close() {
            if (closed) return;
            closed = true;
            try { clientSocket.close(); } catch (Exception ignored) {}
            try { serverSocket.close(); } catch (Exception ignored) {}
        }

        public boolean isClosed() {
            return closed || clientSocket.isClosed() || serverSocket.isClosed();
        }
    }

    public static void main(String[] args) throws Exception {
        int bindPort = 9877;
        String targetHost = "ncas-server";
        int targetPort = 9876;
        for (int i = 0; i < args.length; i++) {
            if ("--listen-port".equals(args[i]) || "-p".equals(args[i])) {
                bindPort = Integer.parseInt(args[++i]);
            } else if ("--target-host".equals(args[i]) || "-h".equals(args[i])) {
                targetHost = args[++i];
            } else if ("--target-port".equals(args[i]) || "-tp".equals(args[i])) {
                targetPort = Integer.parseInt(args[++i]);
            }
        }
        TcpFaultProxy proxy = new TcpFaultProxy("0.0.0.0", bindPort, targetHost, targetPort);
        proxy.start();
        LoggerUtil.info(String.format("🔌 [FaultProxy] Sẵn sàng chuyển tiếp 0.0.0.0:%d -> %s:%d", bindPort, targetHost, targetPort));
        Runtime.getRuntime().addShutdownHook(new NamedThreadFactory("FaultProxy-Shutdown").newThread(proxy::stop));
    }
}
