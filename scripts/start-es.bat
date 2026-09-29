@echo off
REM ============================================================
REM Start Elasticsearch 8.18.3 (local development)
REM
REM Comments are deliberately ASCII-only. Chinese text in a .bat
REM file is decoded as GBK by cmd.exe on a zh-CN Windows console but
REM stored as UTF-8 in git, which shreds the line structure and makes
REM the whole script fail. Keep this file ASCII; the Chinese
REM explanation lives in docs/06-runbook/local-setup.md.
REM
REM Heap is pinned to 1G. ES defaults to 50% of physical RAM, which
REM would eat ~8G on this 16G machine and break the "start on demand,
REM 1G per middleware" budget in architecture.md 6.2.
REM
REM Note: ES refuses to start when run as Administrator.
REM See docs/06-runbook/local-setup.md
REM ============================================================

REM The ".\" prefix is required, not cosmetic. Git Bash sets
REM NoDefaultCurrentDirectoryInExePath=1, which it passes on to any
REM cmd.exe it spawns -- cmd then refuses to look in the current
REM directory for the script, and "call elasticsearch.bat" fails with
REM "'elasticsearch.bat' is not recognized" even after a successful cd.
REM "call .\elasticsearch.bat" names the path explicitly and works.
set ES_JAVA_OPTS=-Xms1g -Xmx1g
cd /d D:\aaaSoftware\elasticsearch-8.18.3\bin
call .\elasticsearch.bat
