#!/usr/bin/env bash
# Brings up the stack, starts the app, runs Playwright, and tears everything down. SYNTHETIC DATA ONLY.
set -euo pipefail
cd "$(dirname "$0")/.."

cleanup() {
  [[ -n "${APP_PID:-}" ]] && kill "$APP_PID" 2>/dev/null || true
  [[ "${KEEP_STACK:-0}" == "1" ]] || docker compose down -v
}
trap cleanup EXIT

docker compose up -d --wait
mvn -B -q -DskipTests package
java -jar target/healthcare-ingestion-pipeline-*-SNAPSHOT.jar > target/app.log 2>&1 &
APP_PID=$!

echo "waiting for app health..."
for i in $(seq 1 90); do
  curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS http://localhost:8080/actuator/health >/dev/null || { tail -50 target/app.log; exit 1; }

(cd e2e && npm install --no-audit --no-fund && npx playwright install chromium && npx playwright test)
