#!/usr/bin/env bash
# =====================================================================
# CMS fees and FX test (CMS-100): fee plan with maker-checker, issuance fee, ATM fees with free uses,
# international purchase fee, foreign-currency purchase with FX markup, fee reversal, missing FX rate,
# monthly / annual fees (FEE_PERIODIC, once per period), replacement fee.
# Uses its own plan TEST_FEES and product PFEE (BIN 999998), created or updated on each run.
# Needs: CMS with the dev profile, hsm-sim, seed-dev.sql, curl, jq, java 21.
# Usage: ./scripts/fees-test.sh [base-url]
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
SUP_PASSWORD="${SUP_PASSWORD:-Dev-Passw0rd!}"
DEXXIS_KEY="${CMS_DEXXIS_API_KEY:-dev-dexxis-key}"
post() {
  case "$1" in
    /api/dexxis/*) curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -H "X-Api-Key: $DEXXIS_KEY" -d "$2" ;;
    *)             curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -u "$CMS_USER:$CMS_PASSWORD" -d "$2" ;;
  esac
}
get()  { curl -s "$BASE$1" -u "$CMS_USER:$CMS_PASSWORD"; }
sup()  { curl -s -X "$1" "$BASE$2" -H 'Content-Type: application/json' -u "supervisor:$SUP_PASSWORD" -d "$3"; }
approve() { local id; id=$(echo "$1" | jq -r '.requestId // empty'); [ -z "$id" ] && { echo "$1"; return; }
  curl -s -X POST "$BASE/api/admin/approvals/$id/approve" -H 'Content-Type: application/json' -u "supervisor2:$SUP_PASSWORD" -d '{"comment":"ok"}'; }
# upsert <collection path> <code> <json>: POST, or PUT when it exists; approved by a second supervisor
upsert() { local r; r=$(sup POST "$1" "$3"); [ "$(echo "$r" | jq -r .code)" = DUPLICATE ] && r=$(sup PUT "$1/$2" "$3"); approve "$r"; }
pb()   { java "$SIM" pinblock "$1" "$2" "$3"; }
expect() {
  local got; got=$(echo "$2" | jq -r "$3")
  [ "$got" = "$4" ] && ok "$1" || bad "$1" "expected $4, got $got :: $(echo "$2" | jq -c '{actionCode,reason,code,message}' 2>/dev/null || echo "$2")"
}
avail() { get /api/admin/accounts/$ACCT | jq -r .availableBalance; }
auth() { # auth <type> <channel> <amount> <pin> [extra json]
  STAN=$((STAN + 1)); local s; s=$(printf '%06d' "$STAN"); local dt; dt="$(date +%m%d%H%M%S)"
  local mti="1200"; [ "$1" = REVERSAL ] && mti="1420"
  local pc="000000"; [ "$1" = WITHDRAWAL ] && pc="010000"; [ "$1" = BALANCE_INQUIRY ] && pc="310000"
  local pin="null"; [ -n "$4" ] && pin="\"$4\""
  local body; body=$(jq -nc --arg t "$1" --arg ch "$2" --argjson amt "$3" --argjson pin "$pin" --arg pan "$PAN" --arg pc "$pc" \
    --arg stan "$s" --arg dt "$dt" --arg mti "$mti" --arg run "${RUN: -6}" --argjson extra "${5:-{\}}" \
    '{type:$t, channel:$ch, mti:$mti, processingCode:$pc, pan:$pan, pinBlock:$pin, amount:$amt, currencyNumeric:"818",
      stan:$stan, rrn:("R"+$stan), transmissionDt:$dt, localDt:$dt, acquirerId:"123456", terminalId:("FE" + $run), advice:false} + $extra')
  LAST_MTI="$mti"; LAST_STAN="$s"; LAST_DT="$dt"
  R=$(post /api/dev/authorize "$body")
}
orig() { echo "{\"original\":{\"mti\":\"$LAST_MTI\",\"stan\":\"$LAST_STAN\",\"transmissionDt\":\"$LAST_DT\",\"acquirerId\":\"123456\"}}"; }
periodic() { sup POST /api/admin/batch/jobs/FEE_PERIODIC/run '{}' > /dev/null; }

echo "== CMS fees and FX test against $BASE (run $RUN)"

# ---------- plan, FX rate, product (maker-checker) ----------
PLAN='{"code":"TEST_FEES","name":"Fee test plan","active":true,"rules":[
  {"event":"ISSUANCE","region":"ANY","fixedAmount":2000,"percent":0,"freePerMonth":0},
  {"event":"REPLACEMENT","region":"ANY","fixedAmount":1500,"percent":0,"freePerMonth":0},
  {"event":"ATM_WITHDRAWAL","region":"ANY","fixedAmount":500,"percent":0,"freePerMonth":1},
  {"event":"ATM_BALANCE_INQUIRY","region":"ANY","fixedAmount":100,"percent":0,"freePerMonth":0},
  {"event":"POS_PURCHASE","region":"INTERNATIONAL","fixedAmount":0,"percent":1,"minAmount":50,"maxAmount":1000,"freePerMonth":0},
  {"event":"FX_MARKUP","region":"ANY","fixedAmount":0,"percent":3,"freePerMonth":0},
  {"event":"MONTHLY","region":"ANY","fixedAmount":300,"percent":0,"freePerMonth":0},
  {"event":"ANNUAL","region":"ANY","fixedAmount":10000,"percent":0,"freePerMonth":0}]}'
R=$(sup POST /api/admin/setup/fee-plans "$PLAN"); [ "$(echo "$R" | jq -r .code)" = DUPLICATE ] && R=$(sup PUT /api/admin/setup/fee-plans/TEST_FEES "$PLAN")
expect "E00 fee plan change needs approval" "$R" .approvalPending true
R=$(approve "$R"); expect "E01 second supervisor approves the plan" "$R" .status APPROVED
R=$(approve "$(sup PUT /api/admin/setup/fx-rates/USD-EGP '{"rate":48.5}')"); expect "E02 FX rate USD/EGP 48.5 approved" "$R" .status APPROVED
PRODUCT='{"code":"PFEE","name":"Fee Test EGP","cardType":"PREPAID","cardTier":"CLASSIC","scheme":"MEEZA","currencyCode":"EGP",
  "bin":"999998","panLength":16,"rangeStart":1,"rangeEnd":99999999,"serviceCode":"221","validityMonths":36,"chipProfile":"TEST_PROFILE",
  "pvki":"1","pvkKeyName":"PVK_P01","cvkKeyName":"CVK_P01","pinTryLimit":3,"dailyWdCount":10,"dailyWdAmount":2000000,"perTxnWdMax":1000000,
  "maxCardsPerAccount":5,"active":true,"usage":{"atmEnabled":true,"posEnabled":true,"ecomEnabled":true,"dailyPosCount":50,
  "dailyPosAmount":5000000,"perTxnPosMax":2000000,"wdFee":0,"biFee":0,"verifyCvv":false,"preauthHoldDays":7,"coreStipLimit":0,
  "feePlanCode":"TEST_FEES","fxAllowed":true}}'
upsert /api/admin/setup/products PFEE "$PRODUCT" > /dev/null
R=$(get /api/admin/setup/products/PFEE); expect "E03 product PFEE on plan TEST_FEES, foreign currency allowed" "$R" '.usage.feePlanCode + " " + (.usage.fxAllowed|tostring)' "TEST_FEES true"
approve "$(sup PUT /api/admin/setup/products/PFEE/eligibility '[{"accountTypeCode":"SAVINGS","segmentCode":"MASS"}]')" > /dev/null

# ---------- card with an issuance fee ----------
CUST=$(post /api/admin/customers "{\"customerRef\":\"FE$RUN\",\"segmentCode\":\"MASS\",\"fullName\":\"Fee Test\",\"embossingName\":\"FEE TEST\"}" | jq -r .id)
ACCT=$(post /api/admin/customers/$CUST/accounts '{"accountTypeCode":"SAVINGS","currencyCode":"EGP"}' | jq -r .id)
approve "$(post /api/admin/accounts/$ACCT/entries '{"type":"FUNDING","amount":100000,"narrative":"fee test"}')" > /dev/null
R=$(post /api/admin/accounts/$ACCT/cards '{"productCode":"PFEE","branchId":"BR001"}'); PAN=$(echo "$R" | jq -r .pan); CARD=$(echo "$R" | jq -r .cardId)
expect "E04 issuance fee 20.00 charged when the card is issued" "$(avail)" . 98000
post /api/dexxis/cards/activate "{\"pan\":\"$PAN\",\"pinBlock\":\"$(pb "$PAN" 1234 "$KIOSK_ZPK")\",\"kioskId\":\"K01\"}" > /dev/null
PIN=$(pb "$PAN" 1234 "$ACQ_ZPK")

# ---------- ATM ----------
auth BALANCE_INQUIRY ATM 0 "$PIN";  expect "E05 balance inquiry fee 1.00" "$R" '.actionCode + " " + (.availableBalance|tostring)' "000 97900"
auth WITHDRAWAL ATM 10000 "$PIN";   expect "E06 first withdrawal of the month is free" "$R" '.availableBalance' 87900
auth WITHDRAWAL ATM 10000 "$PIN";   expect "E07 second withdrawal pays 5.00" "$R" '.availableBalance' 77400
auth REVERSAL ATM 10000 "" "$(orig)"; expect "E08 reversal gives the fee back too" "$R" '.actionCode + " " + (.availableBalance|tostring)' "400 87900"

# ---------- purchases ----------
auth PURCHASE POS 5000 "";          expect "E09 domestic purchase: no fee" "$R" '.availableBalance' 82900
auth PURCHASE POS 10000 "" '{"acquirerCountry":"840"}'; expect "E10 international purchase: 1% fee (1.00)" "$R" '.availableBalance' 72800
auth PURCHASE POS 1000 "" '{"acquirerCountry":"840","currencyNumeric":"840"}'
expect "E11 USD 10.00 = EGP 485.00 + FX 3% 14.55 + intl 1% 4.85" "$R" '.actionCode + " " + (.availableBalance|tostring)' "000 22360"
TX=$(echo "$R" | jq -r .txnId); USD=$(orig)
R=$(get /api/admin/transactions/$TX); expect "E12 transaction keeps billing amount, rate and FX fee" "$R" '"\(.billingAmount) \(.billingCurrency) \(.fxRate) \(.fxFee) \(.feeAmount)"' "48500 EGP 48.5 1455 1940"
auth REVERSAL POS 1000 "" "$USD";   expect "E13 reversal of the USD purchase returns amount and both fees" "$R" '.availableBalance' 72800
auth PURCHASE POS 1000 "" '{"currencyNumeric":"784"}'; expect "E14 AED without an FX rate -> 119" "$R" '.actionCode + " " + .reason' "119 no FX rate AED/EGP"

# ---------- periodic and event fees ----------
periodic; expect "E15 monthly fee waits until the card is a day old" "$(avail)" . 72800
post /api/dev/cards/$CARD/age '{"activatedDaysAgo":2}' > /dev/null
periodic; periodic; expect "E16 monthly fee 3.00 charged once" "$(avail)" . 72500
post /api/dev/cards/$CARD/age '{"activatedDaysAgo":370}' > /dev/null
periodic; periodic; expect "E17 annual fee 100.00 on the first anniversary, once" "$(avail)" . 62500
R=$(post /api/admin/cards/$CARD/replace '{"reason":"DAMAGED","samePan":true}'); R=$(approve "$R")
expect "E18 replacement fee 15.00" "$(avail)" . 61000
R=$(get /api/admin/cards/$CARD/fees); expect "E19 card fee history: issuance, monthly, annual" "$R" '[.items[].event] | sort | join(",")' "ANNUAL,ISSUANCE,MONTHLY"

echo "== Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
