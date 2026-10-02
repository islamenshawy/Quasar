#!/usr/bin/env bash
# =====================================================================
# CMS fraud and risk rules test (CMS-095): rules with maker-checker, MCC / country / velocity / score /
# block actions, advices never declined, alert queue and case outcomes (false positive with exemption,
# confirmed fraud). Test rules (TEST_*) are scoped to product P01 and switched off at the end.
# Needs: CMS with the dev profile, hsm-sim, seed-dev.sql, curl, jq, java 21.
# Usage: ./scripts/fraud-test.sh [base-url]
# =====================================================================
set -uo pipefail

BASE="${1:-http://localhost:8080}"
SIM="$(dirname "$0")/../hsm-sim/HsmSimulator.java"
KIOSK_ZPK="0B0B0B0B0B0B0B0B1616161616161616"
RUN="$(date +%s)"; T="TEST"   # fixed rule codes, updated in place every run
PASS=0; FAIL=0; STAN=$(( RUN % 900000 + 100000 ))

ok()  { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL  $1  ->  $2"; FAIL=$((FAIL+1)); }
CMS_USER="${CMS_USER:-operator}"; CMS_PASSWORD="${CMS_PASSWORD:-Dev-Passw0rd!}"
SUP_USER="${SUP_USER:-supervisor}"; SUP_PASSWORD="${SUP_PASSWORD:-Dev-Passw0rd!}"
DEXXIS_KEY="${CMS_DEXXIS_API_KEY:-dev-dexxis-key}"
post() {
  case "$1" in
    /api/dexxis/*) curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -H "X-Api-Key: $DEXXIS_KEY" -d "$2" ;;
    *)             curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -u "$CMS_USER:$CMS_PASSWORD" -d "$2" ;;
  esac
}
get()  { curl -s "$BASE$1" -u "$CMS_USER:$CMS_PASSWORD"; }
sput() { curl -s -X PUT "$BASE$1" -H 'Content-Type: application/json' -u "$SUP_USER:$SUP_PASSWORD" -d "$2"; }
spost(){ curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -u "$SUP_USER:$SUP_PASSWORD" -d "$2"; }
# approve <response>: approve as the second supervisor (four eyes: supervisor made the change)
approve() { local id; id=$(echo "$1" | jq -r '.requestId // empty'); [ -z "$id" ] && { echo "$1"; return; }
  curl -s -X POST "$BASE/api/admin/approvals/$id/approve" -H 'Content-Type: application/json' -u "supervisor2:$SUP_PASSWORD" -d '{"comment":"ok"}'; }
pb()   { java "$SIM" pinblock "$1" "$2" "$3"; }
expect() {
  local got; got=$(echo "$2" | jq -r "$3")
  [ "$got" = "$4" ] && ok "$1" || bad "$1" "expected $4, got $got :: $(echo "$2" | jq -c '{actionCode,reason,status,code,message}' 2>/dev/null || echo "$2")"
}
# rule <code> <action> <score> <conditions json> [active]: create (or update) and approve
rule() {
  local body; body=$(jq -nc --arg c "$1" --arg a "$2" --argjson s "$3" --argjson cond "$4" --argjson act "${5:-true}" \
    '{code:$c, name:("Test " + $c), action:$a, score:$s, priority:900, conditions:$cond, active:$act}')
  local r; r=$(spost /api/admin/setup/fraud-rules "$body")
  if [ "$(echo "$r" | jq -r .code)" = DUPLICATE ]; then r=$(sput "/api/admin/setup/fraud-rules/$1" "$body"); fi
  approve "$r"
}
RULES=()
cleanup() {
  for c in "${RULES[@]}"; do rule "$c" ALERT 0 '{}' false > /dev/null; done
  approve "$(sput /api/admin/setup/settings/fraud.decline_score '{"value":"100"}')" > /dev/null
}
trap cleanup EXIT

auth() { # auth <type> <channel> <amount> <extra json>
  STAN=$((STAN + 1)); local s; s=$(printf '%06d' "$STAN"); local dt; dt="$(date +%m%d%H%M%S)"
  local body; body=$(jq -nc --arg t "$1" --arg ch "$2" --argjson amt "$3" --arg pan "$PAN" --arg stan "$s" --arg dt "$dt" \
    --arg run "${RUN: -6}" --argjson extra "${4:-{\}}" \
    '{type:$t, channel:$ch, mti:"1200", processingCode:"000000", pan:$pan, amount:$amt, currencyNumeric:"818",
      stan:$stan, rrn:("R"+$stan), transmissionDt:$dt, localDt:$dt, acquirerId:"123456", terminalId:("FR" + $run),
      merchantType:"5411", advice:false} + $extra')
  R=$(post /api/dev/authorize "$body")
}

echo "== CMS fraud rules test against $BASE (run $RUN)"

# ---------- setup: P01 card, funded 1000.00 ----------
CUST=$(post /api/admin/customers "{\"customerRef\":\"FR$RUN\",\"segmentCode\":\"MASS\",\"fullName\":\"Fraud Test\",\"embossingName\":\"FRAUD TEST\"}" | jq -r .id)
ACCT=$(post /api/admin/customers/$CUST/accounts '{"accountTypeCode":"PREPAID","currencyCode":"EGP"}' | jq -r .id)
PAN=$(post /api/admin/accounts/$ACCT/cards '{"productCode":"P01","branchId":"BR001"}' | jq -r .pan)
R=$(post /api/dexxis/cards/activate "{\"pan\":\"$PAN\",\"pinBlock\":\"$(pb "$PAN" 1234 "$KIOSK_ZPK")\",\"kioskId\":\"K01\"}")
expect "F00 setup: card active" "$R" .result ACTIVE
CARD=$(get "/api/admin/cards?accountId=$ACCT" | jq -r '.items[0].id')
R=$(post /api/admin/accounts/$ACCT/entries '{"type":"FUNDING","amount":100000,"narrative":"fraud test"}')
approve "$R" > /dev/null

R=$(get /api/admin/setup/fraud-rules); expect "F01 seeded starter rules are present and inactive" "$R" '[.[] | select(.code=="GAMBLING_MCC" or .code=="VELOCITY_5_IN_10M") | .active] | join(",")' "false,false"

# ---------- MCC rule, maker-checker ----------
MCC_RULE="{\"code\":\"${T}_MCC\",\"name\":\"Test ${T}_MCC\",\"action\":\"DECLINE\",\"score\":100,\"priority\":900,\"conditions\":{\"products\":[\"P01\"],\"mccIn\":[\"7995\"]},\"active\":true}"
R=$(spost /api/admin/setup/fraud-rules "$MCC_RULE")
[ "$(echo "$R" | jq -r .code)" = DUPLICATE ] && R=$(sput "/api/admin/setup/fraud-rules/${T}_MCC" "$MCC_RULE")
expect "F02 fraud rule change needs approval" "$R" .approvalPending true
R=$(approve "$R"); RULES+=("${T}_MCC"); expect "F03 second supervisor approves; rule active" "$R" .status APPROVED
auth PURCHASE POS 5000 '{"merchantType":"7995"}';  expect "F04 gambling MCC declined 102" "$R" '.actionCode + " " + (.reason|startswith("fraud rules")|tostring)' "102 true"
auth PURCHASE POS 5000 '{"merchantType":"5411"}';  expect "F05 grocery MCC approved" "$R" .actionCode 000

# ---------- advice with a declining rule: scored, never declined ----------
auth PURCHASE POS 1000 '{"merchantType":"7995","mti":"1220","advice":true}'; expect "F06 advice is never declined by fraud rules" "$R" .actionCode 000
R=$(get "/api/admin/fraud/alerts?cardId=$CARD"); expect "F07 advice still raised an alert (ALERT)" "$R" '.items[0].actionTaken' ALERT

# ---------- foreign country: alert only ----------
rule "${T}_FOREIGN" ALERT 30 '{"products":["P01"],"foreign":true}' > /dev/null; RULES+=("${T}_FOREIGN")
auth PURCHASE POS 2000 '{"acquirerCountry":"840"}'; expect "F08 foreign purchase approved with an alert" "$R" .actionCode 000
R=$(get "/api/admin/fraud/alerts?cardId=$CARD"); expect "F09 alert names the rule and score 30" "$R" '.items[0].rules + " " + (.items[0].score|tostring)' "${T}_FOREIGN 30"
BEFORE=$(get "/api/admin/fraud/alerts?cardId=$CARD" | jq -r .total)
auth PURCHASE POS 2000 '{"acquirerCountry":"818"}'
expect "F10 domestic purchase raises no new alert" "$(get "/api/admin/fraud/alerts?cardId=$CARD")" .total "$BEFORE"

# ---------- scores add up: two alerts reach the decline score ----------
rule "${T}_BIG" ALERT 80 '{"products":["P01"],"minAmount":15000}' > /dev/null; RULES+=("${T}_BIG")
auth PURCHASE POS 20000 '{"acquirerCountry":"840"}'; expect "F11 foreign 30 + big 80 >= 100 declines 102" "$R" '.actionCode + " " + .reason' "102 fraud rules ${T}_BIG,${T}_FOREIGN (score 110)"
R=$(sput /api/admin/setup/settings/fraud.decline_score '{"value":"200"}'); approve "$R" > /dev/null
auth PURCHASE POS 20000 '{"acquirerCountry":"840"}'; expect "F12 with decline score 200 the same purchase passes" "$R" .actionCode 000
rule "${T}_BIG" ALERT 0 '{}' false > /dev/null; rule "${T}_FOREIGN" ALERT 0 '{}' false > /dev/null

# ---------- velocity ----------
N=$(get "/api/admin/transactions?cardId=$CARD&size=1" | jq -r .total)   # all within the last minutes
rule "${T}_VEL" DECLINE 80 "{\"products\":[\"P01\"],\"velocityMinutes\":10,\"velocityMaxCount\":$N}" > /dev/null; RULES+=("${T}_VEL")
auth PURCHASE POS 100 '{}'; expect "F13 one more than the velocity limit in 10 minutes declined" "$R" '.actionCode + " " + (.reason|contains("_VEL")|tostring)' "102 true"
rule "${T}_VEL" ALERT 0 '{}' false > /dev/null

# ---------- block the card ----------
rule "${T}_BLOCK" DECLINE_BLOCK 100 '{"products":["P01"],"mccIn":["4829"]}' > /dev/null; RULES+=("${T}_BLOCK")
auth PURCHASE POS 3000 '{"merchantType":"4829"}'; expect "F14 money-transfer MCC declined 102" "$R" .actionCode 102
R=$(get /api/admin/cards/$CARD);       expect "F15 DECLINE_BLOCK blocked the card" "$R" .status BLOCKED

# ---------- fraud desk ----------
R=$(get /api/admin/fraud/alerts/count); expect "F16 open alerts counted for the console" "$R" '(.open > 0)' true
A=$(get "/api/admin/fraud/alerts?cardId=$CARD&status=OPEN" | jq -r '.items[0].id')
R=$(post /api/admin/fraud/alerts/$A/assign '{}');                       expect "F17 operator takes the alert" "$R" .assignedTo "$CMS_USER"
R=$(post /api/admin/fraud/alerts/$A/note '{"note":"called customer"}');  expect "F18 note recorded with name" "$R" '.notes|contains("operator: called customer")' true
R=$(post /api/admin/cards/$CARD/status '{"status":"ACTIVE","reason":"customer confirmed"}'); approve "$R" > /dev/null
R=$(post /api/admin/fraud/alerts/$A/resolve '{"outcome":"FALSE_POSITIVE","note":"genuine","exemptHours":1}'); R=$(approve "$R")
expect "F19 false positive with a 1 h exemption" "$R" .status FALSE_POSITIVE
auth PURCHASE POS 3000 '{"merchantType":"4829"}'; expect "F20 exempt card passes the blocking rule" "$R" .actionCode 000
B=$(get "/api/admin/fraud/alerts?cardId=$CARD&status=OPEN" | jq -r '.items[0].id')
R=$(post /api/admin/fraud/alerts/$B/resolve '{"outcome":"CONFIRMED_FRAUD","note":"skimmed","cardStatus":"LOST"}'); R=$(approve "$R")
R=$(get /api/admin/cards/$CARD);        expect "F21 confirmed fraud marks the card LOST" "$R" .status LOST
R=$(get "/api/admin/fraud/alerts?cardId=$CARD&status=OPEN"); expect "F22 the card's other open alerts close with it" "$R" .total 0
R=$(post /api/admin/fraud/alerts/$B/resolve '{"outcome":"CLOSED"}'); expect "F23 resolved alert cannot be resolved again" "$R" .code INVALID_STATUS

echo "== Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
