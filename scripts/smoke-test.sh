#!/usr/bin/env bash
# =====================================================================
# CMS smoke test - issuance lifecycle end to end.
# Needs: CMS running (dev profile), hsm-sim running, seed-dev.sql loaded,
#        curl, jq, java 21 (for PIN block generation via hsm-sim).
# Usage: ./scripts/smoke-test.sh [base-url]
# =====================================================================
set -uo pipefail

BASE="${1:-http://localhost:8080}"
SIM="$(dirname "$0")/../hsm-sim/HsmSimulator.java"
KIOSK_ZPK_CLEAR="0B0B0B0B0B0B0B0B1616161616161616"   # matches ZPK_KIOSK in seed-dev.sql
OP="smoke"
RUN_ID="$(date +%s)"
PASS=0; FAIL=0

ok()   { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "  FAIL  $1  ->  $2"; FAIL=$((FAIL+1)); }
post() { curl -s -w '\n%{http_code}' -X POST "$BASE$1" -H 'Content-Type: application/json' -H "X-Operator: $OP" -d "$2"; }
get()  { curl -s -w '\n%{http_code}' "$BASE$1" -H "X-Operator: $OP"; }
body() { echo "$1" | sed '$d'; }
code() { echo "$1" | tail -n1; }

echo "== CMS smoke test against $BASE (run $RUN_ID)"

# T01 HSM health
R=$(get /api/admin/hsm/health)
[ "$(code "$R")" = 200 ] && ok "T01 HSM health UP" || bad "T01 HSM health" "$(body "$R")"

# T02 create customer
R=$(post /api/admin/customers "{\"customerRef\":\"CIF$RUN_ID\",\"customerType\":\"INDIVIDUAL\",\"segmentCode\":\"MASS\",
     \"fullName\":\"Ahmed Test Hassan\",\"embossingName\":\"AHMED T HASSAN\",\"nationalId\":\"NID$RUN_ID\"}")
CUST=$(body "$R" | jq -r .id)
[ "$(code "$R")" = 200 ] && ok "T02 create customer id=$CUST" || bad "T02 create customer" "$(body "$R")"

# T03 duplicate customer rejected
R=$(post /api/admin/customers "{\"customerRef\":\"CIF$RUN_ID\",\"segmentCode\":\"MASS\",\"fullName\":\"X\",\"embossingName\":\"X\"}")
[ "$(code "$R")" = 409 ] && ok "T03 duplicate CIF rejected" || bad "T03 duplicate CIF" "$(code "$R") $(body "$R")"

# T04 open PREPAID account
R=$(post /api/admin/customers/$CUST/accounts '{"accountTypeCode":"PREPAID","currencyCode":"EGP"}')
ACCT=$(body "$R" | jq -r .id)
[ "$(code "$R")" = 200 ] && ok "T04 open account id=$ACCT" || bad "T04 open account" "$(body "$R")"

# T05 eligible products = P01 only (PREPAID x MASS)
R=$(get /api/admin/accounts/$ACCT/eligible-products)
[ "$(body "$R" | jq -r '[.[].code] | join(",")')" = "P01" ] && ok "T05 eligibility returns P01" || bad "T05 eligibility" "$(body "$R")"

# T06 ineligible product rejected
R=$(post /api/admin/accounts/$ACCT/cards '{"productCode":"P02","branchId":"BR001"}')
[ "$(body "$R" | jq -r .code)" = "PRODUCT_NOT_ELIGIBLE" ] && ok "T06 ineligible product rejected" || bad "T06 ineligible" "$(body "$R")"

# T07 issue card
R=$(post /api/admin/accounts/$ACCT/cards '{"productCode":"P01","branchId":"BR001"}')
PAN=$(body "$R" | jq -r .pan)
[ "$(code "$R")" = 200 ] && [ "$(body "$R" | jq -r .status)" = "PENDING_PRINT" ] \
  && ok "T07 issue card ${PAN:0:6}******${PAN: -4}" || bad "T07 issue card" "$(body "$R")"

# T08 max cards per account enforced (P01 max = 1)
R=$(post /api/admin/accounts/$ACCT/cards '{"productCode":"P01","branchId":"BR001"}')
[ "$(body "$R" | jq -r .code)" = "LIMIT_REACHED" ] && ok "T08 per-account card limit" || bad "T08 limit" "$(body "$R")"

# T09 Dexxis search returns perso data with CVVs
R=$(post /api/dexxis/cards/search "{\"pan\":\"$PAN\"}")
[ "$(code "$R")" = 200 ] && [ "$(body "$R" | jq -r '.cvv1|length')" = 3 ] \
  && ok "T09 Dexxis perso data (track2 + CVVs)" || bad "T09 perso" "$(body "$R")"

# T10 unknown / invalid PAN
R=$(post /api/dexxis/cards/search '{"pan":"4111111111111111"}')
[ "$(code "$R")" = 404 ] && ok "T10 unknown PAN 404" || bad "T10 unknown PAN" "$(code "$R") $(body "$R")"
R=$(post /api/dexxis/cards/search '{"pan":"1234"}')
[ "$(body "$R" | jq -r .code)" = "INVALID_REQUEST" ] && ok "T11 invalid PAN rejected" || bad "T11 invalid PAN" "$(body "$R")"

# T12 activate with PIN 1234 (PIN block under kiosk ZPK)
PB=$(java "$SIM" pinblock "$PAN" 1234 "$KIOSK_ZPK_CLEAR")
R=$(post /api/dexxis/cards/activate "{\"pan\":\"$PAN\",\"pinBlock\":\"$PB\",\"kioskId\":\"K01\"}")
[ "$(body "$R" | jq -r .result)" = "ACTIVE" ] && ok "T12 activate + PIN set" || bad "T12 activate" "$(body "$R")"

# T13 perso refused after activation
R=$(post /api/dexxis/cards/search "{\"pan\":\"$PAN\"}")
[ "$(body "$R" | jq -r .code)" = "INVALID_STATUS" ] && ok "T13 no re-perso of active card" || bad "T13 re-perso" "$(body "$R")"

# T14 cancel flow on a second customer's card
R=$(post /api/admin/customers "{\"customerRef\":\"CIF${RUN_ID}B\",\"segmentCode\":\"PAYROLL\",\"fullName\":\"Mona Test\",\"embossingName\":\"MONA TEST\"}")
C2=$(body "$R" | jq -r .id)
A2=$(body "$(post /api/admin/customers/$C2/accounts '{"accountTypeCode":"PAYROLL","currencyCode":"EGP"}')" | jq -r .id)
PAN2=$(body "$(post /api/admin/accounts/$A2/cards '{"productCode":"P01","branchId":"BR001"}')" | jq -r .pan)
R=$(post /api/dexxis/cards/cancel "{\"pan\":\"$PAN2\",\"reason\":\"print failure\"}")
[ "$(body "$R" | jq -r .result)" = "CANCELLED" ] && ok "T14 cancel on print failure" || bad "T14 cancel" "$(body "$R")"
PB2=$(java "$SIM" pinblock "$PAN2" 1234 "$KIOSK_ZPK_CLEAR")
R=$(post /api/dexxis/cards/activate "{\"pan\":\"$PAN2\",\"pinBlock\":\"$PB2\",\"kioskId\":\"K01\"}")
[ "$(body "$R" | jq -r .code)" = "INVALID_STATUS" ] && ok "T15 cancelled card cannot activate" || bad "T15" "$(body "$R")"

echo "== Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
