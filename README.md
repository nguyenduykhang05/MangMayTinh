# ỨNG DỤNG GIÁM SÁT VÀ CẢNH BÁO KẾT NỐI MẠNG (NETWORK CONNECTION ALERT SYSTEM)
**ĐỒ ÁN CUỐI KỲ: LẬP TRÌNH MẠNG / MẠNG MÁY TÍNH**

---

## 1. GIỚI THIỆU ĐỀ TÀI
Ứng dụng **Network Connection Alert System (NCAS)** mô phỏng các giao thức, cấu trúc và hoạt động của một hệ thống mạng thực tế. Hệ thống cho phép giám sát liên tục tình trạng hoạt động của nhiều máy chủ/dịch vụ mạng cùng lúc, tự động phát hiện và cảnh báo tức thời qua nhiều kênh khi xảy ra sự cố gián đoạn hoặc suy giảm hiệu năng kết nối.

---

## 2. ĐÁP ỨNG CÁC GIAO THỨC THEO CHUẨN MÔN HỌC

Hệ thống triển khai đầy đủ các giao thức trên các tầng mạng:

| Tầng Mạng | Giao Thức | Cơ Chế Triển Khai Trong Mã Nguồn |
|:---|:---|:---|
| **Tầng Ứng Dụng (Application Layer)** | **NCAS-P v1 (Custom Protocol)** | **Giao thức giám sát phân tán nhị phân tự thiết kế: Reverse TCP Push, 11-byte Binary Frame, CRC32 Checksum, Store-and-Forward Queue.** |
| | **HTTP / HTTPS** | `HttpURLConnection` phân tích HTTP Status Code (200, 404, 500), TTFB, SSL/TLS handshake. |
| | **DNS** | `InetAddress.getAllByName()` và UDP DNS Query RFC 1035 đo thời gian phân giải tên miền. |
| | **SMTP** | Gửi email thông báo sự cố có xác thực SSL/TLS qua cổng 465 / 587 bằng Jakarta Mail. |
| **Tầng Vận Chuyển (Transport Layer)** | **TCP** | `Socket` thực hiện 3-way handshake tới Host:Port, đo latency kết nối chính xác (ms). |
| | **UDP** | `DatagramSocket` & `DatagramPacket` gửi nhận gói tin echo phi kết nối, đo mất gói. |
| **Tầng Mạng (Internet Layer)** | **ICMP** | `InetAddress.isReachable()` kết hợp Fallback TCP echo probe kiểm tra độ sẵn sàng của Host. |

---

## 3. CÔNG NGHỆ & MÔI TRƯỜNG PHÁT TRIỂN

- **Ngôn ngữ**: Java SE (JDK 17, JDK 21 hoặc JDK 24).
- **Môi trường (IDE)**: Apache NetBeans 22 (Cấu trúc chuẩn Maven Project).
- **Giao diện (GUI)**: Java Swing kết hợp **FlatLaf** (Dark / Light Theme hiện đại chuẩn chuyên nghiệp).
- **Đồ thị thời gian thực**: Java `Graphics2D` vẽ biểu đồ biến thiên độ trễ (Latency Chart) theo thời gian thực.
- **Xử lý đa luồng**: `ScheduledThreadPoolExecutor` quản lý worker threads kiểm tra đồng thời bất đồng bộ, không gây lag UI.
- **Cơ sở dữ liệu**: **Microsoft SQL Server** (SQLEXPRESS) lưu trữ danh sách máy chủ, nhật ký sự cố (`IncidentLogs`) và cấu hình hệ thống. Có cơ chế tự động Fallback in-memory và ghi file log `network-alert.log` nếu DB tạm thời ngắt kết nối.
- **Bộ giả lập tích hợp (Network Simulator)**: Tích hợp sẵn 3 Mock Server (TCP 9000, UDP 9001, HTTP 9002) ngay trên giao diện để demo trực tiếp.

---

## 4. HƯỚNG DẪN MỞ VÀ CHẠY DỰ ÁN BẰNG APACHE NETBEANS

### Bước 1: Mở Project trong NetBeans
1. Khởi động **Apache NetBeans**.
2. Trên thanh menu, chọn: **File** → **Open Project...**
3. Tìm đến thư mục:
   ```text
   d:\MMT\NetworkConnectionAlertSystem
   ```
4. Nhấn **Open Project**. NetBeans sẽ tự động nhận diện dự án Maven.

### Bước 2: Chạy Ứng Dụng Chính
- Trong cửa sổ **Projects** bên trái, mở rộng:
  `Source Packages` → `com.networkalert` → Click chuột phải vào `Main.java` → Chọn **Run File** (hoặc nhấn **F6** để chạy toàn bộ Project).

### Bước 3 (Tùy chọn): Chạy Demo TCP Server Độc Lập
- Nếu muốn chạy máy chủ TCP độc lập từ console bên ngoài:
  Click chuột phải vào `DemoTcpServer.java` → Chọn **Run File**. Máy chủ sẽ lắng nghe trên cổng `localhost:9000`.

---

## 5. HƯỚNG DẪN KỊCH BẢN DEMO KHI THUYẾT TRÌNH BẢO VỆ ĐỒ ÁN

### 🎯 Kịch bản 1: Giả lập Sập Kết Nối TCP & Kích Hoạt Cảnh Báo
1. Trong giao diện chính, vào tab **"🛠 Giả Lập Mạng (Simulator)"**.
2. Bấm **"Khởi động TCP (9000)"** → Mục tiêu `Local Mock TCP Server` trên bảng chuyển sang **ONLINE (Xanh)**.
3. Bấm **"Dừng TCP Server"** để mô phỏng sự cố đứt mạng.
4. **Kết quả quan sát**:
   - Trạng thái trên bảng lập tức chuyển sang **OFFLINE (Đỏ)**.
   - Phát âm thanh cảnh báo **Beep Alert** qua loa.
   - Popup **Notification Toast** nhảy ra ở góc dưới màn hình.
   - Sự cố được tự động lưu vào tab **"Lịch Sử Sự Cố"** và ghi vào CSDL SQL Server.
   - (Nếu cấu hình Email SMTP): Nhận email cảnh báo sự cố ngay lập tức.

### 🎯 Kịch bản 2: Giả lập Lỗi Web Server Tầng Ứng Dụng (HTTP 500)
1. Trong tab Simulator, bấm **"Khởi động HTTP (9002)"** → Trạng thái máy chủ Web là **ONLINE**.
2. Tại mục "Mã phản hồi HTTP", chọn **"500 Internal Server Error"**.
3. **Kết quả quan sát**: Ứng dụng phát hiện Web Server phản hồi lỗi 500, báo động và ghi lại chi tiết lỗi.

### 🎯 Kịch bản 3: Biểu Đồ Độ Trễ Thời Gian Thực & Cảnh Báo Nghẽn Mạng
1. Click chọn một máy chủ trên bảng (ví dụ `Local Mock TCP Server` hoặc `Google Web HTTPS`).
2. Quan sát biểu đồ **Real-time Latency Chart**: Đồ thị vẽ đường lượn sóng độ trễ (ms) cập nhật liên tục theo từng chu kỳ kiểm tra.
3. Trong tab Simulator, kéo thanh trượt **"Mô phỏng trễ mạng (ms)"** lên `500 ms`.
4. **Kết quả quan sát**: Độ trễ vượt ngưỡng cấu hình (300ms) → Cột trạng thái đổi sang màu vàng **WARNING**, đồ thị vẽ điểm màu đỏ cảnh báo nghẽn mạng!

### 🎯 Kịch bản 4: Xuất Báo Cáo Thống Kê SLA (CSV)
1. Nhấn nút **"📊 Xuất Báo Cáo CSV"** trên thanh công cụ.
2. Chọn nơi lưu file báo cáo.
3. Mở file CSV bằng Microsoft Excel để xem bảng thống kê tỷ lệ sẵn sàng (Uptime %), tổng số lần kiểm tra và toàn bộ lịch sử các đợt sập mạng.

### 🎯 Kịch bản 5 (Thực Tế): Cảnh Báo Đẩy Qua Telegram Bot Về Điện Thoại
1. Nhấn nút **"⚙ Cấu Hình Cảnh Báo & DB"** → Chọn tab **"📱 Telegram Bot (Mobile)"**.
2. Nhập `Bot Token` (từ @BotFather) và `Chat ID` (từ @userinfobot hoặc nhóm kỹ thuật).
3. Bấm **"Gửi Thử Tin Nhắn Cảnh Báo Telegram"** để xác nhận kết nối.
4. Khi có bất kỳ máy chủ nào bị sập, Telegram trên điện thoại sẽ nhận ngay tin nhắn định dạng HTML chuyên nghiệp!

### 🎯 Kịch bản 6 (Thực Tế): Chạy Ngầm 24/7 Dưới Khay Hệ Thống (System Tray)
1. Bấm nút **(X)** đóng cửa sổ ứng dụng.
2. Ứng dụng không bị tắt mà tự động thu nhỏ vào **Khay hệ thống (System Tray)** cạnh đồng hồ Windows.
3. Khi phát hiện sự cố, biểu tượng đổi sang màu đỏ và phát thông báo native balloon của Windows.
4. Nhấp đúp vào icon để mở lại giao diện.

### 🎯 Kịch bản 7 (Thực Tế): Chạy Như Một Dịch Vụ Windows Service 24/7
1. Sử dụng file cấu hình trong thư mục `service/ncas-service.xml`.
2. Hướng dẫn chi tiết trong file `service/README_SERVICE.md`.

### 🎯 Kịch bản 8 (Đột Phá): Trung Tâm Giám Sát Web NOC & Kết Nối Mạng Thật
1. Ứng dụng tự động nhúng một máy chủ Web NOC thời gian thực tại cổng `8080`.
2. Trên máy tính:
   - Bấm nút **"🌐 Web Dashboard"** trên thanh công cụ Toolbar (hoặc phím tắt **Ctrl+W**, hoặc click vào link cổng ở thanh trạng thái dưới cùng) để tự động mở trình duyệt.
3. Trên Điện thoại: Mở Wi-Fi cùng mạng với máy tính, mở trình duyệt gõ:
   ```text
   http://<IP_LAN_CỦA_MÁY>:8080
   ```
   (Ví dụ: `http://192.168.100.96:8080`).
4. **Các tính năng giám sát & chẩn đoán Mạng Thật**:
   - **Bản đồ luồng mạng thật (Live Topology Pipeline)**: Card mạng Wi-Fi/Ethernet ➔ Default Gateway (Router) ➔ Public WAN IP ➔ DNS Quốc Tế.
   - **Tự động đo độ trễ Router & DNS**: Phát hiện ngay khi Router Wi-Fi bị nghẽn mạng cục bộ hoặc mất kết nối ra ngoài Internet.
   - **Đồ thị độ trễ trực tiếp (Canvas Real-time Latency Chart)**: Biểu đồ đường thời gian thực cập nhật từng chu kỳ.
   - **Công cụ Ping chẩn đoán**: Gửi gói tin ping trực tiếp, tính toán Min/Avg/Max, Jitter và tỉ lệ mất gói (Packet Loss %).
   - **Công cụ Quét thiết bị mạng LAN**: Dò tìm toàn bộ thiết bị đang online trong cùng dải mạng Wi-Fi/Ethernet (/24), hiển thị IP, MAC, loại thiết bị và nút bấm "➕ Giám sát" tức thì.
   - **Điều khiển từ xa**: Bấm "⚡ Kiểm tra ngay", "Tạm dừng/Tiếp tục", hoặc "➕ Thêm mục tiêu" trực tiếp từ giao diện Web trên điện thoại!

### 🎯 Kịch bản 9 (Chuyên Sâu): Quét Cổng Mạng (TCP Port Scanner)
1. Trên cửa sổ chính, chọn tab **"🔍 Quét Cổng (Scanner)"**.
2. Nhập Host (ví dụ `127.0.0.1` hoặc IP bất kỳ), chọn dải cổng (ví dụ: `1 - 1024` hoặc `9000 - 9005`).
3. Bấm **"▶ Bắt Đầu Quét"** → Hệ thống dùng đa luồng quét đồng thời và phát hiện các cổng mở, tự động nhận diện dịch vụ (HTTP 80, HTTPS 443, MySQL 3306, MSSQL 1433...).
4. Chọn một cổng đang mở và bấm **"➕ Thêm Cổng Đang Chọn Vào Giám Sát"** để tự động đưa vào danh sách theo dõi chính!

---

## 6. CẤU TRÚC THƯ MỤC MÃ NGUỒN

```text
NetworkConnectionAlertSystem/
├── pom.xml                                  # Cấu hình dự án Maven & Dependencies
├── README.md                                # Tài liệu hướng dẫn đồ án
├── docs/                                    # Tài liệu thiết kế hệ thống phân tán
│   ├── NCAS_P_PROTOCOL_SPEC.md             # Đặc tả kỹ thuật giao thức nhị phân NCAS-P v1
│   └── PROJECT_ARCHITECTURE_OVERVIEW.md    # Tổng quan kiến trúc hệ thống NCAS
└── src/
    └── main/
        ├── java/
        │   └── com/
        │       └── networkalert/
        │           ├── Main.java            # Router điểm vào: Standalone GUI, Server, Agent, Console
        │           ├── DemoTcpServer.java   # TCP Server thử nghiệm độc lập
        │           │
        │           ├── protocol/            # Lõi Giao thức NCAS-P v1 (Custom Protocol)
        │           │   ├── Frame.java       # Khung nhị phân (11-byte Header + Payload + CRC32)
        │           │   ├── FrameEncoder.java# Đóng gói và tính CRC32
        │           │   ├── FrameDecoder.java# Giải mã, kiểm tra Magic/CRC32/Payload length
        │           │   ├── MessageType.java # Enum 13 loại thông điệp nhị phân
        │           │   └── dto/             # 12 Data Transfer Objects trao đổi qua mạng
        │           │
        │           ├── server/              # Máy chủ Trung tâm NCAS Server
        │           │   ├── NcasServer.java  # TCP Server lắng nghe cổng 9876, Console dispatcher
        │           │   ├── AgentManager.java# Quản lý phiên Agent, Heartbeat timeout scanner (30s)
        │           │   ├── AgentSession.java# Phiên kết nối an toàn đa luồng của từng Agent
        │           │   └── TargetDistributor.java # Tái cân bằng tải động (Least-Assigned)
        │           │
        │           ├── agent/               # Trạm đo mạng NCAS Agent (Trạm chi nhánh)
        │           │   ├── NcasAgent.java   # Reverse TCP Client, Multi-thread scheduler, REPL
        │           │   └── AgentConfig.java # Phân tích tham số dòng lệnh CLI
        │           │
        │           ├── console/             # Bàn điều khiển Quản trị NCAS Console CLI
        │           │   ├── NcasConsole.java # CLI tương tác REPL và luồng sự kiện thời gian thực
        │           │   ├── ConsoleConfig.java # Cấu hình kết nối bàn điều khiển
        │           │   └── AsciiTableFormatter.java # Bảng ASCII grid chuyên nghiệp
        │           │
        │           ├── model/               # Tầng dữ liệu nghiệp vụ
        │           │   ├── ProtocolType.java    # Enum: TCP, UDP, HTTP, HTTPS, ICMP, DNS
        │           │   ├── ServerStatus.java    # Enum: ONLINE, OFFLINE, WARNING, PAUSED
        │           │   ├── ServerTarget.java    # Máy chủ giám sát & Runtime Metrics & AssignedAgent
        │           │   ├── TargetStateMachine.java # Máy trạng thái Hysteresis chống chập chờn
        │           │   ├── CheckResult.java     # Kết quả 1 lần đo lường
        │           │   ├── IncidentLog.java     # Nhật ký sự cố
        │           │   ├── AlertConfig.java     # Cấu hình cảnh báo & SMTP
        │           │   └── DatabaseConfig.java  # Cấu hình kết nối SQL Server
        │           │
        │           ├── service/             # Tầng nghiệp vụ mạng & luồng
        │           │   ├── MonitorService.java  # Đa luồng ScheduledThreadPoolExecutor
        │           │   ├── AlertService.java    # Điều phối âm thanh, popup, mail, DB
        │           │   ├── SmtpEmailService.java# Gửi email cảnh báo qua giao thức SMTP
        │           │   └── checker/             # Các bộ kiểm tra giao thức
        │           │       ├── IProtocolChecker.java
        │           │       ├── TcpChecker.java
        │           │       ├── UdpChecker.java
        │           │       ├── HttpChecker.java
        │           │       ├── PingChecker.java
        │           │       ├── DnsChecker.java
        │           │       └── CheckerFactory.java
        │           │
        │           ├── simulator/           # Bộ trạm giả lập mạng
        │           │   ├── MockTcpServer.java   # Giả lập TCP Server
        │           │   ├── MockUdpServer.java   # Giả lập UDP Server
        │           │   └── MockHttpServer.java  # Giả lập HTTP Server
        │           │
        │           ├── repository/          # Tầng kết nối CSDL SQL Server
        │           │   ├── DatabaseManager.java
        │           │   ├── TargetRepository.java
        │           │   ├── IncidentRepository.java
        │           │   └── ConfigRepository.java
        │           │
        │           ├── ui/                  # Giao diện người dùng FlatLaf Swing
        │           │   ├── MainFrame.java
        │           │   ├── TargetDialog.java
        │           │   ├── SettingsDialog.java
        │           │   ├── NotificationToast.java
        │           │   └── components/
        │           │       ├── AgentClusterPanel.java # Tab 5: Quản lý cụm Agent & Server nhúng
        │           │       ├── LatencyChartPanel.java
        │           │       ├── StatusBadgeRenderer.java
        │           │       └── SimulatorPanel.java
        │           │
        │           └── util/                # Tiện ích chung
        │               ├── SoundHelper.java
        │               ├── CsvExporter.java
        │               └── LoggerUtil.java
        │
        └── resources/
            └── setup_database.sql           # Script khởi tạo SQL Server
```

---

## 7. BẢNG MA TRẬN ĐÓNG GÓP THÀNH VIÊN (PROJECT CONTRIBUTION MATRIX)

Bảng thống kê trách nhiệm, phạm vi công việc và các Merge Request / Issue theo quy định bảo vệ đồ án:

| MSSV | Họ và Tên | Công việc đã thực hiện | Merge Requests |
|:---|:---|:---|:---|
| **2380600986** | **Nguyễn Duy Khang** | Xây dựng lõi giao thức phân tán nhị phân **NCAS-P v1/v1.1**, đồng bộ phân phối mục tiêu **TargetDistributor**, cơ chế **Store-and-Forward** trên Agent, tích hợp hạ tầng khám phá mạng LAN thật và đồng bộ thời gian chuẩn **Clock injection**. | #1, #5, #9 (PR #13) |
| **2380600583** | **Vũ Thị Hạnh** | Thiết kế tầng trừu tượng CSDL **DatabaseBackend** (H2 file / SQL Server), công cụ tự động migration **SchemaMigrationEngine**, pipeline ghi bất đồng bộ **JdbcResultSink**, thiết lập bộ quy tắc kiểm định kiến trúc **Architecture Ratchet** và bảo mật bảng điều khiển Web NOC. | #2, #6, #10 (PR #14) |
| **2380600508** | **Trần Nam Định** | Hiện thực các bộ đo giao thức mạng **Checkers** (ICMP ping hệ điều hành, TCP 3-way, UDP echo, DNS RFC 1035, HTTP/HTTPS), bảo mật kênh truyền **TLS 1.3** và xác thực mã vân tay chứng chỉ **SHA-256 Pinning**, chẩn đoán đường truyền **Path Diagnosis**. | #3, #7, #11 (PR #15) |
| **2380601430** | **Biện Khắc Mẫn Nghi** | Thiết kế và hoàn thiện toàn bộ giao diện máy trạm **Java Swing FlatLaf** độ phân giải cao (High-DPI), bố cục chống tràn **No-Overlap Card Layout**, xây dựng kịch bản kiểm thử hỗn loạn **TcpFaultProxy** và hệ thống lab container **Docker Compose**. | #4, #8, #12 (PR #16) |

