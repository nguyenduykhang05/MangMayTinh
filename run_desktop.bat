@echo off
chcp 65001 > nul
echo ========================================================
echo   KHỞI ĐỘNG HỆ THỐNG GIÁM SÁT & CẢNH BÁO MẠNG (NCAS)
echo ========================================================
echo Đang mở ứng dụng giám sát mạng...
start javaw -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%~dp0target\NetworkConnectionAlertSystem-1.0.0-standalone.jar"
exit
