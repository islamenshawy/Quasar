#!/usr/bin/env bash
# =====================================================================
# CMS chip completeness test (CMS-110): issuer scripts end to end through BASE24 (queued, MAC'd by the HSM,
# delivered in field 55, checked and reported by the emulated chip in 9F5B), contactless limits, Visa CVN17,
# CVV1 vs iCVV by entry mode, CVV2 for e-commerce.
# Needs: CMS with the dev profile, hsm-sim 1.2.0 (KU), seed-dev.sql, curl, jq, java 21.
# P02 is switched to CVN17 with CVV checks for part of the run and restored at the end.
# Usage: ./scripts/chip-test.sh [base-url]
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
oput() { curl -s -X PUT "$BASE$1" -H 'Content-Type: application/json' -u "$CMS_USER:$CMS_PASSWORD" -d "$2"; }
sput() { curl -s -X PUT "$BASE$1" -H 'Content-Type: application/json' -u "supervisor:$SUP_PASSWORD" -d "$2"; }
approve() { local id; id=$(echo "$1" | jq -r '.requestId // empty'); [ -z "$id" ] && { echo "$1"; return; }
  curl -s -X POST "$BASE/api/admin/approvals/$id/approve" -H 'Content-Type: application/json' -u "supervisor2:$SUP_PASSWORD" -d '{"comment":"ok"}'; }
pb()   { java "$SIM" pinblock "$1" "$2" "$3"; }
expect() {
  local got; got=$(echo "$2" | jq -r "$3")
  [ "$got" = "$4" ] && ok "$1" || bad "$1" "expected $4, got $got :: $(echo "$2" | jq -c '{actionCode,actionText,reason,code,message,chip}' 2>/dev/null | cut -c1-400 || echo "$2")"
}
send()    { R=$(post /api/dev/iso/send "$1"); }                                   # through BASE24
scripts() { get "/api/admin/cards/$1/chip-scripts"; }
newcard() { # newcard <product> <segment> <account type>: sets PAN CARD ACCT PERSO
  local cust; cust=$(post /api/admin/customers "{\"customerRef\":\"K$2$RUN\",\"segmentCode\":\"$2\",\"fullName\":\"Chip Test\",\"embossingName\":\"CHIP TEST\"}" | jq -r .id)
  ACCT=$(post /api/admin/customers/$cust/accounts "{\"accountTypeCode\":\"$3\",\"currencyCode\":\"EGP\"}" | jq -r .id)
  approve "$(post /api/admin/accounts/$ACCT/entries '{"type":"FUNDING","amount":1000000,"narrative":"chip test"}')" > /dev/null
  local r; r=$(post /api/admin/accounts/$ACCT/cards "{\"productCode\":\"$1\",\"branchId\":\"BR001\"}"); PAN=$(echo "$r" | jq -r .pan); CARD=$(echo "$r" | jq -r .cardId)
  PERSO=$(post /api/dexxis/cards/search "{\"pan\":\"$PAN\"}")
  post /api/dexxis/cards/activate "{\"pan\":\"$PAN\",\"pinBlock\":\"$(pb "$PAN" 1234 "$KIOSK_ZPK")\",\"kioskId\":\"K01\"}" > /dev/null
}
auth() { # dev authorize: auth <type> <channel> <amount> <extra json>
  STAN=$((STAN + 1)); local s; s=$(printf '%06d' "$STAN"); local dt; dt="$(date +%m%d%H%M%S)"
  local pc="000000"; [ "$1" = BALANCE_INQUIRY ] && pc="310000"
  R=$(post /api/dev/authorize "$(jq -nc --arg t "$1" --arg ch "$2" --argjson amt "$3" --arg pan "$PAN" --arg pc "$pc" --arg stan "$s" --arg dt "$dt" \
    --arg run "${RUN: -6}" --argjson extra "${4:-{\}}" \
    '{type:$t, channel:$ch, mti:"1200", processingCode:$pc, pan:$pan, amount:$amt, currencyNumeric:"818", stan:$stan, rrn:("R"+$stan),
      transmissionDt:$dt, localDt:$dt, acquirerId:"123456", terminalId:("KC" + $run), advice:false} + $extra')")
}
P02_ORIG=""
restore() { [ -n "$P02_ORIG" ] && approve "$(sput /api/admin/setup/products/P02 "$P02_ORIG")" > /dev/null; }
trap restore EXIT

echo "== CMS chip completeness test against $BASE (run $RUN)"

# =============== issuer scripts (P01: IMK_SMI_P01) ===============
newcard P01 MASS PREPAID
expect "K00 setup: P01 card active" "$(get /api/admin/cards/$CARD)" .status ACTIVE
R=$(post /api/admin/cards/$CARD/chip-scripts '{"command":"PIN_UNBLOCK","reason":"customer locked offline PIN"}')
expect "K01 PIN unblock queued for the chip" "$R" '.command + " " + .status' "PIN_UNBLOCK QUEUED"
R=$(post /api/admin/cards/$CARD/chip-scripts '{"command":"PIN_UNBLOCK","reason":"again"}')
expect "K02 the same command cannot be queued twice" "$R" .code DUPLICATE
send "{\"type\":\"PURCHASE\",\"channel\":\"POS\",\"cardId\":$CARD,\"amount\":1000,\"currency\":\"818\"}"
expect "K03 magstripe transaction: no script delivered" "$(scripts $CARD)" '.[0].status' QUEUED
send "{\"type\":\"BALANCE_INQUIRY\",\"channel\":\"ATM\",\"cardId\":$CARD,\"pin\":\"1234\",\"chip\":true}"
expect "K04 chip transaction: ARPC valid and the script arrives with a valid MAC" "$R" '"\(.actionCode) \(.chip.arpcValid) \(.chip.scripts | length) \(.chip.scripts[0].macValid)"' "000 true 1 true"
expect "K05 script is SENT, APDU 8424000004 + MAC recorded" "$(scripts $CARD)" '.[0].status + " " + (.[0].apdu | contains("8424000004") | tostring)' "SENT true"
send "{\"type\":\"BALANCE_INQUIRY\",\"channel\":\"ATM\",\"cardId\":$CARD,\"pin\":\"1234\",\"chip\":true}"
expect "K06 next chip transaction reports success (9F5B): APPLIED" "$(scripts $CARD)" '.[0].status' APPLIED
post /api/admin/cards/$CARD/chip-scripts '{"command":"APPLICATION_BLOCK","reason":"test"}' > /dev/null
post /api/admin/cards/$CARD/chip-scripts '{"command":"UPDATE_OFFLINE_LIMIT","value":"5","reason":"test"}' > /dev/null
send "{\"type\":\"BALANCE_INQUIRY\",\"channel\":\"ATM\",\"cardId\":$CARD,\"pin\":\"1234\",\"chip\":true}"
expect "K07 two scripts in one answer, both MACs valid" "$R" '[.chip.scripts[].macValid] | join(",")' "true,true"
post /api/admin/cards/$CARD/chip-scripts '{"command":"APPLICATION_UNBLOCK","reason":"test"}' > /dev/null
send "{\"type\":\"BALANCE_INQUIRY\",\"channel\":\"ATM\",\"cardId\":$CARD,\"pin\":\"1234\",\"chip\":true,\"failScripts\":true}"
send "{\"type\":\"BALANCE_INQUIRY\",\"channel\":\"ATM\",\"cardId\":$CARD,\"pin\":\"1234\",\"chip\":true}"
expect "K08 a script the chip refuses is FAILED" "$(scripts $CARD)" '[.[] | select(.command=="APPLICATION_UNBLOCK")][0].status' FAILED
expect "K09 the earlier two were APPLIED" "$(scripts $CARD)" '[.[] | select(.command=="APPLICATION_BLOCK" or .command=="UPDATE_OFFLINE_LIMIT") | .status] | join(",")' "APPLIED,APPLIED"
SID=$(post /api/admin/cards/$CARD/chip-scripts '{"command":"PIN_UNBLOCK","reason":"cancel me"}' | jq -r .id)
R=$(post /api/admin/cards/chip-scripts/$SID/cancel '{}'); expect "K10 a queued script can be cancelled" "$R" .status CANCELLED
send "{\"type\":\"BALANCE_INQUIRY\",\"channel\":\"ATM\",\"cardId\":$CARD,\"pin\":\"1234\",\"chip\":true}"
expect "K11 cancelled scripts are not delivered" "$R" '.chip.scripts | length' 0

# =============== contactless (P01: 5,000 per tap, PIN above 600, 2,000 cumulative) ===============
tap() { send "{\"type\":\"PURCHASE\",\"channel\":\"POS\",\"cardId\":$CARD,\"amount\":$1,\"currency\":\"818\",\"contactless\":true,\"chip\":true${2:+,\"pin\":\"$2\"}}"; }
tap 10000;          expect "K12 tap 100.00 without PIN" "$R" .actionCode 000
tap 70000;          expect "K13 tap 700.00 without PIN -> 112 PIN required" "$R" .actionCode 112
tap 70000 1234;     expect "K14 the same with PIN" "$R" .actionCode 000
tap 50000; tap 50000; tap 50000; tap 50000
expect "K15 four 500.00 taps fit the 2,000.00 no-PIN total" "$R" .actionCode 000
tap 10000;          expect "K16 one more tap over the total -> 112" "$R" .actionCode 112
tap 10000 1234;     tap 10000
expect "K17 after a PIN the no-PIN total starts again" "$R" .actionCode 000
tap 600000 1234;    expect "K18 tap over the 5,000.00 contactless limit -> 121" "$R" .actionCode 121
LIM=$(get /api/admin/cards/$CARD/limits)
CTRL=$(echo "$LIM" | jq -c '{atmEnabled, posEnabled, ecomEnabled, dailyWdCountLimit, dailyWdAmountLimit, perTxnWdLimit, dailyPosCountLimit, dailyPosAmountLimit, perTxnPosLimit}')
approve "$(oput /api/admin/cards/$CARD/limits "$(echo "$CTRL" | jq -c '. + {contactlessEnabled:false, reason:"customer request"}')")" > /dev/null
tap 1000;           expect "K19 contactless switched off on the card -> 119" "$R" .actionCode 119
approve "$(oput /api/admin/cards/$CARD/limits "$(echo "$CTRL" | jq -c '. + {contactlessEnabled:true, reason:"back on"}')")" > /dev/null

# =============== CVN17, CVV1 / iCVV, CVV2 (P02, restored at the end) ===============
P02_ORIG=$(get /api/admin/setup/products/P02)
P02_NEW=$(echo "$P02_ORIG" | jq -c '.emv = {scheme:"VISA_CVN17", dataList:"9F02,9F37,9F36,9F10:B5"}
  | .usage.verifyCvv = true | .usage.ecomEnabled = true | .chip.verifyCvv2 = true')
R=$(approve "$(sput /api/admin/setup/products/P02 "$P02_NEW")"); expect "K20 P02: CVN17, CVV checks, CVV2 required for e-commerce" "$R" .status APPROVED
newcard P02 PREMIUM CURRENT
CVV1=$(echo "$PERSO" | jq -r .cvv1); ICVV=$(echo "$PERSO" | jq -r .icvv); CVV2=$(echo "$PERSO" | jq -r .cvv2)
EXP=$(echo "$PERSO" | jq -r .expiryYYMM); SVC=$(echo "$PERSO" | jq -r .serviceCode)
TR_MAG="${PAN}=${EXP}${SVC}10000${CVV1}"; TR_CHIP="${PAN}=${EXP}${SVC}10000${ICVV}"
send "{\"type\":\"BALANCE_INQUIRY\",\"channel\":\"ATM\",\"cardId\":$CARD,\"pin\":\"1234\",\"chip\":true}"
expect "K21 Visa CVN17 (qVSDC) cryptogram verified, ARPC valid" "$R" '"\(.actionCode) \(.chip.arpcValid)"' "000 true"
PIN=$(pb "$PAN" 1234 "$ACQ_ZPK")
auth BALANCE_INQUIRY ATM 0 "{\"pinBlock\":\"$PIN\",\"track2\":\"$TR_MAG\",\"entryMode\":\"MAGSTRIPE\"}";  expect "K22 magstripe read with CVV1" "$R" .actionCode 000
auth BALANCE_INQUIRY ATM 0 "{\"pinBlock\":\"$PIN\",\"track2\":\"$TR_CHIP\",\"entryMode\":\"MAGSTRIPE\"}"; expect "K23 chip data copied onto a stripe (iCVV) -> 129" "$R" .actionCode 129
auth BALANCE_INQUIRY ATM 0 "{\"pinBlock\":\"$PIN\",\"track2\":\"$TR_CHIP\",\"entryMode\":\"CHIP\"}";      expect "K24 chip read with iCVV" "$R" .actionCode 000
auth BALANCE_INQUIRY ATM 0 "{\"pinBlock\":\"$PIN\",\"track2\":\"$TR_MAG\",\"entryMode\":\"CHIP\"}";       expect "K25 chip read carrying the stripe CVV1 -> 129" "$R" .actionCode 129
auth PURCHASE ECOM 5000 "{\"expiryYYMM\":\"$EXP\"}";                   expect "K26 e-commerce without CVV2 -> 129" "$R" '.actionCode + " " + .reason' "129 CVV2 required"
auth PURCHASE ECOM 5000 "{\"expiryYYMM\":\"$EXP\",\"cvv2\":\"000\"}";  expect "K27 wrong CVV2 -> 129" "$R" '.actionCode + " " + .reason' "129 CVV2 mismatch"
auth PURCHASE ECOM 5000 "{\"expiryYYMM\":\"$EXP\",\"cvv2\":\"$CVV2\"}"; expect "K28 right CVV2 approved" "$R" .actionCode 000

echo "== Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
