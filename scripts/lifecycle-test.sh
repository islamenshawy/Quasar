#!/usr/bin/env bash
# =====================================================================
# Card replacement, renewal and batch jobs (CMS-080 .. CMS-083).
# Needs: CMS with the dev profile (dev users, /api/dev time helpers), hsm-sim, seed-dev.sql,
#        curl, jq, java 21 (PIN blocks via hsm-sim).
# Usage: ./scripts/lifecycle-test.sh [base-url]
# =====================================================================
set -uo pipefail

BASE="${1:-http://localhost:8080}"
SIM="$(dirname "$0")/../hsm-sim/HsmSimulator.java"
KIOSK_ZPK="0B0B0B0B0B0B0B0B1616161616161616"
ACQ_ZPK="4C4C4C4C4C4C4C4C5E5E5E5E5E5E5E5E"
RUN="$(date +%s)"; PASS=0; FAIL=0; STAN=$(( RUN % 800000 + 100000 ))
CMS_USER="${CMS_USER:-operator}"; CMS_PASSWORD="${CMS_PASSWORD:-Dev-Passw0rd!}"
SUP_USER="${SUP_USER:-supervisor}"; SUP_PASSWORD="${SUP_PASSWORD:-Dev-Passw0rd!}"
DEXXIS_KEY="${CMS_DEXXIS_API_KEY:-dev-dexxis-key}"

ok()  { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL  $1  ->  $2"; FAIL=$((FAIL+1)); }
post() { case "$1" in
  /api/dexxis/*) curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -H "X-Api-Key: $DEXXIS_KEY" -d "$2" ;;
  *)             curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -u "$CMS_USER:$CMS_PASSWORD" -d "$2" ;;
esac; }
sup()  { curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -u "$SUP_USER:$SUP_PASSWORD" -d "${2:-{\}}"; }
get()  { curl -s "$BASE$1" -u "$CMS_USER:$CMS_PASSWORD"; }
approve() { local id; id=$(echo "$1" | jq -r '.requestId // empty'); [ -n "$id" ] && sup "/api/admin/approvals/$id/approve" '{"comment":"test"}' >/dev/null; }
expect() { local got; got=$(echo "$2" | jq -r "$3" 2>/dev/null); [ "$got" = "$4" ] && ok "$1" || bad "$1" "expected $4, got $got :: $(echo "$2" | cut -c1-200)"; }
pb() { java "$SIM" pinblock "$1" "$2" "$3"; }
bi() { STAN=$((STAN+1)); R=$(post /api/dev/authorize "{\"type\":\"BALANCE_INQUIRY\",\"channel\":\"ATM\",\"mti\":\"1200\",\"processingCode\":\"310000\",
  \"pan\":\"$1\",\"pinBlock\":\"$(pb "$1" "$2" "$ACQ_ZPK")\",\"amount\":0,\"currencyNumeric\":\"818\",\"stan\":\"$(printf %06d $STAN)\",
  \"transmissionDt\":\"$(date +%m%d%H%M%S)\",\"acquirerId\":\"123456\",\"terminalId\":\"LC${RUN: -6}\"}"); }
new_card() { # customer id -> sets ACCT, PAN, CARD (activated with PIN 1234)
  ACCT=$(post /api/admin/customers/$1/accounts '{"accountTypeCode":"PREPAID","currencyCode":"EGP"}' | jq -r .id)
  PAN=$(post /api/admin/accounts/$ACCT/cards '{"productCode":"P01","branchId":"BR001"}' | jq -r .pan)
  post /api/dexxis/cards/activate "{\"pan\":\"$PAN\",\"pinBlock\":\"$(pb "$PAN" 1234 "$KIOSK_ZPK")\",\"kioskId\":\"K01\"}" >/dev/null
  CARD=$(get "/api/admin/cards?accountId=$ACCT" | jq -r '.items[0].id')
}

echo "== Card lifecycle test against $BASE (run $RUN)"
CUST=$(post /api/admin/customers "{\"customerRef\":\"LC$RUN\",\"segmentCode\":\"MASS\",\"fullName\":\"Life Cycle\",\"embossingName\":\"LIFE CYCLE\"}" | jq -r .id)
new_card "$CUST"; OLD=$CARD; OLD_PAN=$PAN

# ---------- same-PAN replacement (damaged) ----------
R=$(post /api/admin/cards/$OLD/replace '{"reason":"DAMAGED","samePan":true,"branchId":"BR002"}')
expect "L01 damaged card replaced with the same number" "$R" '.status + " " + (.pan == "'"$OLD_PAN"'" | tostring)' "PENDING_PRINT true"
NEW=$(echo "$R" | jq -r .cardId)
R=$(get /api/admin/cards/$NEW); expect "L02 new card has PSN 01 and points to the old one" "$R" '.psn + " " + (.replacesCardId|tostring)' "01 $OLD"
R=$(post /api/admin/cards/$OLD/replace '{"reason":"DAMAGED","samePan":true}'); expect "L03 second replacement while one waits for print refused" "$R" .code DUPLICATE
R=$(post /api/dexxis/cards/search "{\"pan\":\"$OLD_PAN\"}"); expect "L04 Dexxis search finds the card in production (PSN 01)" "$R" .psn 01
bi "$OLD_PAN" 1234; expect "L05 old card still works until the new one is activated" "$R" .actionCode 000
R=$(post /api/dexxis/cards/activate "{\"pan\":\"$OLD_PAN\",\"pinBlock\":\"$(pb "$OLD_PAN" 4321 "$KIOSK_ZPK")\",\"kioskId\":\"K01\"}")
expect "L06 replacement activated with a new PIN" "$R" .result ACTIVE
R=$(get /api/admin/cards/$OLD); expect "L07 old card cancelled on activation" "$R" .status CANCELLED
bi "$OLD_PAN" 4321; expect "L08 same number now authorizes on the new card (new PIN)" "$R" .actionCode 000

# ---------- new-PAN replacement (lost) and PAN reveal ----------
R=$(post /api/admin/cards/$NEW/replace '{"reason":"LOST","samePan":true}'); expect "L09 lost card cannot keep its number" "$R" .code INVALID_REQUEST
R=$(post /api/admin/cards/$NEW/replace '{"reason":"LOST","samePan":false}'); LOSTREP=$(echo "$R" | jq -r .cardId); LOST_PAN=$(echo "$R" | jq -r .pan)
expect "L10 lost card replaced with a new number" "$R" '(.pan != "'"$OLD_PAN"'") | tostring' true
R=$(get /api/admin/cards/$NEW); expect "L11 lost card blocked at once" "$R" .status LOST
bi "$OLD_PAN" 4321; expect "L12 lost card declines" "$R" .actionCode 208
for i in 1 2 3; do R=$(post /api/admin/cards/$LOSTREP/reveal-pan '{}'); done
expect "L13 card number shown for printing (audited)" "$R" .pan "$LOST_PAN"
R=$(post /api/admin/cards/$LOSTREP/reveal-pan '{}'); expect "L14 reveal limited to 3 times" "$R" .code LIMIT_REACHED

# ---------- batch jobs ----------
R=$(post /api/admin/batch/jobs/CARD_EXPIRY/run '{}'); expect "L15 operators cannot run batch jobs" "$R" .code FORBIDDEN
new_card "$CUST"; EXP_CARD=$CARD
post /api/dev/cards/$EXP_CARD/age '{"expiry":"2001"}' >/dev/null
R=$(sup /api/admin/batch/jobs/CARD_EXPIRY/run); expect "L16 CARD_EXPIRY run succeeds" "$R" .status SUCCESS
R=$(get /api/admin/cards/$EXP_CARD); expect "L17 card past expiry is EXPIRED" "$R" .status EXPIRED

new_card "$CUST"; REN_CARD=$CARD
post /api/dev/cards/$REN_CARD/age "{\"expiry\":\"$(date -d '+20 days' +%y%m 2>/dev/null || date -v+20d +%y%m)\"}" >/dev/null
R=$(sup /api/admin/batch/jobs/CARD_RENEWAL/run); expect "L18 CARD_RENEWAL creates renewals" "$R" '.status + " " + ((.items >= 1)|tostring)' "SUCCESS true"
R=$(get /api/admin/cards/$REN_CARD); expect "L19 expiring card has a renewal waiting for print" "$R" '(.replacedByCardId != null)|tostring' true
RENEWAL=$(echo "$R" | jq -r .replacedByCardId)
R=$(get /api/admin/cards/$RENEWAL); expect "L20 renewal keeps the number with the next PSN" "$R" '.status + " " + .psn + " " + (.maskedPan == "'"$(get /api/admin/cards/$REN_CARD | jq -r .maskedPan)"'"|tostring)' "PENDING_PRINT 01 true"
R=$(sup /api/admin/batch/jobs/CARD_RENEWAL/run); expect "L21 renewal is not created twice" "$R" '.message | startswith("0 renewal")' true

post /api/dev/cards/$LOSTREP/age '{"createdDaysAgo":40}' >/dev/null
R=$(sup /api/admin/batch/jobs/STALE_PENDING_PRINT/run); expect "L22 STALE_PENDING_PRINT run" "$R" .status SUCCESS
R=$(get /api/admin/cards/$LOSTREP); expect "L23 card not printed in 30 days is cancelled" "$R" .status CANCELLED

R=$(post /api/admin/accounts/$ACCT/entries '{"type":"FUNDING","amount":20000,"narrative":"hold test"}'); approve "$R"
STAN=$((STAN+1)); R=$(post /api/dev/authorize "{\"type\":\"PREAUTH\",\"channel\":\"POS\",\"mti\":\"1100\",\"processingCode\":\"000000\",\"pan\":\"$(post /api/admin/cards/$RENEWAL/reveal-pan '{}' | jq -r .pan)\",
  \"amount\":5000,\"currencyNumeric\":\"818\",\"stan\":\"$(printf %06d $STAN)\",\"transmissionDt\":\"$(date +%m%d%H%M%S)\",\"acquirerId\":\"123456\",\"terminalId\":\"POS${RUN: -5}\"}")
expect "L24 pre-auth holds 50.00" "$R" .availableBalance 15000
post /api/dev/accounts/$ACCT/expire-holds '{}' >/dev/null
R=$(sup /api/admin/batch/jobs/HOLD_EXPIRY/run); expect "L25 HOLD_EXPIRY releases the expired hold" "$R" '.status + " " + ((.items >= 1)|tostring)' "SUCCESS true"
R=$(get /api/admin/accounts/$ACCT); expect "L26 funds available again" "$R" .availableBalance 20000
R=$(get /api/admin/batch/jobs); expect "L27 job list shows last runs" "$R" '[.[] | select(.lastRun != null)] | length >= 4' true

echo "== Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
