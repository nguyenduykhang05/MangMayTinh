# ĐẶC TẢ GIAO THỨC TẦNG ỨNG DỤNG NCAS-P v1
## (Network Connection Alert System Protocol - Version 1.0)

**Tác giả**: Đội ngũ Kỹ sư Mạng & Backend NCAS  
**Trạng thái**: Hoàn thiện & Khuyến nghị triển khai (Recommended Standard)  
**Tương thích**: Java 17+, Pure JDK Socket Networking (`java.net`, `javax.net.ssl`)  

---

## 1. TỔNG QUAN VÀ BỐI CẢNH MẠNG THỰC TẾ

### 1.1. Mục đích thiết kế
Giao thức **NCAS-P v1** (Network Connection Alert System Protocol version 1) là giao thức truyền thông tầng Ứng dụng (Application Layer Protocol) nhị phân kết hợp JSON, được thiết kế đặc biệt cho kiến trúc giám sát mạng phân tán **Server – Agent – Console**.

### 1.2. Quyết định Kiến trúc Kết nối: Agent Reverse Connection (Push Model)
Trong môi trường mạng Internet và doanh nghiệp thực tế:
- **Hiện trạng máy Agent**: Các máy Agent đo kiểm thường được đặt tại các chi nhánh, máy trạm nội bộ hoặc mạng gia đình. Những máy này **100% nằm sau NAT (Network Address Translation) hoặc CGNAT (Carrier-Grade NAT)** của các nhà mạng (Viettel, VNPT, FPT), không sở hữu địa chỉ IPv4 tĩnh công khai (Public IP) và được bảo vệ nghiêm ngặt bởi Stateful Firewall (chặn toàn bộ kết nối Inbound).
- **Hạn chế của mô hình Pull (Server chủ động kết nối tới Agent)**:
  - Buộc người dùng phải cấu hình Port Forwarding trên Router biên của Agent (rất phức tạp và nguy hiểm về bảo mật).
  - Không thể hoạt động khi Agent di chuyển mạng (laptop, thiết bị Wi-Fi đổi IP) hoặc dùng mạng 4G/5G.
- **Lựa chọn tối ưu trong NCAS-P v1 (Agent Reverse Connection / Active Push)**:
  - Agent **chủ động khởi tạo kết nối Outbound TCP** tới Server trung tâm (Server có IP Public hoặc IP cố định trong mạng LAN nội bộ).
  - Bảng dịch địa chỉ NAT trên Router sẽ tự động duy trì đường truyền (Stateful NAT Tracking).
  - Sau khi kết nối TCP 3 bước hoàn tất, Socket trở thành kênh truyền **Full-Duplex (hai chiều)**: Server có thể đẩy cấu hình/mục tiêu (`ASSIGN_TARGETS`) xuống Agent, và Agent gửi kết quả (`REPORT_RESULT`) ngược về Server qua cùng một kết nối duy nhất mà không bị chặn bởi bất kỳ Router hay Firewall nào.

---

## 2. ĐẶC TẢ ĐỊNH DẠNG KHUNG TRUYỀN (FRAME FORMAT)

### 2.1. Cơ chế chống Dính gói / Xé gói TCP (Packet Sticking & Fragmentation)
TCP là giao thức hướng dòng byte (`byte-stream`), không lưu giữ ranh giới tin nhắn (`no message boundaries`). Do thuật toán Nagle (`TCP_NODELAY`), kích thước đệm MTU (thường 1500 bytes), một lệnh `read()` có thể đọc được nửa gói tin (xé gói) hoặc nhiều gói tin liền nhau (dính gói).

**NCAS-P v1 áp dụng cơ chế Length-Prefixed Binary Header kèm Magic Bytes và CRC32 Checksum**:

```text
+-------------------+----------------+---------------------+---------------------+-----------------------------+--------------------+
| Magic Bytes (4B)  | Version (1B)   | Message Type (2B)   | Payload Length (4B) | Payload Body (N bytes)      | CRC32 Checksum (4B)|
| 0x4E 0x43 0x41 0x53| 0x01           | short (0x0001..0xFF)| int (0..16,777,216) | JSON (UTF-8 encoded)        | IEEE 802.3 CRC32   |
+-------------------+----------------+---------------------+---------------------+-----------------------------+--------------------+
| <----------------------------- 11 Bytes Binary Header --------------------> | <-------- N Bytes --------> | <---- 4 Bytes ---->|
```

### 2.2. Chi tiết các trường trong Khung truyền (Frame Header)

| Tên trường | Kiểu dữ liệu | Kích thước | Mô tả kỹ thuật |
| :--- | :--- | :--- | :--- |
| **Magic Bytes** | `byte[4]` | 4 bytes | Luôn là chuỗi ký tự ASCII `"NCAS"` (`0x4E, 0x43, 0x41, 0x53`). Dùng để nhận diện và đồng bộ frame, lọc bỏ dữ liệu rác từ các cuộc quét cổng mạng. |
| **Version** | `byte` | 1 byte | Phiên bản giao thức. Hiện tại là `0x01` (v1.0). Hỗ trợ tương thích ngược cho các bản nâng cấp sau này. |
| **Message Type** | `short` | 2 bytes | Mã số định danh loại thông điệp (xem Danh mục mục 3). Cho phép bộ định tuyến đọc nhanh thông điệp mà không cần parse JSON. |
| **Payload Length** | `int` | 4 bytes | Độ dài chính xác $N$ của phần thân tin nhắn tính theo byte (Big-Endian). Bộ giải mã sẽ chờ đọc đủ $N$ bytes trước khi deserialize. |
| **Payload Body** | `byte[N]` | $N$ bytes | Nội dung tin nhắn mã hóa định dạng UTF-8 JSON. Chứa dữ liệu nghiệp vụ có cấu trúc. |
| **CRC32 Checksum** | `int` | 4 bytes | Mã kiểm tra toàn vẹn CRC32 chuẩn IEEE 802.3 tính toán trên toàn bộ $N$ bytes của Payload Body. Phát hiện lỗi truyền dẫn tầng ứng dụng. |

---

## 3. DANH MỤC THÔNG ĐIỆP (MESSAGE TYPES CATALOG)

| Mã Hex | Tên thông điệp | Chiều truyền | Ý nghĩa nghiệp vụ |
| :--- | :--- | :--- | :--- |
| `0x0001` | `MSG_REGISTER_REQ` | Agent $\to$ Server | Agent gửi thông tin danh tính (ID, Hostname, OS, IP, Token) để xin đăng ký. |
| `0x0002` | `MSG_REGISTER_RESP` | Server $\to$ Agent | Server trả lời chấp nhận (`SUCCESS`) hoặc từ chối (`AUTH_FAILED`), kèm chu kỳ Heartbeat. |
| `0x0003` | `MSG_HEARTBEAT_PING` | Agent $\to$ Server | Nhịp tim định kỳ (mỗi 10s), kèm thông số CPU, RAM, số tác vụ đang chạy. |
| `0x0004` | `MSG_HEARTBEAT_PONG` | Server $\to$ Agent | Phản hồi xác nhận sống (Ack) từ Server. |
| `0x0005` | `MSG_ASSIGN_TARGETS` | Server $\to$ Agent | Server phân công danh sách mục tiêu đo kiểm cho Agent kèm thông số chu kỳ. |
| `0x0006` | `MSG_REPORT_RESULT` | Agent $\to$ Server | Agent báo cáo kết quả đo lường kèm phân rã thời gian (DNS, TCP, TLS, TTFB). |
| `0x0007` | `MSG_FORCE_CHECK_REQ` | Server $\to$ Agent | Yêu cầu Agent thực hiện đo ngay lập tức một mục tiêu ngoài chu kỳ. |
| `0x0008` | `MSG_FORCE_CHECK_RESP`| Agent $\to$ Server | Kết quả đo tức thời gửi ngược về Server. |
| `0x0009` | `MSG_CONSOLE_SUB` | Console $\to$ Server | Console kết nối và đăng ký nhận dòng sự kiện mạng thời gian thực. |
| `0x000A` | `MSG_CONSOLE_STREAM` | Server $\to$ Console | Server stream sự kiện: Chuyển trạng thái, Cảnh báo, Kết quả đo cho Console. |
| `0x000B` | `MSG_CONSOLE_CMD` | Console $\to$ Server | Console gửi lệnh quản trị CLI (`list-agents`, `list-targets`, `pause`, `resume`...). |
| `0x000C` | `MSG_CONSOLE_RESP` | Server $\to$ Console | Server trả lời kết quả thực thi lệnh CLI cho Console. |
| `0x00FF` | `MSG_DISCONNECT` | Cả hai chiều | Báo ngắt kết nối chủ động kèm mã nguyên nhân (`SHUTDOWN`, `TIMEOUT`, `KICKED`). |

---

## 4. CHI TIẾT SCHEMA PAYLOAD JSON (PAYLOAD SPECIFICATION)

### 4.1. Đăng ký Agent (`MSG_REGISTER_REQ` & `MSG_REGISTER_RESP`)
**Gửi đi (`0x0001`):**
```json
{
  "agentId": "agent-hcm-branch-01",
  "hostname": "DESKTOP-LAN-HCM",
  "os": "Windows 11 Pro 64-bit",
  "localIp": "192.168.1.150",
  "macAddress": "00:1A:2B:3C:4D:5E",
  "authToken": "e4f8a12b3c4d5e6f7a8b9c0d1e2f3a4b",
  "supportedProtocols": ["TCP", "UDP", "HTTP", "HTTPS", "DNS", "ICMP"],
  "version": "1.0.0"
}
```
**Phản hồi (`0x0002`):**
```json
{
  "status": "ACCEPTED",
  "assignedSessionId": "sess-9988-aabb",
  "heartbeatIntervalSeconds": 10,
  "serverTime": 1729668000000,
  "message": "Đăng ký thành công vào máy chủ NCAS Server."
}
```

### 4.2. Nhịp tim định kỳ (`MSG_HEARTBEAT_PING` & `MSG_HEARTBEAT_PONG`)
**Ping (`0x0003`):**
```json
{
  "agentId": "agent-hcm-branch-01",
  "timestamp": 1729668010000,
  "activeTasks": 4,
  "cpuUsagePercent": 12.5,
  "freeMemoryMb": 2048
}
```
**Pong (`0x0004`):**
```json
{
  "timestamp": 1729668010050,
  "serverStatus": "OK"
}
```

### 4.3. Phân công mục tiêu giám sát (`MSG_ASSIGN_TARGETS` - `0x0005`)
```json
{
  "assignedTargets": [
    {
      "id": 101,
      "name": "Cổng Thanh Toán Chi Nhánh",
      "host": "payment.chinhanh.internal",
      "port": 443,
      "protocol": "HTTPS",
      "intervalSeconds": 5,
      "timeoutMs": 1500,
      "warningThresholdMs": 400
    },
    {
      "id": 102,
      "name": "Router Gateway Nội Bộ",
      "host": "192.168.1.1",
      "port": 80,
      "protocol": "TCP",
      "intervalSeconds": 3,
      "timeoutMs": 1000,
      "warningThresholdMs": 100
    }
  ]
}
```

### 4.4. Báo cáo kết quả đo kiểm (`MSG_REPORT_RESULT` - `0x0006`)
```json
{
  "agentId": "agent-hcm-branch-01",
  "targetId": 101,
  "timestamp": 1729668015000,
  "success": true,
  "latencyMs": 48,
  "responseCode": 200,
  "metrics": {
    "dnsLookupMs": 4,
    "tcpConnectMs": 12,
    "tlsHandshakeMs": 22,
    "ttfbMs": 10
  },
  "message": "HTTP 200 OK [DNS: 4ms | TCP: 12ms | TLS: 22ms | TTFB: 10ms | Tổng: 48ms]"
}
```

---

## 5. MÔ HÌNH TRẠNG THÁI VÀ CƠ CHẾ CHỐNG LỖI MẠNG (RELIABILITY & FAULT TOLERANCE)

### 5.1. Cơ chế Phát hiện Agent Chết (Dead Agent Detection)
- Chu kỳ Heartbeat mặc định: $T_{hb} = 10$ giây.
- Ngưỡng Timeout trên Server: $T_{timeout} = 3 \times T_{hb} = 30$ giây.
- Nếu Server không nhận được bất kỳ byte nào từ kết nối TCP của Agent trong 30s:
  1. Server tự động đóng socket và giải phóng Worker Thread.
  2. Đổi trạng thái Agent thành `OFFLINE`.
  3. Kích hoạt sự kiện cảnh báo `AGENT_DISCONNECTED` (gửi Email, Telegram, Toast notification cho Quản trị viên).
  4. Thu hồi các mục tiêu đã phân công hoặc tự động tái điều phối sang Agent dự phòng khác.

### 5.2. Thuật toán Tự động Tái kết nối (Exponential Backoff Auto-Reconnect)
Khi đường truyền Internet bị ngắt quãng hoặc Server được bảo trì khởi động lại, Agent không dừng chương trình mà chuyển sang chế độ tự động tái kết nối với thuật toán lũy thừa lùi kèm Jitter ngẫu nhiên:
$$T_{wait} = \min(2^{\text{attempt}} + \text{random}(0, 2), 30) \text{ giây}$$
- Lần 1: Chờ 2s
- Lần 2: Chờ 4s
- Lần 3: Chờ 8s
- Lần 4: Chờ 16s
- Lần 5 trở đi: Cố định 30s.  
*Mục đích*: Tránh hiện tượng **Thundering Herd Problem** (hàng trăm Agent cùng ập vào dồn dập tấn công DoS Server khi mạng vừa khôi phục).

### 5.3. Hàng đợi Offline Store-and-Forward (Không mất mát dữ liệu sự cố)
Trong suốt khoảng thời gian Agent mất kết nối tới Server:
- Agent vẫn tiếp tục chu kỳ kiểm tra các mục tiêu được giao bằng các Checker độc lập.
- Kết quả đo lường được lưu tạm vào một hàng đợi tròn trong RAM giới hạn dung lượng (`LinkedBlockingQueue` tối đa 500 bản ghi).
- Ngay khi kết nối TCP tới Server được tái lập thành công, Agent tự động xả hàng đợi (drain queue) gửi bù toàn bộ kết quả lên Server. Giúp hệ thống không bị đứt đoạn dữ liệu lịch sử sự cố (Zero Incident Data Loss).

---

## 6. SƠ ĐỒ TRUYỀN THÔNG TUẦN TỰ (SEQUENCE DIAGRAM)

```text
+-------+                             +--------+                             +---------+
| Agent |                             | Server |                             | Console |
+-------+                             +--------+                             +---------+
    |                                      |                                      |
    | ----- 1. TCP Connect (9876) -------> |                                      |
    | ----- 2. MSG_REGISTER_REQ ---------> |                                      |
    | <---- 3. MSG_REGISTER_RESP --------- |                                      |
    |                                      | <---- 4. TCP Connect (9876) -------- |
    |                                      | <---- 5. MSG_CONSOLE_SUB ----------- |
    | <---- 6. MSG_ASSIGN_TARGETS -------- |                                      |
    |                                      |                                      |
    | [Thực hiện đo mạng nội bộ/target]   |                                      |
    |                                      |                                      |
    | ----- 7. MSG_REPORT_RESULT --------> |                                      |
    |                                      | [State Machine: OFFLINE->ONLINE]     |
    |                                      | ----- 8. MSG_CONSOLE_STREAM -------> |
    |                                      |                                      |
    | ----- 9. MSG_HEARTBEAT_PING -------> |                                      |
    | <---- 10. MSG_HEARTBEAT_PONG ------- |                                      |
    |                                      |                                      |
    |                                      | <---- 11. MSG_CONSOLE_CMD (list) --- |
    |                                      | ----- 12. MSG_CONSOLE_RESP --------> |
```

---

## 7. KẾ HOẠCH TRIỂN KHAI CODE VÀ TỔ CHỨC PACKAGE

Mã nguồn triển khai của giao thức NCAS-P v1 sẽ nằm trong package mới `com.networkalert.protocol`:

```text
com.networkalert.protocol/
├── Frame.java                  # Cấu trúc khung nhị phân chuẩn (Magic, Type, Length, Payload, CRC32)
├── FrameEncoder.java           # Mã hóa Frame POJO ra byte stream (DataOutputStream)
├── FrameDecoder.java           # Giải mã stream thành Frame POJO (DataInputStream, chống dính/xé gói)
├── MessageType.java            # Enum định danh mã lệnh (0x0001 .. 0x00FF)
├── messages/                   # Các lớp DTO POJO Payload định dạng JSON
│   ├── RegisterRequest.java
│   ├── RegisterResponse.java
│   ├── HeartbeatPing.java
│   ├── HeartbeatPong.java
│   ├── AssignTargetsMessage.java
│   ├── ReportResultMessage.java
│   ├── ForceCheckRequest.java
│   ├── ForceCheckResponse.java
│   ├── ConsoleCommand.java
│   └── ConsoleResponse.java
└── ProtocolException.java      # Ngoại lệ xử lý giao thức (Checksum mismatch, bad magic, v.v.)
```

---
*Tài liệu này là căn cứ kỹ thuật chính xác cho việc hiện thực Giai đoạn 1 (Lõi giao thức NCAS-P v1), Giai đoạn 2 (NCAS Server), Giai đoạn 3 (NCAS Agent), và Giai đoạn 4 (NCAS Console).*
