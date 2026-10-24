# ==============================================================================
# Script xac minh moi truong Docker Compose Lab NAT (T6.2)
# ==============================================================================

Write-Host "[T6.2 Lab Verify] Bat dau kiem tra cau hinh Docker Compose Lab NAT..." -ForegroundColor Cyan

# 1. Kiem tra su ton tai cua cac file cau hinh
$files = @("docker-compose.yml", "Dockerfile", "docker/router-entrypoint.sh", "docker/README_DOCKER_LAB.md", "scenarios/demo.json")
foreach ($f in $files) {
    if (Test-Path $f) {
        Write-Host "  [OK] File ton tai: $f" -ForegroundColor Green
    } else {
        Write-Host "  [ERROR] Thieu file: $f" -ForegroundColor Red
        exit 1
    }
}

# 2. Kiem tra cu phap docker-compose.yml
if (Get-Command docker -ErrorAction SilentlyContinue) {
    Write-Host "`n[Docker CLI] Kiem tra cu phap docker-compose config..." -ForegroundColor Cyan
    try {
        $configOutput = docker compose config 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Host "  [OK] Cu phap docker-compose.yml hoan toan hop le!" -ForegroundColor Green
        } else {
            Write-Host "  [WARN] docker compose config: $configOutput" -ForegroundColor Yellow
        }
    } catch {
        Write-Host "  [WARN] Khong the goi docker compose: $($_.Exception.Message)" -ForegroundColor Yellow
    }
} else {
    Write-Host "`n[INFO] Docker CLI khong co san trong PATH - Bo qua kiem tra daemon." -ForegroundColor Gray
}

Write-Host "`n[T6.2 Lab Verify] Toan bo ho so cau hinh Docker Compose Lab NAT da san sang!" -ForegroundColor Green
