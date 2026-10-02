# Changelog

All notable changes to cms-core and hsm-sim. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning: [SemVer](https://semver.org/), rules in [CONTRIBUTING.md](CONTRIBUTING.md).

Each version lists **Migrations** (database changes applied by Flyway on start) and **Breaking** (anything that needs action from whoever deploys or integrates) whenever present.

## [Unreleased]

### Added
- **Fraud and risk rules** (CMS-095): every online card transaction is screened after the PIN check. Conditions (all optional, combined): transaction type, channel, product, amount range, MCC in / not in, acquirer country in / not in, foreign, first use in a country, time-of-day window, card age, velocity (count or spend in a window), recent declines. Actions: ALERT, DECLINE (**102 suspected fraud**), DECLINE_BLOCK (also blocks the card). Scores add up; a total at or above `fraud.decline_score` declines. Advices are scored but never declined. Seven starter rules are installed **inactive**. Rule changes go through maker-checker (`FRAUD_RULE_SAVE`).
- **Fraud desk**: alert queue with take, notes, confirm fraud (card becomes BLOCKED / LOST / STOLEN, the card's other open alerts close), false positive (optional 1 h - 3 days rule pause for the card) and close (`FRAUD_ALERT_RESOLVE`, direct by default). Console pages Fraud alerts (badge with open count) and Setup → Fraud rules with a plain-language preview; command bar action.
- Settings with free values: `institution.country` (domestic vs foreign) and `fraud.decline_score`, editable under Setup → Numbering & settings (maker-checker).
- BASE24 field 19 (acquiring institution country) mapped and recorded; switch simulator can send MCC and country. `scripts/fraud-test.sh` (24 cases).
- **Core banking funds interface** (CMS-090): cards on accounts whose type is `CORE_BANKING` are authorised against core banking: balance inquiry, debit (amount + fee), pre-authorisation hold and capture, refund (credit), reversal and hold release. Core decline reasons map to action codes (insufficient funds 116, closed / unknown account 114, blocked 119, limit 121). **Contract provisional until the bank's API spec (IN-06).**
- **Stand-in (STIP)** per product (`core_stip_limit`): when core does not answer, approve up to the limit per transaction (balance inquiry never), above it 911 "card issuer timed out". Stand-in approvals, reversals, releases and advices are queued in `core_saf` and replayed by the new **CORE_SAF_REPLAY** job every minute, in order per account, with backoff; failed ones wait for operations.
- Console: **Core banking** page (connection, answer time, queue with retry and cancel; simulator controls on DEV), **Core** status light in the top bar, live core balances and the account's queued postings on core accounts, stand-in limit on the product. Cancelling a queued posting needs approval (`CORE_SAF_CANCEL`).
- DEV: in-process core banking simulator `/api/dev/core-sim` (accounts, outage, latency); `scripts/core-test.sh` (25 cases).
- Channel API key rule (`/api/channel/**`, `CMS_CHANNEL_API_KEY`) for ACS / mobile / IVR clients (used from CMS-105).
- **EMV chip cryptograms**: ARQC verification and ARPC generation through the HSM (`KQ`), EMV option A card keys from the product's IMK-AC, cryptogram versions Visa CVN10 (card key) and EMV common session key (M/Chip, Visa CVN18). The data block is built from field 55 in the order of the product's data list (`9F10:CVR` takes the Visa CVR). A wrong ARQC declines 129; an ATC not above the last one seen on the card declines 129 (replay). Every answer to a verified chip request carries field 55 tag 91 = ARPC + ARC (00 approved, 05 declined). `iso_transaction.emv_arqc_ok` is recorded. **KQ field layout and the data list are provisional until IN-03 / the chip profile are confirmed.** (CMS-057)
- **hsm-sim 1.1.0**: `KQ` (modes 0/1/2, schemes 0/1), test key IMK_AC_P01, self-test cases.
- Corehost simulator: chip mode builds field 55 with an ARQC from the dev IMK-AC (card emulation, dev only) and checks the returned ARPC; console Switch simulator has a Chip (EMV) switch. Product setup: cryptogram version and data list.
- `scripts/iso-test.sh`: chip cases I19-I22 (valid ARQC with ARPC check, chip withdrawal, wrong ARQC, replayed ATC).
- **DE55 decoder** in the Switch simulator: request and response field 55 broken down per tag with name, description and interpretation, and bit by bit for TVR, TSI, AIP, AUC, terminal capabilities, CVM results, CID and the Visa IAD/CVR; ARPC/ARC for tag 91. "Decode any DE55" accepts pasted hex (PAN and track 2 masked). The simulated terminal now sends a realistic profile (AID, terminal type and capabilities, CVM results, TSI, AUC, IFD serial, sequence counter) and a selectable TVR scenario.

### Changed
- Manual ledger entries are refused on core banking accounts (the money is not in the CMS ledger).
- **Brand: the console is now Quasar.** New design system from the double-quasar palette (nebula indigo, quasar blue, magenta flare, coral, deep space): "Quasar Light" as the default theme and a matching deep-space dark theme, a starfield header with status lights, a frosted navigation drawer, gradient actions, rounded surfaces, and the Space Grotesk / Inter / JetBrains Mono fonts (self-hosted, no external calls). New double-quasar logo mark and favicon, a nebula sign-in screen (motion off when the OS asks for reduced motion), dashboard tiles with gradient icons and a greeting, and the card preview in brand colours. Page titles read "· Quasar".
- **Layout v2.** Q-mark logo: a quasar seen at an angle (accretion disk = bowl of the Q, relativistic jet = tail). Navigation moves to a deep-space **orbit rail** (icons with tooltips; pin open to show labels; remembered per browser; full drawer on small screens). New top bar with breadcrumb, system pulse lights, avatar menu. **Command bar** (Ctrl+K or `/` anywhere): jump to pages, run actions (issue card, new customer, theme, declines, waiting for print), and find cards (last 4, name), customers (CIF, name) and accounts (number) with keyboard navigation. Dashboard **pulse hero**: today's approval rate drawn as an accretion disk, approved / declined / message counts, main actions.
- HSM messages are exchanged byte for byte (ISO-8859-1 instead of US-ASCII) so binary fields pass unchanged; ASCII commands are unaffected.
- A stored PAN that cannot be decrypted (CMS started with a different `CMS_PAN_ENC_KEY` from the one the card was issued under) is answered `503 PAN_KEY_MISMATCH` with an explanation instead of a bare 500.
- Startup check of the PAN keys against the newest live cards: mismatches are logged with the card ids; outside dev the CMS refuses to start when the keys match none of them (`CMS_PAN_KEY_CHECK`, FAIL by default, WARN on dev).
- Admin API: unexpected errors return `{"code":"INTERNAL_ERROR"}` with a reference that matches the log line.
- BASE24: a request that fails outside the authorization engine is now answered (904 when it cannot be mapped, 909 otherwise) instead of being left to time out at the switch.

### Fixed
- `scripts/smoke-test.sh` contained a second copy of itself since CMS-060 (an edit pasted the rest of the file), so bash ended with a syntax error after the 15 cases and CI's syntax check failed. Restored.
- Dev switch simulator: after a CMS restart it went back to the seed acquirer ZPK while the CMS kept the key from the last Key exchange, so every PIN transaction failed with HSM EC error 20 (909). The simulator now compares check values before sending and re-runs the key exchange when they differ; its status shows both KCVs.

### Migrations
- `V10__fraud_rules.sql`: `fraud_rule` (7 inactive starter rules), `fraud_alert`, `iso_transaction.acquirer_country` / `fraud_score` / `fraud_rules`, `card.fraud_exempt_until`, settings `institution.country` = 818 and `fraud.decline_score` = 100, approval actions.
- `V9__core_banking_interface.sql`: `card_product.core_stip_limit`, `iso_transaction.core_ref` / `stand_in`, `hold.core_hold_ref`, `core_saf` queue, batch job CORE_SAF_REPLAY, approval action CORE_SAF_CANCEL. Seed: account type `CORE_CURRENT` (core banking) eligible for P02, P02 stand-in 500.00 (rerun `seed-dev.sql` on DEV).
- `V8__emv_cryptograms.sql`: `card_product.emv_scheme`, `card_product.emv_data_list`, `card.last_atc`.
- Seed: `scripts/seed-dev.sql` adds IMK_AC_P01 and links it to P01 and P02 (rerun it on DEV).

- **Card replacement**: reasons RENEWAL, DAMAGED, LOST, STOLEN, NOT_RECEIVED, OTHER; same card number with the next PSN and a new expiry, or a new number (always for lost / stolen / not received, which also blocks the old card at once). The old card works until the replacement is activated at the kiosk, then it is cancelled. Channel switches and limit overrides carry over. Maker-checker action CARD_REPLACE (direct by default). (CMS-080)
- **Renewal**: product settings auto-renew, lead days, same number; the CARD_RENEWAL job creates renewals that wait for print. (CMS-081)
- **Batch scheduler**: jobs with cron schedules and run history, one run at a time (also across instances), run now by a supervisor, schedule changes through maker-checker (BATCH_JOB_UPDATE). Jobs: CARD_EXPIRY, CARD_RENEWAL, HOLD_EXPIRY, STALE_PENDING_PRINT (product limit, default 30 days), USAGE_CLEANUP. (CMS-082, CMS-083)
- "Show number for printing": the full PAN of a card waiting for print, audited (CARD_PAN_REVEAL), at most 3 times per card, for replacements and renewals keyed into Dexxis.
- Console: Replace card, replacement links and PSN on the card page, Batch jobs page, product renewal settings.
- `scripts/lifecycle-test.sh`: 27 cases; dev-only time helpers `/api/dev/cards/{id}/age` and `/api/dev/accounts/{id}/expire-holds`.

### Fixed
- Dexxis perso data returned PSN `00` for every card; it now returns the card's PSN.

### Changed
- A PAN may belong to several cards (different PSN). Dexxis search / activate / cancel use the card waiting for print; authorization uses the card whose expiry matches the terminal data, otherwise the active one.

### Migrations
- `V7__card_lifecycle_and_batch.sql`: replacement columns on `card`, unique (pan_hash, psn) instead of unique pan_hash, product renewal settings, `batch_job` (seeded) and `batch_run`, approval actions CARD_REPLACE and BATCH_JOB_UPDATE.

- **Operator sign-in and roles** (Spring Security): users with BCrypt passwords, roles ADMIN / SUPERVISOR / OPERATOR / VIEWER, lock after 5 failed sign-ins, temporary passwords that must be changed at first sign-in, password policy (10+ characters, upper, lower, digit, symbol). Console uses a session cookie with CSRF protection; scripts may use HTTP Basic. The operator on every audit row is now the signed-in user. (CMS-060)
- First start creates `admin` (from `CMS_ADMIN_PASSWORD`, or a generated password written to the log once). The dev profile also creates admin, supervisor, supervisor2, operator and viewer. (CMS-060)
- **Maker-checker**: per-action approval policy (setup changes, ledger entries, hold releases and card limits need approval by default; status changes and PIN-try resets are direct but can be switched on). Requests are validated at submission, approved or rejected by a different SUPERVISOR/ADMIN, and executed as the maker. Console: Approvals page with a pending badge, Approval policy page. (CMS-060)
- Console: sign-in page, forced password change, user menu, Users page (ADMIN), role-aware buttons.
- `scripts/security-test.sh`: 30 cases (roles, four-eyes, lock-out, CSRF, Dexxis key).

### Breaking
- **Dexxis must send `X-Api-Key`** (`CMS_DEXXIS_API_KEY`); without it `/api/dexxis/**` answers 401.
- Admin API needs authentication; `X-Operator` is ignored. Writes that need approval answer **202** with `{approvalPending, requestId}` instead of the result.

### Migrations
- `V6__users_roles_approvals.sql`: `app_user`, `user_role`, `approval_policy` (seeded), `approval_request`. No users are created by the migration.

- **BASE24 ISO 8583:1993 interface** (`com.cms.iso`): TCP server for the ATM/POS switch (port 7000), 2-byte or 4-ASCII length prefix, optional static header, jPOS packager from `iso/base24-1993.xml`. Each message is processed on its own virtual thread. **Layout and mapping are provisional until the BASE24 spec arrives (IN-01).** (CMS-051, CMS-052)
- Message mapping: 1100/1120 pre-auth, 1200/1220 financial and advices, 1220 completion of a 1100, 1420/1421 reversal with partial amount (fields 30 + 4), repeat MTIs answered from the stored response, field 54 balances in responses, field 125 new PIN block. (CMS-053)
- Network management 1804/1814: sign-on 801, sign-off 802, echo 831, dynamic acquirer ZPK exchange 811 (ZPK under ZMK in field 96, HSM FA, KCV check, new key version, old version retired). (CMS-054)
- Corehost simulator (dev profile): `/api/dev/iso/send` and `/api/dev/iso/network` build real messages with ISO-0 PIN blocks and send them over TCP; console page **Dev tools → Switch simulator** with repeat, reverse and complete. `scripts/iso-test.sh`: 18 cases over the real TCP path. (CMS-055)
- `GET /api/admin/iso/status` and a Switch chip in the console header.

### Changed
- Reversals and completions match the original on either field 7 (transmission date-time) or field 12 (local date-time), since BASE24 1993 field 56 carries the local date-time.

- **Authorization engine** (`com.cms.auth`): balance inquiry, ATM withdrawal, POS/e-commerce purchase, pre-authorisation with hold, completion, refund, PIN change, full and partial reversal, stand-in advices. ISO 8583:1993 action codes. One database transaction per request with card and account rows locked; retransmissions get the stored response. (CMS-040, CMS-041, CMS-042, CMS-046, CMS-047, CMS-048)
- Checks in order: card found, status, expiry, customer and account status, channel, track 2 / CVV (optional per product, HSM CY), PIN via HSM EC with try counter and automatic PIN_BLOCKED, transaction/channel fit, currency, limits (per transaction, daily amount, daily count; ATM and POS separately), funds. (CMS-041)
- **Ledger** (`com.cms.ledger`): journals over double-entry postings, per-currency GLs created on first use (ATM cash, POS settlement, fee income, funding suspense, adjustments), holds with expiry, statements with running balance. (CMS-046, CMS-049)
- Product usage settings: ATM / POS / e-commerce switches, purchase limits, ATM withdrawal and balance-inquiry fees, CVV check flag, pre-auth hold days. Card-level channel switches and limit overrides. (CMS-050)
- Console: Transactions page with detail, account Ledger tab (statement, holds with release, transactions), Post entry (funding, credit/debit adjustment), card Controls & limits with today's usage, Reset PIN tries, GL accounts page, today's approvals/declines/volume on the dashboard. (CMS-050)
- `POST /api/dev/authorize` (dev profile only) and `scripts/auth-test.sh`: 32 authorization scenarios end to end. (CMS-040)
- HSM client: `CY` CVV verification.

### Migrations
- `V5__authorization_and_ledger.sql`: product channels/fees/purchase limits/CVV flag/hold days, card controls and limit overrides, POS usage counters, transaction types, advice/fee/balance-after/merchant/original columns, `journal`, hold capture fields and `EXPIRED`, GL types and standard GLs per currency, `auth_id_seq`. Existing products keep their behaviour: purchase amount limits fall back to the ATM limits while empty, fees are zero, e-commerce is off.

- GitHub Actions CI: hsm-sim self-test, `mvn verify`, smoke-script syntax check, jar artifact per build. (CMS-037)
- Pull request template enforcing item ID, migrations, tests, security and docs checks. (CMS-037)
- `docs/GITHUB.md`: repository setup, authentication, push, branch protection, issue tracking, releases. (CMS-037)
- **CMS Console** (`ui/`, Vue 3 + Quasar), served at `/` from the same jar: dashboard, issue-card wizard, customers, accounts, cards, setup screens and audit log. Built by Maven through frontend-maven-plugin; `-DskipUi` builds the backend only. (CMS-070)
- Reference data maintained from screens, no SQL: currencies, customer segments, account types, card products (BIN range, keys, limits) and the product eligibility matrix. Rows are deactivated, never deleted. API under `/api/admin/setup/*`. (CMS-071)
- CIF and account numbering: `number_sequence` (prefix, length, next value, optional Luhn digit) and setting `cif.source` (CMS_GENERATED / CORE_BANKING / EITHER). Each account type is either CMS_GENERATED from a chosen sequence or CORE_BANKING (number typed by the operator). (CMS-072)
- Account type rules: allowed currencies (`account_type_currency`) and max open accounts per customer. (CMS-072)
- Customer maintenance: paged search with status/segment filters, edit, suspend / reactivate / close with reason; audit records field-level changes. (CMS-073)
- Account maintenance: paged search, balances, debit-block / block / reactivate / close (close needs zero balance, no holds, no live cards). (CMS-073)
- Card maintenance: paged search, find by full PAN (POST body, not logged), detail with status history, block / unblock / lost / stolen / cancel with reason. Pending cards can only be cancelled by an operator. (CMS-074)
- Audit log viewer and dashboard figures: `GET /api/admin/audit`, `GET /api/admin/dashboard`. (CMS-075)

### Changed
- `/issuance.html` redirects to the console's Issue card screen (`/#/issue`). (CMS-070)
- Account onboarding moved from `CustomerService` to the new `AccountService`. (CMS-073)

### Migrations
- `V4__configurable_reference_data.sql`: currency name/active, segment description, `number_sequence` (CIF, and ACCOUNT continuing from `account_number_seq`), `cms_setting`, account type numbering/limits, `account_type_currency` (back-filled with every existing type × currency), status reason / updated_by columns, account status `BLOCKED`, audit indexes. Existing behaviour is unchanged until setup is edited.

### Breaking
- Admin API: `GET /api/admin/customers` now returns a page `{items, total, page, size}` instead of a list (the old screen was its only caller).
- Admin API: `GET /api/admin/reference` returns full objects for segments, account types and currencies (code and name are still present).

## [0.4.0] - 2026-09-28

### Added
- **hsm-sim 1.0.0** (`hsm-sim/HsmSimulator.java`): payShield host-command simulator, single file, no build. Supports NC, FA, JE, JG, BA, DG, EC, CW, CY with real 3DES, ISO-0, Visa PVV and Visa CVV algorithms. Includes `seed`, `import`, `pinblock` and `selftest` tools and fault injection (`SIM_DELAY_MS`, `SIM_FAIL`). (CMS-030)
- HSM health endpoint `GET /api/admin/hsm/health` using NC. (CMS-031)
- Version endpoint `GET /api/version`, backed by Maven build-info. (CMS-034)
- `dev` Spring profile (`application-dev.yml`) pointing at hsm-sim. (CMS-033)
- `scripts/seed-dev.sql`: test keys, products P01/P02, eligibility. (CMS-033)
- `scripts/smoke-test.sh`: 15 end-to-end cases. (CMS-033)
- Documentation set: DEPLOYMENT, TESTING, DECISIONS, ROADMAP, CONTRIBUTING, this changelog. (CMS-034)

### Changed
- HSM connections are opened lazily with a semaphore-bounded pool. The CMS now starts when the HSM is down; broken connections are discarded and replaced on next use. (CMS-031)
- Project version set to 0.4.0 (was 0.1.0-SNAPSHOT).

### Breaking
- None for API consumers. Deployers must now use the `dev` or `test` profile (see DEPLOYMENT §4).

## [0.3.0] - 2026-09-28

### Added
- Customer segments, account types (with `ledger_mode`), card product type/tier/scheme, per-account card limit, product eligibility matrix. (CMS-020)
- Operator issuance flow: create customer, open account, issue card. (CMS-021)
- Dexxis search by PAN returning perso data; activation and cancel by PAN (temporary REST). (CMS-022)
- Issuance screen `/issuance.html` and admin REST API. (CMS-023)

### Changed
- Card creation moved from Dexxis to the CMS operator (ADR-010, supersedes ADR-006).

### Migrations
- `V3__segments_and_eligibility.sql`

### Breaking
- `CreateCard` called by Dexxis removed; Dexxis now searches by PAN.

## [0.2.0] - 2026-09-28

### Added
- Maven project (Java 21, Spring Boot 3, jPOS, PostgreSQL, Flyway). (CMS-010)
- PAN allocation from product account range with row lock and Luhn; PAN encryption and lookup hash. (CMS-011)
- Card issuance service: create, activate with PIN set, cancel. (CMS-012)

### Migrations
- `V2__account_sequence.sql`

## [0.1.0] - 2026-09-28

### Added
- Initial schema: products, customers, accounts, cards, status history, ISO transactions, double-entry postings, holds, daily usage, HSM keys, audit log. (CMS-001)
- payShield client with NC, FA, JE, DG, EC, CW. (CMS-002)
- PinService: set, verify and change PIN via the HSM. (CMS-003)

### Migrations
- `V1__init.sql`
