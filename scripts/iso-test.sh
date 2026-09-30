#!/usr/bin/env bash
# =====================================================================
# BASE24 ISO 8583:1993 regression through the real TCP interface (CMS-055).
# The dev corehost simulator (/api/dev/iso/*) builds each message, PIN blocks included,
# sends it to the CMS ISO server and returns the parsed response.
# Needs: CMS with the dev profile, hsm-sim, seed-dev.sql, curl, jq, java 21.
# Usage: ./scripts/iso-test.sh [base-url]
# =====================================================================
set -uo pipefail

BASE="${1:-http://localhost:8080}"
SIM="$(dirname "$0")/../hsm-sim/HsmSimulator.java"
KIOSK_ZPK="0B0B0B0B0B0B0B0B1616161616161616"
RUN="$(date +%s)"; PASS=0; FAIL=0

ok()  { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL  $1  ->  $2"; FAIL=$((FAIL+1)); }
# Credentials (dev profile defaults). Operator makes changes; supervisor approves maker-checker requests.
CMS_USER="${CMS_USER:-operator}"; CMS_PASSWORD="${CMS_PASSWORD:-Dev-Passw0rd!}"
SUP_USER="${SUP_USER:-supervisor}"; SUP_PASSWORD="${SUP_PASSWORD:-Dev-Passw0rd!}"
DEXXIS_KEY="${CMS_DEXXIS_API_KEY:-dev-dexxis-key}"
post() { # Dexxis endpoints authenticate with the API key, everything else as the operator
  case "$1" in
    /api/dexxis/*) curl -s -w "${W:-}" -X POST "$BASE$1" -H 'Content-Type: application/json' -H "X-Api-Key: $DEXXIS_KEY" -d "$2" ;;
    *)             curl -s -w "${W:-}" -X POST "$BASE$1" -H 'Content-Type: application/json' -u "$CMS_USER:$CMS_PASSWORD" -d "$2" ;;
  esac
}
get()  { curl -s -w "${W:-}" "$BASE$1" -u "$CMS_USER:$CMS_PASSWORD"; }
put()  { curl -s -X PUT "$BASE$1" -H 'Content-Type: application/json' -u "$CMS_USER:$CMS_PASSWORD" -d "$2"; }
# approve <response json>: if the response is a pending maker-checker request, approve it as the supervisor
approve() {
  local id; id=$(echo "$1" | jq -r '.requestId // empty' 2>/dev/null)
  [ -z "$id" ] && { echo "$1"; return; }
  curl -s -X POST "$BASE/api/admin/approvals/$id/approve" -H 'Content-Type: application/json' \
       -u "$SUP_USER:$SUP_PASSWORD" -d '{"comment":"approved by test"}'
}
expect() { local got; got=$(echo "$2" | jq -r "$3"); [ "$got" = "$4" ] && ok "$1" || bad "$1" "expected $4, got $got :: $(echo "$2" | jq -c '{mti,actionCode,actionText,availableBalance,message}' 2>/dev/null || echo "$2")"; }
send() { R=$(post /api/dev/iso/send "$1"); }
TERM_ID="T${RUN: -7}"

echo "== BASE24 ISO regression against $BASE (run $RUN)"
R=$(get /api/admin/iso/status);                 expect "I01 ISO server listening" "$R" .running true
R=$(post /api/dev/iso/network '{"function":"801"}'); expect "I02 1804 sign-on -> 1814/800" "$R" '.response["0"] + "/" + .actionCode' "1814/800"
R=$(post /api/dev/iso/network '{"function":"831"}'); expect "I03 echo test" "$R" .actionCode 800

# setup: customer, prepaid account, card with PIN 1234, 500.00 funded
CUST=$(post /api/admin/customers "{\"customerRef\":\"IS$RUN\",\"segmentCode\":\"MASS\",\"fullName\":\"Iso Test\",\"embossingName\":\"ISO TEST\"}" | jq -r .id)
ACCT=$(post /api/admin/customers/$CUST/accounts '{"accountTypeCode":"PREPAID","currencyCode":"EGP"}' | jq -r .id)
PAN=$(post /api/admin/accounts/$ACCT/cards '{"productCode":"P01","branchId":"BR001"}' | jq -r .pan)
post /api/dexxis/cards/activate "{\"pan\":\"$PAN\",\"pinBlock\":\"$(java "$SIM" pinblock "$PAN" 1234 "$KIOSK_ZPK")\",\"kioskId\":\"K01\"}" >/dev/null
CARD=$(get "/api/admin/cards?accountId=$ACCT" | jq -r '.items[0].id')
approve "$(post /api/admin/accounts/$ACCT/entries '{"type":"FUNDING","amount":50000,"narrative":"iso test"}')" >/dev/null

C="\"cardId\":$CARD,\"terminalId\":\"$TERM_ID\""
send "{\"type\":\"BALANCE_INQUIRY\",$C,\"pin\":\"1234\"}"
expect "I04 1200 balance inquiry -> 1210/000, field 54" "$R" '.response["0"] + "/" + .actionCode + " " + (.availableBalance|tostring)' "1210/000 50000"
send "{\"type\":\"WITHDRAWAL\",$C,\"pin\":\"1234\",\"amount\":10000}"; W=$(echo "$R" | jq -r .ref)
expect "I05 cash 100.00 -> 000 with approval code" "$R" '.actionCode + " " + (.response["38"]|length|tostring)' "000 6"
send "{\"type\":\"WITHDRAWAL\",$C,\"repeat\":true,\"originalRef\":\"$W\"}"
expect "I06 1201 repeat answered from stored response, no second debit" "$R" '.response["0"] + " " + (.availableBalance|tostring)' "1210 40000"
send "{\"type\":\"WITHDRAWAL\",$C,\"pin\":\"9999\",\"amount\":1000}"; expect "I07 wrong PIN -> 117" "$R" .actionCode 117
send "{\"type\":\"REVERSAL\",$C,\"originalRef\":\"$W\",\"amountCompleted\":4000}"
expect "I08 1420 partial reversal (40.00 dispensed) -> 1430/400" "$R" '.response["0"] + "/" + .actionCode' "1430/400"
send "{\"type\":\"BALANCE_INQUIRY\",$C,\"pin\":\"1234\"}"; expect "I09 balance after partial reversal" "$R" .availableBalance 46000
send "{\"type\":\"PREAUTH\",\"channel\":\"POS\",$C,\"amount\":20000,\"merchant\":\"SIM HOTEL CAIRO\"}"; P=$(echo "$R" | jq -r .ref)
expect "I10 1100 pre-auth -> 1110/000" "$R" '.response["0"] + "/" + .actionCode' "1110/000"
send "{\"type\":\"COMPLETION\",\"channel\":\"POS\",$C,\"originalRef\":\"$P\",\"amount\":15000}"
expect "I11 1220 completion 150.00 -> 1230/000" "$R" '.response["0"] + "/" + .actionCode' "1230/000"
send "{\"type\":\"PURCHASE\",\"channel\":\"ECOM\",$C,\"amount\":1000}"; expect "I12 e-commerce (field 22 card not present) refused for P01" "$R" .actionCode 119
send "{\"type\":\"PIN_CHANGE\",$C,\"pin\":\"1234\",\"newPin\":\"2468\"}"; expect "I13 PIN change (new PIN in field 125)" "$R" .actionCode 000
send "{\"type\":\"BALANCE_INQUIRY\",$C,\"pin\":\"2468\"}"; expect "I14 new PIN accepted, balance 310.00" "$R" '.actionCode + " " + (.availableBalance|tostring)' "000 31000"

# dynamic key exchange: new acquirer ZPK under ZMK; later PIN blocks use the new key
R=$(post /api/dev/iso/network '{"function":"811"}'); expect "I15 1804/811 key change accepted" "$R" .actionCode 800
send "{\"type\":\"BALANCE_INQUIRY\",$C,\"pin\":\"2468\"}"; expect "I16 PIN verified under the new ZPK" "$R" .actionCode 000
R=$(get "/api/admin/transactions?cardId=$CARD&size=100"); expect "I17 all ISO messages recorded" "$R" '.total >= 10' true
# restore the seeded acquirer ZPK so other test scripts (which use its clear value) keep working
R=$(post /api/dev/iso/network '{"function":"811","zpkClear":"4C4C4C4C4C4C4C4C5E5E5E5E5E5E5E5E"}')
expect "I18 seeded acquirer ZPK restored" "$R" .actionCode 800

echo "== Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
