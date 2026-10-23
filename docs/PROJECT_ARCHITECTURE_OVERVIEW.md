# TỔNG QUAN CHI TIẾT DỰ ÁN: NETWORK CONNECTION ALERT SYSTEM (NCAS)
> **Tài liệu bàn giao & Hướng dẫn kỹ thuật dành cho AI Agent / Kỹ sư tiếp quản**  
> **Cập nhật ngày:** 23/10/2026 | **Build Status:** 77/77 Unit Tests Passed (100% Green)

---

## 1. BỐI CẢNH & MỤC TIÊU ĐỀ TÀI

**Network Connection Alert System (NCAS)** là hệ thống giám sát và cảnh báo trạng thái kết nối mạng đa mục tiêu, hoạt động theo thời gian thực (Real-time Network Monitoring & Alerting System). 
Dự án được xây dựng nhằm đáp ứng toàn diện các chuẩn kiến trúc trong môn học **Mạng máy tính / Lập trình mạng**, đồng thời vươn tới tiêu chuẩn vận hành công nghiệp tương tự các hệ sinh thái lớn như **Zabbix, Nagios, Datadog và Uptime Kuma**.

Hệ thống hỗ trợ 2 chế độ vận hành:
1. **Standalone Desktop Application**: Ứng dụng GUI giàu tính năng, tích hợp sẵn Embedded Server, Embedded Web NOC Dashboard và Simulator.
2. **Distributed Architecture (Server – Agent – Console)**: Hệ thống phân tán chịu lỗi cao, các Agent tại chi nhánh thu thập dữ liệu qua mô hình Reverse TCP Push về Server trung tâm.

---

## 2. CÁC TẦNG MẠNG & GIAO THỨC ĐÃ TRIỂN KHAI

Hệ thống bao phủ trọn vẹn 4 tầng trong mô hình TCP/IP:

| Tầng mạng | Giao thức | Cơ chế & Lớp phụ trách trong mã nguồn |
|:---|:---|:---|
| **Tầng Ứng Dụng (Application Layer)** | **NCAS-P v1** (Custom Protocol) | Giao thức nhị phân tự thiết kế với khung truyền 11-byte Header (`NCAS`), Version `0x01`, MessageType, Length-Prefixed, Payload UTF-8 JSON, kiểm tra tính toàn vẹn bằng **IEEE 802.3 CRC32**. Xử lý triệt để bài toán dính gói/xé gói TCP (`FrameEncoder.java`, `FrameDecoder.java`). |
| | **HTTP / HTTPS** | Đo kiểm Web Server, đo Time To First Byte (TTFB), kiểm tra HTTP Status Code (200, 301, 404, 500...), xác thực SSL/TLS Handshake (`HttpChecker.java`). |
| | **DNS** | Truy vấn phân giải tên miền (DNS Query RFC 1035 qua UDP cổng 53 và `InetAddress.getAllByName`), đo thời gian phân giải DNS (`DnsChecker.java`). |
| | **SMTP** | Gửi email thông báo sự cố có định dạng HTML chuyên nghiệp qua cổng 465 (SSL) hoặc 587 (STARTTLS) sử dụng Jakarta Mail (`SmtpEmailService.java`). |
| | **Telegram Bot API** | Gửi tin nhắn cảnh báo tức thì định dạng HTML về điện thoại/nhóm kỹ thuật qua HTTP REST API (`TelegramAlertService.java`). |
| **Tầng Vận Chuyển (Transport Layer)** | **TCP** | Đo kiểm độ sẵn sàng của cổng dịch vụ qua 3-way handshake (`Socket`), đo chính xác độ trễ kết nối (Round-Trip Latency tính bằng ms) (`TcpChecker.java`). |
| | **UDP** | Gửi/nhận gói tin Echo Datagram phi kết nối (`DatagramSocket`, `DatagramPacket`), đo tỷ lệ mất gói và độ trễ phản hồi (`UdpChecker.java`). |
| **Tầng Mạng (Internet Layer)** | **ICMP / Echo** | Kiểm tra độ sống của Host qua `InetAddress.isReachable()` kết hợp cơ chế tự động Fallback TCP Echo Probe (`PingChecker.java`). |

---

## 3. KIẾN TRÚC PHÂN TÁN (NCAS DISTRIBUTED ARCHITECTURE)

```
                       +-----------------------------------+
                       |        NCAS Central Server        |
                       |       (Port 9876, Auth Token)     |
                       |  - AgentManager (Heartbeat 30s)   |
                       |  - TargetDistributor (Least-Assn) |
                       |  - Console Event Streamer         |
                       +-----------------+-----------------+
                                         |
               +-------------------------+-------------------------+
               | (Reverse TCP Push)                                | (Reverse TCP Push)
               v                                                   v
   +------------------------+                             +------------------------+
   |      NCAS Agent A      |                             |      NCAS Agent B      |
   |   (Trạm Chi nhánh HCM) |                             |   (Trạm Chi nhánh HN)  |
   | - Multi-thread Probes  |                             | - Multi-thread Probes  |
   | - Store-and-Forward Q  |                             | - Store-and-Forward Q  |
   +-----------+------------+                             +-----------+------------+
               |                                                   |
      [TCP, UDP, HTTP, DNS]                               [TCP, UDP, HTTP, DNS]
               v                                                   v
     Các máy chủ nội bộ LAN HCM                          Các máy chủ nội bộ LAN HN

                                         ^
                                         | (Admin Session)
                       +-----------------+-----------------+
                       |        NCAS Console CLI           |
                       |    - Interactive REPL             |
                       |    - Live Event Stream (ASCII)    |
                       +-----------------------------------+
```

### Các nguyên lý phân tán chủ chốt:
1. **Agent Reverse Connection (Push Model)**:
   - Các Agent đặt tại chi nhánh thường nằm sau NAT/CGNAT hoặc Firewall chặn toàn bộ cổng Inbound.
   - Agent **chủ động mở kết nối TCP Outbound** tới Server. Router NAT biên tự động lưu bảng stateful tracking. Socket trở thành kênh truyền hai chiều (**Full-Duplex**), cho phép Server đẩy cấu hình (`ASSIGN_TARGETS`) và nhận kết quả đo (`REPORT_RESULT`).
2. **Hàng đợi đệm ngoại tuyến (Store-and-Forward Queue)**:
   - Nếu kết nối giữa Agent và Server bị đứt, Agent tự động chuyển kết quả kiểm tra vào hàng đợi bộ nhớ đệm (dung lượng tối đa 500 bản ghi).
   - Khi tái lập kết nối thành công, Agent tự động xả (flush) toàn bộ hàng đợi về Server, không làm mất bất kỳ sự cố hay dữ liệu thống kê nào.
3. **Cân bằng tải động (Dynamic Target Distributor)**:
   - Phân chia danh sách máy chủ giám sát theo thuật toán **Least-Assigned**.
   - Tự động tái điều phối (Rebalance) ngay khi có Agent mới gia nhập hoặc Agent bị mất kết nối quá thời gian quy định (Heartbeat timeout sau 30 giây).
4. **Cơ chế đồng thuận đa trạm (Multi-Station Quorum Consensus)**:
   - Một mục tiêu có thể được phân công đo đồng thời từ nhiều trạm (Scope: `MULTI`).
   - Trạng thái tổng hợp được tính toán theo tỷ lệ đồng thuận (Quorum) giữa các trạm để phân biệt chính xác: sự cố xảy ra tại trạm đo hay máy chủ đích thực sự bị sập mạng.

---

## 4. MÁY TRẠNG THÁI & THUẬT TOÁN BẢO VỆ CHỐNG BÁO ĐỘNG ẢO

Trong file `TargetStateMachine.java`, hệ thống triển khai các thuật toán giám sát chuẩn công nghiệp:
1. **Mô hình Trạng thái SOFT và HARD (Nagios / Zabbix Model)**:
   - Khi phát hiện lần lỗi đầu tiên, trạng thái chuyển thành **SOFT DOWN** (thử lại lần 1/3, 2/3 với chu kỳ ngắn hơn `retryIntervalSeconds`).
   - Chỉ khi số lần thất bại liên tiếp đạt ngưỡng `failThreshold` (mặc định 3 lần), trạng thái mới chính thức chuyển thành **HARD DOWN**.
   - **Chỉ trạng thái HARD mới kích hoạt cảnh báo** (âm thanh, popup toast, email SMTP, tin nhắn Telegram). Tránh tối đa báo động giả do nhiễu mạng cục bộ.
2. **Quan hệ phụ thuộc Cha–Con (Parent Dependency)**:
   - Cho phép thiết lập cây phụ thuộc mạng (ví dụ: Switch/Router là Cha của các Server con).
   - Khi thiết bị Cha bị HARD DOWN hoặc Agent phụ trách bị mất kết nối, toàn bộ các mục tiêu con tự động chuyển thành **UNREACHABLE**.
   - **Hệ thống chỉ cảnh báo thiết bị Cha**, triệt tiêu tình trạng "bão cảnh báo" (Alert Storm).
   - Tích hợp thuật toán phát hiện và từ chối vòng lặp phụ thuộc (Cycle Detection).
3. **Thuật toán chống rung lắc mạng (Flapping Detection)**:
   - Lưu trữ lịch sử 20 lần đo gần nhất (`FLAPPING_HISTORY_SIZE = 20`).
   - Tính toán tỷ lệ biến thiên trạng thái: nếu vượt ngưỡng `flappingHighThreshold` (25%), mục tiêu được đánh dấu là `FLAPPING` và tự động gom/hạn chế thông báo. Khi tỷ lệ hạ xuống dưới `flappingLowThreshold` (10%), cảnh báo flapping tự động giải trừ.
4. **Cửa sổ bảo trì định kỳ (Maintenance / Downtime Engine)**:
   - `MaintenanceService`: Thiết lập lịch bảo trì theo ngày trong tuần, khung giờ hoặc khoảng ngày cố định.
   - Khi mục tiêu rơi vào giờ bảo trì, trạng thái chuyển sang `MAINTENANCE` (màu xanh dương), tạm dừng kích hoạt cảnh báo và loại trừ khỏi công thức tính vi phạm SLA.

---

## 5. TỔNG QUAN KIẾN TRÚC MÃ NGUỒN (PACKAGE BREAKDOWN)

Cấu trúc dự án gồm **110 lớp Java** được tổ chức phân tầng rõ ràng:

```
com.networkalert/
├── Main.java                        # Router điểm vào: Khởi chạy Standalone GUI / Server / Agent / Console
├── DemoTcpServer.java               # Máy chủ TCP thử nghiệm độc lập cổng 9000
│
├── protocol/                        # Giao thức nhị phân NCAS-P v1
│   ├── Frame.java                   # Khung truyền 11-byte Header + Payload + CRC32
│   ├── FrameEncoder.java            # Mã hóa nhị phân & tính IEEE 802.3 CRC32
│   ├── FrameDecoder.java            # Giải mã nhị phân, kiểm tra Magic, Length, Checksum
│   ├── MessageType.java             # Enum 13 loại thông điệp nhị phân
│   └── messages/                    # 12 DTOs: Register, Heartbeat, AssignTargets, ReportResult, ConsoleCmd...
│
├── server/                          # Máy chủ Trung tâm NCAS Server
│   ├── NcasServer.java              # TCP Server lắng nghe cổng 9876, điều phối kết nối
│   ├── AgentManager.java            # Quản lý phiên Agent, Heartbeat Timeout Scanner định kỳ
│   ├── AgentSession.java            # Phiên kết nối Socket đa luồng bền vững của từng Agent
│   └── TargetDistributor.java       # Phân bổ mục tiêu theo Least-Assigned & Tự động Rebalance
│
├── agent/                           # Trạm đo kiểm chi nhánh NCAS Agent
│   ├── NcasAgent.java               # Client Reverse TCP, Multi-thread Scheduler, Store-and-Forward Queue, REPL
│   └── AgentConfig.java             # Phân tích tham số dòng lệnh CLI cho Agent
│
├── console/                         # Bàn điều khiển Quản trị NCAS Console CLI
│   ├── NcasConsole.java             # Bàn điều khiển REPL và luồng Live Event Stream
│   ├── ConsoleConfig.java           # Phân tích tham số CLI của Console
│   └── AsciiTableFormatter.java     # Định dạng bảng biểu ASCII chuẩn cho Terminal
│
├── model/                           # Tầng mô hình dữ liệu & nghiệp vụ cốt lõi
│   ├── ProtocolType.java            # TCP, UDP, HTTP, HTTPS, ICMP, DNS
│   ├── ServerStatus.java            # ONLINE, OFFLINE, WARNING, UNREACHABLE, MAINTENANCE, PAUSED, UNCHECKED
│   ├── StateType.java               # SOFT, HARD
│   ├── TargetScope.java             # SERVER, AGENT, MULTI
│   ├── TargetStateMachine.java      # Máy trạng thái Hysteresis, Flapping, Parent Dependency
│   ├── ServerTarget.java            # Thực thể mục tiêu, Runtime Metrics, 60 nhịp tim Uptime Kuma, Uptime 24h/7d/30d
│   ├── Problem.java                 # Sự cố chuẩn Zabbix/Nagios (Ack, Resolved, Severity, Duration)
│   ├── ProblemSeverity.java         # INFO, WARNING, AVERAGE, HIGH, DISASTER
│   ├── ProblemStatus.java           # ACTIVE, ACKNOWLEDGED, RESOLVED
│   ├── MaintenanceWindow.java       # Cấu hình khung giờ bảo trì
│   └── RealNetworkInfo.java         # Dữ liệu mạng thật (IP LAN, Gateway, WAN IP, DNS)
│
├── service/                         # Tầng dịch vụ & xử lý luồng
│   ├── MonitorService.java          # ScheduledThreadPoolExecutor điều phối đa luồng kiểm tra bất đồng bộ
│   ├── AlertService.java            # Điều phối cảnh báo: Sound, Toast, System Tray, Email, Telegram, DB
│   ├── SmtpEmailService.java        # Gửi email SMTP SSL/TLS qua Jakarta Mail
│   ├── TelegramAlertService.java    # Gửi cảnh báo về ứng dụng Telegram trên di động
│   ├── MaintenanceService.java      # Quản lý các cửa sổ bảo trì và kiểm tra trạng thái Downtime
│   ├── RealNetworkService.java      # Lấy card mạng, đo độ trễ Router & DNS, Ping, Traceroute, Quét thiết bị LAN
│   └── checker/                     # Factory Pattern triển khai các bộ đo kiểm giao thức
│       ├── IProtocolChecker.java    # Interface chuẩn đo kiểm
│       ├── CheckerFactory.java      # Factory sinh checker theo ProtocolType
│       ├── TcpChecker.java          # Đo TCP 3-way handshake
│       ├── UdpChecker.java          # Đo UDP Echo
│       ├── HttpChecker.java         # Đo HTTP/HTTPS Status Code & TTFB
│       ├── PingChecker.java         # Đo ICMP Reachable + TCP echo fallback
│       └── DnsChecker.java          # Đo thời gian phân giải tên miền DNS
│
├── scanner/                         # Bộ quét cổng TCP đa luồng (Port Scanner)
│   ├── PortScannerService.java      # Quét song song dải cổng, tự động nhận diện dịch vụ chuẩn
│   └── PortScanResult.java          # Kết quả cổng: Mở, Tên dịch vụ, Độ trễ
│
├── simulator/                       # Bộ giả lập mạng tích hợp (Mock Servers)
│   ├── MockTcpServer.java           # TCP Server giả lập (cổng 9000)
│   ├── MockUdpServer.java           # UDP Server giả lập (cổng 9001)
│   └── MockHttpServer.java          # HTTP Server giả lập (cổng 9002, mô phỏng lỗi 200, 404, 500, delay)
│
├── web/                             # Máy chủ Web NOC nhúng sẵn (Embedded Web Dashboard)
│   └── EmbeddedWebDashboard.java    # Mini HTTP Server (cổng 8080) cung cấp Dashboard và REST API
│
├── repository/                      # Tầng lưu trữ dữ liệu (SQL Server & Memory Fallback)
│   ├── DatabaseManager.java         # Quản lý JDBC kết nối SQLEXPRESS, tự động Fallback In-Memory
│   ├── TargetRepository.java        # CRUD danh sách máy chủ mục tiêu
│   ├── IncidentRepository.java      # Lưu vết và truy vấn lịch sử sự cố
│   ├── ProblemRepository.java       # Quản lý các sự cố Problem kiểu Zabbix
│   └── ConfigRepository.java        # Lưu cấu hình hệ thống, cảnh báo, ngưỡng trễ
│
├── ui/                              # Tầng giao diện đồ họa hiện đại (FlatLaf Swing)
│   ├── MainFrame.java               # Cửa sổ chính tích hợp Header, Health Ratio Bar, SplitPane, Status Bar
│   ├── TargetDialog.java            # Hộp thoại thêm/sửa mục tiêu giám sát
│   ├── SettingsDialog.java          # Hộp thoại cấu hình DB, SMTP, Telegram, Ngưỡng trễ
│   ├── NotificationToast.java       # Thông báo Toast nổi góc màn hình
│   ├── SystemTrayManager.java       # Tích hợp System Tray chạy ngầm 24/7 dưới khay Windows
│   ├── components/
│   │   ├── ServerListPanel.java     # Bảng danh sách mục tiêu + Toolbar + Search/Filter
│   │   ├── ServerTableModel.java    # Table Model hiển thị trạng thái, nhịp tim, toggle
│   │   ├── ServerTableRenderer.java # Custom Renderer hỗ trợ Badge, Heartbeat Bar, ToggleSwitch
│   │   ├── HeartbeatBarRenderer.java# Vẽ 60 vạch nhịp tim trực quan kiểu Uptime Kuma
│   │   ├── LatencyChartPanel.java   # Đồ thị Graphics2D vẽ đường trễ thời gian thực (hỗ trợ đa trạm)
│   │   ├── IncidentHistoryPanel.java# Bảng lịch sử sự cố có huy hiệu đếm số lượng
│   │   ├── PortScannerPanel.java    # Giao diện quét cổng mạng và thêm 1-click vào giám sát
│   │   ├── AgentClusterPanel.java   # Giao diện quản lý cụm Agent, nhúng Server, nút Force Check
│   │   ├── NetworkSimulatorPanel.java# Điều khiển 3 máy chủ giả lập trực tiếp trên GUI
│   │   ├── HealthRatioBar.java      # Thanh tiến trình tổng hợp tỷ lệ OK/Warning/Down toàn hệ thống
│   │   ├── ToggleSwitch.java        # Công tắc trượt iOS/macOS cho cột Bật/Tắt
│   │   └── StatusBar.java           # Thanh trạng thái chân trang hiển thị luồng mạng, số luồng, cổng Web
│   └── theme/
│       ├── ThemeManager.java        # Quản lý giao diện FlatDarkLaf/FlatLightLaf, Design Tokens, SVG Icons
│       └── I18n.java                # Hỗ trợ đa ngôn ngữ Tiếng Việt & Tiếng Anh
│
└── util/                            # Tiện ích bổ trợ
    ├── LoggerUtil.java              # Ghi log đa kênh (Console UTF-8, File network-alert.log)
    ├── SoundHelper.java             # Phát âm thanh cảnh báo Beep/Alert
    └── CsvExporter.java             # Xuất báo cáo SLA Uptime % và sự cố ra Microsoft Excel CSV
```

---

## 6. GIAO DIỆN & TRẢI NGHIỆM NGƯỜI DÙNG (UI/UX)

1. **Theme & Design System**:
   - Sử dụng thư viện **FlatLaf** (Dark Theme chuẩn công nghiệp làm mặc định, hỗ trợ chuyển đổi Light Theme).
   - Bảng màu hài hòa: Xanh lá cây (Online), Đỏ cà chua (Offline/Down), Vàng hổ phách (Warning), Xanh lam (Maintenance), Tím/Xám (Unreachable/Paused).
   - Font chữ hệ thống chuẩn Clean Sans-serif (Segoe UI, Inter, Roboto), hỗ trợ tỷ lệ phân giải màn hình cao (HiDPI scaling).
2. **Dải 60 nhịp tim Uptime Kuma (`HeartbeatBarRenderer`)**:
   - Mỗi máy chủ trên bảng đều có một ô hiển thị 60 khối chữ nhật nhỏ đại diện cho 60 chu kỳ kiểm tra gần nhất.
   - Trực quan hóa ngay lập tức lịch sử hoạt động và độ ổn định của máy chủ trong vòng 5-10 phút gần nhất mà không cần mở bảng log.
3. **Biểu đồ độ trễ thời gian thực (`LatencyChartPanel`)**:
   - Tự động vẽ lại theo từng chu kỳ bằng `Graphics2D` với hiệu ứng khử răng cưa (Anti-Aliasing).
   - Vẽ đường biểu diễn mượt mà, hiển thị giá trị Min/Avg/Max, vạch ngưỡng cảnh báo màu vàng và các điểm cảnh báo màu đỏ khi vượt ngưỡng.
   - Hỗ trợ vẽ **đa đường theo từng Agent** khi mục tiêu được giám sát từ nhiều trạm.
4. **Embedded Web NOC Dashboard (Cổng 8080)**:
   - Tự động mở trình duyệt từ ứng dụng Desktop hoặc truy cập từ xa qua điện thoại/máy tính bảng trong mạng Wi-Fi.
   - Hiển thị bản đồ luồng mạng thật (Live Topology): `Card mạng LAN -> Default Gateway Router -> WAN Public IP -> DNS Quốc tế`.
   - Cung cấp công cụ Ping thời gian thực, Quét thiết bị mạng LAN (/24) và điều khiển kiểm tra tức thì qua REST API.

---

## 7. CƠ SỞ DỮ LIỆU & CƠ CHẾ DUNG LỖI FALLBACK

- **Hệ quản trị CSDL chính**: **Microsoft SQL Server** (kết nối mặc định tới `localhost:1433`, CSDL `NetworkAlertDB`).
- **Cấu trúc bảng**:
  - `Targets`: Lưu cấu hình các máy chủ giám sát, giao thức, chu kỳ, ngưỡng trễ, phạm vi (Scope).
  - `IncidentLogs`: Lưu lịch sử tất cả các lần phát hiện sự cố mạng (thời điểm bắt đầu, kết thúc, lý do, tổng thời gian gián đoạn).
  - `SystemConfigs`: Lưu cấu hình SMTP Email, Telegram Bot Token, ngưỡng cảnh báo toàn cục.
  - `Problems`: Lưu danh sách sự cố theo chuẩn Zabbix (Severity, Trạng thái Xử lý, Acknowledged bởi ai).
- **Cơ chế Tự Động Dung Lỗi (Fail-Safe In-Memory Fallback)**:
  - Nếu máy tính chưa cài SQL Server hoặc dịch vụ SQL Server bị tắt, hệ thống **không bao giờ bị dừng hay báo lỗi crash**.
  - `DatabaseManager` tự động kích hoạt chế độ **In-Memory Storage**, kết hợp ghi nhật ký vào file text `network-alert.log`. Mọi chức năng giám sát, cảnh báo, vẽ đồ thị, xuất báo cáo CSV đều hoạt động bình thường 100%.

---

## 8. HỆ THỐNG KIỂM THỬ TỰ ĐỘNG (AUTOMATED TESTS)

Dự án sở hữu bộ kiểm thử tự động toàn diện gồm **77 Unit & Integration Tests** viết bằng JUnit 5:

1. `ProtocolFrameTest`: Kiểm thử tính toán IEEE 802.3 CRC32, kiểm tra Magic Bytes, chống dính gói và xé gói TCP.
2. `NcasServerTest`: Kiểm thử khởi động Server, xác thực Auth Token, đăng ký Agent, timeout nhịp tim sau 30 giây, điều phối mục tiêu.
3. `NcasAgentTest`: Kiểm thử kết nối Reverse TCP, hàng đợi Store-and-Forward Queue khi đứt kết nối, lệnh REPL.
4. `NcasConsoleTest`: Kiểm thử kết nối Admin CLI, định dạng bảng ASCII, luồng sự kiện thời gian thực.
5. `NagiosStateMachineTest`: Kiểm thử chuyển đổi SOFT/HARD State, quan hệ phụ thuộc Parent/Child, chống bão cảnh báo và Flapping.
6. `TargetScopeQuorumTest`: Kiểm thử tính toán đồng thuận Quorum khi đo mục tiêu từ nhiều trạm đo.
7. `UptimeKumaFeatureTest`: Kiểm thử Ring Buffer 60 nhịp tim, công thức tính Uptime SLA 24h, 7 ngày, 30 ngày.
8. `Stage5ComponentsTest`: Kiểm thử các thành phần UI, bộ lọc bảng, định dạng Badge trạng thái.

---

## 9. CÁC ĐIỂM SÁNG GIÚP ĐỒ ÁN ĐẠT ĐIỂM TỐI ĐA KHI THUYẾT TRÌNH

1. **Chuẩn kiến trúc mạng**: Không dừng ở ứng dụng cục bộ đơn giản mà giải quyết bài toán mạng thực tế: NAT Traversal bằng Reverse TCP Push, Binary Framing xử lý dính/xé gói TCP.
2. **Kỹ thuật chống báo động ảo chuyên nghiệp**: Mô hình SOFT/HARD State, quan hệ phụ thuộc Router/Server cha con, chống rung lắc Flapping.
3. **Bộ giả lập mạng tích hợp (Simulator)**: Trình diễn sự cố tức thì (sập TCP 9000, Web 500 tại 9002, mô phỏng trễ 500ms) ngay trước hội đồng chấm thi mà không cần can thiệp hệ thống ngoài.
4. **Giám sát Mạng Thật & Web NOC**: Bản đồ luồng mạng thật từ máy tính ra Internet, quét thiết bị Wi-Fi LAN và mở Web trên điện thoại để điều khiển từ xa.
5. **Cảnh báo đa kênh tức thời**: Âm thanh, Popup Toast, Khay hệ thống 24/7, Email SMTP SSL, Telegram Bot trên smartphone.
