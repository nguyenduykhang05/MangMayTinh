# HƯỚNG DẪN CẤU HÌNH NCAS CHẠY 24/7 DƯỚI DẠNG WINDOWS SERVICE

Trong môi trường thực tế doanh nghiệp, hệ thống giám sát mạng cần hoạt động liên tục **24/7** kể cả khi chưa có người dùng đăng nhập vào Windows.

---

## Cách 1: Sử dụng WinSW (Khuyên dùng)
1. Tải file thực thi `WinSW-x64.exe` từ [WinSW Releases](https://github.com/winsw/winsw/releases).
2. Đổi tên `WinSW-x64.exe` thành `ncas-service.exe` và đặt vào thư mục `service/` này (cùng cấp với `ncas-service.xml`).
3. Mở **Command Prompt (CMD)** hoặc **PowerShell** với quyền **Administrator**.
4. Di chuyển vào thư mục `service/`:
   ```cmd
   cd /d "d:\MMT\NetworkConnectionAlertSystem\service"
   ```
5. Cài đặt và khởi chạy dịch vụ:
   ```cmd
   ncas-service.exe install
   ncas-service.exe start
   ```
6. Kiểm tra trạng thái: Mở `services.msc` trên Windows sẽ thấy dịch vụ **Network Connection Alert System (NCAS)** đang chạy ở trạng thái `Running` (Tự động khởi động cùng Windows).

Để dừng hoặc gỡ bỏ dịch vụ:
```cmd
ncas-service.exe stop
ncas-service.exe uninstall
```

---

## Cách 2: Sử dụng NSSM (Non-Sucking Service Manager)
1. Tải `nssm.exe` từ [nssm.cc](https://nssm.cc/).
2. Chạy lệnh:
   ```cmd
   nssm install NCASService "java" "-jar d:\MMT\NetworkConnectionAlertSystem\target\NetworkConnectionAlertSystem-1.0.0-standalone.jar"
   nssm start NCASService
   ```

---

## Cách 3: Chạy Khay Hệ Thống (System Tray) Desktop 24/7
Nếu bạn sử dụng máy tính làm việc hàng ngày:
1. Double-click file `run_desktop.bat` ở thư mục gốc.
2. Bấm nút **(X)** ở góc phải cửa sổ.
3. Ứng dụng sẽ tự động thu nhỏ vào **Khay hệ thống (System Tray)** bên cạnh đồng hồ Windows và tiếp tục giám sát mạng 24/7.
4. Khi có máy chủ bị sập, biểu tượng khay hệ thống sẽ đổi sang **Màu đỏ**, đồng thời gửi thông báo đẩy tới **Telegram trên điện thoại** của bạn ngay lập tức!
