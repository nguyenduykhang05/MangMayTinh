# scripts/scan-secrets.ps1 — Quet tu dong phat hien secret/token/mat khau trong ma nguon
Param (
    [string]$Path = "."
)

Write-Host "[Secret Scanner] Bat dau quet phat hien ro ri bi mat trong $Path..." -ForegroundColor Cyan

$excludePaths = @(
    "*\.git\*",
    "*\target\*",
    "*\.mvn\*",
    "*\docs\BASELINE.md",
    "*\docs\DEVIATIONS.md",
    "*\docs\protocol\NCAS-P-v1.md",
    "*\docs\DECISIONS.md",
    "*\src\test\resources\protocol\golden\*",
    "*\src\test\resources\ratchet\baseline-violations.txt",
    "*\scratch\*",
    "*\data\*"
)

$patterns = @(
    '-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----',
    '(?i)(password|passwd|api_key|secret_key)\s*[:=]\s*["''][A-Za-z0-9+/=_-]{12,}["'']'
)

$foundViolations = @()

$files = Get-ChildItem -Path $Path -Recurse -File | Where-Object {
    $filePath = $_.FullName
    $matchExclude = $false
    foreach ($ex in $excludePaths) {
        if ($filePath -like $ex) { $matchExclude = $true; break }
    }
    -not $matchExclude
}

foreach ($file in $files) {
    if ($file.Extension -in @(".dll", ".jar", ".zip", ".png", ".jpg", ".svg", ".ico", ".class", ".db")) {
        continue
    }

    $lines = Get-Content -Path $file.FullName -ErrorAction SilentlyContinue
    $lineNum = 0
    foreach ($line in $lines) {
        $lineNum++
        if ($line -match "TEST-AGENT-SECRET" -or $line -match "secret-token-123" -or $line -match "dedicated-secret-key") {
            continue
        }
        foreach ($pat in $patterns) {
            if ($line -match $pat) {
                $foundViolations += [PSCustomObject]@{
                    File = $file.FullName.Replace((Get-Location).Path + "\", "")
                    Line = $lineNum
                    Content = $line.Trim()
                }
            }
        }
    }
}

if ($foundViolations.Count -gt 0) {
    Write-Host "PHAT HIEN BI MAT / SECRET KHA NGHI TRONG MA NGUON:" -ForegroundColor Red
    foreach ($v in $foundViolations) {
        Write-Host "   * $($v.File):$($v.Line) -> $($v.Content)" -ForegroundColor Yellow
    }
    Exit 1
} else {
    Write-Host "[Secret Scanner] Quet hoan tat: Khong phat hien secret nhay cam nao bi lo!" -ForegroundColor Green
    Exit 0
}
