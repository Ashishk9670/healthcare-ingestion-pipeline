#!/usr/bin/env bash
# One-command demo: starts Postgres/Kafka/Keycloak, starts the app (background), seeds SYNTHETIC patients through the
# real pipeline and prints how to log in. No real data, no PHI. Stop with: make down (and kill the printed app PID).
set -euo pipefail
cd "$(dirname "$0")/.."

scripts/gen-dev-env.sh
set -a; source ./.env; set +a

docker compose up -d --wait
mvn -B -q -DskipTests package
mkdir -p target
if ! curl -fsS http://localhost:8081/actuator/health >/dev/null 2>&1; then
  nohup java -jar target/healthcare-ingestion-pipeline-*-SNAPSHOT.jar > target/app.log 2>&1 &
  echo "$!" > target/app.pid
fi
for i in $(seq 1 90); do
  curl -fsS http://localhost:8081/actuator/health >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS http://localhost:8081/actuator/health >/dev/null || { tail -50 target/app.log; exit 1; }

token=$(curl -fsS -d grant_type=password -d client_id=pipeline-client -d username=ingestor1 \
  --data-urlencode "password=${HDIP_PW_INGESTOR1}" \
  http://localhost:8180/realms/healthcare/protocol/openid-connect/token | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
curl -fsS -o /dev/null -X POST -H "Authorization: Bearer ${token}" "http://localhost:8080/api/v1/synthetic?count=40"

cat <<EOF

HDIP demo is running (SYNTHETIC DATA ONLY; 40 synthetic patients are being ingested).

  Dashboard:  http://localhost:8080/
  Keycloak:   http://localhost:8180/
  Dev users:  clinician1 (patients + cohort), analyst1 (cohort aggregates), auditor1 (audit log), ingestor1 (ingest)
  Passwords:  random per machine, stored in ./.env (HDIP_PW_<USER>, e.g. grep HDIP_PW_CLINICIAN1 .env). Never commit .env.

  App log:    target/app.log   (app PID in target/app.pid)
  Stop:       kill \$(cat target/app.pid); make down
EOF
