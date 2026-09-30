# CMS Core — Card Management System

Issuer-side card management: operator-driven card issuance, perso data for Dexxis, PIN set/verify/change through a Thales payShield, and (next) ISO 8583 BASE24 authorization with a CMS-held ledger.

| | |
|---|---|
| Version | **0.4.0** (see [CHANGELOG](CHANGELOG.md)) |
| HSM simulator | hsm-sim **1.0.0** |
| Stack | Java 21, Spring Boot 3, jPOS, PostgreSQL, Flyway · console: Vue 3 + Quasar (`ui/`) |
| Environments | DEV and TEST only. **Test data only.** |

## Architecture

```
Operator ──> CMS Console (/)              Setup, customers, accounts, cards, audit; issue card (PENDING_PRINT)
Kiosk (NCR KGS) ──> Dexxis ──> CMS         Search card by PAN (perso data) / Activate + PIN / Cancel
ATM ──> Corehost ──BASE24──> CMS           Balance inquiry / Withdrawal / PIN change / Reversal   (0.6.0)
                              │
                              ├── payShield 10K, or hsm-sim in DEV/TEST (host commands over TCP)
                              └── PostgreSQL (customers, accounts, cards, ledger, audit)
```

## Documentation map

| Document | Read it when |
|---|---|
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Installing, running, deploying, upgrading, switching to the real HSM |
| [docs/TESTING.md](docs/TESTING.md) | Testing a build; test data; recording a test run |
| [docs/ROADMAP.md](docs/ROADMAP.md) | What's done, next and blocked, and which inputs are needed |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Why things are built the way they are |
| [docs/GITHUB.md](docs/GITHUB.md) | Publishing to GitHub, branch protection, issues, releases |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Branching, commits, versioning, migrations, Definition of Done |
| [CHANGELOG.md](CHANGELOG.md) | What changed in each version |

## Quick start (DEV)

```bash
# terminal 1: HSM simulator
java hsm-sim/HsmSimulator.java selftest && java hsm-sim/HsmSimulator.java serve 1500 4

# terminal 2: CMS (database "cms" created first, see DEPLOYMENT §3)
export SPRING_PROFILES_ACTIVE=dev CMS_DB_PASSWORD=change-me
mvn clean verify && java -jar target/cms-core-0.4.0.jar

# terminal 3: seed + verify
psql -h localhost -U cms -d cms -f scripts/seed-dev.sql
./scripts/smoke-test.sh
```

Then open the CMS Console at `http://localhost:8080/` and sign in (dev users: `admin`, `supervisor`, `operator`, `viewer`; password `Dev-Passw0rd!`). For console development with hot reload: `cd ui && npm install && npm run dev` (port 9000, API proxied to 8080).
