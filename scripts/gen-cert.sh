#!/usr/bin/env bash
# scripts/gen-cert.sh — Sinh chứng chỉ tự ký PKCS12 cho NCAS Server bằng JDK keytool (ERRATA §E2.1)
set -euo pipefail

KEYSTORE_PATH="${1:-data/tls/keystore.p12}"
PASSWORD="${NCAS_KEYSTORE_PASSWORD:-}"
ALIAS="ncas-server"
VALIDITY_DAYS=365
DNAME="CN=NCAS Server, O=NCAS Distributed Monitoring, C=VN"
SAN="dns:localhost,ip:127.0.0.1"

if [ -z "$PASSWORD" ]; then
    PASS_FILE="data/tls/keystore.pass"
    if [ -f "$PASS_FILE" ]; then
        PASSWORD=$(head -n 1 "$PASS_FILE" | tr -d '\r\n')
    else
        PASSWORD=$(openssl rand -base64 18 2>/dev/null || date +%s%N | sha256sum | head -c 24)
        mkdir -p "$(dirname "$PASS_FILE")"
        echo "$PASSWORD" > "$PASS_FILE"
        chmod 600 "$PASS_FILE"
        echo "[TLS Setup] Đã tạo mật khẩu ngẫu nhiên lưu tại $PASS_FILE"
    fi
fi

mkdir -p "$(dirname "$KEYSTORE_PATH")"

if [ -f "$KEYSTORE_PATH" ]; then
    echo "[TLS Setup] Keystore đã tồn tại tại $KEYSTORE_PATH. Đang sao lưu..."
    cp "$KEYSTORE_PATH" "${KEYSTORE_PATH}.bak"
    rm -f "$KEYSTORE_PATH"
fi

KEYTOOL_BIN="keytool"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
    KEYTOOL_BIN="$JAVA_HOME/bin/keytool"
fi

echo "[TLS Setup] Đang tạo chứng chỉ PKCS12 tự ký bằng keytool..."
"$KEYTOOL_BIN" -genkeypair -alias "$ALIAS" -keyalg RSA -keysize 2048 -storetype PKCS12 \
    -keystore "$KEYSTORE_PATH" -storepass "$PASSWORD" -validity "$VALIDITY_DAYS" \
    -dname "$DNAME" -ext "SAN=$SAN"

echo "[TLS Setup] Tạo keystore thành công tại $KEYSTORE_PATH"

# Trích xuất Fingerprint SHA-256
LIST_OUT=$("$KEYTOOL_BIN" -list -v -keystore "$KEYSTORE_PATH" -storepass "$PASSWORD" -alias "$ALIAS")
FP_HEX=$(echo "$LIST_OUT" | grep -i "SHA256:" | head -n 1 | awk '{print $2}' | tr -d ':' | tr '[:upper:]' '[:lower:]' | tr -d '\r\n')

echo "======================================================================"
echo "SHA-256 FINGERPRINT CHỨNG CHỈ LÁ (SERVER LEAF CERTIFICATE):"
echo "   sha256:${FP_HEX}"
echo "----------------------------------------------------------------------"
echo "Tham số khởi chạy Agent và Console:"
echo "   --server-fp sha256:${FP_HEX}"
echo "======================================================================"
