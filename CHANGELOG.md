# Changelog

All notable changes to cms-core and hsm-sim. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning: [SemVer](https://semver.org/), rules in [CONTRIBUTING.md](CONTRIBUTING.md).

Each version lists **Migrations** (database changes applied by Flyway on start) and **Breaking** (anything that needs action from whoever deploys or integrates) whenever present.

## [Unreleased]

### Added
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
