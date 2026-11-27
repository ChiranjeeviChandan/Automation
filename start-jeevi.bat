@echo off
chcp 65001 >nul
title Khoi dong Jeevi OS
cd /d "%~dp0"

echo Bat Jeevi OS chay NEN - khong con cua so den (port 7777)...
REM Viec tat instance cu + chay server AN giao het cho start-jeevi.vbs (cua so an hoan toan).
REM Log ghi vao server\jeevi.log (mo file do neu can xem loi). Tat server: stop-jeevi.bat.
wscript //nologo start-jeevi.vbs

echo.
echo Da bat. Cho ~10 giay roi mo http://localhost:7777 va bam Ctrl+Shift+R.
echo (Tat server: chay stop-jeevi.bat. Xem loi: mo file server\jeevi.log)
timeout /t 4 >nul
