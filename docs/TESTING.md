# Testing

| | |
|---|---|
| Applies to | cms-core **0.4.0**, hsm-sim **1.0.0** |
| Owner | Development chapter |
| Last updated | 2026-09-28 |

## 1. Test levels

| Level | What | How to run | When |
|---|---|---|---|
| L1 Unit | Luhn, HSM helpers, pure logic | `mvn test` | Every commit |
| L2 Simulator self-test | 3DES, ISO-0, Visa PVV, Visa CVV (reference vector 561), protocol | `java hsm-sim/HsmSimulator.java selftest` | After any hsm-sim change |
| L3 HSM integration | CMS `PayShieldClient` + `PinService` against hsm-sim over TCP | Planned as a JUnit test (CMS-032); verified manually for 0.4.0 | Every release |
| L4 Smoke (end to end) | Issuance lifecycle via REST, 15 cases below | `./scripts/smoke-test.sh` | Every deployment |
| L5 Manual UI | Issuance screen cases below | Browser | Every release that touches the screen |
| L6 Real HSM | L4 against payShield 10K with test LMK | See DEPLOYMENT §10 | Before connecting Dexxis or the corehost |

A release may be deployed to TEST only when L1, L2 and L4 pass.

## 2. Test data reference (DEV / TEST only)

| Item | Value |
|---|---|
| Kiosk ZPK (clear) | `0B0B0B0B0B0B0B0B1616161616161616` (KCV `FB57EE`) |
| Corehost ZPK (clear) | `4C4C4C4C4C4C4C4C5E5E5E5E5E5E5E5E` (KCV `266B8A`) |
| PVK (clear) | `FEDCBA98765432100123456789ABCDEF` (KCV `7B8358`) |
| CVK (clear) | `0123456789ABCDEFFEDCBA9876543210` (KCV `08D7B4`) |
| hsm-sim LMK check value | `EB7A8DF91182DBE2` (default `SIM_LMK`) |
| Test BIN | `999999` (not a real BIN) |
| Products | P01 Prepaid Classic EGP, max 1 per account. P02 Debit Gold EGP, max 2 per account. |
| Test PIN | `1234` |

Make a PIN block for any card:

```bash
java hsm-sim/HsmSimulator.java pinblock <PAN> 1234 0B0B0B0B0B0B0B0B1616161616161616
```

## 3. Smoke test catalogue (`scripts/smoke-test.sh`)

| ID | Case | Expected |
|---|---|---|
| T01 | HSM health | 200, `UP` |
| T02 | Create customer (MASS) | 200, id returned |
| T03 | Duplicate CIF | 409 `DUPLICATE` |
| T04 | Open PREPAID EGP account | 200 |
| T05 | Eligible products for PREPAID × MASS | exactly `P01` |
| T06 | Issue P02 on that account | `PRODUCT_NOT_ELIGIBLE` |
| T07 | Issue P01 | 200, `PENDING_PRINT`, full PAN returned once |
| T08 | Issue a second P01 | `LIMIT_REACHED` |
| T09 | Dexxis search by PAN | 200, track 2 and 3-digit CVVs |
| T10 | Search unknown valid PAN | 404 |
| T11 | Search malformed PAN | `INVALID_REQUEST` |
| T12 | Activate with PIN block | `ACTIVE`, PVV stored |
| T13 | Search the now-active card | `INVALID_STATUS` (no re-perso) |
| T14 | Cancel a pending card | `CANCELLED` |
| T15 | Activate the cancelled card | `INVALID_STATUS` |

## 4. Manual UI cases (CMS Console, `/`)

| ID | Steps | Expected |
|---|---|---|
| UI-01 | First visit | Operator id prompt; the id is shown in the header and recorded on every change |
| UI-02 | Issue card → New customer, type the full name only | Name on card fills in uppercase, max 26, and stops following once edited |
| UI-03 | New customer with an invalid name on card (digits) | Clear error, nothing created |
| UI-04 | Setup → Numbering: CIF source = CMS_GENERATED, then create a customer | CIF field hidden; CIF assigned from the CIF sequence |
| UI-05 | Setup → Account types: CURRENT = Core banking, EGP + USD, max 1. Open CURRENT in AED, then two in EGP | AED not offered; account number required; the second CURRENT is refused with the limit |
| UI-06 | Issue a card on an account type with no eligible product | The product list says no product is set up for that combination |
| UI-07 | Issue card | Full PAN shown once with a copy button; Hide number leaves only the masked PAN |
| UI-08 | Card page of a pending card → Change status | Only Cancel card is offered |
| UI-09 | Suspend a customer without a reason | Blocked with Reason is required; with a reason the badge turns Suspended |
| UI-10 | Close an account that has a live card | Refused: cancel the live card first |
| UI-11 | Cards → Find by full card number, unknown PAN | Card not found; the PAN never appears in the URL |
| UI-12 | Setup → Card products → P01: change eligibility, save | The Issue card product list follows the new matrix |
| UI-13 | Audit log, filter by action | Entries show actor, record link and details; no PAN anywhere |
| UI-14 | Phone width (390 px) and dark mode | No horizontal scroll; the stepper is vertical; text stays readable |

## 5. Fault-injection cases (hsm-sim)

| ID | Start hsm-sim with | Action | Expected CMS behaviour |
|---|---|---|---|
| F01 | (simulator stopped) | Start CMS, call health | CMS starts; health 503 `DOWN` |
| F02 | (stop simulator while CMS runs) | Issue + Dexxis search | 503 `HSM_ERROR`; no partial card update; restart sim then works again |
| F03 | `SIM_DELAY_MS=3000` | Dexxis search | Times out after `read-timeout-ms` (2000) with `HSM_ERROR`; connection discarded |
| F04 | `SIM_FAIL=CW:10` | Dexxis search | 503 `HSM_ERROR ... CW / 10`; perso fetch count **not** incremented |
| F05 | `SIM_FAIL=JE:20` | Activate | 503; card stays `PENDING_PRINT` |
| F06 | header length 2 on sim, 4 on CMS | Health | `HSM header mismatch` |

## 6. Test run log

Add one row per deployment or release test. Newest first.

| Date | Version | Env | HSM | L1 | L2 | L4 | L5 | By | Notes |
|---|---|---|---|---|---|---|---|---|---|
| 2026-09-28 | 0.4.0 | build sandbox | hsm-sim 1.0.0 | n/a | PASS | n/a | n/a | tech lead | L2 passed; L3 verified manually (lazy start, HSM-down detection, PIN set/verify/change, CVV 561, 50 parallel calls on pool of 2). Full CMS build not compiled in sandbox: first `mvn verify` pending. |
