# Return with exactly 20 cheese

An ordinary Baby Rat previously returned only with **more than 20 cheese**.
A worker carrying exactly 20 could continue searching for another pile while
its existing cargo remained undelivered. This change makes the existing return
condition inclusive: **20 or more** starts the normal delivery route.

## Read the change

The production change is one condition in `BabyRat.takeTurn`. It calls the
existing `returnCheese` method, which faces and locates the king, transfers to
a reachable king footprint tile, or navigates home. It does not change that
method, the navigator, spawning, combat or shared memory. Loads below 20 retain
the main branch's collection behavior.

Twenty is an inherited experimental cargo target, not an engine capacity.
Returning earlier can bank food sooner but can also spend more turns traveling.
The combined Sprint 2 bot's results do not establish the benefit of this one
condition in isolation.

## Test and run

With JDK 21, from the repository root on Windows:

```powershell
.\gradlew.bat clean test verify
.\gradlew.bat run
```

On macOS/Linux use `./gradlew`. `run` compiles saved Java files, plays against
the configured opponent and writes a replay under `matches/`.

The new public-behavior test starts next to a reachable king and another cheese
pile. Loads **0 and 19** collect; loads **20 and 21** deposit their current
cargo and leave the other pile alone. The exactly-20 case fails under the old
condition. No other Sprint 2 policy is required for this standalone PR.

## Isolated results — October 28, 2026

- Tested candidate: `2f3676834398b660e23b8763c7839a6a1f5194b8`.
- Baseline: team main `a59ebb01c8586baa2f3e171e26c6c8c96ab3f132`.
- JDK 21, engine 1.2.6; seven predefined maps, both starting sides and one
  fixed seed per map. All **14/14 games completed**.
- All **18 unit tests** and the engine verifier pass. The new regression
  failed against the old condition before the fix.

The candidate wins **9-5**, including two coin flips split one each. Excluding
those coin flips it wins eight and loses four. **Delivery income regresses**:
final delivered totals are 48,640 / 68,120; the six games where both sides
reach round 1,000 total 25,700 / 41,980. Final totals reflect different match
lengths, and this sample does not establish a general economic improvement.
The open-map losses and lower fixed-round deliveries are material tradeoffs
for review, despite fixing the exactly-20 return behavior.

The [per-game CSV](delivery-boundary-results.csv) records both sides' final
metrics and seeds. Raw manifests, exact source/JAR hashes, logs and replays
remain locally under `matches/sprint2-split-delivery-20261028/`; those generated
files are not included in a clone. Later documentation commits leave the tested
production code unchanged.

To repeat this comparison with Python 3.10+ installed:

```powershell
.\gradlew.bat sim -PsimPreset=full -PsimCandidate=2f3676834398b660e23b8763c7839a6a1f5194b8 -PsimBaseline=a59ebb01c8586baa2f3e171e26c6c8c96ab3f132 -PsimOffline=true
```
