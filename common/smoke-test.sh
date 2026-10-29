#!/usr/bin/env bash
# End-to-end proof that a message flows through package-status-topic.
#
# Requires the four domain services to be running (start them first — see
# README.md). This script brings up the broker, triggers a stage change,
# and polls transit-service /mq-status until a message is observed.
#
# Exit 0 = message flowed. Exit 1 = something failed (details printed).
set -uo pipefail

# ── Styling ─────────────────────────────────────────────────────────────
GRN=$'\033[0;32m'; RED=$'\033[0;31m'; YEL=$'\033[0;33m'
BLD=$'\033[1m';    RST=$'\033[0m'
step() { printf "${BLD}[%d/%d]${RST} %s\n" "$1" "$2" "$3"; }
ok()   { printf "        ${GRN}OK${RST}   %s\n" "$1"; }
warn() { printf "        ${YEL}WARN${RST} %s\n" "$1"; }
fail() { printf "        ${RED}FAIL${RST} %s\n" "$1" >&2; }
info() { printf "        %s\n" "$1"; }

# ── Config ──────────────────────────────────────────────────────────────
HUB_ID="${HUB_ID:-H-501}"
STAGE="${STAGE:-5}"
BROKER_TIMEOUT="${BROKER_TIMEOUT:-60}"
MSG_TIMEOUT="${MSG_TIMEOUT:-15}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

TOTAL_STEPS=6
RC=0

echo
echo "══════════════════════════════════════════════════════════════════════"
echo " LogisticsConnect — end-to-end MQ smoke test"
echo " ══════════════════════════════════════════════════════════════════════"
echo " hubId=${HUB_ID}  stage=${STAGE}  broker-timeout=${BROKER_TIMEOUT}s  msg-timeout=${MSG_TIMEOUT}s"
echo

# ── 1. Prereqs ──────────────────────────────────────────────────────────
step 1 "$TOTAL_STEPS" "Checking prerequisites"
command -v docker >/dev/null || { fail "docker not found"; exit 1; }
docker info    >/dev/null 2>&1 || { fail "docker daemon not running"; exit 1; }
command -v curl >/dev/null || { fail "curl not found"; exit 1; }
ok "docker, curl"

# ── 2. Start broker ─────────────────────────────────────────────────────
step 2 "$TOTAL_STEPS" "Starting broker (docker compose up -d)"
if ! ( cd "$SCRIPT_DIR" && docker compose up -d ); then
  fail "docker compose up failed"; exit 1
fi
ok "docker compose up"

# ── 3. Wait for OpenWire port ───────────────────────────────────────────
step 3 "$TOTAL_STEPS" "Waiting for broker to accept connections on :61616"
i=0; READY=0
while [ "$i" -lt "$BROKER_TIMEOUT" ]; do
  if (exec 3<>/dev/tcp/localhost/61616) 2>/dev/null; then
    exec 3>&-
    ok "broker reachable after ${i}s"
    READY=1; break
  fi
  sleep 1; i=$((i + 1))
done
[ "$READY" -eq 1 ] || { fail "broker not reachable after ${BROKER_TIMEOUT}s"; RC=1; }

# ── 4. Check domain services ────────────────────────────────────────────
step 4 "$TOTAL_STEPS" "Checking domain services"
svc_ok() { curl -fsS --max-time 2 "$2" >/dev/null 2>&1; }
check() { local name="$1" url="$2"; if svc_ok "$name" "$url"; then ok "$name ($url)"; else fail "$name ($url)"; return 1; fi; }
check ingestion-service   "http://localhost:7050/health" || RC=1
check hub-service         "http://localhost:7051/health" || RC=1
check delay-stage-service "http://localhost:7052/health" || RC=1
check transit-service     "http://localhost:7053/health" || RC=1
if [ $RC -ne 0 ]; then
  echo
  info "Start the domain services first, then re-run this script:"
  info "  cd ingestion-service   && mvn package && java -jar target/ingestion-service.jar"
  info "  cd hub-service         && mvn package && java -jar target/hub-service.jar"
  info "  cd delay-stage-service && mvn package && java -jar target/delay-stage-service.jar"
  info "  cd transit-service     && mvn package && java -jar target/transit-service.jar"
  echo
  exit 1
fi

# ── 5. Trigger a stage change ───────────────────────────────────────────
step 5 "$TOTAL_STEPS" "Publishing stage change POST :7052/delay-stage/${HUB_ID} {stage:${STAGE}}"
TMP_BODY=$(mktemp); trap 'rm -f "$TMP_BODY"' EXIT
HTTP=$(curl -sS -o "$TMP_BODY" -w '%{http_code}' \
  -X POST "http://localhost:7052/delay-stage/${HUB_ID}" \
  -H 'Content-Type: application/json' \
  -d "{\"stage\":${STAGE}}" 2>/dev/null || echo "000")
BODY=$(cat "$TMP_BODY" 2>/dev/null || echo "")
case "$HTTP" in
  200|201) ok "HTTP ${HTTP} — ${BODY}" ;;
  *)       fail "HTTP ${HTTP} — ${BODY}"; RC=1 ;;
esac

# ── 6. Poll transit /mq-status ──────────────────────────────────────────
step 6 "$TOTAL_STEPS" "Verifying delivery at transit-service (subscribed consumer)"
GOT=0; i=0
while [ "$i" -lt "$MSG_TIMEOUT" ]; do
  MSG=$(curl -fsS --max-time 2 "http://localhost:7053/mq-status" 2>/dev/null || echo "")
  COUNT=$(printf '%s' "$MSG" | grep -o '"messagesReceived":[0-9]*' | head -n1 | cut -d: -f2)
  COUNT=${COUNT:-0}
  if [ "$COUNT" -ge 1 ]; then
    ok "messagesReceived=${COUNT} after ${i}s"
    info "$MSG"
    GOT=1; break
  fi
  sleep 1; i=$((i + 1))
done
if [ "$GOT" -eq 0 ]; then
  fail "no message observed at transit-service within ${MSG_TIMEOUT}s"
  fail "current transit /mq-status: $(curl -fsS http://localhost:7053/mq-status 2>/dev/null || echo unreachable)"
  RC=1
fi

# ── Summary ─────────────────────────────────────────────────────────────
echo
echo "══════════════════════════════════════════════════════════════════════"
if [ "$RC" -eq 0 ]; then
  printf " ${GRN}${BLD}PASS${RST}  message flowed end-to-end\n"
  echo "══════════════════════════════════════════════════════════════════════"
  echo
  echo " Evidence:"
  echo "   transit /mq-status:  $(curl -fsS http://localhost:7053/mq-status 2>/dev/null)"
  echo "   transit /eta/${HUB_ID}: $(curl -fsS "http://localhost:7053/eta/${HUB_ID}" 2>/dev/null)"
  echo "   broker console:      http://localhost:8161  (admin / admin)"
  echo "                        → Topics → package-status-topic → Enqueued/Dequeued"
  echo
  echo " Broker log tail:"
  ( cd "$SCRIPT_DIR" && docker compose logs --tail=5 activemq 2>/dev/null | sed 's/^/   /' )
  echo
  exit 0
else
  printf " ${RED}${BLD}FAIL${RST}  see failures above\n"
  echo "══════════════════════════════════════════════════════════════════════"
  echo
  echo " Broker log tail:"
  ( cd "$SCRIPT_DIR" && docker compose logs --tail=20 activemq 2>/dev/null | sed 's/^/   /' )
  echo
  exit 1
fi