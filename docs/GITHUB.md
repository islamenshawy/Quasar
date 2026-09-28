# GitHub Setup

| | |
|---|---|
| Item | CMS-037 |
| Owner | Tech lead |
| Last updated | 2026-09-28 |

The zip already contains a git repository: branch `main`, full history, and tag `v0.4.0`. These steps publish it to GitHub and switch on the controls that CONTRIBUTING.md relies on.

## 1. Create the repository

1. On github.com: **New repository**.
2. Name: `cms-core` (or your choice). Visibility: **Private**.
   The repo holds clear test keys and card-system internals. Keep it private even though the data is test-only.
3. **Do not** tick "Add a README", ".gitignore" or "license". The repo must be empty, otherwise the first push is rejected.

## 2. Authenticate (once per machine)

GitHub does not accept account passwords for git. Pick one:

- **GitHub CLI (easiest):** install from cli.github.com, then `gh auth login`.
- **SSH key:** `ssh-keygen -t ed25519 -C "you@example.com"`, add `~/.ssh/id_ed25519.pub` under GitHub → Settings → SSH and GPG keys.
- **HTTPS + token:** GitHub → Settings → Developer settings → Personal access tokens (fine-grained, access to this repo only, Contents: read/write). Use the token as the password when git asks.

## 3. Push

```bash
unzip cms.zip && cd cms
git status                      # should say: On branch main, nothing to commit
git log --oneline --decorate    # shows the commits and tag v0.4.0

git remote add origin git@github.com:<your-user-or-org>/cms-core.git
# or HTTPS: https://github.com/<your-user-or-org>/cms-core.git

git push -u origin main
git push origin --tags          # publishes v0.4.0
```

With the GitHub CLI, instead of the remote/push lines:

```bash
gh repo create <your-user-or-org>/cms-core --private --source=. --push
git push origin --tags
```

If git says **"dubious ownership"** after unzipping (common on Windows or shared drives):
`git config --global --add safe.directory <full path to cms>`

## 4. Protect `main` (Settings → Branches → Add rule, or Rulesets)

| Setting | Value |
|---|---|
| Branch name pattern | `main` |
| Require a pull request before merging | on, 1 approval |
| Require status checks to pass | on, select **CI / build** (appears after the first CI run) |
| Require branches to be up to date | on |
| Block force pushes | on |
| Do not allow deletions | on |

On a free plan, branch protection for **private** repos needs GitHub Team (or a paid plan). Without it, follow the same rules by agreement and check them in review.

## 5. Security settings (Settings → Code security)

- Enable **secret scanning** and **push protection** where available on your plan.
- Enable **Dependabot alerts** for Maven dependencies.

## 6. Track work in GitHub

- Create a label per area: `auth`, `issuance`, `hsm`, `base24`, `dexxis`, `docs`, `blocked`.
- Create one issue per open ROADMAP item and put the ID in the title: `CMS-046 Withdrawal with holds and postings`.
- Create milestones per version: `0.5.0`, `0.6.0`, ... and assign issues.
- Branch names and commits already carry the ID (CONTRIBUTING §2–3), so issues, PRs and commits link up.
- `docs/ROADMAP.md` stays the source of truth for status and blocked inputs; update it in the same PR that closes the issue.

## 7. Releases

When a version is tagged (CONTRIBUTING §9), create a GitHub Release from the tag:

```bash
gh release create v0.5.0 --title "cms-core 0.5.0" --notes-file <(sed -n '/## \[0.5.0\]/,/## \[/p' CHANGELOG.md | sed '$d')
```

or in the web UI: Releases → Draft a new release → choose the tag → paste that version's CHANGELOG section. Attach the jar from the CI artifact.

## 8. Verify

- The **Actions** tab shows a green **CI** run for `main`.
- The **Tags** list shows `v0.4.0`.
- A test pull request shows the PR template and the CI check.
