# ==============================================================================
# Dockerfile cho NCAS 2.0 (Server, Agent, Simulator, FaultProxy)
# Tuân thủ ERRATA §E14.3 & §E9.6:
# - Chạy non-root (User 'ncas' UID 1000)
# - Cấp quyền CAP_NET_RAW cho ping ICMP
# - Đóng gói Fat-JAR độc lập chạy đa nền tảng
# ==============================================================================

# STAGE 1: Build Jar với Maven Wrapper
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /build

# Sao chép toàn bộ mã nguồn
COPY . .

# Biên dịch và đóng gói fat-JAR
RUN chmod +x mvnw && ./mvnw clean package -DskipTests

# STAGE 2: Runtime Image nhẹ
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Cài đặt iputils cho lệnh ping hệ thống, libcap cho thiết lập quyền raw socket
RUN apk add --no-cache iputils libcap curl jq bash

# Tạo người dùng non-root 'ncas'
RUN addgroup -g 1000 ncas && \
    adduser -u 1000 -G ncas -s /bin/sh -D ncas && \
    mkdir -p /app/data /app/scenarios && \
    chown -R ncas:ncas /app

# Cấp đặc quyền CAP_NET_RAW cho binary ping và Java runtime
RUN setcap cap_net_raw+ep /bin/ping 2>/dev/null || true
RUN setcap cap_net_raw+ep /opt/java/openjdk/bin/java 2>/dev/null || true

# Sao chép artifact và kịch bản từ builder stage
COPY --from=builder /build/target/NetworkConnectionAlertSystem-1.0.0.jar /app/ncas.jar
COPY --from=builder /build/scenarios /app/scenarios

USER ncas

# Cổng 9876: NCAS-P Protocol | 8080: Web NOC Dashboard | 9000-9002: Mock Servers | 9877: FaultProxy
EXPOSE 9876 8080 9000 9001 9002 9877

ENTRYPOINT ["java", "-jar", "/app/ncas.jar"]
CMD ["server"]
