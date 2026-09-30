#!/usr/bin/env bash
# =====================================================================
# Operator sign-in, roles, maker-checker, CSRF and Dexxis API key (CMS-060).
# Needs: CMS with the dev profile (dev users), curl, jq.
# Usage: ./scripts/security-test.sh [base-url]
# =====================================================================
set -uo pipefail

BASE="${1:-http://localhost:8080}"
PW="${CMS_DEV_PASSWORD:-Dev-Passw0rd!}"
DEXXIS_KEY="${CMS_DEXXIS_API_KEY:-dev-dexxis-key}"
RUN="$(date +%s)"; PASS=0; FAIL=0
JAR="$(mktemp)"; trap 'rm -f "$JAR"' EXIT

ok()  { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL  $1  ->  $2"; FAIL=$((FAIL+1)); }
# as <user> <method> <path> [json] -> prints "<status> <body>"
as() {
  local auth=(); [ "$1" != "-" ] && auth=(-u "$1:${PASSWORD_OF[$1]:-$PW}")
  curl -s -o /tmp/sec_body.$$ -w '%{http_code}' -X "$2" "$BASE$3" -H 'Content-Type: application/json' "${auth[@]}" ${4:+-d "$4"}
  echo " $(cat /tmp/sec_body.$$)"
}
declare -A PASSWORD_OF
status() { echo "$1" | cut -d' ' -f1; }
body()   { echo "$1" | cut -d' ' -f2-; }
expect_status() { [ "$(status "$2")" = "$3" ] && ok "$1" || bad "$1" "expected $3, got $(echo "$2" | cut -c1-160)"; }
expect_json() { local got; got=$(body "$2" | jq -r "$3"); [ "$got" = "$4" ] && ok "$1" || bad "$1" "expected $4, got $got"; }

echo "== CMS security test against $BASE (run $RUN)"

# ---------- authentication ----------
R=$(as - GET /api/admin/customers);                   expect_status "S01 anonymous API call refused" "$R" 401
R=$(as - GET /api/version);                           expect_status "S02 version is public" "$R" 200
R=$(curl -s -o /dev/null -w '%{http_code}' -u "operator:wrong-password" "$BASE/api/admin/customers")
[ "$R" = 401 ] && ok "S03 wrong password refused" || bad "S03 wrong password" "$R"

# ---------- roles ----------
R=$(as viewer GET /api/admin/customers);              expect_status "S04 viewer can read" "$R" 200
R=$(as viewer POST /api/admin/customers '{"segmentCode":"MASS","fullName":"X","embossingName":"X"}')
expect_status "S05 viewer cannot write" "$R" 403
R=$(as operator PUT /api/admin/setup/segments/MASS '{"code":"MASS","name":"Mass retail","active":true}')
expect_status "S06 operator cannot change setup" "$R" 403
R=$(as operator GET /api/admin/users);                expect_status "S07 users API is admin only" "$R" 403

# ---------- maker-checker ----------
R=$(as supervisor PUT /api/admin/setup/segments/MASS "{\"code\":\"MASS\",\"name\":\"Mass retail $RUN\",\"active\":true}")
expect_status "S08 setup change by supervisor goes to approval (202)" "$R" 202
REQ=$(body "$R" | jq -r .requestId)
R=$(as supervisor POST /api/admin/approvals/$REQ/approve '{}'); expect_json "S09 maker cannot approve own request" "$R" .code FOUR_EYES
R=$(as operator POST /api/admin/approvals/$REQ/approve '{}');   expect_status "S10 operator cannot approve" "$R" 403
R=$(as supervisor2 POST /api/admin/approvals/$REQ/reject '{}'); expect_status "S11 reject needs a reason" "$R" 422
R=$(as supervisor2 POST /api/admin/approvals/$REQ/approve '{"comment":"ok"}'); expect_json "S12 second supervisor approves" "$R" .status APPROVED
R=$(as viewer GET /api/admin/setup/segments); expect_json "S13 change applied after approval" "$R" '.[] | select(.code=="MASS") | .name' "Mass retail $RUN"
R=$(as supervisor PUT /api/admin/setup/segments/MASS '{"code":"MASS","name":"","active":true}')
expect_status "S14 invalid change refused at submission (dry run)" "$R" 422

# ---------- users, temporary password, lock-out ----------
U="sec$RUN"
R=$(as admin POST /api/admin/users "{\"username\":\"$U\",\"fullName\":\"Security Test\",\"roles\":[\"OPERATOR\"]}")
expect_status "S15 admin creates user with temporary password" "$R" 200
PASSWORD_OF[$U]=$(body "$R" | jq -r .password); UID_=$(as admin GET /api/admin/users | cut -d' ' -f2- | jq -r ".[] | select(.username==\"$U\") | .id")
R=$(as "$U" GET /api/admin/customers);                expect_json "S16 temporary password must be changed first" "$R" .code PASSWORD_CHANGE_REQUIRED
NEWPW="New-Passw0rd-$RUN!"
R=$(as "$U" POST /api/auth/password "{\"current\":\"${PASSWORD_OF[$U]}\",\"next\":\"short\"}")
expect_status "S17 weak password refused" "$R" 422
R=$(as "$U" POST /api/auth/password "{\"current\":\"${PASSWORD_OF[$U]}\",\"next\":\"$NEWPW\"}")
expect_status "S18 password changed" "$R" 200
PASSWORD_OF[$U]="$NEWPW"
R=$(as "$U" GET /api/admin/customers);                expect_status "S19 new password works" "$R" 200
for i in 1 2 3 4 5; do curl -s -o /dev/null -u "$U:bad-$i" "$BASE/api/admin/customers"; done
R=$(as "$U" GET /api/admin/customers);                expect_status "S20 locked after 5 failed sign-ins" "$R" 401
R=$(as admin GET /api/admin/users);                   expect_json "S21 user shows LOCKED" "$R" ".[] | select(.username==\"$U\") | .status" LOCKED
R=$(as admin POST /api/admin/users/$UID_/reset-password); expect_status "S22 admin resets password (unlocks)" "$R" 200

# ---------- console session + CSRF ----------
curl -s -c "$JAR" -b "$JAR" "$BASE/api/auth/csrf" >/dev/null
XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' "$JAR")
R=$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $XSRF" -d "{\"username\":\"operator\",\"password\":\"$PW\"}")
[ "$R" = 200 ] && ok "S23 console sign-in with CSRF token" || bad "S23 sign-in" "$R"
XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' "$JAR")
R=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -X POST "$BASE/api/admin/customers" -H 'Content-Type: application/json' \
    -d '{"segmentCode":"MASS","fullName":"Csrf Test","embossingName":"CSRF TEST"}')
[ "$R" = 403 ] && ok "S24 session POST without CSRF token refused" || bad "S24 CSRF" "$R"
R=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -X POST "$BASE/api/admin/customers" -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $XSRF" -d "{\"segmentCode\":\"MASS\",\"fullName\":\"Csrf Test\",\"embossingName\":\"CSRF TEST\"}")
[ "$R" = 200 ] && ok "S25 session POST with CSRF token accepted" || bad "S25 CSRF" "$R"
R=$(curl -s -b "$JAR" "$BASE/api/auth/me");           [ "$(echo "$R" | jq -r .username)" = operator ] && ok "S26 /api/auth/me" || bad "S26 me" "$R"

# ---------- Dexxis API key ----------
R=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/dexxis/cards/search" -H 'Content-Type: application/json' -d '{"pan":"4111111111111111"}')
[ "$R" = 401 ] && ok "S27 Dexxis without API key refused" || bad "S27 Dexxis no key" "$R"
R=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/dexxis/cards/search" -H 'Content-Type: application/json' -H "X-Api-Key: $DEXXIS_KEY" -d '{"pan":"4111111111111111"}')
[ "$R" = 404 ] && ok "S28 Dexxis with API key reaches the service" || bad "S28 Dexxis key" "$R"
R=$(curl -s -o /dev/null -w '%{http_code}' -u "operator:$PW" -X POST "$BASE/api/dexxis/cards/search" -H 'Content-Type: application/json' -d '{"pan":"4111111111111111"}')
[ "$R" = 403 ] && ok "S29 operators cannot call the Dexxis API" || bad "S29 operator on Dexxis" "$R"

# ---------- policy ----------
R=$(as supervisor PUT /api/admin/approval-policy/CARD_STATUS '{"required":true}'); expect_status "S30 only admin changes approval policy" "$R" 403

echo "== Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
