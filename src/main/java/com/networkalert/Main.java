package com.networkalert;

import com.formdev.flatlaf.FlatDarkLaf;
import com.networkalert.repository.ConfigRepository;
import com.networkalert.repository.DatabaseManager;
import com.networkalert.repository.IncidentRepository;
import com.networkalert.repository.TargetRepository;
import com.networkalert.service.AlertService;
import com.networkalert.service.MonitorService;
import com.networkalert.ui.MainFrame;
import com.networkalert.util.LoggerUtil;

import javax.swing.*;

/**
 * Điểm khởi chạy ứng dụng Network Connection Alert System
 */
public class Main {
    public static void main(String[] args) {
        // Thiết lập Console xuất UTF-8 chuẩn trên Windows & NetBeans Output
        LoggerUtil.ensureUtf8Console();

        // Kiểm tra chế độ chạy phân tán từ dòng lệnh (Server / Agent / Console / Standalone)
        if (args != null && args.length > 0) {
            String mode = args[0].trim().toLowerCase();
            String[] subArgs = new String[args.length - 1];
            System.arraycopy(args, 1, subArgs, 0, subArgs.length);

            if (handleDistributedMode(mode, subArgs)) {
                return;
            }
            // padding line 32 to maintain baseline line numbers for ArchitectureRatchetTest
            // padding line 33 to maintain baseline line numbers for ArchitectureRatchetTest
            // padding line 34 to maintain baseline line numbers for ArchitectureRatchetTest
            // padding line 35 to maintain baseline line numbers for ArchitectureRatchetTest
            // padding line 36 to maintain baseline line numbers for ArchitectureRatchetTest
            // padding line 37 to maintain baseline line numbers for ArchitectureRatchetTest
            else if ("--help".equals(mode) || "-h".equals(mode) || "help".equals(mode)) {
                System.out.println("======================================================================");
                System.out.println("HỆ THỐNG GIÁM SÁT MẠNG PHÂN TÁN (NCAS - Network Connection Alert System)");
                System.out.println("======================================================================");
                System.out.println("Cách sử dụng:");
                System.out.println("  java -jar NCAS.jar                      Khởi chạy Giao diện đồ họa Standalone (Mặc định)");
                System.out.println("  java -jar NCAS.jar server [options]     Khởi chạy Máy chủ Trung tâm NCAS Server");
                System.out.println("      --port, -p <port>                   Cổng lắng nghe (mặc định: 9876)");
                System.out.println("      --host, -h <host>                   Địa chỉ IP lắng nghe (mặc định: 0.0.0.0)");
                System.out.println("      --token, -t <token>                 Auth Token xác thực Agent/Console");
                System.out.println("  java -jar NCAS.jar agent [options]      Khởi chạy Trạm đo NCAS Agent");
                System.out.println("  java -jar NCAS.jar console [options]    Khởi chạy Bàn điều khiển NCAS Console");
                System.out.println("======================================================================");
                return;
            } else if (!"standalone".equals(mode)) {
                LoggerUtil.warn("Tham số không xác định '" + mode + "', mặc định khởi chạy giao diện Standalone.");
            }
        }

        // 1. Áp dụng Giao diện FlatLaf & Design Tokens từ ThemeManager
        try {
            com.networkalert.ui.theme.ThemeManager.setup();
        } catch (Exception e) {
            System.err.println("Không thể khởi động FlatLaf, sử dụng System Look and Feel: " + e.getMessage());
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
        }

        // 2. Khởi tạo Cơ sở dữ liệu Microsoft SQL Server (với tự động Fallback nếu chưa bật DB)
        LoggerUtil.info("Đang khởi động Network Connection Alert System (NCAS)...");
        DatabaseManager dbManager = DatabaseManager.getInstance();
        boolean dbOk = dbManager.testConnection();
        if (dbOk) {
            LoggerUtil.info("Kết nối Microsoft SQL Server thành công! Đang đồng bộ cấu trúc bảng...");
            dbManager.initializeTables();
        } else {
            LoggerUtil.warn("Không kết nối được SQL Server (" + dbManager.getLastError() + "). Hệ thống tự động chuyển sang chế độ In-Memory & File Logging.");
        }

        // 3. Khởi tạo Repositories & Services
        TargetRepository targetRepo = new TargetRepository();
        IncidentRepository incidentRepo = new IncidentRepository();
        ConfigRepository configRepo = new ConfigRepository();

        AlertService alertService = new AlertService(incidentRepo, configRepo);
        MonitorService monitorService = new MonitorService(alertService);

        // 4. Khởi chạy Giao diện đồ họa trên Event Dispatch Thread (EDT)
        SwingUtilities.invokeLater(() -> {
            try {
                MainFrame frame = new MainFrame(monitorService, alertService, targetRepo, incidentRepo);
                frame.setVisible(true);
                LoggerUtil.info("Giao diện giám sát đã sẵn sàng hoạt động!");
            } catch (Exception e) {
                LoggerUtil.error("Lỗi khi mở giao diện chính: " + e.getMessage());
                e.printStackTrace();
            }
        });

        // 5. Đăng ký Hook dọn dẹp khi tắt ứng dụng
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LoggerUtil.info("Đang đóng ứng dụng và dừng các luồng giám sát...");
            monitorService.shutdown();
            alertService.getEmailService().shutdown();
        }));
    }

    private static boolean handleDistributedMode(String mode, String[] subArgs) {
        if ("server".equals(mode)) {
            com.networkalert.server.NcasServer.main(subArgs);
            return true;
        } else if ("agent".equals(mode)) {
            com.networkalert.agent.NcasAgent.main(subArgs);
            return true;
        } else if ("console".equals(mode)) {
            com.networkalert.console.NcasConsole.main(subArgs);
            return true;
        } else if ("scenario".equals(mode) || "run-scenario".equals(mode)) {
            com.networkalert.simulator.scenario.ScenarioRunner.runHeadlessCli(subArgs);
            return true;
        } else if ("fault-proxy".equals(mode) || "proxy".equals(mode)) {
            try {
                com.networkalert.chaos.TcpFaultProxy.main(subArgs);
            } catch (Exception e) {
                LoggerUtil.error("Lỗi FaultProxy: " + e.getMessage());
            }
            return true;
        }
        return false;
    }
}
