@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
set "MCB_INSTALL_EXIT=%ERRORLEVEL%"
echo.
if not "%MCB_INSTALL_EXIT%"=="0" echo Installation is incomplete. Read the question or error above.
pause
exit /b %MCB_INSTALL_EXIT%
