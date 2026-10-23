# Đặc Tả Giao Thức Mạng Phân Tán NCAS-P v1.1

> **Phiên bản:** 1.1.0 · Ngày ban hành: 24/10/2026 · Áp dụng cho: NCAS 2.0 (Task T1.4, ERRATA §E3)  
> **Kế thừa và mở rộng:** [NCAS-P-v1.md](file:///d:/Workspace/MMT/NetworkConnectionAlertSystem/docs/protocol/NCAS-P-v1.md)

---

## 1. Tổng quan & Triết lý Thiết kế

Giao thức **NCAS-P v1.1** mở rộng giao thức mạng phân tán NCAS-P v1.0 nhằm đáp ứng 6 bất biến độ tin cậy ($I_1$–$I_6$) trong môi trường phân tán:
1. **Bảo tồn định dạng khung (Wire format):** Giữ nguyên header nhị phân 11 byte và trailer CRC32 4 byte của v1.0. Mọi parser v1.0 đều có thể đọc khung v1.1 mà không gặp lỗi framing.
2. **Đàm phán phiên bản tương thích ngược (`protoMax`):** Peer khai báo năng lực tối đa trong gói tin đăng ký `REGISTER_REQ`. Máy chủ luôn hạ bậc về phiên bản tối thiểu chung `proto = min(serverProtoMax, clientProtoMax)`.
3. **Độ tin cậy dữ liệu (At-least-once & Watermark bền):** Bổ sung định danh luồng `streamId` (ngẫu nhiên 64-bit), số thứ tự `seq` tăng đơn điệu, cơ chế xác nhận `ACK` tích lũy và xử lý khoảng hụt dữ liệu `DataGap`.
4. **Đồng bộ thời gian phân tán (NTP 4 mốc):** Trao đổi 4 mốc thời gian $t_0, t_1, t_2, t_3$ qua chu kỳ Heartbeat giúp Server chuẩn hóa chính xác thời điểm đo kiểm bất chấp sai lệch đồng hồ của Agent.

---

## 2. Cấu trúc Khung Dữ liệu (Frame Structure)

Khung NCAS-P v1.1 giữ nguyên 100% cấu trúc nhị phân 15 byte vỏ bọc của v1.0:

```
+-----------------------------------+--------------------+------------------------+------------------------------------+--------------------------+
| Magic (4 Bytes)                   | Version (1 Byte)   | Message Type (2 Bytes) | Payload Length (4 Bytes)           | Payload JSON (N Bytes)   | Checksum CRC32 (4 Bytes) |
| 0x4E 0x43 0x41 0x53 ("NCAS")      | 0x01               | Big-Endian uint16      | Big-Endian uint32 (Max 1 MiB)      | UTF-8 Encoded JSON       | Big-Endian IEEE 802.3    |
+-----------------------------------+--------------------+------------------------+------------------------------------+--------------------------+
|<----------------- Offset 0: Header 11 Bytes ----------------------------------->|<--- Offset 11: Length N ---------->|<- Offset 11+N: 4 Bytes ->|
```

---

## 3. Bảng Mã Thông Điệp (Message Types)

NCAS-P v1.1 bổ sung 4 MessageType mới nối tiếp sau 13 loại thông điệp của v1.0:

| Mã Hex | Tên Enum | Chiều truyền | Ý nghĩa | Phiên bản |
|---|---|---|---|---|
| `0x0001` | `REGISTER_REQ` | Agent $\rightarrow$ Server | Đăng ký danh tính (bổ sung `protoMax`, `streamId`) | v1.0 / v1.1 |
| `0x0002` | `REGISTER_RESP` | Server $\rightarrow$ Agent | Phản hồi đăng ký (bổ sung `protoVersion`) | v1.0 / v1.1 |
| `0x0003` | `HEARTBEAT_PING` | Agent $\rightarrow$ Server | Nhịp tim định kỳ (bổ sung mốc NTP $t_0$, `streams`, `gaps`) | v1.0 / v1.1 |
| `0x0004` | `HEARTBEAT_PONG` | Server $\rightarrow$ Agent | Phản hồi nhịp tim cho client legacy v1.0 | v1.0 |
| `0x0005` | `ASSIGN_TARGETS` | Server $\rightarrow$ Agent | Phân công danh sách mục tiêu giám sát | v1.0 |
| `0x0006` | `REPORT_RESULT` | Agent $\rightarrow$ Server | Báo cáo kết quả đơn lẻ (bổ sung `streamId`, `seq`, `diag`) | v1.0 / v1.1 |
| `0x0007` | `FORCE_CHECK_REQ` | Server $\rightarrow$ Agent | Yêu cầu đo tức thời | v1.0 |
| `0x0008` | `FORCE_CHECK_RESP` | Agent $\rightarrow$ Server | Trả kết quả đo tức thời | v1.0 |
| `0x0009` | `CONSOLE_SUBSCRIBE` | Console $\rightarrow$ Server | Đăng ký nhận stream sự kiện real-time | v1.0 |
| `0x000A` | `CONSOLE_EVENT_STREAM` | Server $\rightarrow$ Console | Phát sóng sự kiện trạng thái | v1.0 |
| `0x000B` | `CONSOLE_COMMAND` | Console $\rightarrow$ Server | Gửi lệnh quản trị | v1.0 |
| `0x000C` | `CONSOLE_COMMAND_RESP` | Server $\rightarrow$ Console | Trả kết quả lệnh quản trị | v1.0 |
| `0x000D` | `AUTH_CHALLENGE` | Server $\rightarrow$ Agent | Gửi nonce thách đố (chế độ `--no-tls`) | v1.0+ (T1.2) |
| `0x000E` | `AUTH_RESPONSE` | Agent $\rightarrow$ Server | Gửi chữ ký HMAC phản hồi challenge | v1.0+ (T1.2) |
| **`0x000F`** | **`ACK`** | Server $\rightarrow$ Agent | **Xác nhận lưu bền Watermark cho stream & mốc NTP** | **v1.1 Mới** |
| **`0x0010`** | **`REPORT_BATCH`** | Agent $\rightarrow$ Server | **Gửi lô kết quả đo kiểm (Backlog) kèm danh sách Gap** | **v1.1 Mới** |
| `0x00FF` | `DISCONNECT` | Cả hai chiều | Ngắt kết nối phiên an toàn | v1.0 |

---

## 4. Đặc tả Chi tiết các Trường Mở rộng (Optional Fields)

Mọi trường mới trong v1.1 đều là tùy chọn (**optional**) trong JSON payload. Parser Gson của hệ thống tự động bỏ qua trường lạ khi giao tiếp với client cũ.

### 4.1. Thông điệp `REGISTER_REQ` (0x0001)
```json
{
  "agentId": "agent-hanoi-01",
  "hostname": "srv-node-hn",
  "os": "Linux amd64",
  "localIp": "192.168.1.10",
  "macAddress": "00:1A:2B:3C:4D:5E",
  "authToken": "TOKEN-XYZ",
  "supportedProtocols": ["TCP", "HTTP", "HTTPS", "ICMP", "DNS"],
  "version": "1.1.0",
  "agentName": "NCAS-Agent-Hanoi",
  "agentVersion": "1.1.0",
  "protoMax": "1.1",
  "capabilities": ["stream", "batch", "gap", "ntp"],
  "location": "Hanoi DC",
  "clockMs": 1700000000000,
  "streamId": 8394018294819283
}
```

### 4.2. Thông điệp `REGISTER_RESP` (0x0002)
```json
{
  "status": "ACCEPTED",
  "assignedSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "heartbeatIntervalSeconds": 10,
  "serverTime": 1700000000010,
  "message": "Đăng ký thành công vào NCAS Server.",
  "protoVersion": "1.1"
}
```

### 4.3. Thông điệp `HEARTBEAT_PING` (0x0003)
```json
{
  "agentId": "agent-hanoi-01",
  "timestamp": 1700000010000,
  "activeTasks": 8,
  "cpuUsagePercent": 12.5,
  "freeMemoryMb": 512,
  "hbSeq": 42,
  "clockMs": 1700000010000,
  "clockOffsetMs": -15,
  "clockDelayMs": 8,
  "spoolDepth": 150,
  "spoolBytes": 65536,
  "activeProbes": 8,
  "droppedResults": 0,
  "skippedProbes": 0,
  "streams": [
    { "streamId": 8394018294819283, "lowSeq": 1, "lastSeq": 350 }
  ],
  "gaps": []
}
```

### 4.4. Thông điệp `REPORT_RESULT` (0x0006)
```json
{
  "agentId": "agent-hanoi-01",
  "targetId": 1,
  "timestamp": 1700000005000,
  "success": true,
  "latencyMs": 15,
  "responseCode": 200,
  "message": "HTTP 200 OK",
  "streamId": 8394018294819283,
  "seq": 101,
  "checkedAtMs": 1700000005000,
  "errorCode": "NONE",
  "method": "HTTP_GET",
  "backfilled": false,
  "diag": "DNS resolved in 2ms; TCP connected in 5ms; TLS handshake 8ms"
}
```

### 4.5. Thông điệp `ACK` (0x000F) — Mới
```json
{
  "streams": [
    { "streamId": 8394018294819283, "ackSeq": 101 }
  ],
  "hbSeq": 42,
  "serverRecvMs": 1700000010005,
  "serverSendMs": 1700000010006
}
```

### 4.6. Thông điệp `REPORT_BATCH` (0x0010) — Mới
```json
{
  "streamId": 8394018294819283,
  "firstSeq": 102,
  "lastSeq": 105,
  "items": [
    { "agentId": "agent-hanoi-01", "targetId": 1, "seq": 102, "success": true, "latencyMs": 14 },
    { "agentId": "agent-hanoi-01", "targetId": 2, "seq": 103, "success": true, "latencyMs": 2 },
    { "agentId": "agent-hanoi-01", "targetId": 3, "seq": 105, "success": false, "latencyMs": 3000 }
  ],
  "gaps": [
    { "streamId": 8394018294819283, "fromSeq": 104, "toSeq": 104, "reason": "dropped" }
  ]
}
```

---

## 5. Quy trình Đàm phán Phiên bản (`protoMax`)

```
   NCAS Agent (Client)                               NCAS Server
         |                                                 |
         | --- 1. REGISTER_REQ { protoMax: "1.1", ... } -> |
         |                                                 | (Tính toán: proto = min(serverProtoMax, clientProtoMax))
         |                                                 | (Nếu không có protoMax -> coi là v1.0)
         | <- 2. REGISTER_RESP { protoVersion: "1.1" } --- |
         |                                                 |
(Phiên kết nối hoạt động theo giao thức v1.1)
```

**Quy tắc bất biến:** Server **tuyệt đối không gửi** MessageType mới (`ACK`, `REPORT_BATCH`) hoặc các trường mới cho peer nếu phiên đàm phán là `protoVersion == "1.0"`.

---

## 6. Cơ chế Đồng bộ Đồng hồ 4 Mốc Thời gian (NTP-Style)

```
   NCAS Agent                                         NCAS Server
         |                                                 |
    (t0) | ------- HEARTBEAT_PING { clockMs: t0 } -------> |
         |                                                 | (t1 = serverRecvMs)
         |                                                 | (t2 = serverSendMs)
    (t3) | <------ ACK { serverRecvMs: t1, ...: t2 } ----- |
         |                                                 |
```

### Công thức tính toán:
$$\text{offset} = \frac{(t_1 - t_0) + (t_2 - t_3)}{2}$$
$$\text{delay} = (t_3 - t_0) - (t_2 - t_1)$$

- Agent lưu giữ 8 mẫu gần nhất, lọc mẫu có `delay` nhỏ nhất để đại diện cho giá trị `clockOffsetMs`.
- Nếu $|\text{offset}| > 5000\text{ ms}$, máy chủ kích hoạt cờ cảnh báo `clock_skew` cho Agent.
- Máy chủ chuẩn hóa thời điểm đo: $\text{checkedAtServer} = \min(\text{checkedAtMs} + \text{offset}, \text{serverRecvMs})$.

---

## 7. Cơ chế Quản lý Luồng (Stream), Số thứ tự (Seq) và Gap

- **`streamId`:** Số nguyên 64-bit ngẫu nhiên sinh mới mỗi lần Agent khởi động.
- **`seq`:** Số thứ tự bắt đầu từ 1 cho mỗi stream, tăng 1 đơn vị cho mỗi kết quả đo.
- **Watermark:** Mốc liên tục cao nhất mà Server đã lưu bền (commit CSDL).
- **Cửa sổ trượt `BitSet`:** Hỗ trợ tiếp nhận các kết quả đến lệch thứ tự (Out-of-order) trong khoảng tối đa 65.536 seq.
- **`DataGap`:** Khai báo khoảng bị mất (`dropped`) hoặc lượt đo OK bị lược bớt (`sampled`). Khi nhận được Gap, Server tự động kéo tiến Watermark qua khoảng Gap, ngăn chặn việc luồng ACK bị kẹt vĩnh viễn khi spool bị đầy.
