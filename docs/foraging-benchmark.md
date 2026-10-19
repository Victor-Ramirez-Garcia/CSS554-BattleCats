# Shared-home and batched-foraging benchmark

Measured on October 18, 2026, with Battlecode **1.2.6** and portable Temurin
**21.0.12.1+1**. The baseline is the previously merged `battlecats` collector,
pinned to commit `6612d97ee3d540be42d78767accdba6221eb0eee`.
Amounts below are **cheese units**,
not match points. Every map is run from both starting sides.

## Policies and controls

The original collector returns after any pickup and discovers a king through
local sensing. The refactor reads shared king locations, unloads opportunistically
when beside a king, and otherwise starts returning at a cargo threshold or
**60 turns since its first pickup**. That timeout starts the return journey; it
does not guarantee delivery within 60 turns. Pickups take the whole pile, so the
threshold is a soft trigger, not a cargo limit.

The population-controlled experiments give both sides the same stationary king
implementation: at most **four lifetime spawns per king**, a 100-cheese reserve,
and shared-location publishing. This cap is an experimental control, **not the
production spawning policy**. Live populations can differ because babies die.
Production experiments separately remove that spawn cap. Their final king policy
allows 40 cheese of startup spending, then protects the projected starting food
stock after one king's upkeep, with a minimum reserve of 100 cheese. Deliveries
can therefore fund additional babies and replacements without a lifetime cap.

The engine replay's cumulative transferred-cheese counter is authoritative for
delivery amounts. Instrumented pickup logs measure collected cheese; delivery
logs must equal replay totals at every checkpoint. Collected minus delivered
includes cheese lost with dead babies as well as cargo still carried. Results
record exceptions, surviving populations, match duration, and source hashes.

## Population-controlled results

The pilot used DefaultSmall, DefaultMedium, and cheesefarm: five thresholds
(1, 10, 40, 80, 120), with both starting sides, for **30 matches**. An additional
**16 matches** tested thresholds 80 and 120 on DefaultLarge, minimaze,
safelycontained, and pipes.

| Threshold | Pilot delivered at round 1,000, new / original | Pilot delivered at match end, new / original | Additional maps at round 1,000, new / original | Additional maps at match end, new / original |
| --- | ---: | ---: | ---: | ---: |
| 1 | 7,920 / 10,460 (-24.3%) | 11,740 / 15,120 (-22.4%) | Not run | Not run |
| 10 | 7,920 / 10,460 (-24.3%) | 11,740 / 15,120 (-22.4%) | Not run | Not run |
| 40 | 10,700 / 11,240 (-4.8%) | 18,240 / 18,920 (-3.6%) | Not run | Not run |
| 80 | 14,300 / 10,580 (+35.2%) | 19,820 / 16,100 (+23.1%) | 12,580 / 10,020 (+25.5%) | 20,220 / 18,240 (+10.9%) |
| 120 | 14,960 / 11,140 (+34.3%) | 21,980 / 16,560 (+32.7%) | 10,700 / 9,980 (+7.2%) | 18,160 / 18,280 (-0.7%) |

For threshold 80 across all seven maps (**14 matches**), collected cheese was
**28,520 / 21,000 (+35.8%)** at round 1,000 and **41,920 / 34,920 (+20.0%)** at
match end. Delivered cheese was **26,880 / 20,600 (+30.5%)** at round 1,000 and
**40,040 / 34,340 (+16.6%)** at match end.

The aggregate hides important regressions. Each row combines the two starting
sides:

| Map | Delivered at round 1,000, new / original | Delivered at match end, new / original | Change at match end |
| --- | ---: | ---: | ---: |
| DefaultSmall | 6,440 / 4,060 | 8,640 / 6,920 | +24.9% |
| DefaultMedium | 5,720 / 2,880 | 8,260 / 4,760 | +73.5% |
| DefaultLarge | 5,140 / 2,560 | 5,140 / 2,560 | +100.8% |
| cheesefarm | 2,140 / 3,640 | 2,920 / 4,420 | -33.9% |
| minimaze | 3,140 / 3,060 | 5,840 / 6,500 | -10.2% |
| safelycontained | 4,300 / 4,400 | 9,240 / 9,180 | +0.7% |
| pipes | 0 / 0 | 0 / 0 | No gathering by either side |

## Isolating batching

A further **six matches** used the same refactored shared-home collector on both
sides, changing only its threshold: 80 versus 1, with identical capped kings.
Threshold 80 delivered **11,760 / 10,040 (+17.1%)** at round 1,000 but
**14,720 / 16,760 (-12.2%)** at match end. Collected amounts were
**12,820 / 10,280 (+24.7%)** and **15,880 / 17,000 (-6.6%)**, respectively.

At match end, delivery totals were 4,980 / 8,980 on DefaultSmall, 6,840 / 4,940
on DefaultMedium, and 2,900 / 2,840 on cheesefarm. Across these six games the
threshold-80 side had six babies alive at round 1,000 versus nine for threshold
1, and three versus six at match end. This is a material late-game regression;
the original-baseline gains cannot all be attributed to batching.

## Production spawning

An initial experiment removed the spawn cap while keeping only the 100-cheese
reserve. It was rejected: on cheese-poor maps, spending the initial food stock
on many babies caused early king starvation. For example, both pipes games
ended at round 155 with zero collection. Babies do not have food upkeep; the
problem was spawning expenditure consuming cheese needed by the king. The
projected-food reserve above was added before the final production comparison.

The final king policy was tested against the previous collector and capped king
on all seven maps (**14 matches**). Delivered cheese was **84,300 / 13,540
(6.23×)** at round 1,000 and **153,440 / 21,080 (7.28×)** at match end.
Collected cheese was **111,520 / 13,880** and **198,520 / 21,560**, respectively.
All 14 games reached round 1,000 and ended between rounds 1,290 and 2,000.

These are whole-bot gains that include worker growth and replacement. Across
the 14 games, surviving babies at round 1,000 summed to **541 / 32**; this is
not a measurement of per-rat efficiency.

To isolate the return threshold under the final spawning policy, another
**12 matches** used the same refactored collector and king on both sides,
changing only threshold 80 versus 1. Delivered cheese was **60,380 / 40,620
(+48.6%)** at round 1,000 and **120,440 / 82,840 (+45.4%)** at match end.
Collected cheese was **80,780 / 43,720** and **151,480 / 88,240**, respectively.
Both sides follow the same spawn policy, but extra deliveries can finance extra
workers: surviving babies summed to **447 / 399** at round 1,000 and **442 / 402**
at match end. This measures batching together with that population feedback.

| Map | Final production delivered, refactor / previous capped bot | Final delivered with identical production kings, threshold 80 / 1 |
| --- | ---: | ---: |
| DefaultSmall | 27,480 / 2,160 | 18,600 / 15,460 |
| DefaultMedium | 42,200 / 1,520 | 30,980 / 22,140 |
| DefaultLarge | 49,500 / 1,180 | 41,400 / 26,340 |
| cheesefarm | 19,160 / 3,420 | 13,140 / 1,920 |
| minimaze | 5,840 / 3,620 | 7,000 / 7,620 |
| safelycontained | 9,260 / 9,180 | 9,320 / 9,360 |
| pipes | 0 / 0 | Not run |

**80 cheese is the initial default**, supported by the additional-map comparison
against 120 and the final production comparison against 1. It is not a universal
optimum: minimaze delivered **8.1% less** than threshold 1 with identical
production kings, safelycontained was effectively flat, and both policies still
gathered nothing on pipes. The capped shared-home control also showed a late-game
regression. Navigation was unchanged by this refactor.

These five completed suites contain **78 matches**, with zero logged bot
exceptions and exact delivery-log/replay agreement. Rejected exploratory runs
are separate from that count. An actual Gradle match using the production
default constructor also completed on DefaultSmall side A at round 1,530,
matching the benchmark outcome. All 24 project tests, engine verification,
and submission packaging passed.

## Reproducibility and limits

Map seeds are fixed in the supplied maps: DefaultSmall **158**, DefaultMedium
**977**, DefaultLarge **370**, cheesefarm **495**, minimaze **239**,
safelycontained **856**, and pipes **992**. These runs test one seed per map,
not a random-seed sweep. Side swaps address starting-position differences but
do not establish statistical generality. The two bots interact, so the original
bot's totals can change when its opponent's threshold changes.

Use matched round-1,000 results to compare equal elapsed game time. Match-end
totals share a duration within each match, but different matches can finish at
different rounds. Equal spawn caps do not ensure equal live populations or
isolate pathfinding efficiency. The new king remains stationary in these runs;
moving-king tracking is covered by unit tests, not these match benchmarks.

The local evidence is under ignored `matches/foraging-refactor/`, with separate
`pilot/`, `heldout/`, `shared-control/`, `final-production/`, and
`final-shared-control/` directories.
Each directory preserves a manifest, the exact harness and generated Java
sources, replay files, logs, command lines, `comparison.csv`, and `summary.csv`.
Pilot/additional-map seed audits are in `engine-seeds.csv`; newer comparison
files include seeds directly. Manifests pin the engine and source SHA-256 hashes.
For the population-controlled suites, the collector's only change after the
initial snapshots was its default threshold from 40 to 80; the harness passes
an explicit threshold, so that edit does not change measured behavior. Final
production suites use fresh source snapshots including the projected-food
reserve; rejected experiments retain their separate manifests.

[The tracked result extract](foraging-benchmark-results.csv) contains both roles
at round 1,000 and match end for all 78 matches, including map seeds, populations,
exception counts, and each suite's full source-snapshot fingerprint. Raw logs
and replays remain local to keep the change small.

From the repository root, with Python 3.10+ and a JDK 21 installed:

```powershell
$jdk21 = 'C:/path/to/jdk-21'
$baseline = '6612d97ee3d540be42d78767accdba6221eb0eee'
python tools/benchmark_foraging.py --java-home $jdk21 --baseline-ref $baseline --thresholds 1 10 40 80 120 --maps DefaultSmall DefaultMedium cheesefarm --sides A B --output matches/repro-pilot
python tools/benchmark_foraging.py --java-home $jdk21 --baseline-ref $baseline --thresholds 80 120 --maps DefaultLarge minimaze safelycontained pipes --sides A B --output matches/repro-additional
python tools/benchmark_foraging.py --java-home $jdk21 --baseline-ref $baseline --baseline-kind shared --thresholds 80 --maps DefaultSmall DefaultMedium cheesefarm --sides A B --output matches/repro-shared-control
python tools/benchmark_foraging.py --java-home $jdk21 --baseline-ref $baseline --thresholds 80 --maps DefaultSmall DefaultMedium DefaultLarge cheesefarm minimaze safelycontained pipes --sides A B --king-policy production --baseline-king-policy capped --output matches/repro-production
python tools/benchmark_foraging.py --java-home $jdk21 --baseline-ref $baseline --baseline-kind shared --thresholds 80 --maps DefaultSmall DefaultMedium DefaultLarge cheesefarm minimaze safelycontained --sides A B --king-policy production --baseline-king-policy production --output matches/repro-production-control
```

The engine JAR must already be cached by Gradle, or supplied with `--engine-jar`.
Commands use the current checkout's refactor and reproduce the experiment design;
exact historical snapshots are preserved with the local evidence. Reusing an
output directory resumes only when source/settings hashes still match. Use a
fresh output directory after editing sources.
