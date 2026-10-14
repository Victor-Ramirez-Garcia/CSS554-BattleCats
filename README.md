# BattleCats — Battlecode 2026 starter

Java starter for the CSS554 group project, pinned to **Battlecode engine 1.2.6**.
The graphical replay client has its own version: **1.0.6**, as supplied by the official scaffold.

## Prerequisites

- A 64-bit **JDK 21** (for example [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=21)). Set `JAVA_HOME` to its installation directory.
- Git and an internet connection for the first Gradle/dependency download.
- No separate Gradle installation is needed; use the checked-in wrapper.
- Python is only needed if you choose to use the upstream Python example.

## Run locally

Clone this repository and enter its directory. In PowerShell:

```powershell
# If Java is not already configured, substitute your JDK 21 directory:
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\gradlew.bat version
.\gradlew.bat build
.\gradlew.bat verify
.\gradlew.bat run
```

On macOS/Linux, set `JAVA_HOME` to your JDK 21 directory and use `./gradlew` instead.
`version` should report engine **1.2.6** and client **1.0.6**.
`verify` runs the engine's bytecode verifier on `battlecats` using the compiled JAR.
This avoids an engine 1.2.6 bug in folder verification on Windows.
`run` plays `battlecats` against `examplefuncsplayer` on `DefaultSmall` without requiring an open GUI.

The match writes `matches/battlecats-vs-examplefuncsplayer-on-DefaultSmall.bc26`.
Open that file in the Battlecode client downloaded under `client/` by `build`.
Keep the client in that folder, as the upstream scaffold expects.

To change opponents/maps without editing shared defaults:

```powershell
.\gradlew.bat listPlayers listMaps
.\gradlew.bat run -PteamA=battlecats -PteamB=examplefuncsplayer -Pmaps=DefaultSmall
.\gradlew.bat run -PoutputVerbose=true
```

To compile and run without downloading the graphical client:

```powershell
.\gradlew.bat classes run
```

Build artifacts, client downloads, and replays are ignored by Git.

If a managed workspace prevents Gradle from deleting generated directories, put build
outputs in a writable temporary directory instead:

```powershell
.\gradlew.bat "-PlocalBuildDir=$env:TEMP\battlecats-local-build" build verify run zipForSubmit
```

Use the same override on subsequent commands. Sources, replays, and `submission.zip`
stay in the project directory; compiled files go to the selected directory.

## Team bot and scope

- `src/battlecats/RobotPlayer.java`: team entry point; a minimal movement loop that yields each turn.
- `src/examplefuncsplayer/`: unchanged official Java example, kept as a baseline opponent.
- `src/examplefuncsplayer_py/` and `run.py`: upstream optional Python example/support.
- `build.gradle`, `gradle/`, `gradlew`, `gradlew.bat`: build and execution tooling.
- `gradle.properties`: shared match defaults.

This starter verifies that the project builds and runs. **Rat King spawning and Baby Rat cheese collection/delivery are still to be implemented for Sprint 1.**
There are no team behavior tests yet; add meaningful tests as the strategy is implemented.
The upstream arithmetic-only sample test was omitted.

## Submission ZIP

```powershell
.\gradlew.bat zipForSubmit
```

This compiles the source and creates `submission.zip` containing only one top-level
`battlecats/` folder with `RobotPlayer.java` and any additional Java sources in that package.
The example opponents are excluded. Coordinate with the team before uploading to the course website;
this command does not upload anything.

## Version policy and sources

Do not update to the latest engine automatically. `update` is deliberately disabled;
review any version change with the team and the course requirements first.

Imported from [the official Java scaffold](https://github.com/battlecode/battlecode26-scaffold)
at commit `f69e2ab872a0061c9d4a684aa1dd798a0829da85`.
The Gradle 8.10 wrapper download is verified against its official SHA-256 checksum.
The upstream AGPL-3.0 license is preserved in `LICENSE`.

- [Game specification 1.2.6](https://releases.battlecode.org/specs/battlecode26/1.2.6/specs.pdf)
- [API documentation 1.2.6](https://releases.battlecode.org/javadoc/battlecode26/1.2.6/index.html)
