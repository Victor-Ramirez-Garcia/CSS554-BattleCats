# BattleCats — Battlecode 2026

Java bot for the course project, using **Battlecode engine 1.2.6** and the
scaffold's separately versioned **client 1.0.7**. Start development branches from
the latest `main` in the upstream team repository.

## Development workflow

Use the public team main as the reference and keep development branches local
or on your personal fork. The simulation mode fetches main without pushing any
branches. It uses upstream/main when configured, otherwise origin/main.

Before proposing a PR, commit the candidate, run the full comparison with
`-PsimCandidate=HEAD`, and have a separate reviewer inspect the code and results.
See [AGENTS.md](AGENTS.md) for this project's development and review guidance.

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

## Easy simulations

Save your Java edits and run the quick comparison:

```powershell
.\gradlew.bat sim
```

Requires Python 3.10+ and the same JDK 21 used above; no extra Python packages
are needed. Gradle finds Python automatically, or you can specify an interpreter
with `"-PsimPython=C:\path\to\python.exe"`. On macOS/Linux use `./gradlew`.

The default snapshots saved local Java files, including new untracked files
under `src/`; no commit is needed for an experiment. It fetches `upstream/main`
as the baseline, or `origin/main` when no `upstream` remote exists, and runs
`battlecats` against that revision on **DefaultSmall and DefaultMedium, with
both starting sides** (four matches). The bots keep their original Java packages
and run from separate JARs. Each source snapshot is compiled and verified.

```powershell
# Full comparison: seven maps, both sides (14 matches).
.\gradlew.bat sim -PsimPreset=full

# Choose maps and the local bot package you are developing.
.\gradlew.bat sim -PsimPackage=mybot -PsimMaps=DefaultSmall,cheesefarm

# Final comparison: commit changes first, then test that exact revision.
.\gradlew.bat sim -PsimPreset=full -PsimCandidate=HEAD
```

Each run creates a fresh `matches/sim-.../` folder with `summary.md`,
`results.csv`, source snapshots, exact baseline/candidate identities in
`manifest.json`, and `.bc26` replays plus logs for each match. Load a replay in
the Battlecode graphical client's replay viewer to inspect a run. The summary
shows delivered cheese, wins and their reasons, end rounds, and map regressions.
Final totals can reflect different match durations; strict round 1,000 results
include only matches that reach that round. Failed or interrupted runs retain
completed cases and are labeled incomplete/failed rather than a successful suite.

Other options: `-PsimBaseline=origin/main` chooses a baseline ref;
`-PsimBaselinePackage=examplefuncsplayer` selects its bot;
`-PsimOutput=matches/my-test` chooses a **new** output folder;
`-PsimPrepareOnly=true` only snapshots, compiles, and verifies;
`-PsimOffline=true` skips fetching and uses the cached baseline, which may be
outdated. Saved snapshots include all Java sources under `src/`, so unrelated
Java compile errors also stop a run. This mode does not compile Scala bots.
Runs save their artifacts locally and refresh remote refs when fetching main.
For direct use and additional options, run
`python tools/simulate.py --help`.

## Sprint 1 Baby Rat baseline

- `src/battlecats/RobotPlayer.java` runs the turn loop and selects the behavior
  for each unit type. Each robot keeps its own behavior instance and memory.
- `BabyRat.java` scans for a friendly king near spawn, explores, remembers
  visible cheese, picks it up, returns to a friendly king, transfers the whole
  load, and resumes searching. Targets are rechecked when visible. A missing
  king is forgotten and a nearby replacement can be discovered.
- `Navigation.java` turns toward the desired move and follows obstacles when
  a direct move is blocked. Exploration uses changing waypoints and scans.
- `RatKing.java` is a small stationary spawning baseline, producing up to four
  babies per king while reserving cheese. It makes collector testing possible;
  the team's fuller king strategy can replace it independently.
- `src/examplefuncsplayer/` and `src/testfuncsplayer/` remain available as local
  opponents. The classmate's test-player implementation is preserved.

Baby Rats do **not** receive a global list of cheese. In v1.2.6 their sensing
cone is 90 degrees with radius squared 20 (about 4.47 tiles), and cheese is
discovered through visible map information. Walls do not occlude that cone.
The collector remembers a target outside its current field of view, then checks
whether cheese is still there when it becomes visible again.

This is a basic collector, not an optimal path planner. It does not implement
combat, traps, king promotion, global map sharing, or cat avoidance. Carried
cheese slows movement, so babies return after each pickup. If a king disappears,
the collector searches for another; rapidly moving kings can make delivery less
reliable than the stationary baseline. Collection and delivery use the same
logic in cooperation and backstabbing.

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
