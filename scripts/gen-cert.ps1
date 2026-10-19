# scripts/gen-cert.ps1 — Sinh chứng chỉ tự ký PKCS12 cho NCAS Server bằng JDK keytool (ERRATA §E2.1)
Param (
    [string]$KeystorePath = "data/tls/keystore.p12",
    [string]$Password = $env:NCAS_KEYSTORE_PASSWORD,
    [string]$Alias = "ncas-server",
    [int]$ValidityDays = 365,
    [string]$DName = "CN=NCAS Server, O=NCAS Distributed Monitoring, C=VN",
    [string]$SAN = "dns:localhost,ip:127.0.0.1"
)

$ErrorActionPreference = "Stop"

if (-not $Password) {
    $passFile = "data/tls/keystore.pass"
    if (Test-Path $passFile) {
        $Password = (Get-Content -Path $passFile -Raw).Trim()
    } else {
        $bytes = New-Object byte[] 18
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $Password = [System.Convert]::ToBase64String($bytes)
        $dir = [System.IO.Path]::GetDirectoryName($passFile)
        if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        Set-Content -Path $passFile -Value $Password -Encoding utf8
        Write-Host "[TLS Setup] Da tao mat khau ngau nhien luu tai $passFile" -ForegroundColor Yellow
    }
}

$dir = [System.IO.Path]::GetDirectoryName($KeystorePath)
if ($dir -and -not (Test-Path $dir)) {
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
}

if (Test-Path $KeystorePath) {
    Write-Host "[TLS Setup] Keystore da ton tai tai $KeystorePath. Dang sao luu..." -ForegroundColor Yellow
    Copy-Item $KeystorePath "$KeystorePath.bak" -Force
    Remove-Item $KeystorePath -Force
}

Write-Host "[TLS Setup] Dang tao chung chi PKCS12 tu ky bang keytool..." -ForegroundColor Cyan

$keytool = "keytool"
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\keytool.exe"
    if (Test-Path $candidate) { $keytool = $candidate }
}

& $keytool -genkeypair -alias $Alias -keyalg RSA -keysize 2048 -storetype PKCS12 `
    -keystore $KeystorePath -storepass $Password -validity $ValidityDays `
    -dname $DName -ext "SAN=$SAN"

if ($LASTEXITCODE -ne 0) {
    Write-Host "[TLS Setup] Loi khi thuc thi keytool!" -ForegroundColor Red
    Exit 1
}

Write-Host "[TLS Setup] Tao keystore thanh cong tai $KeystorePath" -ForegroundColor Green

# Trich xuat Fingerprint SHA-256
$listOutput = & $keytool -list -v -keystore $KeystorePath -storepass $Password -alias $Alias
$fpLine = $listOutput | Select-String "SHA256:\s*([0-9A-Fa-f:]+)"
if ($fpLine -match "SHA256:\s*([0-9A-Fa-f:]+)") {
    $fpHex = $matches[1].Replace(":", "").ToLower()
    Write-Host "======================================================================" -ForegroundColor Green
    Write-Host "SHA-256 FINGERPRINT CHUNG CHI LA (SERVER LEAF CERTIFICATE):" -ForegroundColor Green
    Write-Host "   sha256:$fpHex" -ForegroundColor Yellow
    Write-Host "----------------------------------------------------------------------" -ForegroundColor Green
    Write-Host "Tham so khoi chay Agent va Console:" -ForegroundColor Cyan
    Write-Host "   --server-fp sha256:$fpHex" -ForegroundColor White
    Write-Host "======================================================================" -ForegroundColor Green
}
