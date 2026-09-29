@echo off
REM ============================================================
REM Start Kafka 3.9.1 (KRaft mode, no ZooKeeper)
REM
REM Comments are deliberately ASCII-only -- see start-es.bat for why.
REM
REM In KRaft mode there is no separate ZooKeeper process: the broker
REM manages metadata itself through the controller port (9093).
REM After starting, the process list shows only java -- no zookeeper.
REM
REM One-time prerequisites (already done, recorded in
REM docs/06-runbook/local-setup.md):
REM   1. Generate cluster id: kafka-storage.bat random-uuid
REM   2. Format storage:      kafka-storage.bat format -t <ID> -c config\kraft\server.properties
REM ============================================================

REM The ".\" prefix is required -- see start-es.bat for the reason
REM (NoDefaultCurrentDirectoryInExePath, set by Git Bash).
set KAFKA_HEAP_OPTS=-Xmx1g -Xms1g
cd /d D:\aaaSoftware\kafka-3.9.1
call .\bin\windows\kafka-server-start.bat config\kraft\server.properties
