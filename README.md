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
