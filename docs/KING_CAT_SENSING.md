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
