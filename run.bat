@echo off
REM Start WaniCam (Windows). Extra options are passed through.
cd /d "%~dp0"
where py >nul 2>nul
if %errorlevel%==0 (py -3 -m wanicam %*) else (python -m wanicam %*)
pause
