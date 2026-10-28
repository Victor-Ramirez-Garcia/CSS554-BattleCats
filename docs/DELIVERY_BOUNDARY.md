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
