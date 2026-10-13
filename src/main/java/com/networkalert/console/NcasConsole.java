package com.networkalert.console;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.networkalert.protocol.Frame;
import com.networkalert.protocol.FrameDecoder;
import com.networkalert.protocol.FrameEncoder;
import com.networkalert.protocol.MessageType;
import com.networkalert.protocol.messages.ConsoleCommand;
import com.networkalert.protocol.messages.ConsoleResponse;
import com.networkalert.protocol.messages.DisconnectMessage;
import com.networkalert.protocol.messages.ForceCheckResponse;
import com.networkalert.protocol.messages.ReportResultMessage;
import com.networkalert.util.LoggerUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bàn điều khiển Quản trị mạng NCAS Console (NCAS-P v1).
 * <p>
 * Hỗ trợ 2 chế độ vận hành:
 * 1. <b>Interactive REPL (Shell tương tác)</b>: Gõ lệnh trực tiếp với Command Prompt `NCAS-Admin> `.
 * 2. <b>One-Shot Execution (Lệnh đơn lẻ)</b>: Thực thi một lệnh cụ thể rồi thoát, phục vụ Scripting, Cron, DevOps pipeline.
 * </p>
 */
public class NcasConsole {
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ConsoleConfig config;

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    private volatile boolean running = false;
    private volatile boolean connected = false;
    private volatile boolean streaming = false;

    private final AtomicReference<CompletableFuture<ConsoleResponse>> pendingCommand = new AtomicReference<>();
    private Thread listenerThread;

    public NcasConsole(ConsoleConfig config) {
        this.config = config != null ? config : new ConsoleConfig();
    }

    /**
     * Khởi động kết nối tới NCAS Server
     */
    public synchronized void connect() throws IOException {
        if (connected) return;

        if (config.isTlsEnabled()) {
            Socket rawSocket = new Socket();
            rawSocket.connect(new java.net.InetSocketAddress(config.getServerHost(), config.getServerPort()), 5000);
            rawSocket.setTcpNoDelay(true);

            javax.net.ssl.SSLContext sslCtx;
            try {
                sslCtx = com.networkalert.security.TlsContextFactory.createClientSSLContext(config.getServerFingerprints());
            } catch (Exception e) {
                try { rawSocket.close(); } catch (Exception ignored) {}
                throw new IOException("Không thể khởi tạo ngữ cảnh TLS Console: " + e.getMessage(), e);
            }

            javax.net.ssl.SSLSocketFactory ssf = sslCtx.getSocketFactory();
            javax.net.ssl.SSLSocket sslSocket = (javax.net.ssl.SSLSocket) ssf.createSocket(
                    rawSocket, config.getServerHost(), config.getServerPort(), true);
            com.networkalert.security.TlsContextFactory.configureClientSocket(sslSocket);

            try {
                sslSocket.startHandshake();
            } catch (Exception e) {
                try { sslSocket.close(); } catch (Exception ignored) {}
                throw new IOException("Lỗi bắt tay TLS hoặc Fingerprint không khớp: " + e.getMessage(), e);
            }

            socket = sslSocket;
        } else {
            socket = new Socket(config.getServerHost(), config.getServerPort());
            socket.setTcpNoDelay(true);
        }

        in = new BufferedInputStream(socket.getInputStream(), 16384);
        out = new BufferedOutputStream(socket.getOutputStream(), 16384);
        connected = true;
        running = true;

        // Khởi động luồng đọc nền nhận kết quả phản hồi và Stream sự kiện thời gian thực
        listenerThread = new Thread(this::runReadLoop, "NCAS-ConsoleReader");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    /**
     * Vòng lặp nhận và điều phối Frame trả về từ Server
     */
    private void runReadLoop() {
        while (running && connected && !socket.isClosed()) {
            try {
                Frame frame = FrameDecoder.readFrame(in);
                if (frame == null) {
                    // Server ngắt kết nối an toàn (EOF)
                    break;
                }

                MessageType type = frame.getType();

                if (type == MessageType.CONSOLE_COMMAND_RESP) {
                    ConsoleResponse resp = frame.parsePayload(ConsoleResponse.class);
                    CompletableFuture<ConsoleResponse> future = pendingCommand.getAndSet(null);
                    if (future != null) {
                        future.complete(resp);
                    }
                } else if (type == MessageType.REPORT_RESULT) {
                    ReportResultMessage rep = frame.parsePayload(ReportResultMessage.class);
                    if (streaming && rep != null) {
                        String st = rep.isSuccess() ? "🟢 ONLINE" : "🔴 OFFLINE";
                        System.out.printf("\n📡 [%s STREAM] Target #%d: %s | %d ms | %s\nNCAS-Admin> ",
                                LocalDateTime.now().format(TIME_FMT),
                                rep.getTargetId(), st, rep.getLatencyMs(), rep.getMessage() != null ? rep.getMessage() : "");
                        System.out.flush();
                    }
                } else if (type == MessageType.FORCE_CHECK_RESP) {
                    ForceCheckResponse fresp = frame.parsePayload(ForceCheckResponse.class);
                    if (streaming && fresp != null && fresp.getResult() != null) {
                        ReportResultMessage r = fresp.getResult();
                        System.out.printf("\n⚡ [%s FORCE-PROBE] Target #%d: %s (%d ms)\nNCAS-Admin> ",
                                LocalDateTime.now().format(TIME_FMT),
                                fresp.getTargetId(), r.isSuccess() ? "🟢 THÀNH CÔNG" : "🔴 THẤT BẠI", r.getLatencyMs());
                        System.out.flush();
                    }
                } else if (type == MessageType.DISCONNECT) {
                    DisconnectMessage disc = frame.parsePayload(DisconnectMessage.class);
                    System.out.println("\n[Console] Server đã ngắt kết nối: " + (disc != null ? disc.getReason() : ""));
                    break;
                }
            } catch (Exception e) {
                if (!running) break;
                System.out.println("\n[Console] Mất kết nối tới Server: " + e.getMessage());
                break;
            }
        }

        connected = false;
        CompletableFuture<ConsoleResponse> f = pendingCommand.getAndSet(null);
        if (f != null && !f.isDone()) {
            f.completeExceptionally(new IOException("Đứt kết nối tới Server"));
        }
    }

    /**
     * Gửi lệnh Console Command và chờ phản hồi ConsoleResponse
     */
    public synchronized ConsoleResponse executeCommand(String command, Map<String, String> params) throws Exception {
        if (!connected || socket == null || socket.isClosed()) {
            throw new IOException("Console chưa kết nối tới Server!");
        }

        CompletableFuture<ConsoleResponse> future = new CompletableFuture<>();
        pendingCommand.set(future);

        ConsoleCommand cmd = new ConsoleCommand(command, params);
        Frame frame = Frame.create(MessageType.CONSOLE_COMMAND, cmd);
        FrameEncoder.writeFrame(frame, out);

        return future.get(5, TimeUnit.SECONDS);
    }

    /**
     * Đăng ký nhận luồng sự kiện thời gian thực (Real-time Event Stream)
     */
    public synchronized void subscribeStream() throws IOException {
        if (!connected || socket == null || socket.isClosed()) {
            throw new IOException("Console chưa kết nối tới Server!");
        }
        Frame frame = Frame.empty(MessageType.CONSOLE_SUBSCRIBE);
        FrameEncoder.writeFrame(frame, out);
        streaming = true;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }

    public boolean isStreaming() {
        return streaming;
    }

    /**
     * Đóng Console an toàn
     */
    public synchronized void disconnect() {
        if (!running) return;
        running = false;
        connected = false;

        if (socket != null && !socket.isClosed()) {
            try {
                Frame disc = Frame.create(MessageType.DISCONNECT, DisconnectMessage.of("CONSOLE_EXIT", "Người dùng thoát Console"));
                FrameEncoder.writeFrame(disc, out);
            } catch (Exception ignored) {}
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    // --- Các hàm tiện ích thao tác lệnh CLI ---

    public void printAgents() {
        try {
            ConsoleResponse resp = executeCommand("list-agents", null);
            if (resp.isSuccess() && resp.getDataJson() != null) {
                Type type = new TypeToken<List<Map<String, Object>>>(){}.getType();
                List<Map<String, Object>> agents = GSON.fromJson(resp.getDataJson(), type);
                System.out.println(AsciiTableFormatter.formatAgents(agents));
            } else {
                System.out.println("❌ Lỗi: " + resp.getMessage());
            }
        } catch (Exception e) {
            System.out.println("❌ Lỗi lấy danh sách Agent: " + e.getMessage());
        }
    }

    public void printTargets() {
        try {
            ConsoleResponse resp = executeCommand("list-targets", null);
            if (resp.isSuccess() && resp.getDataJson() != null) {
                Type type = new TypeToken<List<Map<String, Object>>>(){}.getType();
                List<Map<String, Object>> targets = GSON.fromJson(resp.getDataJson(), type);
                System.out.println(AsciiTableFormatter.formatTargets(targets));
            } else {
                System.out.println("❌ Lỗi: " + resp.getMessage());
            }
        } catch (Exception e) {
            System.out.println("❌ Lỗi lấy danh sách Mục tiêu: " + e.getMessage());
        }
    }

    public void forceCheck(int targetId) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("targetId", String.valueOf(targetId));
            ConsoleResponse resp = executeCommand("check-now", params);
            if (resp.isSuccess()) {
                System.out.println("✅ " + resp.getMessage());
            } else {
                System.out.println("❌ " + resp.getMessage());
            }
        } catch (Exception e) {
            System.out.println("❌ Lỗi gửi yêu cầu Force Check: " + e.getMessage());
        }
    }

    public void rebalance() {
        try {
            ConsoleResponse resp = executeCommand("rebalance", null);
            System.out.println((resp.isSuccess() ? "✅ " : "❌ ") + resp.getMessage());
        } catch (Exception e) {
            System.out.println("❌ Lỗi yêu cầu tái phân bổ: " + e.getMessage());
        }
    }

    public void printServerInfo() {
        try {
            ConsoleResponse resp = executeCommand("server-info", null);
            if (resp.isSuccess() && resp.getDataJson() != null) {
                Type type = new TypeToken<Map<String, Object>>(){}.getType();
                Map<String, Object> info = GSON.fromJson(resp.getDataJson(), type);

                long uptime = 0;
                Object uObj = info.get("uptimeSeconds");
                if (uObj instanceof Number) uptime = ((Number) uObj).longValue();
                long h = uptime / 3600, m = (uptime % 3600) / 60, s = uptime % 60;

                System.out.println("======================================================================");
                System.out.println("🖥️  THÔNG TIN MÁY CHỦ TRUNG TÂM NCAS SERVER");
                System.out.println("----------------------------------------------------------------------");
                System.out.printf("   • Địa chỉ lắng nghe: %s:%s\n", info.get("bindHost"), info.get("port"));
                System.out.printf("   • Thời gian hoạt động: %02d:%02d:%02d (%d giây)\n", h, m, s, uptime);
                System.out.printf("   • Số Agent trực tuyến: %s\n", info.get("activeAgents"));
                System.out.printf("   • Tổng số mục tiêu quản lý: %s\n", info.get("monitoredTargets"));
                System.out.printf("   • Bộ nhớ JVM: %s MB / %s MB trống\n", info.get("totalMemoryMb"), info.get("freeMemoryMb"));
                System.out.println("======================================================================");
            } else {
                System.out.println("❌ Lỗi: " + resp.getMessage());
            }
        } catch (Exception e) {
            System.out.println("❌ Lỗi lấy thông tin Server: " + e.getMessage());
        }
    }

    public void printHelp() {
        System.out.println("======================================================================");
        System.out.println("DANH SÁCH LỆNH ĐIỀU KHIỂN NCAS CONSOLE:");
        System.out.println("----------------------------------------------------------------------");
        System.out.println("  agents                  Hiển thị bảng danh sách các Agent đang trực tuyến");
        System.out.println("  targets                 Hiển thị bảng danh sách mục tiêu đo và trạng thái");
        System.out.println("  check <targetId>        Gửi lệnh đo tức thời (Force Check) tới Agent");
        System.out.println("  rebalance               Yêu cầu Server tái cân bằng tải mục tiêu cho Agent");
        System.out.println("  info, server-info       Hiển thị thông số hoạt động của NCAS Server");
        System.out.println("  stream, watch           Bật / tắt nhận stream kết quả đo thời gian thực");
        System.out.println("  clear                   Xóa sạch màn hình console");
        System.out.println("  help                    Xem trợ giúp này");
        System.out.println("  exit, quit              Thoát khỏi bàn điều khiển Console");
        System.out.println("======================================================================");
    }

    /**
     * Vòng lặp tương tác REPL chính của Console
     */
    public void startInteractiveRepl() {
        printBanner();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (running && connected) {
            System.out.print("NCAS-Admin> ");
            System.out.flush();

            String line;
            try {
                line = reader.readLine();
            } catch (Exception e) {
                break;
            }

            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+");
            String cmd = parts[0].toLowerCase();

            switch (cmd) {
                case "exit":
                case "quit":
                    disconnect();
                    return;

                case "help":
                case "?":
                    printHelp();
                    break;

                case "agents":
                case "list-agents":
                    printAgents();
                    break;

                case "targets":
                case "list-targets":
                    printTargets();
                    break;

                case "check":
                case "probe":
                case "check-now":
                    if (parts.length < 2) {
                        System.out.println("Cú pháp: check <targetId>");
                    } else {
                        try {
                            int tid = Integer.parseInt(parts[1]);
                            forceCheck(tid);
                        } catch (NumberFormatException e) {
                            System.out.println("Target ID phải là số nguyên!");
                        }
                    }
                    break;

                case "rebalance":
                    rebalance();
                    break;

                case "info":
                case "server-info":
                case "status":
                    printServerInfo();
                    break;

                case "stream":
                case "watch":
                    if (!streaming) {
                        try {
                            subscribeStream();
                            System.out.println("✅ Đã BẬT stream sự kiện thời gian thực từ Server (Gõ 'stream' lần nữa để tắt).");
                        } catch (Exception e) {
                            System.out.println("❌ Không thể đăng ký stream: " + e.getMessage());
                        }
                    } else {
                        streaming = false;
                        System.out.println("⏹️ Đã TẮT stream sự kiện thời gian thực.");
                    }
                    break;

                case "clear":
                case "cls":
                    System.out.print("\033[H\033[2J");
                    System.out.flush();
                    break;

                default:
                    System.out.println("Lệnh không nhận diện được: '" + cmd + "'. Gõ 'help' để xem danh sách.");
                    break;
            }
        }
    }

    private void printBanner() {
        System.out.println("======================================================================");
        System.out.println("🎮 NCAS INTERACTIVE ADMIN CONSOLE v1.0");
        System.out.printf("   • Kết nối tới Server: %s:%d\n", config.getServerHost(), config.getServerPort());
        System.out.println("   • Gõ 'help' để xem danh sách lệnh, 'exit' để thoát.");
        System.out.println("======================================================================");
    }

    public boolean isConnected() { return connected; }
    public boolean isRunning() { return running; }
    public ConsoleConfig getConfig() { return config; }

    /**
     * Entry point khởi chạy Console CLI
     */
    public static void main(String[] args) {
        LoggerUtil.ensureUtf8Console();

        ConsoleConfig config = ConsoleConfig.parse(args);
        if (!config.isTlsEnabled()) {
            com.networkalert.util.LoggerUtil.warn("⚠️  [AN NINH] NCAS Console đang chạy ở chế độ --no-tls! Kênh truyền không được mã hóa TLS.");
        } else {
            com.networkalert.util.LoggerUtil.info("🔒 [AN NINH] NCAS Console kích hoạt mã hóa an toàn TLS 1.2/1.3.");
        }
        NcasConsole console = new NcasConsole(config);

        Runtime.getRuntime().addShutdownHook(new Thread(console::disconnect, "NCAS-ConsoleShutdown"));

        try {
            console.connect();
        } catch (Exception e) {
            System.err.printf("❌ Không thể kết nối tới NCAS Server tại %s:%d: %s\n",
                    config.getServerHost(), config.getServerPort(), e.getMessage());
            System.exit(1);
            return;
        }

        // Chế độ One-Shot Command
        if (config.getOneShotCommand() != null) {
            String cmd = config.getOneShotCommand().toLowerCase();
            List<String> cmdArgs = config.getCommandArgs();

            try {
                if ("agents".equals(cmd) || "list-agents".equals(cmd)) {
                    console.printAgents();
                } else if ("targets".equals(cmd) || "list-targets".equals(cmd)) {
                    console.printTargets();
                } else if ("info".equals(cmd) || "server-info".equals(cmd)) {
                    console.printServerInfo();
                } else if ("rebalance".equals(cmd)) {
                    console.rebalance();
                } else if ("check".equals(cmd) || "check-now".equals(cmd)) {
                    if (!cmdArgs.isEmpty()) {
                        console.forceCheck(Integer.parseInt(cmdArgs.get(0)));
                    } else {
                        System.err.println("Cú pháp: check <targetId>");
                    }
                } else {
                    // Lệnh tùy chỉnh
                    ConsoleResponse r = console.executeCommand(cmd, null);
                    System.out.println(r.getMessage() + (r.getDataJson() != null ? "\n" + r.getDataJson() : ""));
                }
            } catch (Exception e) {
                System.err.println("Lỗi thực thi lệnh: " + e.getMessage());
            } finally {
                console.disconnect();
                System.exit(0);
            }
            return;
        }

        // Chế độ Stream-Only
        if (config.isStreamMode()) {
            try {
                console.subscribeStream();
                System.out.println("📡 Đang lắng nghe luồng sự kiện thời gian thực... (Bấm Ctrl + C để dừng)");
                // Chờ đợi luồng nhận
                console.listenerThread.join();
            } catch (Exception e) {
                System.err.println("Lỗi stream: " + e.getMessage());
            }
            return;
        }

        // Chế độ REPL tương tác
        console.startInteractiveRepl();
    }
}
