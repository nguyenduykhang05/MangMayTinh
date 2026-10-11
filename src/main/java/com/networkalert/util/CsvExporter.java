package com.networkalert.util;

import com.networkalert.model.IncidentLog;
import com.networkalert.model.ServerTarget;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Tiện ích xuất báo cáo giám sát và lịch sử sự cố mạng ra định dạng CSV (hỗ trợ tiếng Việt Excel UTF-8)
 */
public class CsvExporter {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static boolean exportReport(File file, List<ServerTarget> targets, List<IncidentLog> incidents) {
        try (FileOutputStream fos = new FileOutputStream(file);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
             PrintWriter pw = new PrintWriter(osw)) {

            // Ghi BOM UTF-8 để Microsoft Excel nhận diện đúng tiếng Việt có dấu
            fos.write(0xEF);
            fos.write(0xBB);
            fos.write(0xBF);

            pw.println("=== BÁO CÁO GIÁM SÁT KẾT NỐI MẠNG (NETWORK MONITOR REPORT) ===");
            pw.println();

            // Phần 1: Bảng tổng quan máy chủ
            pw.println("1. DANH SÁCH MÁY CHỦ VÀ TỶ LỆ SẴN SÀNG (UPTIME / SLA)");
            pw.println("ID,Tên Máy Chủ,Host / IP,Cổng,Giao Thức,Trạm Đo / Quorum,Trạng Thái,Độ Trễ Hiện Tại (ms),Tổng Số Lần Check,Check Thành Công,Uptime (%),Uptime 24h (%),Uptime 7d (%),Uptime 30d (%)");
            for (ServerTarget t : targets) {
                pw.printf("%d,\"%s\",\"%s\",%d,%s,\"%s\",%s,%d,%d,%d,%.2f%%,%.2f%%,%.2f%%,%.2f%%\n",
                        t.getId(),
                        escapeCsv(t.getName()),
                        escapeCsv(t.getHost()),
                        t.getPort(),
                        t.getProtocol().name(),
                        escapeCsv(t.getQuorumSummary()),
                        t.getStatus().name(),
                        t.getCurrentLatency(),
                        t.getTotalChecks(),
                        t.getSuccessfulChecks(),
                        t.getUptimePercentage(),
                        t.getUptime24h(),
                        t.getUptime7d(),
                        t.getUptime30d()
                );
            }

            pw.println();
            pw.println("2. LỊCH SỬ CÁC SỰ CỐ MẤT KẾT NỐI (INCIDENT LOGS)");
            pw.println("ID,Mục Tiêu,Host / IP,Cổng,Giao Thức,Loại Sự Cố,Thời Gian Xảy Ra,Thông Điệp / Nguyên Nhân");
            for (IncidentLog inc : incidents) {
                pw.printf("%d,\"%s\",\"%s\",%d,%s,%s,\"%s\",\"%s\"\n",
                        inc.getId(),
                        escapeCsv(inc.getTargetName()),
                        escapeCsv(inc.getHost()),
                        inc.getPort(),
                        inc.getProtocol() != null ? inc.getProtocol().name() : "N/A",
                        inc.getEventType(),
                        inc.getEventTime() != null ? inc.getEventTime().format(FORMATTER) : "",
                        escapeCsv(inc.getMessage())
                );
            }

            pw.flush();
            return true;
        } catch (Exception e) {
            LoggerUtil.error("Lỗi xuất file CSV: " + e.getMessage());
            return false;
        }
    }

    private static String escapeCsv(String input) {
        if (input == null) return "";
        return input.replace("\"", "\"\"");
    }
}
