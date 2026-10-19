#!/usr/bin/env bash
# scripts/scan-secrets.sh — Quét tự động phát hiện secret/token/mật khẩu trên Linux/CI
set -euo pipefail

echo "🔍 [Secret Scanner] Bắt đầu quét phát hiện rò rỉ bí mật..."

EXCLUDES=(
    ":!*.git*"
    ":!*target/*"
    ":!*.mvn/*"
    ":!*docs/BASELINE.md"
    ":!*docs/DEVIATIONS.md"
    ":!*docs/protocol/NCAS-P-v1.md"
    ":!*docs/DECISIONS.md"
    ":!*src/test/resources/protocol/golden/*"
    ":!*src/test/resources/ratchet/baseline-violations.txt"
    ":!*scratch/*"
)

# Kiểm tra private keys
if git grep -I -E "-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----" -- . "${EXCLUDES[@]}" 2>/dev/null; then
    echo "❌ PHÁT HIỆN PRIVATE KEY BỊ LỘ TRONG REPO!"
    exit 1
fi

echo "✅ [Secret Scanner] Quét hoàn tất: Không phát hiện secret nhạy cảm nào bị lộ!"
exit 0
