# Let the king sense neutral cats

`RatKing.avoidEnemyThreats` already has a policy for moving away from observed
cats. Its observation query requested only the opposing team, but actual cats
belong to **`Team.NEUTRAL`**. The existing cat branch therefore missed them.

This change queries visible robots without a team filter, then accepts cats
and opposing Baby Rats as threats. The explicit Baby Rat team check prevents
friendly workers from becoming threats after broadening the query. The
existing separate friendly-worker query still supplies delivery constraints.

This is a sensing fix. It retains the current evasion scoring, vision geometry,
legal-move checks, delivery-range constraint, spawning and cheese collection.
It adds no bites, traps, PvP or food-budget policy. The fuller Sprint 2 cat
combat behavior will be reviewed separately.

The new public-behavior regression supplies a neutral cat and requires a move
farther away. It fails under the opponent-only query. Existing scenarios also
check that friendly workers do not trigger evasion and that enemy Baby Rats
are not treated as friendly delivery allies.

With JDK 21, run from the repository root:

```powershell
.\gradlew.bat clean test verify
.\gradlew.bat run
```

On macOS/Linux use `./gradlew`. The normal `run` uses the configured opponent
and map, prints the result and writes a replay under `matches/`. Tests and a
single match do not establish the general competitive value of sensing cats.

## Isolated results — October 28, 2026

- Tested candidate: `9897384d7872affcb87ed2fc285f82f5f1c71191`.
- Baseline: team main `a59ebb01c8586baa2f3e171e26c6c8c96ab3f132`.
- JDK 21, engine 1.2.6; seven predefined maps, both starting sides and one
  fixed seed per map. All **14/14 games completed**.
- All **18 unit tests** and the engine verifier pass. The neutral-cat
  regression failed against the old query before the fix.

The result is **7-7**, including four coin flips (one candidate win, three
baseline wins). Excluding coin flips it is six wins and four losses. Final
deliveries total 35,900 / 34,360, while the four games reaching round 1,000
are tied at 18,600 / 18,600. Final totals mix match lengths; this is a tested
sensing correction, not proof of a stronger general strategy or income rate.

The [per-game CSV](king-cat-sensing-results.csv) retains both sides' final
metrics and seeds. Exact source/JAR hashes, raw checkpoint data, logs and
replays remain locally under `matches/sprint2-split-king-cats-20261028/`;
these generated files are not included in a clone. Later documentation
commits leave the tested production code unchanged.

To repeat the comparison with Python 3.10+ installed:

```powershell
.\gradlew.bat sim -PsimPreset=full -PsimCandidate=9897384d7872affcb87ed2fc285f82f5f1c71191 -PsimBaseline=a59ebb01c8586baa2f3e171e26c6c8c96ab3f132 -PsimOffline=true
```
