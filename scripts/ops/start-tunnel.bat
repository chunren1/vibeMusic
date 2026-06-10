@echo off
title cpolar tunnel - vibeMusic
echo ================================
echo   cpolar auto-reconnect tunnel
echo   Port: 5173
echo   Press Ctrl+C to stop
echo ================================

:loop
echo.
echo [%time%] Starting cpolar...
cpolar http 5173
echo [%time%] cpolar exited, restarting in 3 seconds...
timeout /t 3 >nul
goto loop
