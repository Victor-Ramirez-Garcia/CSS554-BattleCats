# Sprint 2 validation

## Selected behavior

This PR combines the selected Sprint 2 bot, its relevant tests and a JaCoCo
coverage gate. All ten production Java files in `battlecats`, and the team
tests, match revision `6cc7522bcd688f2bcf668bd8cde699ac97f19c8d` exactly.
That snapshot includes conditional PvP, cat engagement, three construction
kinds, food safeguards, worker efficiency and normal return at 20 or more.
The parallel pool, opponent adapters, profiler and later strategy experiments
are outside this PR.

The selected historical fixed suite recorded 14/14 wins against team main,
13/14 against Team 2 and 33/84 against six external opponents in total. Its
strict-return predecessor scored 31/84, but beat this version 10-4 directly.
The later contextual delivery and travel variants scored 29/84 and 31/84.
These results support choosing a simple, coherent snapshot; they do not
establish a universal ordering or general tournament win probability.
Those broader comparisons used separate experimental tooling. Fresh evidence
for the assembled PR against team main is recorded separately from history.

## Clean checks

The JDK 21 clean run passed:

```powershell
.\gradlew.bat clean test jacocoTestReport jacocoTestCoverageVerification verify zipForSubmit
```

- 182 unit tests, zero failures.
- Complete-team line coverage: **905/939 = 96.38%**; the 21% gate passes.
- Branch coverage: **854/938 = 91.04%**.
- Engine verifier and submission packaging pass.

Coverage includes all production classes in `battlecats`, including the
entry-point loop. Engine matches run separately from the unit-test coverage.
`submission.zip` is a local artifact; packaging does not upload it.

## Limits

These policies cannot guarantee food after a worker wipe or safety against
stronger opponents. Combat can interrupt normal deliveries; emergency trips
retain priority. Shared targeting is implemented and scenario-tested, but an
independent match-win benefit is unproven. Navigation has no remembered threat
costs, global map or mine memory. The bot does not promote, carry or throw rats.

See [the teammate guide](SPRINT_2.md) for the full priorities and experimental
values, and [testing](TESTING.md) for report locations and comparison commands.
