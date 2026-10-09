@echo off
REM ============================================================
REM Stop Kafka (the Docker container inside WSL2).
REM
REM Comments are deliberately ASCII-only -- see start-es.bat for why.
REM
REM "down" keeps the data volume, so messages survive a stop/start.
REM To also wipe the data and start from a clean state, run inside WSL2:
REM   docker compose -f /mnt/d/aaaDocuments/project/WingtiskyForum/deploy/docker-compose.yml down -v
REM
REM Note: if start-kafka.bat's window is still open, Kafka is kept alive
REM by that window's session. Close it (or Ctrl+C) as well.
REM
REM See docs/02-decisions/ADR-0021-docker-in-wsl2.md and
REM docs/06-runbook/troubleshooting.md
REM ============================================================

set COMPOSE=/mnt/d/aaaDocuments/project/WingtiskyForum/deploy/docker-compose.yml
set DISTRO=Ubuntu-22.04

echo [stop-kafka] Stopping Kafka container (data volume is kept)...
wsl.exe -d %DISTRO% -u root -e bash -lc "docker compose -f %COMPOSE% down"
echo [stop-kafka] Done.
