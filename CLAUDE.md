# CraftedPirateCatch

Paper plugin by SamsSide. Work under the `mc-dev` skill family: its standing rules bind here.

- **Target:** MC 26.2, Java 25, Paper API `[26.2.build,)`, `api-version: '26.2'`.
- **Package:** `net.samsside.craftedpiratecatch`. Version lives in `pom.xml` only; never bump unless told.
- **Gates:** `./check.sh` (mvn verify) and `./scripts/exploit-audit.sh`. Both, whole, quoted, every change.
- **Planning docs:** `docs/planning/specs/`, `docs/planning/plans/` (plan with `mc-plan`, build with `mc-plan-execute`).
- No test server: server-only behaviour becomes hand-test steps for Sam. (Add a line `Test server: coral <id>` to opt in.)
- **Git:** feature branches; no AI attribution in commits; offer the push, never open a PR.

## Local notes
(What's specific to this plugin: architecture, quirks, decisions worth remembering.)
