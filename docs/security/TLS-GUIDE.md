# Hướng dẫn Kênh An Toàn TLS 1.2/1.3 và Ghim Fingerprint (NCAS 2.0)

> **Tài liệu tham chiếu:** NCAS 2.0 Architecture · ERRATA §E2.1 · Task T1.3

---

## 1. Tổng quan Kiến trúc An ninh Kênh truyền

Trong NCAS 2.0, cổng TCP **9876** (phục vụ kết nối giữa NCAS Agent, NCAS Console và NCAS Server) được bảo vệ bằng mã hóa **TLS (Transport Layer Security)** mặc định.

### 1.1. Quy tắc giao thức bắt buộc
- **Phiên bản tối thiểu:** `TLS 1.2`.
- **Phiên bản ưu tiên:** `TLS 1.3` (tự động thương lượng nếu JRE hỗ trợ).
- **Nghiêm cấm tuyệt đối:** Mọi phiên bản cũ $\le 1.1$ (`TLS 1.0`, `TLS 1.1`, `SSLv3`, `SSLv2Hello`) bị máy chủ từ chối ngay tại pha bắt tay TLS (TLS Handshake).
- **Mã hóa đầu cuối:** Toàn bộ payload giao thức NCAS-P v1 (gồm header 11 byte, CRC32 và dữ liệu JSON định danh, kết quả đo kiểm, lệnh console) được đóng gói trong TLS Application Data records (loại `0x17`), ngăn chặn 100% việc nghe lén hoặc chèn gói tin giả mạo trên đường truyền.

---

## 2. Tạo Keystore và Chứng chỉ Tự ký (Self-Signed PKCS12)

NCAS sử dụng định dạng chuẩn công nghiệp **PKCS12** (`.p12`) đặt tại `data/tls/keystore.p12`. Hệ thống không bổ sung bất kỳ thư viện bên ngoài nào, mà tận dụng công cụ tiêu chuẩn `keytool` đi kèm JDK.

### 2.1. Sinh chứng chỉ trên Windows (PowerShell)
```powershell
.\scripts\gen-cert.ps1
```
* **Tùy chọn nâng cao:**
  ```powershell
  .\scripts\gen-cert.ps1 -KeystorePath "data/tls/keystore.p12" -ValidityDays 365 -Alias "ncas-server"
  ```

### 2.2. Sinh chứng chỉ trên Linux / macOS (Bash)
```bash
chmod +x ./scripts/gen-cert.sh
./scripts/gen-cert.sh
```

### 2.3. Đầu ra sau khi sinh
Script sẽ in ra Fingerprint SHA-256 của chứng chỉ lá (Leaf Certificate):
```
======================================================================
SHA-256 FINGERPRINT CHỨNG CHỈ LÁ (SERVER LEAF CERTIFICATE):
   sha256:8f61fbdaa9e146ff8f4328ec4c7f60a7f7932272655099bb4ace94279d7c41b2
----------------------------------------------------------------------
Tham số khởi chạy Agent và Console:
   --server-fp sha256:8f61fbdaa9e146ff8f4328ec4c7f60a7f7932272655099bb4ace94279d7c41b2
======================================================================
```

Mật khẩu keystore được lưu an toàn tại `data/tls/keystore.pass` (phân quyền hạn chế) hoặc nạp qua biến môi trường `NCAS_KEYSTORE_PASSWORD`. Thư mục `data/tls/` và các tệp chứng chỉ/mật khẩu được cấu hình loại trừ triệt để trong `.gitignore` để không bao giờ bị lộ vào kho mã nguồn Git.

---

## 3. Cơ chế Ghim Fingerprint (Fingerprint Pinning)

Do NCAS triển khai trong môi trường mạng nội bộ hoặc Edge node sử dụng chứng chỉ tự ký, hệ thống áp dụng cơ chế **Ghim Fingerprint SHA-256** của chứng chỉ lá thông qua `FingerprintTrustManager`:

1. Khi Agent hoặc Console kết nối tới Server, trong pha TLS Handshake, `FingerprintTrustManager` trích xuất chứng chỉ lá của Server.
2. Tính hàm băm SHA-256 trên nội dung nhị phân DER (`cert.getEncoded()`).
3. So sánh với danh sách Fingerprint được cấu hình bằng `MessageDigest.isEqual(...)` nhằm triệt tiêu hoàn toàn rủi ro tấn công kênh bên theo thời gian (Timing Attack).
4. Nếu khớp: Bắt tay thành công.
5. Nếu không khớp: Ngay lập tức hủy kết nối và ném ngoại lệ `CertificateException` / `SSLHandshakeException`.

### Định dạng tham số dòng lệnh:
Tham số `--server-fp` (hoặc `--fingerprint`) chấp nhận nhiều định dạng linh hoạt:
- Có tiền tố: `--server-fp sha256:8f61fbdaa9e1...`
- Không tiền tố: `--server-fp 8f61fbdaa9e1...`
- Dạng hex có dấu hai chấm: `--server-fp 8F:61:FB:DA:...`
- Phân cách dấu phẩy để ghim nhiều chứng chỉ: `--server-fp sha256:fp1,sha256:fp2`

---

## 4. Quy trình Xoay Chứng chỉ Không Gián Đoạn (Zero-Downtime Certificate Rotation)

Khi chứng chỉ máy chủ sắp hết hạn hoặc cần thay đổi khóa định kỳ, quản trị viên áp dụng quy trình 4 bước chuyển tiếp mượt mà:

```
[Bước 1: Sinh Cert B] ---> [Bước 2: Cập nhật Agent ghim (A, B)] ---> [Bước 3: Restart Server với Cert B] ---> [Bước 4: Thu hồi A khỏi Agent]
```

### Bước 1: Sinh keystore mới chứa chứng chỉ B
```powershell
.\scripts\gen-cert.ps1 -KeystorePath "data/tls/keystore-new.p12" -Alias "ncas-server-v2"
```
Ghi nhận Fingerprint của Cert B: `sha256:bbbbbb...` (giả sử Cert A hiện tại là `sha256:aaaaaa...`).

### Bước 2: Cập nhật cấu hình Agent và Console chấp nhận cả A và B
Khởi động Agent với danh sách ghim chứa cả hai fingerprint:
```bash
java -jar ncas-agent.jar --server 10.0.0.1 --port 9876 --id agent-01 --token <TOKEN> \
    --server-fp sha256:aaaaaa...,sha256:bbbbbb...
```
*Lúc này Server vẫn đang dùng Cert A, Agent kết nối bình thường vì Cert A nằm trong danh sách hợp lệ.*

### Bước 3: Đổi chứng chỉ trên Server và khởi động lại
Sao chép `data/tls/keystore-new.p12` đè lên `data/tls/keystore.p12`, sau đó khởi động lại NCAS Server.
*Khi Server khởi động với Cert B, các Agent đang chạy sẽ tự động kết nối lại và chấp nhận Cert B ngay lập tức mà không gặp lỗi bắt tay.*

### Bước 4: Thu hồi Fingerprint cũ khỏi Agent và Console
Sau khi toàn bộ hệ thống đã hoạt động ổn định trên Cert B, loại bỏ fingerprint cũ `sha256:aaaaaa...` khỏi cấu hình Agent/Console:
```bash
java -jar ncas-agent.jar --server 10.0.0.1 --port 9876 --id agent-01 --token <TOKEN> \
    --server-fp sha256:bbbbbb...
```

---

## 5. Chế độ Thử nghiệm Không TLS (`--no-tls`)

Chế độ `--no-tls` chỉ phục vụ môi trường phòng thí nghiệm (Lab / Debug).
- Kích hoạt bằng cờ tường minh `--no-tls` trên cả Server, Agent và Console.
- Server và Agent sẽ xuất cảnh báo nổi bật màu vàng trên log hệ thống và banner:
  ```
  ⚠️  [AN NINH] NCAS Server đang chạy ở chế độ --no-tls!
  ⚠️  Mọi Agent bắt buộc xác thực qua Challenge-Response HMAC-SHA256 (hoặc --allow-legacy-auth).
  ```
- Trong chế độ `--no-tls`, để bảo vệ bí mật, Agent **tuyệt đối không gửi token bản rõ** qua mạng. Thay vào đó, Server phát thách đố ngẫu nhiên 32-byte `AUTH_CHALLENGE`, và Agent phản hồi bằng chữ ký điện tử `HMAC-SHA256(H, nonce ‖ agentId)` (Đặc tả §E2.3 & Task T1.2).

---

## 6. Xử lý Sự cố Thường gặp

| Triệu chứng | Nguyên nhân khả dĩ | Giải pháp xử lý |
|---|---|---|
| `SSLHandshakeException: Từ chối kết nối! Fingerprint chứng chỉ máy chủ không khớp` | Agent/Console cấu hình `--server-fp` sai hoặc Server vừa đổi chứng chỉ chưa cập nhật danh sách ghim. | Chạy `scripts/gen-cert.ps1` để lấy fingerprint chính xác, hoặc bổ sung fingerprint mới vào danh sách ghim của Agent. |
| `SSLException: Unsupported or unrecognized SSL message` | Agent cố kết nối TLS tới Server đang chạy cờ `--no-tls` (hoặc ngược lại). | Đồng bộ chế độ TLS giữa Server và Agent (cả hai cùng bật TLS hoặc cùng bật `--no-tls`). |
| `SSLHandshakeException: no cipher suites in common` hoặc `Received fatal alert: protocol_version` | Client cố kết nối bằng phiên bản TLS $\le 1.1$. | Nâng cấp môi trường JRE lên Java 17+ và đảm bảo client hỗ trợ TLS 1.2 hoặc TLS 1.3. |
| `AUTH_FAIL: Kênh TLS yêu cầu gửi Auth Token trong thông điệp đăng ký!` | Agent kết nối qua TLS nhưng không khai báo `--token`. | Truyền `--token <SECRET>` khi khởi chạy Agent hoặc đăng ký token mới qua lệnh `ncas-server token create <agentId>`. |
| Agent chuyển sang backoff cố định $\ge 60$ giây (`AuthConfigurationException`) | Sai token xác thực hoặc Fingerprint không khớp (ADR-11). | Kiểm tra và sửa cấu hình token hoặc fingerprint; Agent sẽ thử lại sau ít nhất 60 giây. |

<!-- Ki?m tra x�c th?c TLS 1.3 v� SHA-256 Pinning ho�n t?t -->
