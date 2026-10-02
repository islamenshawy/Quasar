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

## 3a. Authorization catalogue (`scripts/auth-test.sh`)

Needs the **dev** profile (it calls `POST /api/dev/authorize`), hsm-sim and the seed. It creates its own customer, prepaid account and card (PIN 1234), funds 1000.00 and runs:

| ID | Case | Expected |
|---|---|---|
| A02-A04 | Balance inquiry: correct PIN / no PIN / wrong PIN | 000 with balances / 112 / 117 |
| A05-A06 | Withdraw 200.00, then resend the identical message | 000; the resend returns the same transaction id |
| A07-A09 | Over per-withdrawal limit / over available / USD on an EGP account | 121 / 116 / 119 |
| A10-A13 | Full reversal, repeated reversal, partial reversal (100.00 dispensed), unmatched reversal | 400 each; balance restored only where due |
| A14-A16 | PIN change; old PIN rejected; new PIN accepted | 000 / 117 / 000 |
| A17-A22 | POS purchase, cash at POS, pre-auth + completion, refund, e-commerce on a product without e-commerce | 000 / 902 / hold then capture / credit / 119 |
| A23-A24 | Card-level per-withdrawal override; card e-commerce on while product is off | 121 / 119 |
| A25-A26b | Blocked card; stand-in advice on it; advice in another currency | 104 / 000 posted / 000 not posted |
| A27-A28 | Third wrong PIN | 106 and card PIN_BLOCKED |
| A29-A30 | Statement closes at the ledger balance; transactions recorded | pass |

Ledger integrity after any run (all must return 0):

```sql
SELECT count(*) FROM (SELECT journal_id FROM posting GROUP BY journal_id HAVING sum(amount) <> 0) x;       -- unbalanced journals
SELECT count(*) FROM account a WHERE ledger_balance <> COALESCE((SELECT sum(amount) FROM posting p WHERE p.account_id = a.id), 0);
SELECT count(*) FROM account a WHERE held_amount <> COALESCE((SELECT sum(amount) FROM hold h WHERE h.account_id = a.id AND status = 'OPEN'), 0);
```

## 3b. BASE24 ISO catalogue (`scripts/iso-test.sh`)

Dev profile only. Every case goes through the real TCP interface via the corehost simulator.

| ID | Case | Expected |
|---|---|---|
| I01-I03 | ISO status, 1804 sign-on, echo | listening; 1814 with 800 |
| I04 | 1200 balance inquiry with PIN | 1210 / 000, field 54 with ledger and available |
| I05-I06 | 1200 cash, then its 1201 repeat | 000 with approval code; repeat gets the stored answer, one debit only |
| I07 | Wrong PIN | 117 |
| I08-I09 | 1420 partial reversal (40.00 of 100.00 dispensed) | 1430 / 400; balance reflects 40.00 |
| I10-I11 | 1100 pre-auth, 1220 completion | 1110 / 000, 1230 / 000 |
| I12 | e-commerce (field 22 card not present) on P01 | 119 |
| I13-I14 | PIN change with field 125, then new PIN | 000, 000 |
| I15-I16 | 1804/811 key change, then a PIN under the new ZPK | 800, 000 |
| I17-I18 | Messages recorded; seeded ZPK restored | pass |
| I19-I20 | Chip balance inquiry and cash with a valid ARQC | 000; field 55 tag 91 ARPC valid, ARC 00 |
| I21 | Chip with a tampered ARQC | 129, no ARPC |
| I22 | Chip with a replayed ATC | 129, ARPC with ARC 05 |

## 3c. Security catalogue (`scripts/security-test.sh`)

Dev profile (dev users). Every test script signs in: operator for changes, supervisor for approvals (`CMS_USER`, `CMS_PASSWORD`, `SUP_USER`, `SUP_PASSWORD`, `CMS_DEXXIS_API_KEY` override the dev defaults).

| ID | Case | Expected |
|---|---|---|
| S01-S03 | Anonymous call, public version, wrong password | 401, 200, 401 |
| S04-S07 | Viewer reads / writes; operator changes setup; operator opens Users | 200, 403, 403, 403 |
| S08-S14 | Supervisor setup change; same supervisor approves; operator approves; reject without reason; second supervisor approves; change applied; invalid change | 202, FOUR_EYES, 403, 422, APPROVED, applied, 422 at submission |
| S15-S22 | New user with temporary password; forced change; weak password; lock after 5 failures; admin reset unlocks | as named |
| S23-S26 | Console session: sign-in with CSRF token; POST without / with token; /api/auth/me | 200, 403, 200, operator |
| S27-S29 | Dexxis without key / with key; operator on Dexxis API | 401, reaches service (404), 403 |
| S30 | Supervisor changes approval policy | 403 (admin only) |

## 3d. Card lifecycle catalogue (`scripts/lifecycle-test.sh`)

Dev profile (uses the dev time helpers to age cards and holds).

| ID | Case | Expected |
|---|---|---|
| L01-L04 | Damaged card replaced with the same number; PSN 01; second replacement; Dexxis search | PENDING_PRINT, points to old card; DUPLICATE; Dexxis gets PSN 01 |
| L05-L08 | Old card before / after the replacement is activated (new PIN) | works; old CANCELLED; the number authorizes on the new card |
| L09-L12 | Lost card: keep number refused; new number; old card | INVALID_REQUEST; new PAN; old LOST, declines 208 |
| L13-L14 | Show number for printing 3 times, then again | PAN shown; 4th refused (LIMIT_REACHED) |
| L15-L17 | Operator runs a job; card past expiry after CARD_EXPIRY | 403; EXPIRED |
| L18-L21 | Card expiring within 30 days; CARD_RENEWAL twice | renewal waiting for print, same number, PSN 01; no duplicate |
| L22-L23 | Card pending print for 40 days; STALE_PENDING_PRINT | CANCELLED |
| L24-L26 | Pre-auth hold expired; HOLD_EXPIRY | funds available again |
| L27 | Job list | last runs recorded |

## 3e. Core banking catalogue (`scripts/core-test.sh`)

Runs against the DEV core banking simulator (`/api/dev/core-sim`); P02 card on a `CORE_CURRENT` account.

| ID | Case | Expected |
|---|---|---|
| C00-C01 | Core connected; P02 card on a core account activated | up; ACTIVE |
| C02-C08 | Balance inquiry, withdrawal, insufficient funds, reversal, pre-auth, capture, refund | balances are core's; 116 on insufficient funds; reversal restores core |
| C09-C10 | Account blocked in core; live balance for the account page | 119; APPROVED with balance |
| C11-C14 | Core down: balance inquiry, withdrawal within / over stand-in limit, reversal | 911; 000 stand-in; 911; 400 |
| C15-C18 | Queue holds debit + reversal; replay while down retries the debit and keeps the reversal behind it; replay when back sends both in order | balance unchanged after replay |
| C19-C22 | Stand-in purchase, cancel queued posting (maker-checker), replay | cancelled posting never reaches core |
| C23-C24 | Nothing pending; manual ledger entry on a core account | refused (INVALID_REQUEST) |

## 3f. Fraud rules catalogue (`scripts/fraud-test.sh`)

Test rules use fixed codes `TEST_*`, scoped to product P01, and are switched off at the end (the decline score is reset to 100).

| ID | Case | Expected |
|---|---|---|
| F00-F03 | Setup; starter rules inactive; rule change needs approval by a second supervisor | approvalPending, APPROVED |
| F04-F05 | Gambling MCC rule (DECLINE); other MCC | 102; 000 |
| F06-F07 | Advice hitting a DECLINE rule | 000, alert with action ALERT |
| F08-F10 | Foreign acquirer rule (ALERT 30); domestic purchase | 000 + alert score 30; no new alert |
| F11-F12 | Two alert rules 30 + 80 reach decline score 100; with score 200 | 102 (score 110); 000 |
| F13 | Velocity: one more than the allowed count in 10 minutes | 102 |
| F14-F15 | DECLINE_BLOCK rule on MCC 4829 | 102 and card BLOCKED |
| F16-F19 | Open count; take; note; false positive with 1 h pause | assignedTo, note stamped, FALSE_POSITIVE |
| F20 | Paused card passes the blocking rule | 000 |
| F21-F23 | Confirm fraud as LOST; other open alerts closed; resolve twice | LOST, 0 open, INVALID_STATUS |

## 3g. Fees and FX catalogue (`scripts/fees-test.sh`)

Plan `TEST_FEES`, product `PFEE` (BIN 999998, SAVINGS × MASS) and rate USD/EGP 48.5 are created or updated on each run.

| ID | Case | Expected |
|---|---|---|
| E00-E03 | Plan, rate and product changes need a second supervisor | approvalPending, APPROVED; product on TEST_FEES, FX on |
| E04 | Card issued on a funded account | issuance fee 20.00 charged |
| E05-E08 | Balance inquiry fee; first withdrawal free; second pays 5.00; reversal returns the fee | balances 979.00, 879.00, 774.00, 879.00 |
| E09-E10 | Domestic purchase; international purchase (country 840) | no fee; 1% fee |
| E11-E13 | USD 10.00 purchase: EGP 485.00 + FX 3% + intl 1%; transaction record; full reversal | 504.40 debited; billing 48500, rate 48.5, FX fee 1455; all returned |
| E14 | AED purchase without a rate | 119 "no FX rate AED/EGP" |
| E15-E17 | FEE_PERIODIC twice: monthly fee after the first day; annual fee on the anniversary | each charged once |
| E18-E19 | Replacement fee; card fee history | 15.00; ANNUAL, ISSUANCE, MONTHLY |

## 3h. Notifications and OTP catalogue (`scripts/notify-test.sh`)

Uses the DEV LOG provider and its sink (`/api/dev/notifications/sink`); the dispatcher is triggered with `/api/dev/notifications/dispatch`.

| ID | Case | Expected |
|---|---|---|
| N00 | Starting templates | TXN_APPROVED SMS in EN and AR |
| N01-N04 | Card issued, sent, text, activated | CARD_ISSUED SMS, SENT, name + last 4 only, CARD_ACTIVATED |
| N05-N06 | Withdrawal approved; insufficient funds | amount and balance; TXN_DECLINED with reason |
| N07-N10 | Preferences SMS+e-mail, Arabic, minimum 500.00; below / above the minimum; alerts off | nothing; Arabic SMS + e-mail; nothing |
| N11-N12 | PIN blocked; operator unblocks | CARD_STATUS even with alerts off |
| N13-N14 | Gateway failing; resend after recovery | PENDING with attempt and error; SENT |
| N15-N21 | OTP send (masked mobile), wrong code, right code, reuse, redaction, lock after 3, rate limit | 2 tries left, VERIFIED, false, ******, LOCKED, LIMIT_REACHED |
| N22 | Channel API with a wrong key | 401 |
| N23-N25 | Template change needs approval; OTP text without {{code}} | APPROVED; INVALID_REQUEST |

## 3i. Chip completeness catalogue (`scripts/chip-test.sh`)

Needs hsm-sim 1.2.0 (KU). Script and contactless cases go through BASE24 with the switch simulator's emulated chip; P02 is switched to CVN17 with CVV checks and restored at the end.

| ID | Case | Expected |
|---|---|---|
| K00-K03 | Setup; queue PIN unblock; queue it twice; magstripe transaction | QUEUED; DUPLICATE; not delivered |
| K04-K06 | Chip transaction; script recorded; next chip transaction | ARPC valid, MAC valid; SENT with 8424000004; APPLIED from 9F5B |
| K07-K09 | Two scripts in one answer; chip refuses one | both MACs valid; FAILED; the others APPLIED |
| K10-K11 | Cancel a queued script | CANCELLED, not delivered |
| K12-K18 | Contactless: 100 no PIN; 700 no PIN; 700 with PIN; four 500 taps; one more; after a PIN; over 5,000 | 000; 112; 000; 000; 112; 000; 121 |
| K19 | Contactless off on the card | 119 |
| K20-K21 | P02 on CVN17; chip transaction | ARPC valid |
| K22-K25 | Magstripe read with CVV1 / with iCVV; chip read with iCVV / with CVV1 | 000; 129; 000; 129 |
| K26-K28 | E-commerce without CVV2; wrong CVV2; right CVV2 | 129 "CVV2 required"; 129 "CVV2 mismatch"; 000 |

## 4. Manual UI cases (Quasar console, `/`)

| ID | Steps | Expected |
|---|---|---|
| UI-01 | First visit | Sign-in page; after sign-in the user menu shows name and roles; every change is recorded under that user |
| UI-01b | Operator posts a funding entry; supervisor opens Approvals | Operator sees "Sent for approval"; supervisor sees the badge and approves; the maker cannot approve |
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
