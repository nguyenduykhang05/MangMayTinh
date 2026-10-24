# Hướng Dẫn Vận Hành Môi Trường Lab NAT Phân Tán (Docker Compose Lab NAT)

> **Mã Task:** `T6.2`  
> **Đặc tả thiết kế:** `BLUEPRINT-ERRATA.md` §E14.3 & §E14.1  
> **Nền tảng hỗ trợ:** Windows 10/11 (Docker Desktop) & Linux (Docker Engine / Docker Compose v2)

---

## 1. Kiến Trúc Mạng Lab NAT

```
+-----------------------------------------------------------------------------------------+
|                               MANAGEMENT NETWORK (172.28.0.0/24)                        |
|                                                                                         |
|   +-----------------------+     +-----------------------+     +---------------------+   |
|   |      ncas-server      |     |      fault-proxy      |     |   tcpdump-sidecar   |   |
|   |     (172.28.0.10)     |     |     (172.28.0.30)     |     |    (172.28.0.99)    |   |
|   |  Ports: 9876 & 8080   |     |      Port: 9877       |     |   Ghi pcap mạng     |   |
|   +-----------^-----------+     +-----------^-----------+     +---------------------+   |
+---------------|-----------------------------|-------------------------------------------+
                | eth0                        | eth0
    +-----------+-----------+     +-----------+-----------+
    |    branch1-router     |     |    branch2-router     |
    | iptables MASQUERADE   |     | iptables MASQUERADE   |
    | (172.28.0.21 /        |     | (172.28.0.22 /        |
    |   10.10.1.1)          |     |   10.10.2.1)          |
    +-----------+-----------+     +-----------+-----------+
                | eth1                        | eth1
+---------------|-----------------------------|-------------------------------------------+
| BRANCH 1 NETWORK (10.10.1.0/24) | BRANCH 2 NETWORK (10.10.2.0/24)                       |
|                                 |                                                       |
|   +-------------------------+   |   +-------------------------+                         |
|   |      branch1-agent      |   |   |      branch2-agent      |                         |
|   |      (10.10.1.100)      |   |   |      (10.10.2.100)      |                         |
|   |    Non-root (CAP_RAW)   |   |   |    Non-root (CAP_RAW)   |                         |
|   +------------+------------+   |   +------------+------------+                         |
|                |                |                |                                      |
|   +------------v------------+   |   +------------v------------+                         |
|   |    branch1-simulator    |   |   |    branch2-simulator    |                         |
|   |      (10.10.1.200)      |   |   |      (10.10.2.200)      |                         |
|   |    Port 9000-9002       |   |   |    Port 9000-9002       |                         |
|   +-------------------------+   |   +-------------------------+                         |
+---------------------------------+-------------------------------------------------------+
```

### Đặc điểm thiết kế cốt lõi:
1. **NAT MASQUERADE một chiều:** Hai chi nhánh (`Branch 1` và `Branch 2`) sử dụng router Alpine độc lập với cấu hình `iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE`. Lưu lượng từ Agent đi ra Server được chuyển dịch địa chỉ nguồn; bên ngoài Server không có route đi ngược vào trong chi nhánh (Chống route vào).
2. **Quyền hạn tối thiểu (Least Privilege):** Tất cả Agent đều chạy dưới người dùng không đặc quyền `ncas` (UID 1000). Quyền thực thi lệnh ping ICMP được cấp riêng biệt thông qua Linux Capabilities `cap_add: [NET_RAW]`.
3. **Mô phỏng đứt gãy mạng qua FaultProxy:** `branch2-agent` kết nối tới Server thông qua container `fault-proxy` (cổng 9877), cho phép kiểm chứng Store-and-Forward và spool bền vững khi đường truyền bị cắt đứt.
4. **Tự động hóa hoàn toàn (Zero Touch):** Kịch bản `scenarios/demo.json` được kích hoạt tự động trên simulator, Server tự động ingest và hiển thị trên Web NOC Dashboard mà không cần can thiệp thủ công.

---

## 2. Cách Khởi Chạy

### Yêu cầu:
- Đã cài đặt Docker và Docker Compose (trên Windows: Docker Desktop với WSL2 backend).

### Khởi động toàn bộ Lab:
```bash
docker compose up -d --build
```

### Kiểm tra trạng thái các container:
```bash
docker compose ps
```

### Xem log của Server:
```bash
docker compose logs -f ncas-server
```

### Xem log của các Agent:
```bash
docker compose logs -f branch1-agent branch2-agent
```

### Truy cập Web NOC Dashboard:
Mở trình duyệt tại:
```
http://localhost:8080
```
- Quan sát danh sách 2 Agent (`agent-branch-1` và `agent-branch-2`) tự động kết nối và báo cáo kết quả kiểm tra.
- Xem đồ thị độ trễ, nhật ký sự cố và biểu đồ cấu trúc mạng (Topology Map).

---

## 3. Thử Nghiệm Chaos và Sự Cố Mạng

### Cắt kết nối chi nhánh 2 (Mô phỏng đứt cáp):
```bash
# Dừng container fault-proxy
docker compose stop fault-proxy
```
- Quan sát `branch2-agent`: Bắt đầu kích hoạt Spool bền vững lưu trữ kết quả vào đĩa cục bộ, chuyển sang cơ chế Reconnect với Full Jitter Backoff.
- Khôi phục kết nối:
```bash
docker compose start fault-proxy
```
- Quan sát `branch2-agent` xả toàn bộ dữ liệu backlog về Server mà không làm mất hoặc trùng lặp kết quả.

---

## 4. Dừng và Thu Dọn Môi Trường Lab

```bash
docker compose down -v
```
Lệnh trên sẽ dừng toàn bộ các container, xóa mạng ảo và thu hồi volume dữ liệu.
