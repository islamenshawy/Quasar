#!/usr/bin/env bash
# =====================================================================
# CMS core banking funds interface test (CMS-090) - card on a CORE_BANKING account, end to end
# against the dev core banking simulator: balance, debit, hold / capture, refund, reversal,
# core declines, outage with stand-in (STIP), store-and-forward replay, retry and cancel.
# Needs: CMS with the dev profile, hsm-sim, seed-dev.sql (CORE_CURRENT, P02), curl, jq, java 21.
# Usage: ./scripts/core-test.sh [base-url]
# =====================================================================
set -uo pipefail

BASE="${1:-http://localhost:8080}"
SIM="$(dirname "$0")/../hsm-sim/HsmSimulator.java"
KIOSK_ZPK="0B0B0B0B0B0B0B0B1616161616161616"
ACQ_ZPK="4C4C4C4C4C4C4C4C5E5E5E5E5E5E5E5E"
RUN="$(date +%s)"
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
put()  { curl -s -X PUT "$BASE$1" -H 'Content-Type: application/json' -u "$CMS_USER:$CMS_PASSWORD" -d "$2"; }
sup()  { curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -u "$SUP_USER:$SUP_PASSWORD" -d "${2:-{\}}"; }
approve() { local id; id=$(echo "$1" | jq -r '.requestId // empty'); [ -z "$id" ] && { echo "$1"; return; }; sup "/api/admin/approvals/$id/approve" '{"comment":"approved by test"}'; }
pb()   { java "$SIM" pinblock "$1" "$2" "$3"; }
expect() {
  local got; got=$(echo "$2" | jq -r "$3")
  [ "$got" = "$4" ] && ok "$1" || bad "$1" "expected $4, got $got :: $(echo "$2" | jq -c '{actionCode,reason,availableBalance}' 2>/dev/null || echo "$2")"
}
core()   { put "/api/dev/core-sim/accounts/$CORE_REF" "$1" > /dev/null; }
outage() { put "/api/dev/core-sim/state" "{\"down\":$1}" > /dev/null; }
coreBal(){ get /api/dev/core-sim/accounts | jq -r --arg r "$CORE_REF" '.[] | select(.ref == $r) | .ledgerBalance'; }
queue()  { get "/api/admin/core-banking/saf?accountId=$ACCT&size=50"; }
replay() { sup /api/admin/batch/jobs/CORE_SAF_REPLAY/run > /dev/null; }
trap 'outage false' EXIT

auth() {
  STAN=$((STAN + 1)); local s; s=$(printf '%06d' "$STAN"); local dt; dt="$(date +%m%d%H%M%S)"
  local mti="1200"; [ "$1" = PREAUTH ] && mti="1100"; [ "$1" = REVERSAL ] && mti="1420"
  local pc="010000"; [ "$1" = BALANCE_INQUIRY ] && pc="310000"; [ "$1" = PURCHASE ] || [ "$1" = PREAUTH ] || [ "$1" = COMPLETION ] && pc="000000"; [ "$1" = REFUND ] && pc="200000"
  local pin="null"; [ -n "$4" ] && pin="\"$4\""
  local body; body=$(jq -nc --arg t "$1" --arg ch "$2" --argjson amt "$3" --argjson pin "$pin" --arg pan "$PAN" --arg pc "$pc" \
    --arg stan "$s" --arg dt "$dt" --arg mti "$mti" --arg run "${RUN: -6}" --argjson extra "${5:-{\}}" \
    '{type:$t, channel:$ch, mti:$mti, processingCode:$pc, pan:$pan, pinBlock:$pin, amount:$amt,
      currencyNumeric:"818", stan:$stan, rrn:("R"+$stan), transmissionDt:$dt, localDt:$dt, acquirerId:"123456",
      terminalId:("CB" + $run), advice:false} + $extra')
  LAST_MTI="$mti"; LAST_STAN="$s"; LAST_DT="$dt"
  R=$(post /api/dev/authorize "$body")
}
orig() { echo "{\"original\":{\"mti\":\"$LAST_MTI\",\"stan\":\"$LAST_STAN\",\"transmissionDt\":\"$LAST_DT\",\"acquirerId\":\"123456\"}}"; }

echo "== CMS core banking test against $BASE (run $RUN)"

R=$(get /api/admin/core-banking/status); expect "C00 core banking connected (simulator)" "$R" '.configured and .up' true

# ---------- setup: PREMIUM customer, CORE_CURRENT account (number from core), P02 card, core balance 1000.00 ----------
CORE_REF="CB$RUN"
CUST=$(post /api/admin/customers "{\"customerRef\":\"CB$RUN\",\"segmentCode\":\"PREMIUM\",\"fullName\":\"Core Test\",\"embossingName\":\"CORE TEST\"}" | jq -r .id)
ACCT=$(post /api/admin/customers/$CUST/accounts "{\"accountTypeCode\":\"CORE_CURRENT\",\"currencyCode\":\"EGP\",\"accountNumber\":\"$CORE_REF\"}" | jq -r .id)
PAN=$(post /api/admin/accounts/$ACCT/cards '{"productCode":"P02","branchId":"BR001"}' | jq -r .pan)
R=$(post /api/dexxis/cards/activate "{\"pan\":\"$PAN\",\"pinBlock\":\"$(pb "$PAN" 1234 "$KIOSK_ZPK")\",\"kioskId\":\"K01\"}")
expect "C01 setup: P02 card on a core account is active" "$R" .result ACTIVE
core '{"balance":100000,"currency":"EGP"}'
PIN=$(pb "$PAN" 1234 "$ACQ_ZPK")

# ---------- online with core ----------
auth BALANCE_INQUIRY ATM 0 "$PIN";   expect "C02 balance inquiry answered by core" "$R" '.actionCode + " " + (.availableBalance|tostring)' "000 100000"
auth WITHDRAWAL ATM 20000 "$PIN";    expect "C03 withdrawal 200.00 debited in core" "$R" '.actionCode + " " + (.availableBalance|tostring)' "000 80000"
W1=$(orig)
auth WITHDRAWAL ATM 90000 "$PIN";    expect "C04 core says insufficient funds -> 116" "$R" .actionCode 116
auth REVERSAL ATM 20000 "" "$W1";    expect "C05 reversal goes back to core" "$(echo "$R" | jq -c --arg b "$(coreBal)" '. + {core:$b}')" '.actionCode + " " + .core' "400 100000"
auth PREAUTH POS 30000 "";           expect "C06 pre-auth 300.00 holds funds in core" "$R" '.actionCode + " " + (.availableBalance|tostring)' "000 70000"
P1=$(orig)
auth COMPLETION POS 25000 "" "$P1";  expect "C07 completion 250.00 captures the core hold" "$R" '.actionCode + " " + (.availableBalance|tostring)' "000 75000"
auth REFUND POS 5000 "";             expect "C08 refund 50.00 credited in core" "$R" '.actionCode + " " + (.ledgerBalance|tostring)' "000 80000"
core '{"status":"BLOCKED"}'
auth WITHDRAWAL ATM 1000 "$PIN";     expect "C09 account blocked in core -> 119" "$R" .actionCode 119
core '{"status":"ACTIVE"}'
R=$(get /api/admin/accounts/$ACCT/core-balance); expect "C10 live core balance for the account page" "$R" '.status + " " + (.availableBalance|tostring)' "APPROVED 80000"

# ---------- core down: stand-in and store-and-forward ----------
outage true
auth BALANCE_INQUIRY ATM 0 "$PIN";   expect "C11 core down: balance inquiry -> 911" "$R" .actionCode 911
auth WITHDRAWAL ATM 10000 "$PIN";    expect "C12 core down: 100.00 within stand-in limit approved" "$R" '.actionCode + " " + .reason' "000 stand-in: core banking unavailable"
S1=$(orig)
auth WITHDRAWAL ATM 60000 "$PIN";    expect "C13 core down: 600.00 over the 500.00 stand-in limit -> 911" "$R" .actionCode 911
auth REVERSAL ATM 10000 "" "$S1";    expect "C14 core down: reversal accepted" "$R" .actionCode 400
R=$(queue); expect "C15 debit and its reversal wait in the queue" "$R" '[.items[] | select(.status=="PENDING") | .operation] | sort | join(",")' "DEBIT,REVERSAL"
replay
R=$(queue); expect "C16 core still down: debit retried later, its reversal waits behind it" "$R" '[.items[] | select(.status=="PENDING")] | sort_by(.id) | map(.operation + ":" + (.attempts|tostring)) | join(",")' "DEBIT:1,REVERSAL:0"
outage false
for id in $(echo "$R" | jq -r '.items[] | select(.status=="PENDING") | .id'); do post /api/admin/core-banking/saf/$id/retry '{}' > /dev/null; done
replay
R=$(queue); expect "C17 core back: replay sends both, in order" "$R" '[.items[] | select(.status=="SENT")] | length' 2
expect "C18 core balance after debit + reversal is unchanged" "$(coreBal)" . 80000

# ---------- cancel a queued posting (maker-checker) ----------
outage true
auth PURCHASE POS 4000 "";           expect "C19 core down: purchase 40.00 in stand-in" "$R" .actionCode 000
SAF=$(queue | jq -r '[.items[] | select(.status=="PENDING")][0].id')
R=$(post /api/admin/core-banking/saf/$SAF/cancel '{"reason":"settled manually"}'); expect "C20 cancelling a queued posting needs approval" "$R" .approvalPending true
R=$(approve "$R");                   expect "C21 supervisor approves the cancel" "$R" .status APPROVED
outage false; replay
R=$(queue); expect "C22 cancelled posting never reaches core" "$R" "[.items[] | select(.id==$SAF)][0].status + \" \" + \"$(coreBal)\"" "CANCELLED 80000"

R=$(get /api/admin/core-banking/status); expect "C23 status: nothing pending" "$R" .queue.PENDING 0
R=$(post /api/admin/accounts/$ACCT/entries '{"type":"FUNDING","amount":1000,"narrative":"should be refused"}'); R=$(approve "$R")
expect "C24 manual ledger entries refused on a core account" "$R" '.code // .error // .status' INVALID_REQUEST

echo "== Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
