@echo off
REM ============================================================
REM Start Kafka (a Docker container running inside WSL2).
REM
REM Comments are deliberately ASCII-only -- see start-es.bat for why.
REM
REM WHY THIS SCRIPT NO LONGER STARTS A NATIVE WINDOWS KAFKA
REM -------------------------------------------------------
REM Kafka on native Windows is unusable: whenever it needs to reclaim
REM disk (deleting old log segments, or deleting a topic) the broker
REM crashes. It did so 16 times on 2026-10-07. Tested and ruled out:
REM bad data, stale directory, antivirus, retention settings, and the
REM log cleaner. The only thing that correlates is "there is something
REM to delete". Inside a Linux container that failure mode does not exist.
REM
REM Decision and reasoning: docs/02-decisions/ADR-0021-docker-in-wsl2.md
REM Troubleshooting notes:  docs/06-runbook/troubleshooting.md
REM
REM The old native install is still on disk (D:\aaaSoftware\kafka-3.9.1)
REM but is no longer used. Do not delete it yet -- see ADR-0021.
REM
REM WHY THIS WINDOW MUST STAY OPEN
REM ------------------------------
REM WSL2 shuts the whole virtual machine down after roughly a minute of
REM inactivity. When that happens the container dies with it and port
REM 9092 goes away -- which looks like "Kafka randomly stops working".
REM Keeping one WSL session alive holds the VM up, so this script stays
REM in the foreground on purpose. Closing the window stops Kafka.
REM (The .wslconfig `vmIdleTimeout` setting did NOT work on this WSL
REM  build -- 3.0.1 -- so we rely on the live session instead.)
REM ============================================================

set COMPOSE=/mnt/d/aaaDocuments/project/WingtiskyForum/deploy/docker-compose.yml
set DISTRO=Ubuntu-22.04

echo [start-kafka] Starting Kafka via WSL2 + Docker Compose...

wsl.exe -d %DISTRO% -u root -e bash -lc "docker compose -f %COMPOSE% up -d"
if errorlevel 1 (
  echo [start-kafka] FAILED to start.
  echo   Check that WSL2 and Docker work:  wsl.exe -d %DISTRO% -u root -e bash -lc "docker ps"
  echo   Troubleshooting: docs/06-runbook/troubleshooting.md
  pause
  exit /b 1
)

echo [start-kafka] Waiting for the broker to become healthy...
wsl.exe -d %DISTRO% -u root -e bash -lc "for i in $(seq 1 30); do s=$(docker inspect -f '{{.State.Health.Status}}' wt-kafka 2>/dev/null); if [ \"$s\" = healthy ]; then echo started; exit 0; fi; sleep 2; done; echo NOT-HEALTHY; docker logs wt-kafka --tail 20; exit 1"

if errorlevel 1 (
  echo [start-kafka] Broker did not become healthy in time.
  pause
  exit /b 1
)

echo.
echo [start-kafka] Kafka is UP. Windows side connects to localhost:9092.
echo.
echo   *** KEEP THIS WINDOW OPEN ***
echo   Closing it stops the WSL session and Kafka goes down with it.
echo   To stop Kafka: press Ctrl+C, or run scripts\stop-kafka.bat
echo.
echo   (Why: WSL2 powers off the whole virtual machine after ~1 minute of
echo    inactivity, taking the container with it. This window keeps it up.)
echo.

REM Hold the WSL session open for as long as this window lives.
wsl.exe -d %DISTRO% -u root -e bash -lc "while true; do sleep 3600; done"
