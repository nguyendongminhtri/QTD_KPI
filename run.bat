@echo off
chcp 65001 >nul
cd /d C:\sync_kpi
java -jar sync-kpi-1.0-all.jar >> sync.log 2>&1
