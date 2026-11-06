# sync-kpi

Job đồng bộ chứng từ từ SQL Server (ITDVAPCF) lên website Spring Boot/MySQL.

## Build
    gradle fatJar            (hoặc gradlew fatJar nếu đã tạo wrapper: gradle wrapper)
Kết quả: build/libs/sync-kpi-1.0-all.jar

## Chạy thử
    java -jar build\libs\sync-kpi-1.0-all.jar 05/11/2026 05/11/2026

## Triển khai
Chép sync-kpi-1.0-all.jar và run.bat vào C:\sync_kpi\, đặt biến môi trường (xem set-env.bat.example),
rồi tạo Task Scheduler gọi run.bat (At startup + lặp mỗi 30-60 phút).
