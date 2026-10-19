# BattleCats — Battlecode 2026

Java bot for the course project, using **Battlecode engine 1.2.6** and the
scaffold's separately versioned **client 1.0.7**. Start development branches from
the latest `main` in the upstream team repository.

## Run and check

Install a 64-bit JDK 21 and set `JAVA_HOME` to its installation directory.
Use the checked-in Gradle wrapper; a separate Gradle installation is unnecessary.
The first run downloads Gradle and engine dependencies.

```powershell
.\gradlew.bat test verify
.\gradlew.bat run
.\gradlew.bat zipForSubmit
```

On macOS/Linux, use `./gradlew` instead. `run` compiles automatically and plays
`battlecats` against `examplefuncsplayer` on `DefaultSmall`. Replays are written
under `matches/`. `build` also downloads the graphical replay client under
`client/`; keep the client in that directory.

```powershell
.\gradlew.bat listMaps
.\gradlew.bat run -PteamB=testfuncsplayer -Pmaps=DefaultSmall
.\gradlew.bat run -PoutputVerbose=true
```

The submission task compiles and packages only Java sources under one top-level
`battlecats/` folder in `submission.zip`. It does not upload the ZIP.
Keep `engine_version.txt` at 1.2.6 unless the team agrees to change it.

## Sprint 1 Baby Rat baseline

- `src/battlecats/RobotPlayer.java` runs the turn loop and selects the behavior
  for each unit type. Each robot keeps its own behavior instance and memory.
- `BabyRat.java` reads shared king locations, explores, remembers visible cheese,
  collects a batch, returns, transfers the whole load, and resumes searching.
  Targets are rechecked when visible. It follows updated king positions outside
  its vision and falls back to sensing friendly kings when no fresh broadcast
  is available.
- `Navigation.java` turns toward the desired move and follows obstacles when
  a direct move is blocked. Exploration uses changing waypoints and scans.
- `RatKing.java` publishes its location every turn and builds a baby when the
  engine permits it and the current spawn price leaves its food reserve intact.
  There is no lifetime baby limit, so it can grow the workforce and replace
  dead collectors. It spends up to 40 starting cheese on workers, then uses
  delivered income for growth. Its reserve is the starting stock minus that
  budget and one king's upkeep to date, with a minimum of 100. This protects
  the king from spending its starting food on workers that cannot find cheese.
  Multiple kings make this projection conservative. Staying stationary and
  this spending policy are strategy choices the team's king strategy can change.
- `SharedKingLocations.java` reserves shared slots 0–41. Slots 0/1 preserve the
  old latest-publisher x/y pair; slots 2–41 hold five independent king records
  with IDs, positions, and absolute round timestamps. Babies use the nearest
  record refreshed within three rounds. Dead kings' records expire and can be
  reused. Slots 42–63 remain available for other team communication. King
  movement code must publish the new location after moving each turn.
- `src/examplefuncsplayer/` and `src/testfuncsplayer/` remain available as local
  opponents. The classmate's test-player implementation is preserved.

Baby Rats do **not** receive a global list of cheese. In v1.2.6 their sensing
cone is 90 degrees with radius squared 20 (about 4.47 tiles), and cheese is
discovered through visible map information. Walls do not occlude that cone.
The collector remembers a target outside its current field of view, then checks
whether cheese is still there when it becomes visible again.

The collector currently returns at 80 carried cheese, or after 60 rounds since
its first pickup if the batch stays smaller. A pickup takes the whole pile, so
80 is a return trigger rather than a carrying cap. It also unloads a smaller
amount whenever a king is already within transfer range. The threshold and
timeout can be supplied to the `BabyRat` constructor for local experiments.

In engine 1.2.6, carried cheese multiplies movement/action cooldown by
`1 + 0.01 * rawCheese`, with integer truncation when cooldown is added. Thus
100 cheese doubles the added cooldown and 1,000 makes it 11 times as large;
larger batches trade fewer return trips against slower movement and cargo lost
on death. The engine prices a new baby from the number currently alive, not
the number ever spawned: `10 + 10 * floor(livingBabies / 4)`. The five-unit
limit applies to Rat Kings.

This remains a basic collector. It does not implement combat, traps, king
promotion, global map sharing, or cat avoidance. Collection and delivery use
the same logic in cooperation and backstabbing.

## Local comparisons

[Benchmark results and limitations](docs/foraging-benchmark.md) compare return
thresholds, separate spawning changes from collection behavior, and document
map-specific regressions. Results count cheese units, not match points.
With Python 3.10 or later and JDK 21 installed, the runner compiles temporary players and
saves source snapshots, logs, and replays under ignored `matches/` directories:

```powershell
python tools/benchmark_foraging.py --thresholds 1 10 40 80 120 --output matches/foraging-pilot
```

The default experiment gives both teams the same four-spawn king for population
control. Use the documented production comparisons to test the actual spawning
policy; that experimental cap is absent from the submitted bot.

## Sources and build support

- [Upstream team repository](https://github.com/Victor-Ramirez-Garcia/CSS554-BattleCats)
- [Official scaffold](https://github.com/battlecode/battlecode26-scaffold)
- [Game specification 1.2.6](https://releases.battlecode.org/specs/battlecode26/1.2.6/specs.pdf)
- [API documentation 1.2.6](https://releases.battlecode.org/javadoc/battlecode26/1.2.6/index.html)

The Gradle 8.10 wrapper JAR and distribution use verified official checksums.
`verify` checks the compiled JAR to avoid the engine's Windows folder-path issue.
For synced-directory build problems, use a writable local output directory:

```powershell
.\gradlew.bat "-PlocalBuildDir=$env:TEMP\battlecats-local-build" test verify run
```

The upstream AGPL license remains in `LICENSE`.
