package com.networkalert;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Ứng dụng Server TCP độc lập phục vụ kịch bản thử nghiệm kết nối
 * Có thể chạy riêng biệt bằng lệnh "Run File" trong NetBeans
 */
public class DemoTcpServer {
    private static final int PORT = 9000;

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("   DEMO TCP SERVER CHO BÀI TẬP LẬP TRÌNH MẠNG     ");
        System.out.println("   Đang lắng nghe trên cổng: " + PORT);
        System.out.println("   Nhấn Enter hoặc gõ 'exit' để tắt Server        ");
        System.out.println("==================================================");

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            // Luồng đọc lệnh tắt server từ bàn phím
            Thread consoleThread = new Thread(() -> {
                Scanner scanner = new Scanner(System.in);
                scanner.nextLine();
                System.out.println("[DemoTcpServer] Nhận tín hiệu dừng! Đang đóng server...");
                try {
                    serverSocket.close();
                } catch (IOException ignored) {}
                System.exit(0);
            });
            consoleThread.setDaemon(true);
            consoleThread.start();

            while (!serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("[DemoTcpServer] Client kết nối từ: " + clientSocket.getRemoteSocketAddress());

                    new Thread(() -> {
                        try (clientSocket; OutputStream os = clientSocket.getOutputStream()) {
                            String msg = "HELLO_FROM_DEMO_TCP_SERVER_PORT_9000\n";
                            os.write(msg.getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        } catch (IOException ignored) {}
                    }).start();
                } catch (IOException e) {
                    if (serverSocket.isClosed()) break;
                    System.err.println("[DemoTcpServer] Lỗi client: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[DemoTcpServer] Không thể mở cổng " + PORT + ": " + e.getMessage());
        }

        System.out.println("[DemoTcpServer] Server đã dừng hoàn toàn!");
    }
}
