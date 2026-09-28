# Changelog

All notable changes to cms-core and hsm-sim. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning: [SemVer](https://semver.org/), rules in [CONTRIBUTING.md](CONTRIBUTING.md).

Each version lists **Migrations** (database changes applied by Flyway on start) and **Breaking** (anything that needs action from whoever deploys or integrates) whenever present.

## [Unreleased]

### Added
- GitHub Actions CI: hsm-sim self-test, `mvn verify`, smoke-script syntax check, jar artifact per build. (CMS-037)
- Pull request template enforcing item ID, migrations, tests, security and docs checks. (CMS-037)

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
