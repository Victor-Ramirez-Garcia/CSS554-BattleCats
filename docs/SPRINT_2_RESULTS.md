# Sprint 2 validation

## Selected behavior

This PR combines the selected Sprint 2 bot, its relevant tests and a JaCoCo
coverage gate. The strategy comes from revision
`6cc7522bcd688f2bcf668bd8cde699ac97f19c8d`. Nine production files match that
snapshot exactly; `KingSignal` adds an interrupted-publication fix, with a
regression test. The selected snapshot includes conditional PvP, cat engagement,
three construction kinds, food safeguards, worker efficiency and normal return
at 20 or more.
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

## Review correction

Independent review found that a bytecode pause between the home-coordinate
writes could expose mixed coordinates under the old still-fresh timestamp.
The new regression test failed under that implementation. `KingSignal` now
invalidates its timestamp before changing X/Y and publishes the fresh timestamp
last, matching the existing focus signal. The test injects interruption before
each write, checks that workers see either the previous complete message or no
message, and verifies that a later complete publication recovers.

## Clean checks

The JDK 21 clean run passed:

```powershell
.\gradlew.bat clean test jacocoTestReport jacocoTestCoverageVerification verify zipForSubmit
```

- **183 unit tests**, zero failures, including the interrupted-write regression.
- Complete-team line coverage: **906/940 = 96.38%**; the 21% gate passes.
- Branch coverage: **854/938 = 91.04%**.
- Engine verifier and submission packaging pass.

Coverage includes all production classes in `battlecats`, including the
entry-point loop. Engine matches run separately from the unit-test coverage.
`submission.zip` is a local artifact; packaging does not upload it.

## Fresh committed-candidate comparisons

Both final suites use candidate **`a14183e56acd4fc55b9ce555ec729e05fda06c95`**,
including the broadcast fix. Team main was fetched again immediately before
these comparisons and remained `711b3b669d0826c0353d17e4b7ad43759b662626`.
Team 2 is pinned to `cd1f4b73b44695552e22609d3247744547cae2ca`, package
`ratplayer`. Later evidence-only commits do not change the tested bot or build.

Engine 1.2.6 and JDK 21 ran `DefaultSmall`, `DefaultMedium`, `DefaultLarge`,
`cheesefarm`, `minimaze`, `safelycontained` and `pipes` on both starting sides.
All **28/28 games completed**, with no rejected games or coin-flip outcomes.

| Opponent | Wins | Final delivered cheese, ours / opponent |
| --- | ---: | ---: |
| Current team main | **14/14** | 42,120 / 21,520 |
| Pinned Team 2 | **13/14** | 181,579 / 41,119 |

Every main win is by king destruction. Team 2 wins `minimaze-B` on points at
round 2,000: both kings survive, deliveries are 4,490 / 3,640 and cat damage is
0 / 455. Cheese is only one component of points. No win count regressed from
the selected historical version or the initial clean snapshot.

Final deliveries combine different match lengths. At fixed round 1,000, only
two main games remain: 15,680 / 9,655 delivered. Eleven Team 2 games reach that
round: 79,550 / 19,587. These limited cohorts do not isolate worker efficiency
from king strategy, combat or survival. On both `pipes` cases against main,
the game ends at round 155 with zero deliveries on either side.

The candidate replay audit records zero exception deaths and zero recorded
turns at or above the relevant unit bytecode limit across these 28 games. It
finds no isolated ten-health starvation-loss observations, but leaves 32 king
health-loss turns with incoming rat bites unclassified. Those counters do not
prove zero starvation damage. A scan of the logs found seventeen
viewer WebSocket port-bind warnings during concurrent runs; those warnings do
not correspond to bot exceptions. The replay audit and complete game results
are independent evidence that the matches finished. Food safety on this suite
does not guarantee survival under stronger opponent pressure.

The tracked [final-game CSV](sprint2-comparison.csv) includes both teams' final
metrics for all 28 games. The [validation record](sprint2-validation.json)
retains candidate/baseline identities, source and JAR hashes, engine and map
identities, per-case seeds, log/replay fingerprints, fixed-round totals and
candidate replay-audit counters. Its raw XML and replay-audit hashes identify
the local generated evidence. The auxiliary audit reader remains local; it is
not an added simulator dependency or production component.

Local raw artifacts, deliberately ignored by Git:

- `matches/sprint2-pr-final-main-20261027/`
- `matches/sprint2-pr-final-team2-20261027/`
- `matches/sprint2-pr-final-analysis/`

Each simulation directory retains its manifest, source snapshots, JARs,
commands, full checkpoint CSV, reports, logs and replays. The earlier directories
without `final` measure `2cd45c4` before the broadcast fix and are not the final
PR evidence. These local generated files are not included in a clone.

To repeat the final comparisons, fetch the recorded main and Team 2 sources,
then use JDK 21 and Python 3.10+:

```powershell
git fetch upstream main
git fetch https://github.com/Naton-Cai/Team_2_BattleCode_454.git main:refs/remotes/team2/main
.\gradlew.bat sim -PsimPreset=full -PsimCandidate=a14183e56acd4fc55b9ce555ec729e05fda06c95 -PsimBaseline=711b3b669d0826c0353d17e4b7ad43759b662626 -PsimOffline=true
.\gradlew.bat sim -PsimPreset=full -PsimCandidate=a14183e56acd4fc55b9ce555ec729e05fda06c95 -PsimBaseline=cd1f4b73b44695552e22609d3247744547cae2ca -PsimBaselinePackage=ratplayer -PsimOffline=true
```

Use `./gradlew` on macOS/Linux. If either remote moves, these results describe
the recorded revisions; compare with the new team main before merging. No
code was merged or submission uploaded by these checks.

## Independent review

A separate agent reviewed the actual proposed code, tests, strategy assumptions,
guides and final comparison evidence. Its home-publication finding was fixed and
regression-tested. Final review reconciled CSV totals, all 56 log/replay hashes,
the coverage XML fingerprint and the unchanged production/test/build tree since
`a14183e`. No blocking finding remains for opening a draft PR; teammate review
is still required before adoption.

## Limits

These policies cannot guarantee food after a worker wipe or safety against
stronger opponents. Combat can interrupt normal deliveries; emergency trips
retain priority. Shared targeting is implemented and scenario-tested, but an
independent match-win benefit is unproven. Navigation has no remembered threat
costs, global map or mine memory. The bot does not promote, carry or throw rats.

See [the teammate guide](SPRINT_2.md) for the full priorities and experimental
values, and [testing](TESTING.md) for report locations and comparison commands.
