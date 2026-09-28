# Roadmap and Backlog

Every piece of work has an ID (`CMS-nnn`). Commits, branches and changelog entries reference it, so any line of code can be traced back to why it exists.

Status: **Done**, **In progress**, **Next**, **Blocked** (waiting for an input), **Planned**.

Last updated: 2026-09-28. Current version: **0.4.0**.

## Blocked inputs (owner: product side)

| Ref | Input needed | Unblocks |
|---|---|---|
| IN-01 | BASE24 ISO 8583:1993 interface spec + masked sample traces (withdrawal, reversal, PIN change, key exchange) | CMS-051 to CMS-055 |
| IN-02 | Dexxis WSDL or sample SOAP messages (search by PAN, activation, cancel) | CMS-056 |
| IN-03 | Who derives EMV ICC keys: Dexxis HSM from IMK, or CMS? | CMS-057, CMS-058 |
| IN-04 | Track 2 discretionary data layout per product | CMS-059 |
| IN-05 | payShield config: LMK type (variant/key block), header length, PIN block format | CMS-043 |
| IN-06 | Kiosk PIN entry key (TPK or ZPK) and format | CMS-044 |
| IN-07 | Real segments, account types, products and eligibility matrix | CMS-045 |

## Released

| ID | Item | Version | Status |
|---|---|---|---|
| CMS-001 | Database schema: cards, accounts, ledger, transactions, keys, audit | 0.1.0 | Done |
| CMS-002 | payShield client: NC, FA, JE, DG, EC, CW | 0.1.0 | Done |
| CMS-003 | PinService: set / verify / change PIN | 0.1.0 | Done |
| CMS-010 | Maven project, config, application wiring | 0.2.0 | Done |
| CMS-011 | PAN allocation (account range + Luhn), PAN encryption/hash | 0.2.0 | Done |
| CMS-012 | Card issuance service (create / activate / cancel) | 0.2.0 | Done |
| CMS-020 | Customer segments, account types, product eligibility (V3) | 0.3.0 | Done |
| CMS-021 | Operator issuance: customer → account → card | 0.3.0 | Done |
| CMS-022 | Dexxis search by PAN (temporary REST) | 0.3.0 | Done |
| CMS-023 | Issuance screen `/issuance.html` + admin API | 0.3.0 | Done |
| CMS-030 | hsm-sim 1.0.0: payShield simulator + tooling | 0.4.0 | Done |
| CMS-031 | Lazy HSM connections + HSM health endpoint | 0.4.0 | Done |
| CMS-033 | Dev profile, seed script, smoke test (15 cases) | 0.4.0 | Done |
| CMS-034 | Version endpoint + build info; docs set; versioning process | 0.4.0 | Done |

## 0.4.x — stabilise (next)

| ID | Item | Status |
|---|---|---|
| CMS-035 | First full `mvn verify` on a developer machine; fix compile issues | Next |
| CMS-032 | L3 HSM integration test as JUnit (starts hsm-sim in-process) | Next |
| CMS-036 | Run smoke test on DEV and TEST; record in TESTING.md | Next |

## 0.5.0 — Authorization engine and ledger

| ID | Item | Status |
|---|---|---|
| CMS-040 | Internal auth request/response model (independent of BASE24) | Planned |
| CMS-041 | Card checks: status, expiry, service code, PIN tries, PIN_BLOCKED after limit | Planned |
| CMS-042 | Balance inquiry | Planned |
| CMS-046 | Withdrawal: limits, velocity, available balance, hold, posting against ATM cash GL | Planned |
| CMS-047 | PIN change (verify old, new PVV, atomic update) | Planned |
| CMS-048 | Reversals: full and partial, idempotent, duplicate detection | Planned |
| CMS-049 | Account top-up / funding (admin), with double-entry postings | Planned |
| CMS-050 | Admin: block/unblock card, reset PIN tries, transaction view | Planned |
| CMS-043 | Key-block LMK support in HSM client | Blocked (IN-05) |
| CMS-044 | Kiosk PIN key handling (TPK vs ZPK) | Blocked (IN-06) |
| CMS-045 | Load real product catalogue and eligibility | Blocked (IN-07) |

## 0.6.0 — BASE24 interface

| ID | Item | Status |
|---|---|---|
| CMS-051 | BASE24 packager (jPOS) from spec | Blocked (IN-01) |
| CMS-052 | ISO server channel, header, sign-on / echo | Blocked (IN-01) |
| CMS-053 | Message mapping to auth engine, action codes | Blocked (IN-01) |
| CMS-054 | Dynamic ZPK exchange (FA) | Blocked (IN-01) |
| CMS-055 | Corehost simulator + BASE24 regression suite from traces | Blocked (IN-01) |

## 0.7.0 — Dexxis SOAP and EMV

| ID | Item | Status |
|---|---|---|
| CMS-056 | SOAP endpoint from Dexxis WSDL | Blocked (IN-02) |
| CMS-057 | ARQC verification / ARPC generation (KQ) + hsm-sim support | Blocked (IN-03) |
| CMS-058 | EMV data in perso response if CMS owns ICC key derivation | Blocked (IN-03) |
| CMS-059 | Final track discretionary data per product | Blocked (IN-04) |

## 0.8.0 → 1.0.0 — Hardening

| ID | Item | Status |
|---|---|---|
| CMS-060 | Operator authentication and roles (replace X-Operator header) | Planned |
| CMS-061 | PAN keys from KMS / HSM-wrapped store; key rotation | Planned |
| CMS-062 | mTLS / allow-list for Dexxis and corehost interfaces | Planned |
| CMS-063 | Reconciliation report (CMS vs corehost journal) | Planned |
| CMS-064 | High availability and failover behaviour | Planned |
| CMS-065 | Full L6 test pass on real payShield | Planned |
