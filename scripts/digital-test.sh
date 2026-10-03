#!/usr/bin/env bash
# =====================================================================
# CMS digital channels test (CMS-115): wallet tokens (green / yellow / red, token payments through BASE24, issuer
# and wallet lifecycle, the TSP outbox, card events, a replacement taking the tokens over), 3-D Secure (frictionless,
# challenge, CAVV checks on the payment, 3-D Secure required) and the cardholder app API (freeze, controls,
# transactions, card details, PIN, activation, lost).
# Needs: CMS with the dev profile (TSP and ACS simulators, LOG SMS sink), hsm-sim with the seed-dev.sql keys
# (ZPK_CHANNEL, CAVV_P01), curl, jq, java 21. P02 is changed for the run and restored at the end.
# Usage: ./scripts/digital-test.sh [base-url]
# =====================================================================
set -uo pipefail

BASE="${1:-http://localhost:8080}"
SIM="$(dirname "$0")/../hsm-sim/HsmSimulator.java"
KIOSK_ZPK="0B0B0B0B0B0B0B0B1616161616161616"
ACQ_ZPK="4C4C4C4C4C4C4C4C5E5E5E5E5E5E5E5E"
CHANNEL_ZPK="2D2D2D2D2D2D2D2D7A7A7A7A7A7A7A7A"
RUN="$(date +%s)"
PASS=0; FAIL=0; STAN=$(( RUN % 900000 + 100000 ))

ok()  { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL  $1  ->  $2"; FAIL=$((FAIL+1)); }
CMS_USER="${CMS_USER:-operator}"; CMS_PASSWORD="${CMS_PASSWORD:-Dev-Passw0rd!}"
SUP_PASSWORD="${SUP_PASSWORD:-Dev-Passw0rd!}"
DEXXIS_KEY="${CMS_DEXXIS_API_KEY:-dev-dexxis-key}"
CHANNEL_KEY="${CMS_CHANNEL_API_KEY:-dev-channel-key}"
TSP_KEY="${CMS_TSP_INBOUND_API_KEY:-dev-tsp-key}"
post() {
  case "$1" in
    /api/dexxis/*)  curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -H "X-Api-Key: $DEXXIS_KEY" -d "$2" ;;
    /api/channel/*) curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -H "X-Api-Key: $CHANNEL_KEY" -d "$2" ;;
    /api/tsp/*)     curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -H "X-Api-Key: $TSP_KEY" -d "$2" ;;
    *)              curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -u "$CMS_USER:$CMS_PASSWORD" -d "$2" ;;
  esac
}
get()  { case "$1" in
    /api/channel/*) curl -s "$BASE$1" -H "X-Api-Key: $CHANNEL_KEY" ;;
    *)              curl -s "$BASE$1" -u "$CMS_USER:$CMS_PASSWORD" ;;
  esac; }
cput() { curl -s -X PUT "$BASE$1" -H 'Content-Type: application/json' -H "X-Api-Key: $CHANNEL_KEY" -d "$2"; }
put()  { curl -s -X PUT "$BASE$1" -H 'Content-Type: application/json' -u "$CMS_USER:$CMS_PASSWORD" -d "$2"; }
sput() { curl -s -X PUT "$BASE$1" -H 'Content-Type: application/json' -u "supervisor:$SUP_PASSWORD" -d "$2"; }
approve() { local id; id=$(echo "$1" | jq -r '.requestId // empty'); [ -z "$id" ] && { echo "$1"; return; }
  curl -s -X POST "$BASE/api/admin/approvals/$id/approve" -H 'Content-Type: application/json' -u "supervisor2:$SUP_PASSWORD" -d '{"comment":"ok"}'; }
pb()   { java "$SIM" pinblock "$1" "$2" "$3"; }
expect() {
  local got; got=$(echo "$2" | jq -r "$3" 2>/dev/null)
  [ "$got" = "$4" ] && ok "$1" || bad "$1" "expected $4, got $got :: $(echo "$2" | jq -c '.' 2>/dev/null | cut -c1-400 || echo "$2" | cut -c1-200)"
}
send() { R=$(post /api/dev/iso/send "$1"); }                                   # through BASE24
code() { # code <mobile>: the newest one-time password sent to that mobile
  post /api/dev/notifications/dispatch '{}' > /dev/null
  get /api/dev/notifications/sink | jq -r --arg m "$1" '[.[] | select(.to == $m and (.text | test("one-time password")))][0].text | match("[0-9]{6}").string'
}
newcard() { # newcard <cif> <mobile> [account id]: sets PAN CARD ACCT CUST (P02, active, activated 3 days ago)
  if [ -z "${3:-}" ]; then
    CUST=$(post /api/admin/customers "{\"customerRef\":\"$1\",\"segmentCode\":\"PREMIUM\",\"fullName\":\"Dina Digital\",\"embossingName\":\"DINA DIGITAL\",\"mobile\":\"$2\"}" | jq -r .id)
    ACCT=$(post /api/admin/customers/$CUST/accounts '{"accountTypeCode":"CURRENT","currencyCode":"EGP"}' | jq -r .id)
    approve "$(post /api/admin/accounts/$ACCT/entries '{"type":"FUNDING","amount":2000000,"narrative":"digital test"}')" > /dev/null
  else ACCT=$3; fi
  local r; r=$(post /api/admin/accounts/$ACCT/cards '{"productCode":"P02","branchId":"BR001"}'); PAN=$(echo "$r" | jq -r .pan); CARD=$(echo "$r" | jq -r .cardId)
  post /api/dexxis/cards/search "{\"pan\":\"$PAN\"}" > /dev/null
  post /api/dexxis/cards/activate "{\"pan\":\"$PAN\",\"pinBlock\":\"$(pb "$PAN" 1234 "$KIOSK_ZPK")\",\"kioskId\":\"K01\"}" > /dev/null
  post /api/dev/cards/$CARD/age '{"activatedDaysAgo":3}' > /dev/null
}
auth() { # dev authorize: auth <type> <channel> <amount> [extra json]
  STAN=$((STAN + 1)); local s; s=$(printf '%06d' "$STAN"); local dt; dt="$(date +%m%d%H%M%S)"
  local pc="000000"; [ "$1" = WITHDRAWAL ] && pc="010000"
  R=$(post /api/dev/authorize "$(jq -nc --arg t "$1" --arg ch "$2" --argjson amt "$3" --arg pan "$PAN" --arg pc "$pc" --arg stan "$s" --arg dt "$dt" \
    --arg run "${RUN: -6}" --argjson extra "${4:-{\}}" \
    '{type:$t, channel:$ch, mti:"1200", processingCode:$pc, pan:$pan, amount:$amt, currencyNumeric:"818", stan:$stan, rrn:("R"+$stan),
      transmissionDt:$dt, localDt:$dt, acquirerId:"123456", terminalId:("DG" + $run), cardAcceptor:"QUASAR STORE", advice:false} + $extra')")
}
tsp_received() { get /api/dev/tsp-sim/received; }
tsp_dispatch() { post /api/dev/tsp-sim/dispatch '{}' > /dev/null; }

P02_ORIG=""
restore() {
  [ -n "$P02_ORIG" ] && approve "$(sput /api/admin/setup/products/P02 "$P02_ORIG")" > /dev/null
  curl -s -X PUT "$BASE/api/dev/tsp-sim/mode" -H 'Content-Type: application/json' -u "$CMS_USER:$CMS_PASSWORD" -d '{"fail":false}' > /dev/null
}
trap restore EXIT

echo "== CMS digital channels test against $BASE (run $RUN)"

# P02 for the run: e-commerce on, no CVV2 requirement, contactless PIN above 600.00, 3-D Secure frictionless up to 500.00
P02_ORIG=$(get /api/admin/setup/products/P02)
P02_NEW=$(echo "$P02_ORIG" | jq -c '.usage.ecomEnabled = true | .chip.verifyCvv2 = false | .chip.contactlessCvmLimit = 60000
  | .digital = {tokenEnabled:true, tokenMaxPerCard:5, tdsEnabled:true, tdsRequired:false, tdsFrictionlessMax:50000, cavvKeyName:"CAVV_P01"}')
R=$(approve "$(sput /api/admin/setup/products/P02 "$P02_NEW")"); expect "D00 P02: wallets, 3-D Secure (CAVV_P01), e-commerce on" "$R" .status APPROVED

# =============== wallet tokens ===============
MOB_A="+2011${RUN: -8}"
newcard "DA$RUN" "$MOB_A"; CARD_A=$CARD; PAN_A=$PAN; ACCT_A=$ACCT
R=$(post /api/dev/tsp-sim/provision "{\"cardId\":$CARD_A,\"wallet\":\"Quasar Pay\",\"deviceName\":\"Pixel 9\"}")
expect "D01 low-risk request with CVV2: GREEN, token created ACTIVE" "$R" '.decision.decision + " " + .token.status' "GREEN ACTIVE"
REF1=$(echo "$R" | jq -r .token.tokenRef)
R=$(get /api/admin/cards/$CARD_A/tokens)
expect "D02 vault: wallet, device, token last 4 (never the token number)" "$R" '.[0].wallet + " / " + .[0].deviceName + " / " + (.[0].tokenLast4 | length | tostring)' "Quasar Pay / Pixel 9 / 4"
expect "D03 cardholder told by SMS that the card went into a wallet" "$(get "/api/admin/notifications?cardId=$CARD_A&size=5")" '[.items[] | select(.event == "TOKEN_ADDED")] | length' 1
send "{\"type\":\"PURCHASE\",\"channel\":\"POS\",\"cardId\":$CARD_A,\"amount\":70000,\"contactless\":true}"
expect "D04 plastic tap 700.00 without PIN -> 112" "$R" .actionCode 112
send "{\"type\":\"PURCHASE\",\"channel\":\"POS\",\"cardId\":$CARD_A,\"amount\":70000,\"contactless\":true,\"tokenRef\":\"$REF1\"}"
expect "D05 phone tap with the token: verified on the device, approved" "$R" .actionCode 000
send "{\"type\":\"PURCHASE\",\"channel\":\"POS\",\"cardId\":$CARD_A,\"amount\":1000,\"contactless\":true,\"tokenRef\":\"UNKNOWNREF0001\"}"
expect "D06 unknown token -> 111" "$R" .actionCode 111

R=$(post /api/dev/tsp-sim/provision "{\"cardId\":$CARD_A,\"wallet\":\"Quasar Pay\",\"deviceName\":\"Tab S9\",\"walletRiskScore\":50}")
expect "D07 medium wallet risk: YELLOW, code by SMS to the masked mobile" "$R" '.decision.decision + " " + .decision.idvMethod + " " + (.decision.destination | test("^\\+?[*0-9]+$") | tostring)' "YELLOW OTP_SMS true"
REF2=$(echo "$R" | jq -r .decision.tokenRef)
R=$(post /api/dev/tsp-sim/provision/$REF2/verify '{"code":"000000"}')
expect "D08 wrong code: not verified, no token" "$R" '"\(.verification.verified) \(.token == null)"' "false true"
R=$(post /api/dev/tsp-sim/provision/$REF2/verify "{\"code\":\"$(code "$MOB_A")\"}")
expect "D09 right code: token created" "$R" '.token.status' ACTIVE
R=$(post /api/dev/tsp-sim/provision "{\"cardId\":$CARD_A,\"walletRiskScore\":85}")
expect "D10 high wallet risk: RED, recorded as DECLINED" "$R" '.decision.decision + " " + (.decision.reasons | join(","))' "RED WALLET_RISK_HIGH"

TID1=$(get /api/admin/cards/$CARD_A/tokens | jq -r --arg r "$REF1" '.[] | select(.tokenRef == $r) | .id')
R=$(post /api/admin/tokens/$TID1/suspend '{"reason":"customer lost the phone"}')
expect "D11 operator suspends the token" "$R" .status SUSPENDED
send "{\"type\":\"PURCHASE\",\"channel\":\"POS\",\"cardId\":$CARD_A,\"amount\":1000,\"contactless\":true,\"tokenRef\":\"$REF1\"}"
expect "D12 suspended token -> 119" "$R" '.actionCode' 119
tsp_dispatch
expect "D13 TSP told: SUSPEND for the token" "$(tsp_received)" "[.[] | select(.tokenRef == \"$REF1\")][0].action" SUSPEND
curl -s -X PUT "$BASE/api/dev/tsp-sim/mode" -H 'Content-Type: application/json' -u "$CMS_USER:$CMS_PASSWORD" -d '{"fail":true}' > /dev/null
post /api/admin/tokens/$TID1/resume '{"reason":"phone found"}' > /dev/null
tsp_dispatch
R=$(get "/api/admin/digital/token-events?tokenId=$TID1")
expect "D14 TSP down: RESUME stays PENDING with the error, retried later" "$R" '.items[0].action + " " + .items[0].status + " " + (.items[0].attempts >= 1 | tostring) + " " + (.items[0].lastError | contains("503") | tostring)' "RESUME PENDING true true"
curl -s -X PUT "$BASE/api/dev/tsp-sim/mode" -H 'Content-Type: application/json' -u "$CMS_USER:$CMS_PASSWORD" -d '{"fail":false}' > /dev/null
tsp_dispatch
expect "D15 TSP back: RESUME delivered" "$(get "/api/admin/digital/token-events?tokenId=$TID1")" '.items[0].status' SENT
send "{\"type\":\"PURCHASE\",\"channel\":\"POS\",\"cardId\":$CARD_A,\"amount\":1000,\"contactless\":true,\"tokenRef\":\"$REF1\"}"
expect "D16 resumed token pays again" "$R" .actionCode 000

approve "$(post /api/admin/cards/$CARD_A/status '{"status":"BLOCKED","reason":"investigation"}')" > /dev/null
expect "D17 card blocked: its tokens are suspended (CARD_BLOCKED)" "$(get /api/admin/cards/$CARD_A/tokens)" '[.[] | select(.status == "SUSPENDED" and .statusReason == "CARD_BLOCKED")] | length' 2
approve "$(post /api/admin/cards/$CARD_A/status '{"status":"ACTIVE","reason":"cleared"}')" > /dev/null
expect "D18 card active again: tokens resumed" "$(get /api/admin/cards/$CARD_A/tokens)" '[.[] | select(.status == "ACTIVE")] | length' 2
EVENTS=$(get "/api/admin/digital/token-events?size=200" | jq .total)
TID2=$(get /api/admin/cards/$CARD_A/tokens | jq -r --arg r "$REF2" '.[] | select(.tokenRef == $r) | .id')
R=$(post /api/dev/tsp-sim/tokens/$REF2/wallet '{"action":"DELETE","reason":"removed from the wallet"}')
expect "D19 wallet deletes a token: DELETED, nothing sent back to the TSP" "$R" .status DELETED
expect "D20 no outbox message for a wallet-side change" "$(get "/api/admin/digital/token-events?size=200")" .total "$EVENTS"

R=$(post /api/admin/cards/$CARD_A/replace '{"reason":"LOST","samePan":false,"branchId":"BR001"}'); NEW_PAN=$(echo "$R" | jq -r .pan); NEW_CARD=$(echo "$R" | jq -r .cardId)
expect "D21 plastic reported lost: the wallet token is suspended (CARD_LOST)" "$(get /api/admin/cards/$CARD_A/tokens)" "[.[] | select(.tokenRef == \"$REF1\")][0].status + \" \" + [.[] | select(.tokenRef == \"$REF1\")][0].statusReason" "SUSPENDED CARD_LOST"
post /api/dexxis/cards/search "{\"pan\":\"$NEW_PAN\"}" > /dev/null
post /api/dexxis/cards/activate "{\"pan\":\"$NEW_PAN\",\"pinBlock\":\"$(pb "$NEW_PAN" 1234 "$KIOSK_ZPK")\",\"kioskId\":\"K01\"}" > /dev/null
expect "D22 replacement activated: the token moved to it and works again" "$(get /api/admin/cards/$NEW_CARD/tokens)" "[.[] | select(.tokenRef == \"$REF1\")][0].status" ACTIVE
tsp_dispatch
expect "D23 TSP told the token's new card number (UPDATE_CARD, new last 4)" "$(tsp_received)" "[.[] | select(.tokenRef == \"$REF1\" and .action == \"UPDATE_CARD\")][0].panLast4" "${NEW_PAN: -4}"
send "{\"type\":\"PURCHASE\",\"channel\":\"POS\",\"cardId\":$NEW_CARD,\"amount\":2500,\"contactless\":true,\"tokenRef\":\"$REF1\"}"
expect "D24 the same phone pays on the new card" "$R" .actionCode 000
R=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/tsp/tokens/authorize" -H 'Content-Type: application/json' -H 'X-Api-Key: wrong' -d '{}')
[ "$R" = 401 ] && ok "D25 TSP API refuses a wrong API key (401)" || bad "D25 TSP API refuses a wrong API key" "HTTP $R"
R=$(post /api/tsp/tokens/authorize "{\"tokenRef\":\"T$RUN\",\"pan\":\"$PAN_A\",\"tokenRequestorId\":\"40010030273\",\"wallet\":\"Other Pay\"}")
expect "D26 request for the lost card through the TSP API: RED" "$R" '.decision + " " + (.reasons | index("CARD_LOST") != null | tostring)' "RED true"

# =============== 3-D Secure ===============
CARD=$NEW_CARD; PAN=$NEW_PAN
post /api/dev/cards/$CARD/age '{"activatedDaysAgo":10}' > /dev/null
R=$(post /api/dev/acs-sim/authenticate "{\"cardId\":$CARD,\"amount\":30000,\"currency\":\"EGP\",\"merchant\":\"BOOK SHOP\"}")
expect "D27 300.00, nothing risky: frictionless, ECI 05, CAVV" "$R" '.transStatus + " " + .eci + " " + (.cavv | length | tostring)' "Y 05 40"
CAVV=$(echo "$R" | jq -r .cavv)
auth PURCHASE ECOM 30000 "{\"cavv\":\"$CAVV\",\"eci\":\"05\"}";  expect "D28 payment with the CAVV approved" "$R" .actionCode 000
auth PURCHASE ECOM 30000 "{\"cavv\":\"$CAVV\",\"eci\":\"05\"}";  expect "D29 the same CAVV again -> 129" "$R" '.actionCode + " " + .reason' "129 CAVV already used"
R=$(post /api/dev/acs-sim/authenticate "{\"cardId\":$CARD,\"amount\":200000,\"currency\":\"EGP\",\"merchant\":\"PHONE SHOP\",\"newDevice\":true}")
expect "D30 2,000.00 on a new device: challenge, code sent" "$R" '.transStatus + " " + (.reasons | join(","))' "C AMOUNT_ABOVE_FRICTIONLESS,NEW_DEVICE"
AUTHID=$(echo "$R" | jq -r .authId)
R=$(post /api/dev/acs-sim/$AUTHID/challenge '{"code":"000000"}'); expect "D31 wrong code: still challenging, 2 tries left" "$R" '.transStatus + " " + (.attemptsLeft | tostring)' "C 2"
R=$(post /api/dev/acs-sim/$AUTHID/challenge "{\"code\":\"$(code "$MOB_A")\"}"); expect "D32 right code: authenticated, CAVV" "$R" '.transStatus + " " + .outcome' "Y AUTHENTICATED"
CAVV=$(echo "$R" | jq -r .cavv)
BADCAVV="${CAVV:0:7}$(( (${CAVV:7:1} + 1) % 10 ))${CAVV:8}"
auth PURCHASE ECOM 200000 "{\"cavv\":\"$BADCAVV\",\"eci\":\"05\"}"; expect "D33 altered CAVV -> 129 (HSM check fails)" "$R" '.actionCode + " " + .reason' "129 CAVV mismatch"
auth PURCHASE ECOM 300000 "{\"cavv\":\"$CAVV\",\"eci\":\"05\"}";  expect "D34 3,000.00 on a 2,000.00 authentication -> 129" "$R" '.reason' "amount above the authenticated amount"
auth PURCHASE ECOM 200000 "{\"cavv\":\"$CAVV\",\"eci\":\"05\"}";  expect "D35 the authenticated amount is approved" "$R" .actionCode 000
P02_REQ=$(get /api/admin/setup/products/P02 | jq -c '.digital.tdsRequired = true')
approve "$(sput /api/admin/setup/products/P02 "$P02_REQ")" > /dev/null
auth PURCHASE ECOM 1000;  expect "D36 3-D Secure required: e-commerce without CAVV -> 119" "$R" '.actionCode + " " + .reason' "119 3-D Secure authentication required"

# =============== cardholder app ===============
MOB_B="+2012${RUN: -8}"
newcard "DB$RUN" "$MOB_B"; CARD_B=$CARD; PAN_B=$PAN; ACCT_B=$ACCT; CIF="DB$RUN"
APP="/api/channel/app/customers/$CIF/cards"
R=$(get $APP); expect "D37 app lists the customer's cards, masked" "$R" '(.[0].cardId | tostring) + " " + (.[0].maskedPan | contains("******") | tostring)' "$CARD_B true"
R=$(get "/api/channel/app/customers/DA$RUN/cards/$CARD_B"); expect "D38 another customer's card is not found" "$R" .code CARD_NOT_FOUND
R=$(post $APP/$CARD_B/freeze '{"frozen":true}'); expect "D39 cardholder freezes the card" "$R" .frozen true
auth PURCHASE POS 1000; expect "D40 frozen card -> 104" "$R" '.actionCode + " " + .reason' "104 card frozen by the cardholder"
post $APP/$CARD_B/freeze '{"frozen":false}' > /dev/null
auth PURCHASE POS 1000; expect "D41 unfrozen: approved" "$R" .actionCode 000
R=$(cput $APP/$CARD_B/controls '{"international":false}'); expect "D42 cardholder switches international use off" "$R" .controls.international false
auth PURCHASE POS 1000 '{"acquirerCountry":"840"}'; expect "D43 purchase abroad -> 119" "$R" '.actionCode + " " + .reason' "119 international use switched off"
cput $APP/$CARD_B/controls '{"international":true}' > /dev/null
auth PURCHASE POS 1000 '{"acquirerCountry":"840"}'; expect "D44 switched back on: approved" "$R" .actionCode 000
R=$(get "$APP/$CARD_B/transactions?size=5"); expect "D45 app transaction list, newest first" "$R" '.[0].result + " " + .[1].result + " " + .[1].reason' "APPROVED DECLINED Transaction not permitted to cardholder"
R=$(post $APP/$CARD_B/details '{"otpId":"00000000-0000-0000-0000-000000000000"}'); expect "D46 card details without a verified code: refused" "$R" .code OTP_REQUIRED
OTP=$(post $APP/$CARD_B/otp '{"purpose":"CARD_DETAILS"}' | jq -r .otpId)
post $APP/$CARD_B/otp/verify "{\"otpId\":\"$OTP\",\"code\":\"$(code "$MOB_B")\"}" > /dev/null
R=$(post $APP/$CARD_B/details "{\"otpId\":\"$OTP\"}"); expect "D47 card number, expiry and CVV2 after the code" "$R" '(.pan == "'"$PAN_B"'" | tostring) + " " + (.cvv2 | test("^[0-9]{3}$") | tostring)' "true true"
R=$(post $APP/$CARD_B/details "{\"otpId\":\"$OTP\"}"); expect "D48 a code unlocks the details once" "$R" .code OTP_REQUIRED
OTP=$(post $APP/$CARD_B/otp '{"purpose":"PIN_SET"}' | jq -r .otpId)
post $APP/$CARD_B/otp/verify "{\"otpId\":\"$OTP\",\"code\":\"$(code "$MOB_B")\"}" > /dev/null
R=$(post $APP/$CARD_B/pin "{\"otpId\":\"$OTP\",\"pinBlock\":\"$(pb "$PAN_B" 4321 "$CHANNEL_ZPK")\"}"); expect "D49 new PIN set in the app" "$R" .status ACTIVE
auth WITHDRAWAL ATM 10000 "{\"pinBlock\":\"$(pb "$PAN_B" 4321 "$ACQ_ZPK")\"}"; expect "D50 ATM accepts the new PIN" "$R" .actionCode 000
auth WITHDRAWAL ATM 10000 "{\"pinBlock\":\"$(pb "$PAN_B" 1234 "$ACQ_ZPK")\"}"; expect "D51 the old PIN no longer works" "$R" .actionCode 117

R=$(post /api/admin/accounts/$ACCT_B/cards '{"productCode":"P02","branchId":"BR001"}'); CARD_C=$(echo "$R" | jq -r .cardId); PAN_C=$(echo "$R" | jq -r .pan)
post /api/dev/cards/$CARD_C/age '{"status":"PRINTED"}' > /dev/null
OTP=$(post $APP/$CARD_C/otp '{"purpose":"ACTIVATION"}' | jq -r .otpId)
post $APP/$CARD_C/otp/verify "{\"otpId\":\"$OTP\",\"code\":\"$(code "$MOB_B")\"}" > /dev/null
R=$(post $APP/$CARD_C/activate "{\"otpId\":\"$OTP\"}"); expect "D52 delivered card without a PIN cannot be activated" "$R" .message "Set a PIN first"
OTP=$(post $APP/$CARD_C/otp '{"purpose":"PIN_SET"}' | jq -r .otpId)
post $APP/$CARD_C/otp/verify "{\"otpId\":\"$OTP\",\"code\":\"$(code "$MOB_B")\"}" > /dev/null
post $APP/$CARD_C/pin "{\"otpId\":\"$OTP\",\"pinBlock\":\"$(pb "$PAN_C" 2468 "$CHANNEL_ZPK")\"}" > /dev/null
OTP=$(post $APP/$CARD_C/otp '{"purpose":"ACTIVATION"}' | jq -r .otpId)
R=$(post $APP/$CARD_C/activate "{\"otpId\":\"$OTP\"}"); expect "D53 activation needs the code verified first" "$R" .code OTP_REQUIRED
post $APP/$CARD_C/otp/verify "{\"otpId\":\"$OTP\",\"code\":\"$(code "$MOB_B")\"}" > /dev/null
R=$(post $APP/$CARD_C/activate "{\"otpId\":\"$OTP\"}"); expect "D54 PIN set in the app, then activated" "$R" .status ACTIVE
R=$(post $APP/$CARD_C/report '{"status":"STOLEN","note":"bag stolen"}'); expect "D55 cardholder reports the card stolen" "$R" .status STOLEN

echo "== Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
