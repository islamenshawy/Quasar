# Contributing: Development Chapter Rules

These rules keep every change traceable: **roadmap item → branch → commits → changelog → version tag → deployment record.** If a change can't be traced along that line, it isn't finished.

## 1. Work items

- Every change starts from an item in [docs/ROADMAP.md](docs/ROADMAP.md) with an ID `CMS-nnn`. No ID, no branch.
- Decisions that are expensive to reverse (data model, protocols, security, crypto, tech stack) get an ADR in [docs/DECISIONS.md](docs/DECISIONS.md) **before** the code is merged.

## 2. Branching

Light trunk-based model:

| Branch | Purpose | Rule |
|---|---|---|
| `main` | Always releasable | Protected. Changes only via pull request, with at least one review. |
| `feature/CMS-nnn-short-name` | One roadmap item | Branch from `main`, keep short-lived (days, not weeks) |
| `fix/CMS-nnn-short-name` | Bug fix | Same as feature |
| `release/x.y` | Only if a released version needs a patch while `main` has moved on | Cherry-pick fixes, tag `vx.y.z` |

## 3. Commit messages

[Conventional Commits](https://www.conventionalcommits.org/) with the item ID:

```
feat(auth): withdrawal with holds and ledger postings [CMS-046]
fix(hsm): discard connection after read timeout [CMS-031]
docs(deploy): add rollback steps [CMS-034]
db(migration): V4 add card_block_reason [CMS-050]
```

Types: `feat`, `fix`, `db`, `refactor`, `test`, `docs`, `build`, `chore`, `security`.

## 4. Versioning (SemVer)

Version lives in `pom.xml` and is reported by `GET /api/version`. hsm-sim has its own version (`VERSION` constant in the file).

While below 1.0.0:

| Change | Bump | Example |
|---|---|---|
| New feature, new migration, or behaviour change | **minor** | 0.4.0 → 0.5.0 |
| Bug fix, no schema change | **patch** | 0.4.0 → 0.4.1 |
| Breaking interface change (Dexxis, corehost, admin API) | **minor**, listed under **Breaking** in the changelog | |

From 1.0.0: breaking changes bump **major**.

## 5. Database migrations (Flyway)

1. **Never edit a migration that has run anywhere.** Flyway checksums it and the CMS will refuse to start. Fix mistakes with a new migration.
2. Name: `V{n}__{what}.sql`, the next number in sequence, one logical change per file.
3. Forward-only. Rollback is a database restore (DEPLOYMENT §9.8), so every migration in a release is listed in the changelog.
4. Prefer additive changes (new columns nullable or with defaults). Destructive changes need an ADR.
5. No test data in migrations. Test data goes in `scripts/`.

## 6. Security rules (non-negotiable, also in test)

- No PAN, PIN block, CVV, key or cryptogram in logs, exceptions, audit details or URLs. Mask PANs as `first6******last4`.
- PINs never exist in clear in CMS code. Only the HSM handles them.
- CVVs are never stored.
- Only test keys and test BINs in DEV/TEST. Real card data never enters these environments.
- Secrets live in environment variables or the env file, never in git.

## 7. Definition of Done

A roadmap item is Done when all of these are true:

- [ ] Code merged to `main` through a reviewed pull request
- [ ] Unit tests for the new logic; `mvn verify` green
- [ ] Smoke test updated if the flow changed, and passing
- [ ] Security rules in §6 respected (reviewer checks explicitly)
- [ ] `CHANGELOG.md` updated under `[Unreleased]` with the item ID
- [ ] ADR added or updated if a decision was made
- [ ] DEPLOYMENT/TESTING docs updated if setup or tests changed
- [ ] ROADMAP status updated

## 8. Pull request checklist

Copy into the PR description:

```
Item: CMS-nnn
What changed:
Migrations: none | V{n}__...
Breaking: none | ...
Tests: unit / smoke / manual (which)
Security: no sensitive data logged; PAN masked; no secrets committed
Docs: CHANGELOG / ROADMAP / DECISIONS / DEPLOYMENT / TESTING updated
```

## 9. Release process

1. Move `[Unreleased]` entries into a new version section in `CHANGELOG.md` with today's date.
2. Set the version in `pom.xml` (and `VERSION` in hsm-sim if it changed).
3. Update the "Applies to" line in `docs/DEPLOYMENT.md` and `docs/TESTING.md`.
4. `mvn clean verify`, then commit: `build(release): 0.5.0`.
5. Tag: `git tag -a v0.5.0 -m "cms-core 0.5.0"` and push the tag.
6. Deploy to TEST following DEPLOYMENT §9.7 and record the run in TESTING §6.

## 10. Code conventions

- Java 21: records for DTOs, `final` fields, constructor injection.
- Money: `long` minor units only. No `double`/`float` for amounts, ever.
- Business errors: `IssuanceException` (and later auth errors) with stable codes. Callers depend on codes, not messages.
- SQL lives in repositories/services as text blocks; every state change writes `card_status_history` and/or `audit_log`.
- HSM command layouts are documented in the method comment with request and response fields.
