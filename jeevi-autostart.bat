@echo off
chcp 65001 >nul
title Tu khoi dong Jeevi OS
REM Tu khoi dong Jeevi cung Windows. Dung: jeevi-autostart.bat install | uninstall | status
REM Logic nam trong jeevi-autostart.ps1 (ghi/xoa muc JeeviOS trong HKCU Run, khong can quyen
REM admin). Bat roi thi dang nhap may la server tu chay nen va tu mo dashboard (0.306.0).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0jeevi-autostart.ps1" %1
