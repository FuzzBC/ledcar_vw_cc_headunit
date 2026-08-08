@echo off
cd /d "%~dp0"

taskkill /F /IM LedCar01Simulator.exe >nul 2>&1

dotnet build -c Release --nologo -v q
if errorlevel 1 (
    echo Build failed - see errors above.
    pause
    exit /b 1
)

start "" pythonw gui.py
exit
