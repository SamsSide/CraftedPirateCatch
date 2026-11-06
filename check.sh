#!/usr/bin/env bash
# check.sh — the build gate. Installed by the mc-setup skill.
#   ./check.sh                    mvn verify: compile, unit tests, package (the gate)
#   ./check.sh -Dtest=SomeTest    scoped tests while iterating (not a gate)
# Never run two at once in one repo: Maven shares target/.
set -euo pipefail
cd "$(dirname "$0")"
command -v mvn >/dev/null || { echo "check.sh: mvn not on PATH" >&2; exit 2; }
start=$(date +%s)
if [ "$#" -gt 0 ]; then
  mvn -B -q test -Dsurefire.failIfNoSpecifiedTests=false "$@"
else
  mvn -B -q verify
fi
jar=$(ls -t target/*.jar 2>/dev/null | grep -vE '(original|sources|javadoc)' | head -1 || true)
echo "check.sh: PASS in $(( $(date +%s) - start ))s${jar:+ — $jar ($(du -h "$jar" | cut -f1))}"
