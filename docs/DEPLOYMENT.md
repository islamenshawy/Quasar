# Deployment Guide

| | |
|---|---|
| Applies to | cms-core **0.4.0** with the Quasar console (CMS-070 to CMS-075), hsm-sim **1.0.0** |
| Environments covered | DEV (developer laptop), TEST (shared test server) |
| Owner | Development chapter, tech lead |
| Last updated | 2026-09-30 |

> UAT and production are out of scope. They need operator authentication, a real payShield, a KMS for PAN keys, and PCI controls first (see [ROADMAP](ROADMAP.md)).

---

## 0. Environments at a glance

| | DEV | TEST |
|---|---|---|
| Where | Developer laptop (Windows or Linux) | Linux server (Ubuntu 22.04/24.04 LTS) |
| HSM | hsm-sim on `localhost:1500` | hsm-sim, or real payShield 10K with **test LMK** |
| Database | Local PostgreSQL | PostgreSQL on the server |
| How it runs | `mvn spring-boot:run` or `java -jar` | `systemd` services |
| Spring profile | `dev` | `test` (you create `application-test.yml`, see §9.3) |
| Quasar console | `http://localhost:8080/` (or `:9000` with `npm run dev`, §6.3) | `http://<server>:8080/` from the test subnet only |
| Data | Test data only | Test data only. **Never real cards, PANs or keys.** |

The Quasar console is part of the cms-core jar. There is no separate web server or front-end deployment.

---

## 1. What to install

| Tool | Version | Why |
|---|---|---|
| Git | any recent | Version control |
| JDK | **21** (Eclipse Temurin recommended) | Runs the CMS and hsm-sim |
| Maven | 3.9+ (3.8 works) | Builds the CMS |
| PostgreSQL | **15 or 16** | CMS database |
| curl, jq | any | Smoke test script |
| Internet access on the build machine | first build only | Maven downloads Node and the console's npm packages (§6.1) |
| Optional: Node.js | 20+ (24 used in development) | Only for console hot reload (`npm run dev`). Maven builds do **not** need it installed. |
| Optional: DBeaver or pgAdmin | any | Browse the database |
| Optional: Postman | any | Manual API testing |

### 1.1 Ubuntu 22.04 / 24.04

```bash
sudo apt update
sudo apt install -y git openjdk-21-jdk maven postgresql postgresql-contrib curl jq unzip
java -version     # must show 21
mvn -v            # must show Java 21 as the runtime
psql --version
```

### 1.2 Windows 10/11

Install the following (winget IDs may change; the official download pages always work):

```powershell
winget install --id Git.Git
winget install --id EclipseAdoptium.Temurin.21.JDK
winget install --id jqlang.jq
```

- **Maven:** download the binary zip from maven.apache.org, extract to `C:\tools\maven`, add `C:\tools\maven\bin` to `PATH`.
- **PostgreSQL 16:** use the installer from postgresql.org. Remember the `postgres` password you set.
- Set `JAVA_HOME` to the Temurin 21 folder, then open a **new** terminal and check `java -version` and `mvn -v`.
- Run the `.sh` scripts from **Git Bash**, which is installed with Git.

---

## 2. Get the code

```bash
unzip cms.zip && cd cms      # or: git clone <your-repo-url> cms && cd cms
git log --oneline -1         # confirm you are on the expected version
git tag                      # release tags, e.g. v0.4.0
```

Project layout:

```
cms/
├── pom.xml                      build, version
├── CHANGELOG.md                 what changed in each version
├── CONTRIBUTING.md              branching, commits, versioning, DB migration rules
├── docs/                        DEPLOYMENT, TESTING, DECISIONS, ROADMAP
├── hsm-sim/HsmSimulator.java    payShield simulator (single file, no build)
├── scripts/                     seed-dev.sql, smoke-test.sh
├── ui/                          Quasar console (Vue 3 + Quasar); built into the jar by Maven
└── src/main/...                 CMS source, config, Flyway migrations
```

---

## 3. Create the database

Linux:

```bash
sudo -u postgres psql <<'SQL'
CREATE USER cms WITH PASSWORD 'change-me';
CREATE DATABASE cms OWNER cms;
SQL
```

Windows: open **SQL Shell (psql)** as `postgres` and run the same two statements.

Check the connection:

```bash
psql -h localhost -U cms -d cms -c "select version();"
```

The tables are **not** created by hand. Flyway creates and upgrades them automatically when the CMS starts (§6).

---

## 4. Configure

The CMS reads configuration from `application.yml`, from the active profile file (`application-dev.yml`), and from environment variables. Environment variables always win.

| Variable | Required | DEV value | Notes |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | yes | `dev` | Selects `application-dev.yml` |
| `CMS_DB_PASSWORD` | yes | your DB password | |
| `SPRING_DATASOURCE_URL` | no | default `jdbc:postgresql://localhost:5432/cms` | Override for another host **or port**, e.g. `jdbc:postgresql://localhost:1455/cms` if PostgreSQL was installed on a non-default port |
| `CMS_PAN_ENC_KEY` | TEST: yes | dev profile has a fixed default | 32 random bytes, base64 |
| `CMS_PAN_HMAC_KEY` | TEST: yes | dev profile has a fixed default | 32 random bytes, base64 |
| `CMS_PAN_KEY_CHECK` | no | `FAIL` (dev: `WARN`) | Startup check of the PAN keys against the newest live cards. FAIL refuses to start when they match none of them; WARN only logs |
| `CMS_HSM_HOST` / `CMS_HSM_PORT` | no | `localhost` / `1500` | Point at the real payShield later |
| `CMS_ADMIN_PASSWORD` | first start | dev profile has its own users | Password of the bootstrap `admin` (must be changed at first sign-in). Empty = generated and written to the log once |
| `CMS_DEXXIS_API_KEY` | TEST: yes | `dev-dexxis-key` | Key Dexxis sends in `X-Api-Key`. Empty = Dexxis calls refused. Long random value, e.g. `openssl rand -hex 32` |
| `CMS_DEV_PASSWORD` | no | `Dev-Passw0rd!` | DEV only: password of the dev users admin, supervisor, supervisor2, operator, viewer |
| `CMS_CORE_BANKING_URL` | for core accounts | dev: in-process simulator | Base URL of the core banking funds API (provisional contract, IN-06). Empty = not connected: cards on `CORE_BANKING` accounts approve only within their stand-in limit |
| `CMS_CORE_BANKING_API_KEY` / `CMS_CORE_BANKING_TIMEOUT_MS` | no | `dev-core-key` / `3000` | Sent as `X-Api-Key`; timeout per call (the switch waits for the answer, keep it well under the switch timeout) |
| `CMS_CHANNEL_API_KEY` | no | `dev-channel-key` | Key for `/api/channel/**` (ACS, mobile, IVR). Empty = refused |
| `CMS_ISO_PORT` / `CMS_ISO_ENABLED` | no | `7000` / `true` | BASE24 switch interface. Also `cms.iso.length-prefix` (BINARY2 / ASCII4) and `cms.iso.header-length`; set them from the BASE24 spec |

> **Important:** the PAN keys encrypt card numbers at rest. If you change them, existing cards can no longer be decrypted. On TEST, generate them once (`openssl rand -base64 32`), store them in the env file (§9.2), and never rotate them casually.

Linux (DEV):

```bash
export SPRING_PROFILES_ACTIVE=dev
export CMS_DB_PASSWORD=change-me
```

Windows PowerShell (DEV):

```powershell
$env:SPRING_PROFILES_ACTIVE="dev"
$env:CMS_DB_PASSWORD="change-me"
```

---

## 5. Start the HSM simulator

In its own terminal:

```bash
cd hsm-sim
java HsmSimulator.java selftest      # must end with: SELFTEST PASSED
java HsmSimulator.java serve 1500 4  # port 1500, header length 4
```

Expected output: `hsm-sim 1.0.0 listening on 1500, header length 4, LMK check EB7A8DF91182DBE2`. Leave it running; every HSM command is logged in this window.

Useful options:

| Env var | Example | Effect |
|---|---|---|
| `SIM_DELAY_MS` | `3000` | Slow every response (tests CMS timeouts) |
| `SIM_FAIL` | `EC:15,CW:10` | Force an error code per command |
| `SIM_LMK` | 32 hex | Different simulated LMK. **Regenerate the seed if you change it.** |

---

## 6. Build and run the CMS

### 6.1 Build

```bash
mvn clean verify        # console build + compile + unit tests + jar with version info
```

One build produces one jar that contains the backend **and** the Quasar console:

1. `frontend-maven-plugin` installs its own Node (version in `pom.xml`, `node.version`) into `target/node`. Your installed Node, if any, is not used.
2. `npm install` in `ui/` (exact versions from `ui/package-lock.json`), then `npm run build` into `ui/dist/`.
3. `ui/dist/` is copied into the jar as static content served at `/`.

| Option | Use |
|---|---|
| `-DskipUi` | Backend only; faster when you only changed Java. The jar then has **no console** (blank page at `/`). |
| `"-Dnpm.install=ci --no-audit --no-fund"` | Strict clean install, as used in CI. Avoid it inside OneDrive (see below). |

> **OneDrive / synced folders:** sync can lock files in `ui/node_modules` and make npm fail with `EPERM`, `ENOTEMPTY` or `unlink` errors. Delete `ui/node_modules` (PowerShell: `Remove-Item ui\node_modules -Recurse -Force`) and build again, or keep the working copy outside the synced folder.

### 6.2 Run

```bash
java -jar target/cms-core-0.4.0.jar
# or during development: mvn spring-boot:run  (add -DskipUi for faster restarts)
```

On first start, Flyway creates the schema (migrations V1 to V5). The log shows `Successfully applied 5 migrations`, then `Started CmsApplication`. An existing database gets only the migrations it is missing, e.g. a 0.4.0 database gets V4 and V5 (`Migrating schema "public" to version "4 - configurable reference data"`); see §9.7 before upgrading a shared database.

The CMS starts even if the HSM is down, because HSM connections are opened on first use. Check the HSM state with the health endpoint (§8).

Open the console at `http://localhost:8080/` and **sign in**:

- **DEV profile:** users `admin`, `supervisor`, `supervisor2`, `operator`, `viewer`, password `Dev-Passw0rd!` (or `CMS_DEV_PASSWORD`).
- **Other profiles:** the first start creates `admin` with `CMS_ADMIN_PASSWORD`, or with a generated password printed once in the log (`No users found. Created 'admin'...`). Sign in, change it, then create named users under **Control → Users**. Do not share accounts: every change and approval is recorded under the user.

Roles: **ADMIN** (users, approval policy), **SUPERVISOR** (setup changes, approvals), **OPERATOR** (customers, accounts, cards, entries), **VIEWER** (read only). Changes covered by the approval policy wait in **Approvals** until a *different* supervisor approves them.

The old `http://localhost:8080/issuance.html` redirects to **Issue card**.

### 6.3 Console development (hot reload)

Only needed when changing the console itself. Requires Node 20+.

```bash
cd ui
npm install
npm run dev                                   # http://localhost:9000, API proxied to localhost:8080
CMS_API=http://localhost:8081 npm run dev     # proxy to a CMS on another port
```

PowerShell: `$env:CMS_API="http://localhost:8081"; npm run dev`.

---

## 7. Load dev seed data

Run this after the first start, so the tables exist:

```bash
psql -h localhost -U cms -d cms -f scripts/seed-dev.sql
```

It loads:
- **Test keys** for hsm-sim: ZMK and ZPK for the corehost, ZPK for the kiosk, PVK and CVK. The clear values are printed as comments in the file.
- **Two products:** `P01` Prepaid Classic (PREPAID/PAYROLL accounts × MASS/PAYROLL/STAFF segments) and `P02` Debit Gold (CURRENT/SAVINGS accounts × PREMIUM/STAFF segments).

After seeding, products and eligibility are maintained in the console (§7.1). Only the **HSM keys** still need SQL, because the console never handles key material.

### 7.1 First-time configuration in the Quasar console

Migrations seed a starting set of currencies (EGP, USD, AED), segments and account types. Review them under **Setup**, in this order, since each step uses the previous one:

| # | Screen | What to decide |
|---|---|---|
| 1 | Setup → Currencies | Currencies in use. Decimal places lock once an account exists in that currency. |
| 2 | Setup → Customer segments | Segments (e.g. MASS, PREMIUM, STAFF). Inactive segments stay on existing customers. |
| 3 | Setup → Numbering & settings | **CIF source:** CMS_GENERATED, CORE_BANKING or EITHER (default EITHER). **Number sequences:** prefix, digits, next value, optional Luhn digit. Add one sequence per account-number format you need, e.g. `ACCOUNT_PREPAID`. |
| 4 | Setup → Account types | Per type: account number **generated by the CMS** (choose the sequence) or **entered from core banking**; currencies offered; max open accounts per customer; where the balance is held. |
| 5 | Setup → Card products | BIN, PAN length and range start (fixed after creation), range end, validity, service code, PVK/CVK (from `hsm_key`), PVKI, PIN tries, ATM limits. |
| 6 | Card product → Eligibility | Tick which account type × segment combinations may receive the product. The account's currency must also match the product's. |

Every change is recorded in **Audit log** with the operator id. Existing customers, accounts and cards are never changed by setup edits; the new rules apply to new records.

---

## 8. Verify the deployment

Run these checks in order. Each must pass before moving to the next.

| # | Check | Command | Expected |
|---|---|---|---|
| 1 | Version | `curl localhost:8080/api/version` | `"version":"0.4.0"` |
| 2 | HSM | `curl localhost:8080/api/admin/hsm/health` | `"status":"UP"`, `lmkCheckValue` = `EB7A8DF91182DBE2` (sim default) |
| 3 | Console | Open `http://localhost:8080/` | Dashboard loads and the header shows **HSM UP**; Setup → Account types lists the seeded types |
| 4 | Smoke test | `./scripts/smoke-test.sh` | `15 passed, 0 failed` |
| 5 | Authorization (DEV only) | `./scripts/auth-test.sh` | `32 passed, 0 failed` |
| 5b | BASE24 interface (DEV only) | `./scripts/iso-test.sh` | `22 passed, 0 failed` |
| 5c | Security (DEV only) | `./scripts/security-test.sh` | `30 passed, 0 failed` |
| 5d | Card lifecycle (DEV only) | `./scripts/lifecycle-test.sh` | `27 passed, 0 failed` |
| 5e | Core banking (DEV only) | `./scripts/core-test.sh` | `25 passed, 0 failed` |
| 5f | Fraud rules (DEV only) | `./scripts/fraud-test.sh` | `24 passed, 0 failed` |
| 6 | Console flow | In the console: Issue card → pick or create a customer → open or pick an account → issue a card | Full card number shown once; the card appears under Cards as **Pending print** |

If all checks pass, the deployment is good. Check 5 needs the `dev` profile, because it uses the dev-only `/api/dev/authorize` endpoint; skip it on TEST. For a full UI check, run the manual cases in [TESTING.md](TESTING.md) §4. Record the result in [TESTING.md](TESTING.md) §6.

---

## 9. Deploy to the TEST server (Linux, systemd)

### 9.1 Directories and user

```bash
sudo useradd --system --home /opt/cms --shell /usr/sbin/nologin cms
sudo mkdir -p /opt/cms/releases /opt/cms/hsm-sim /opt/cms/config /opt/cms/backup /etc/cms
sudo chown -R cms:cms /opt/cms
```

### 9.2 Environment file (secrets)

`/etc/cms/cms.env`, owned by root, mode `600`:

```bash
SPRING_PROFILES_ACTIVE=test
CMS_DB_PASSWORD=<db password>
CMS_PAN_ENC_KEY=<openssl rand -base64 32, generated ONCE>
CMS_PAN_HMAC_KEY=<openssl rand -base64 32, generated ONCE>
```

```bash
sudo chmod 600 /etc/cms/cms.env
```

### 9.3 TEST profile

Create `src/main/resources/application-test.yml` before building, or place it at `/opt/cms/config/application-test.yml`:

```yaml
cms:
  hsm:
    host: localhost        # hsm-sim on the same server, or the payShield IP
    port: 1500
    header-length: 4
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/cms
```

### 9.4 Install the release

```bash
VERSION=0.4.0
sudo cp target/cms-core-$VERSION.jar /opt/cms/releases/
sudo ln -sfn /opt/cms/releases/cms-core-$VERSION.jar /opt/cms/cms-core.jar
sudo cp hsm-sim/HsmSimulator.java /opt/cms/hsm-sim/
sudo chown -R cms:cms /opt/cms
```

Keeping every jar in `releases/` and switching a symlink makes rollback a one-line operation.

### 9.5 systemd services

`/etc/systemd/system/hsm-sim.service`:

```ini
[Unit]
Description=payShield simulator (TEST ONLY)
After=network.target

[Service]
User=cms
WorkingDirectory=/opt/cms/hsm-sim
ExecStart=/usr/bin/java HsmSimulator.java serve 1500 4
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

`/etc/systemd/system/cms.service`:

```ini
[Unit]
Description=CMS core
After=network.target postgresql.service hsm-sim.service

[Service]
User=cms
WorkingDirectory=/opt/cms
EnvironmentFile=/etc/cms/cms.env
ExecStart=/usr/bin/java -Xms512m -Xmx1g -jar /opt/cms/cms-core.jar --spring.config.additional-location=optional:/opt/cms/config/
Restart=on-failure
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now hsm-sim cms
sudo journalctl -u cms -f          # follow logs
```

Then run the checks in §8 against the server.

### 9.6 Network

| Port | Service | Open to |
|---|---|---|
| 8080 | CMS (Console, admin API, Dexxis API) | Test users' subnet and the Dexxis server **only** |
| 7000 | BASE24 ISO interface (`cms.iso.port`) | The switch (corehost) only |
| 1500 | hsm-sim | localhost only (do not expose) |
| 5432 | PostgreSQL | localhost only |

```bash
sudo ufw allow from <test-subnet> to any port 8080 proto tcp
```

> **Sign-in is local users over plain HTTP in TEST.** Put the CMS behind TLS before any shared use (reverse proxy or `server.ssl.*`) and then set `server.servlet.session.cookie.secure=true`. Keep 8080 restricted to the test subnet and Dexxis; the BASE24 port (7000) to the switch only.

### 9.7 Upgrade procedure (every new version)

1. Read the version's entry in `CHANGELOG.md`, especially **Migrations** and **Breaking**.
2. Back up the database:
   `sudo -u postgres pg_dump -Fc cms | sudo tee /opt/cms/backup/cms-$(date +%F-%H%M)-before-<new-version>.dump > /dev/null`
3. Copy the new jar to `releases/` and repoint the symlink (§9.4).
4. Restart: `sudo systemctl restart cms`. Flyway applies any new migrations.
5. Verify with §8: the version must show the new number, and the smoke test must pass.
6. Record the deployment in [TESTING.md](TESTING.md) §6.

**Upgrading to the Quasar console release (migration V4):**

- V4 is additive and keeps current behaviour: every account type stays CMS-generated from the `ACCOUNT` sequence (continuing after the last `account_number_seq` value), allows every existing currency, and the CIF source is `EITHER`.
- Back up first (step 2). V4 cannot be undone except by restoring that backup.
- Never start a feature-branch build against a shared database. Once a migration has run, its file can no longer change (Flyway checksum), so a branch build can block the next release.
- After the restart, check Setup → Numbering & settings (the `ACCOUNT` next number is above the highest existing account number) and Setup → Account types before operators start working.
- **V5 (authorization and ledger)** is also additive: products keep their limits (purchase amount limits follow the ATM limits until set), fees are zero and e-commerce is off. Set fees, purchase limits and channels per product under Setup → Card products → *Channels, purchases and fees*.
- **V6 (users, roles, maker-checker):** on the first start after upgrading, the CMS creates `admin` (see §6.2). Give Dexxis the API key (`CMS_DEXXIS_API_KEY`) **before** upgrading, or perso and activation calls fail with 401. Update any script that called the admin API to sign in (HTTP Basic) and to handle 202 approval responses.
- **V7 (card lifecycle, batch):** the batch scheduler starts with the CMS. Review **Control → Batch jobs** after the upgrade: CARD_RENEWAL will create renewal cards for every live card expiring within its product's lead days (default 30) at 01:00, and STALE_PENDING_PRINT cancels cards not printed within 30 days. Switch a job off, or change the product settings, before the first night if that is not wanted. To stop all scheduled runs on an instance set `cms.batch.scheduler-enabled=false`.
- **V10 (fraud):** nothing changes until rules are switched on: the seven starter rules are installed inactive. Check `institution.country` (default 818) under Setup → Numbering & settings, agree thresholds with the fraud team, then activate rules one by one and watch Operations → Fraud alerts. The BASE24 packager now maps field 19 (acquirer country, provisional with IN-01).
- **V9 (core banking):** no change for existing accounts (all `CMS_LEDGER`). To use core banking accounts, set `CMS_CORE_BANKING_URL` (and key), create an account type with ledger mode `CORE_BANKING`, and set each product's stand-in limit. Watch Control → Core banking for failed postings. On DEV, rerun `scripts/seed-dev.sql` (adds `CORE_CURRENT`).
- **V8 (EMV):** chip cryptograms are checked only for products with an IMK-AC key. On DEV, rerun `scripts/seed-dev.sql` (adds IMK_AC_P01 and links P01/P02) and restart hsm-sim so it runs **1.1.0** (`java hsm-sim/HsmSimulator.java selftest` must list the KQ cases). On a real payShield, import the issuer IMK-AC and confirm the KQ layout before enabling it on a product.
- Admin API changes for any script that calls it: `GET /api/admin/customers` returns a page `{items, total, page, size}`, and `/api/admin/reference` returns full objects (see CHANGELOG, **Breaking**).

### 9.8 Rollback

- **No new migrations in the release:** repoint the symlink to the previous jar, then restart.
- **New migrations were applied:** Flyway is forward-only. Stop the CMS, restore the backup from step 2 (`sudo -u postgres pg_restore --clean -d cms <file>`), repoint the symlink, start, and verify.

---

## 10. Switching from hsm-sim to the real payShield 10K

The simulator uses the **same clear test keys** you will load into the payShield, so PVVs and CVVs stay identical. Test cards created on the simulator keep working after the switch.

1. **payShield host settings:** note the TCP host port, the **message header length**, and the **LMK type** (variant or key block). The CMS client currently supports **variant LMK** key tokens (`U` + 32 hex); a key-block LMK needs a client update (roadmap item CMS-043).
2. **Import the test keys** from the clear values in `scripts/seed-dev.sql`, using a **test LMK** only, your normal key ceremony method, and dual control even in test so the procedure is rehearsed. Import them as the right key types: ZMK, ZPK, PVK and CVK.
3. **Check the KCVs.** The KCV the payShield reports for each key must equal the KCV in the seed file. If one differs, stop: the key was entered wrongly.
4. **Update `hsm_key`** with the payShield cryptograms (keep the key names; update `key_under_lmk` and `kcv`).
5. **Point the CMS** at the HSM (`CMS_HSM_HOST`, `CMS_HSM_PORT`, header length) and restart.
6. **Verify:** health shows the real LMK check value; then run the smoke test. `pinblock` in the smoke test uses the clear kiosk ZPK, so it still works against the real HSM.
7. **Verify command layouts:** any `HSM_ERROR` with an error code points to a field layout difference for your firmware. Compare with the payShield Host Command Reference and log the issue in the ROADMAP.

---

## 11. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `mvn` uses Java 17 or older | Wrong `JAVA_HOME` | Point `JAVA_HOME` to JDK 21, open a new terminal |
| `password authentication failed for user "cms"` | Wrong `CMS_DB_PASSWORD` | Check the variable and the DB user password |
| `Validate failed: Migrations have failed validation` | An applied migration file was edited | Never edit applied migrations (see CONTRIBUTING). Restore the original file. |
| Health shows `DOWN` / `cannot connect to HSM` | Simulator not running, wrong port | Start hsm-sim, check `cms.hsm.port` |
| `HSM header mismatch` | Header length differs between CMS and HSM | Align `header-length` with the HSM setting |
| `PAN_KEY_MISMATCH`, or at startup `CMS_PAN_ENC_KEY does not decrypt card(s) [...]` | The CMS runs with different PAN keys from the ones those cards were issued under (often: variables not set in this terminal, so the dev default is used) | Start with the original `CMS_PAN_ENC_KEY` / `CMS_PAN_HMAC_KEY`. If they are lost, those card numbers cannot be recovered: cancel the cards and reissue |
| `HSM_ERROR ... / 15` | Input data error, wrong field layout or key token | Check the key rows in `hsm_key`; on a real HSM, check the command layout |
| `KEY_MISSING` | Seed not loaded or key name differs | Run `seed-dev.sql`; check product key names |
| `PRODUCT_NOT_ELIGIBLE` / "No product is allowed" in Issue card | No eligibility for that account type × segment, or the currency differs | Setup → Card products → product → Eligibility; check the product and account currency |
| Existing cards fail with `PAN decryption failed`, or known PANs return `Card not found` | PAN keys differ from those used when the cards were issued | Start the CMS with the original `CMS_PAN_ENC_KEY` / `CMS_PAN_HMAC_KEY` |
| Console dropdowns empty | CMS not reachable, or reference data inactive/empty | Check `/api/admin/reference`; migrations must have run; activate the rows under Setup |
| Blank page at `http://localhost:8080/` | Jar built with `-DskipUi`, or `ui/dist` missing | Rebuild without `-DskipUi` |
| Console shows "Cannot reach the CMS server" | CMS stopped, or `npm run dev` proxying to the wrong port | Start the CMS; set `CMS_API` for the dev server (§6.3) |
| `Could not extract the Node archive ... Unexpected end of ZLIB input stream` | Node download was cut off | Delete `~/.m2/repository/com/github/eirslett/node/<version>` and build again |
| npm `EPERM`, `ENOTEMPTY` or `unlink` during the build | OneDrive or antivirus locking `ui/node_modules` | Delete `ui/node_modules` and build again, or move the working copy out of OneDrive |
| "Account type X needs the core banking account number" | The type is set to core-banking numbering | Type the core banking number, or change the type under Setup → Account types |
| "CIF is generated by the CMS; leave it blank" | CIF source is CMS_GENERATED | Leave CIF empty, or change Setup → Numbering & settings → CIF source |
| Setup change not visible in a form | The browser has the old reference data | Reload the page |
