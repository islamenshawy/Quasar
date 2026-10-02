#!/usr/bin/env bash
# =====================================================================
# CMS authorization engine test - ATM / POS scenarios end to end.
# Needs: CMS running with the dev profile (POST /api/dev/authorize), hsm-sim,
#        seed-dev.sql loaded, curl, jq, java 21 (PIN blocks via hsm-sim).
# Usage: ./scripts/auth-test.sh [base-url]
# =====================================================================
set -uo pipefail

BASE="${1:-http://localhost:8080}"
SIM="$(dirname "$0")/../hsm-sim/HsmSimulator.java"
KIOSK_ZPK="0B0B0B0B0B0B0B0B1616161616161616"      # ZPK_KIOSK in seed-dev.sql
ACQ_ZPK="4C4C4C4C4C4C4C4C5E5E5E5E5E5E5E5E"        # ZPK_COREHOST in seed-dev.sql
RUN="$(date +%s)"
PASS=0; FAIL=0; STAN=$(( RUN % 900000 + 100000 ))
EGP=818

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
pb()   { java "$SIM" pinblock "$1" "$2" "$3"; }
expect() { # name, response json, jq filter, expected
  local got; got=$(echo "$2" | jq -r "$3")
  [ "$got" = "$4" ] && ok "$1" || bad "$1" "expected $4, got $got :: $(echo "$2" | jq -c '{actionCode,reason,availableBalance}' 2>/dev/null || echo "$2")"
}

# auth <type> <channel> <amount> <pinblock|""> [extra json fields]; sets R (response) and LAST_* (for reversals).
# Runs in the current shell, never in $(...), so the STAN counter advances.
auth() {
  STAN=$((STAN + 1)); local s; s=$(printf '%06d' "$STAN"); local dt; dt="$(date +%m%d%H%M%S)"
  local mti="1200"; [ "$1" = PREAUTH ] && mti="1100"; [ "$1" = REVERSAL ] && mti="1420"
  local pin="null"; [ -n "$4" ] && pin="\"$4\""
  local body; body=$(jq -nc --arg t "$1" --arg ch "$2" --argjson amt "$3" --argjson pin "$pin" --arg pan "$PAN" \
    --arg stan "$s" --arg dt "$dt" --arg mti "$mti" --arg run "${RUN: -6}" --arg ccy "${CCY:-$EGP}" --argjson extra "${5:-{\}}" \
    '{type:$t, channel:$ch, mti:$mti, processingCode:"010000", pan:$pan, pinBlock:$pin, amount:$amt,
      currencyNumeric:$ccy, stan:$stan, rrn:("R"+$stan), transmissionDt:$dt, localDt:$dt, acquirerId:"123456",
      terminalId:("ATM" + $run), advice:false} + $extra')
  LAST_BODY="$body"; LAST_MTI="$mti"; LAST_STAN="$s"; LAST_DT="$dt"
  R=$(post /api/dev/authorize "$body")
}
orig() { echo "{\"original\":{\"mti\":\"$1\",\"stan\":\"$2\",\"transmissionDt\":\"$3\",\"acquirerId\":\"123456\"}}"; }

echo "== CMS authorization test against $BASE (run $RUN)"

# ---------- setup: customer, prepaid account, card P01 activated with PIN 1234, funded 1000.00 ----------
CUST=$(post /api/admin/customers "{\"customerRef\":\"AT$RUN\",\"segmentCode\":\"MASS\",\"fullName\":\"Auth Test\",\"embossingName\":\"AUTH TEST\"}" | jq -r .id)
ACCT=$(post /api/admin/customers/$CUST/accounts '{"accountTypeCode":"PREPAID","currencyCode":"EGP"}' | jq -r .id)
PAN=$(post /api/admin/accounts/$ACCT/cards '{"productCode":"P01","branchId":"BR001"}' | jq -r .pan)
R=$(post /api/dexxis/cards/activate "{\"pan\":\"$PAN\",\"pinBlock\":\"$(pb "$PAN" 1234 "$KIOSK_ZPK")\",\"kioskId\":\"K01\"}")
expect "A00 setup: card active" "$R" .result ACTIVE
CARD=$(get "/api/admin/cards?accountId=$ACCT" | jq -r '.items[0].id')
R=$(post /api/admin/accounts/$ACCT/entries '{"type":"FUNDING","amount":100000,"narrative":"test funding"}')
expect "A01a funding needs approval (maker-checker)" "$R" .approvalPending true
R=$(approve "$R"); expect "A01b supervisor approves" "$R" .status APPROVED
R=$(get /api/admin/accounts/$ACCT); expect "A01c balance after approval 1000.00" "$R" .availableBalance 100000

PIN_OK=$(pb "$PAN" 1234 "$ACQ_ZPK"); PIN_BAD=$(pb "$PAN" 9999 "$ACQ_ZPK")

# ---------- ATM ----------
auth BALANCE_INQUIRY ATM 0 "$PIN_OK";       expect "A02 balance inquiry" "$R" '.actionCode + " " + (.availableBalance|tostring)' "000 100000"
auth BALANCE_INQUIRY ATM 0 "";              expect "A03 ATM without PIN -> 112" "$R" .actionCode 112
auth BALANCE_INQUIRY ATM 0 "$PIN_BAD";      expect "A04 wrong PIN -> 117" "$R" .actionCode 117
auth WITHDRAWAL ATM 20000 "$PIN_OK";        expect "A05 withdraw 200.00" "$R" '.actionCode + " " + (.availableBalance|tostring)' "000 80000"
W1=$(orig "$LAST_MTI" "$LAST_STAN" "$LAST_DT"); W1_TXN=$(echo "$R" | jq .txnId)
R=$(post /api/dev/authorize "$LAST_BODY");       expect "A06 duplicate returns stored response" "$R" .txnId "$W1_TXN"
auth WITHDRAWAL ATM 1500000 "$PIN_OK";      expect "A07 over per-withdrawal limit -> 121" "$R" .actionCode 121
auth WITHDRAWAL ATM 90000 "$PIN_OK";        expect "A08 insufficient funds -> 116" "$R" .actionCode 116
CCY=840; auth WITHDRAWAL ATM 1000 "$PIN_OK"; CCY=$EGP; expect "A09 foreign currency -> 119" "$R" .actionCode 119
auth REVERSAL ATM 20000 "" "$W1";           expect "A10 full reversal -> 400, balance back" "$R" '.actionCode + " " + (.availableBalance|tostring)' "400 100000"
auth REVERSAL ATM 20000 "" "$W1";           expect "A11 repeated reversal changes nothing" "$R" '.actionCode + " " + .reason' "400 already reversed"
auth WITHDRAWAL ATM 30000 "$PIN_OK";        W2=$(orig "$LAST_MTI" "$LAST_STAN" "$LAST_DT")
auth REVERSAL ATM 30000 "" "$(echo "$W2" | jq -c '. + {amountCompleted:10000}')"
expect "A12 partial reversal (dispensed 100.00)" "$R" .availableBalance 90000
auth REVERSAL ATM 5000 "" "$(orig 1200 999999 0101000000)"; expect "A13 unmatched reversal accepted" "$R" .reason "original not found"

# ---------- PIN change ----------
NEW_PIN=$(pb "$PAN" 4321 "$ACQ_ZPK")
auth PIN_CHANGE ATM 0 "$PIN_OK" "{\"newPinBlock\":\"$NEW_PIN\"}"; expect "A14 PIN change" "$R" .actionCode 000
auth BALANCE_INQUIRY ATM 0 "$PIN_OK";        expect "A15 old PIN now wrong -> 117" "$R" .actionCode 117
auth BALANCE_INQUIRY ATM 0 "$NEW_PIN";       expect "A16 new PIN works" "$R" .actionCode 000
PIN_OK="$NEW_PIN"

# ---------- POS ----------
auth PURCHASE POS 5000 "" '{"terminalId":"POS00001","merchantType":"5411","cardAcceptor":"TEST MARKET CAIRO"}'
expect "A17 POS purchase 50.00" "$R" .availableBalance 85000
auth WITHDRAWAL POS 1000 "";                 expect "A18 cash at POS -> 902" "$R" .actionCode 902
auth PREAUTH POS 10000 "" '{"terminalId":"POS00001"}'; P1=$(orig "$LAST_MTI" "$LAST_STAN" "$LAST_DT")
expect "A19 pre-auth 100.00 holds funds" "$R" '(.ledgerBalance|tostring) + " " + (.availableBalance|tostring)' "85000 75000"
auth COMPLETION POS 8000 "" "$(echo "$P1" | jq -c '. + {terminalId:"POS00001"}')"
expect "A20 completion 80.00 releases hold" "$R" '(.ledgerBalance|tostring) + " " + (.availableBalance|tostring)' "77000 77000"
auth REFUND POS 2000 "" '{"terminalId":"POS00001"}'; expect "A21 refund 20.00" "$R" .availableBalance 79000
auth PURCHASE ECOM 1000 "";                  expect "A22 e-commerce off for P01 -> 119" "$R" .actionCode 119

# ---------- controls, status ----------
R=$(approve "$(put /api/admin/cards/$CARD/limits '{"atmEnabled":true,"posEnabled":true,"ecomEnabled":true,"perTxnWdLimit":5000,"reason":"test"}')")
auth WITHDRAWAL ATM 6000 "$PIN_OK";          expect "A23 card-level limit override -> 121" "$R" .actionCode 121
auth PURCHASE ECOM 1000 "";                  expect "A24 card e-com on, product off -> still 119" "$R" .actionCode 119
post /api/admin/cards/$CARD/status '{"status":"BLOCKED","reason":"test"}' >/dev/null
auth BALANCE_INQUIRY ATM 0 "$PIN_OK";        expect "A25 blocked card -> 104" "$R" .actionCode 104
auth WITHDRAWAL ATM 1000 "" '{"advice":true,"mti":"1220"}'; expect "A26 stand-in advice posts anyway" "$R" .actionCode 000
CCY=784; auth WITHDRAWAL ATM 1000 "" '{"advice":true,"mti":"1220"}'; CCY=$EGP
expect "A26b advice in a currency without an FX rate (AED) acknowledged, not posted" "$R" '.actionCode + " " + (.reason|startswith("NOT POSTED")|tostring)' "000 true"
post /api/admin/cards/$CARD/status '{"status":"ACTIVE","reason":"test done"}' >/dev/null
for i in 1 2; do auth BALANCE_INQUIRY ATM 0 "$PIN_BAD" >/dev/null; done
auth BALANCE_INQUIRY ATM 0 "$PIN_BAD";       expect "A27 third wrong PIN blocks -> 106" "$R" .actionCode 106
R=$(get /api/admin/cards/$CARD);                  expect "A28 card is PIN_BLOCKED" "$R" .status PIN_BLOCKED

# ---------- ledger integrity ----------
R=$(get "/api/admin/accounts/$ACCT/statement?size=200")
expect "A29 statement closes at ledger balance" "$R" '.items[0].balanceAfter' "$(get /api/admin/accounts/$ACCT | jq .ledgerBalance)"
R=$(get "/api/admin/transactions?cardId=$CARD&size=200"); expect "A30 transactions recorded" "$R" '.total > 25' true

echo "== Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
