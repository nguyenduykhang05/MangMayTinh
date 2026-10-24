#!/bin/sh
set -e

echo "🚀 [NAT Gateway] Đang khởi tạo Gateway NAT cho chi nhánh..."

# Cài đặt iptables nếu chưa có
which iptables > /dev/null 2>&1 || apk add --no-cache iptables

# Bật IP Forwarding trong Linux kernel
sysctl -w net.ipv4.ip_forward=1 > /dev/null 2>&1 || true

# Bật NAT MASQUERADE một chiều cho lưu lượng đi ra ngoài qua eth0 (hướng Server)
iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE

# Chống route vào: Ngăn chặn bất kỳ kết nối chủ động nào từ bên ngoài đi vào mạng chi nhánh
# Chỉ cho phép các gói tin phản hồi của kết nối đã khởi tạo trước đó (ESTABLISHED, RELATED)
iptables -A FORWARD -i eth0 -m state --state ESTABLISHED,RELATED -j ACCEPT
iptables -A FORWARD -i eth0 -j DROP

echo "✅ [NAT Gateway] Đã kích hoạt iptables MASQUERADE một chiều (Chống route vào thành công)."

# Duy trì tiến trình nền cho Router Gateway
exec tail -f /dev/null
