#!/usr/bin/env bash
# =====================================================================
# CMS notifications and OTP test (CMS-105): outbox per event, templates and languages, customer preferences
# and minimum amount, security messages, retries, OTP send / verify / lock / rate limit, redaction.
# Runs against the DEV LOG provider (/api/dev/notifications/sink); the dispatcher is triggered directly.
# Needs: CMS with the dev profile, hsm-sim, seed-dev.sql, curl, jq, java 21.
# Usage: ./scripts/notify-test.sh [base-url]
# =====================================================================
set -uo pipefail

BASE="${1:-http://localhost:8080}"
SIM="$(dirname "$0")/../hsm-sim/HsmSimulator.java"
KIOSK_ZPK="0B0B0B0B0B0B0B0B1616161616161616"
ACQ_ZPK="4C4C4C4C4C4C4C4C5E5E5E5E5E5E5E5E"
RUN="$(date +%s)"
PASS=0; FAIL=0; STAN=$(( RUN % 900000 + 100000 ))
MOBILE="+2010${RUN: -8}"

ok()  { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL  $1  ->  $2"; FAIL=$((FAIL+1)); }
CMS_USER="${CMS_USER:-operator}"; CMS_PASSWORD="${CMS_PASSWORD:-Dev-Passw0rd!}"
SUP_PASSWORD="${SUP_PASSWORD:-Dev-Passw0rd!}"
DEXXIS_KEY="${CMS_DEXXIS_API_KEY:-dev-dexxis-key}"
CHANNEL_KEY="${CMS_CHANNEL_API_KEY:-dev-channel-key}"
post() {
  case "$1" in
    /api/dexxis/*)  curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -H "X-Api-Key: $DEXXIS_KEY" -d "$2" ;;
    /api/channel/*) curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -H "X-Api-Key: $CHANNEL_KEY" -d "$2" ;;
    *)              curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -u "$CMS_USER:$CMS_PASSWORD" -d "$2" ;;
  esac
}
get()  { curl -s "$BASE$1" -u "$CMS_USER:$CMS_PASSWORD"; }
put()  { curl -s -X PUT "$BASE$1" -H 'Content-Type: application/json' -u "$CMS_USER:$CMS_PASSWORD" -d "$2"; }
approve() { local id; id=$(echo "$1" | jq -r '.requestId // empty'); [ -z "$id" ] && { echo "$1"; return; }
  curl -s -X POST "$BASE/api/admin/approvals/$id/approve" -H 'Content-Type: application/json' -u "supervisor:$SUP_PASSWORD" -d '{"comment":"ok"}'; }
pb()   { java "$SIM" pinblock "$1" "$2" "$3"; }
expect() {
  local got; got=$(echo "$2" | jq -r "$3")
  [ "$got" = "$4" ] && ok "$1" || bad "$1" "expected $4, got $got :: $(echo "$2" | jq -c '.' 2>/dev/null | cut -c1-300)"
}
dispatch() { post /api/dev/notifications/dispatch '{}' > /dev/null; }
mine()     { get "/api/admin/notifications?customerId=$CUST&size=50"; }          # newest first
sink()     { get /api/dev/notifications/sink | jq --arg m "$MOBILE" '[.[] | select(.to == $m)]'; }
auth() {
  STAN=$((STAN + 1)); local s; s=$(printf '%06d' "$STAN"); local dt; dt="$(date +%m%d%H%M%S)"
  local pc="010000"; [ "$1" = PURCHASE ] && pc="000000"
  local pin="null"; [ -n "$4" ] && pin="\"$4\""
  R=$(post /api/dev/authorize "$(jq -nc --arg t "$1" --arg ch "$2" --argjson amt "$3" --argjson pin "$pin" --arg pan "$PAN" --arg pc "$pc" \
    --arg stan "$s" --arg dt "$dt" --arg run "${RUN: -6}" \
    '{type:$t, channel:$ch, mti:"1200", processingCode:$pc, pan:$pan, pinBlock:$pin, amount:$amt, currencyNumeric:"818", stan:$stan,
      rrn:("R"+$stan), transmissionDt:$dt, localDt:$dt, acquirerId:"123456", terminalId:("NT" + $run), cardAcceptor:"CITY STARS", advice:false}')")
}
trap 'put /api/dev/notifications/sink "{\"fail\":false}" > /dev/null' EXIT

echo "== CMS notifications test against $BASE (run $RUN)"

R=$(get /api/admin/setup/notification-templates); expect "N00 starting templates in English and Arabic" "$R" '[.[] | select(.event=="TXN_APPROVED" and .channel=="SMS") | .language] | sort | join(",")' "AR,EN"

# ---------- customer with a mobile, card issued and activated ----------
CUST=$(post /api/admin/customers "{\"customerRef\":\"NT$RUN\",\"segmentCode\":\"MASS\",\"fullName\":\"Nadia Test\",\"embossingName\":\"NADIA TEST\",\"mobile\":\"$MOBILE\",\"email\":\"nadia$RUN@example.com\"}" | jq -r .id)
ACCT=$(post /api/admin/customers/$CUST/accounts '{"accountTypeCode":"PREPAID","currencyCode":"EGP"}' | jq -r .id)
approve "$(post /api/admin/accounts/$ACCT/entries '{"type":"FUNDING","amount":100000,"narrative":"notify test"}')" > /dev/null
R=$(post /api/admin/accounts/$ACCT/cards '{"productCode":"P01","branchId":"BR001"}'); PAN=$(echo "$R" | jq -r .pan); CARD=$(echo "$R" | jq -r .cardId)
expect "N01 card issued: CARD_ISSUED queued for the customer's mobile" "$(mine)" '.items[0].event + " " + .items[0].channel' "CARD_ISSUED SMS"
dispatch
expect "N02 dispatcher sends it (LOG provider)" "$(mine)" '.items[0].status' SENT
expect "N03 text names the customer and shows only the last 4 digits" "$(sink)" '.[0].text | (contains("Nadia") and contains("****" + "'"${PAN: -4}"'") and (contains("'"$PAN"'") | not))' true
post /api/dexxis/cards/activate "{\"pan\":\"$PAN\",\"pinBlock\":\"$(pb "$PAN" 1234 "$KIOSK_ZPK")\",\"kioskId\":\"K01\"}" > /dev/null
expect "N04 activation: CARD_ACTIVATED" "$(mine)" '.items[0].event' CARD_ACTIVATED
PIN=$(pb "$PAN" 1234 "$ACQ_ZPK"); BADPIN=$(pb "$PAN" 9999 "$ACQ_ZPK")

# ---------- transaction alerts ----------
auth WITHDRAWAL ATM 20000 "$PIN"; dispatch
expect "N05 withdrawal 200.00: TXN_APPROVED with amount and balance" "$(sink)" '.[0].text | contains("200.00 EGP") and contains("800.00 EGP")' true
auth WITHDRAWAL ATM 200000 "$PIN"
expect "N06 insufficient funds: TXN_DECLINED with the reason" "$(mine)" '.items[0].event + " " + (.items[0].body | contains("Not sufficient funds") | tostring)' "TXN_DECLINED true"
R=$(put /api/admin/customers/$CUST/notification-settings '{"notifySms":true,"notifyEmail":true,"language":"AR","alertThreshold":50000}')
expect "N07 preferences: SMS + e-mail, Arabic, alerts from 500.00" "$R" '"\(.notifySms) \(.notifyEmail) \(.language) \(.alertThreshold)"' "true true AR 50000"
BEFORE=$(mine | jq -r .total)
auth PURCHASE POS 10000 ""
expect "N08 purchase 100.00 below the 500.00 minimum: no message" "$(mine)" .total "$BEFORE"
auth PURCHASE POS 60000 ""
expect "N09 purchase 600.00: SMS in Arabic and an e-mail" "$(mine)" '([.items[0:2][] | .channel] | sort | join(",")) + " " + ([.items[] | select(.channel=="SMS")][0].body | contains("بطاقة") | tostring)' "EMAIL,SMS true"
put /api/admin/customers/$CUST/notification-settings '{"notifySms":false,"notifyEmail":false,"language":"EN","alertThreshold":0}' > /dev/null
BEFORE=$(mine | jq -r .total)
auth PURCHASE POS 1000 ""
expect "N10 alerts switched off: no transaction message" "$(mine)" .total "$BEFORE"

# ---------- security messages ignore the switches ----------
auth WITHDRAWAL ATM 1000 "$BADPIN"; auth WITHDRAWAL ATM 1000 "$BADPIN"; auth WITHDRAWAL ATM 1000 "$BADPIN"
expect "N11 PIN blocked after wrong PINs: CARD_STATUS sent even with alerts off" "$(mine)" '.items[0].event + " " + (.items[0].body | contains("PIN blocked") | tostring)' "CARD_STATUS true"
R=$(post /api/admin/cards/$CARD/status '{"status":"ACTIVE","reason":"customer verified"}'); approve "$R" > /dev/null
expect "N12 operator unblocks: CARD_STATUS active" "$(mine)" '.items[0].body | contains("active")' true

# ---------- retries ----------
put /api/dev/notifications/sink '{"fail":true}' > /dev/null
post /api/admin/cards/$CARD/status '{"status":"BLOCKED","reason":"test"}' > /dev/null; dispatch
expect "N13 gateway failing: message stays pending, attempt and error recorded" "$(mine)" '.items[0].status + " " + (.items[0].attempts|tostring) + " " + (.items[0].lastError != null | tostring)' "PENDING 1 true"
put /api/dev/notifications/sink '{"fail":false}' > /dev/null
ID=$(mine | jq -r '.items[0].id'); post /api/admin/notifications/$ID/resend '{}' > /dev/null; dispatch
expect "N14 resend after the gateway recovers: SENT" "$(mine)" '.items[0].status' SENT
approve "$(post /api/admin/cards/$CARD/status '{"status":"ACTIVE","reason":"test done"}')" > /dev/null

# ---------- one-time passwords (channel API) ----------
R=$(post /api/channel/otp/send "{\"pan\":\"$PAN\",\"purpose\":\"ECOM_3DS\"}"); OTP=$(echo "$R" | jq -r .otpId)
expect "N15 OTP sent to the masked mobile" "$R" '.destination' "***${MOBILE: -4}"
dispatch; CODE=$(sink | jq -r '[.[] | select(.text | contains("one-time password"))][0].text | match("[0-9]{6}").string')
R=$(post /api/channel/otp/verify "{\"otpId\":\"$OTP\",\"code\":\"000000\"}"); expect "N16 wrong code: not verified, 2 tries left" "$R" '"\(.verified) \(.attemptsLeft)"' "false 2"
R=$(post /api/channel/otp/verify "{\"otpId\":\"$OTP\",\"code\":\"$CODE\"}");  expect "N17 right code verifies" "$R" '"\(.verified) \(.status)"' "true VERIFIED"
R=$(post /api/channel/otp/verify "{\"otpId\":\"$OTP\",\"code\":\"$CODE\"}");  expect "N18 a code works only once" "$R" '.verified' false
R=$(mine); expect "N19 OTP text is redacted in the console" "$R" '[.items[] | select(.event=="OTP")][0].body | contains("******") and (contains("'"$CODE"'") | not)' true
R=$(post /api/channel/otp/send "{\"pan\":\"$PAN\"}"); O2=$(echo "$R" | jq -r .otpId)
for c in 111111 222222 333333; do R=$(post /api/channel/otp/verify "{\"otpId\":\"$O2\",\"code\":\"$c\"}"); done
expect "N20 three wrong codes lock it" "$R" .status LOCKED
R=$(post /api/channel/otp/send "{\"pan\":\"$PAN\"}"); R=$(post /api/channel/otp/send "{\"pan\":\"$PAN\"}")
expect "N21 more than 3 codes in 10 minutes refused" "$R" .code LIMIT_REACHED
R=$(curl -s -X POST "$BASE/api/channel/otp/send" -H 'Content-Type: application/json' -H 'X-Api-Key: wrong' -d "{\"pan\":\"$PAN\"}" -o /dev/null -w '%{http_code}')
expect "N22 channel API refuses a wrong key" "\"$R\"" . 401

# ---------- templates under maker-checker ----------
R=$(curl -s -X PUT "$BASE/api/admin/setup/notification-templates/CARD_ACTIVATED.SMS.EN" -H 'Content-Type: application/json' -u "supervisor2:$SUP_PASSWORD" \
  -d '{"body":"Card {{pan}} is active. Welcome, {{name}}!","active":true}')
expect "N23 template change needs approval" "$R" .approvalPending true
R=$(approve "$R"); expect "N24 approved by a second supervisor" "$R" .status APPROVED
R=$(curl -s -X PUT "$BASE/api/admin/setup/notification-templates/OTP.SMS.EN" -H 'Content-Type: application/json' -u "supervisor2:$SUP_PASSWORD" \
  -d '{"body":"Your code","active":true}')
expect "N25 an OTP text without {{code}} is refused" "$R" .code INVALID_REQUEST

echo "== Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
