@echo off
REM DeepSeek Harness (dsh) startup script
REM Sets up Node 22 path and launches dsh web UI

set PATH=C:\Users\jiang.ke\node22;%PATH%
cd /d C:\Users\jiang.ke\Desktop\internJ\deepseek-harness

echo Starting DeepSeek Harness Web UI...
echo Web UI will be available at http://127.0.0.1:3080
echo Press Ctrl+C to stop.

pnpm dsh web
