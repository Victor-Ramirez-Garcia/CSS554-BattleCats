# Sprint 2 teammate guide

The bot keeps one Rat King and uses Baby Rats as collectors, scouts and local
fighters. It runs on engine **1.2.6**. The strategy comes from selected revision
`6cc7522bcd688f2bcf668bd8cde699ac97f19c8d`, with an additional interrupted-write
fix for home broadcasts. The PR excludes the later contextual delivery,
threat-aware travel and mine-memory policies.

## Read and edit the code

| File in `src/battlecats/` | Responsibility |
| --- | --- |
| `RobotPlayer.java` | Select the unit behavior and yield each engine turn |
| `BabyRat.java` | Choose collection, delivery, safety and exploration priorities |
| `Navigation.java` | Outward opening, seeded waypoints and bounded wall following |
| `CatTactics.java` | Nearby cat bites, terrain-protected approaches and escape |
| `PvPTactics.java` | Conditional betrayal, local force estimates and retaliation |
| `Construction.java` | Rat/Cat Traps, route clearing and isolated dirt screens |
| `RatKing.java` | Threat evasion, defense and worker spawning |
| `FoodBudget.java` | Bank reserve, emergency threshold and income-funded growth |
| `KingSignal.java` | Fresh home location in shared slots 0-2 |
| `FocusSignal.java` | Fresh enemy ID in shared slots 3-7 |

Each robot owns its behavior state. Helpers take the controller and observations;
they do not reach into another behavior's fields. Only kings write shared memory.
Both signals invalidate their timestamp before writing data and publish the new
timestamp last, so an interrupted write cannot expose a partial fresh message.
Actions use their matching `can...` checks. Test a policy through its public
behavior before changing a threshold; the engine's bytecode limit makes extra
scans and allocations significant.

## What a worker does

1. Refresh its known king using local sightings and a fresh shared location.
   A newborn without a home tries up to four facing scans before exploring.
2. If the bank is at most 200 and it carries any cheese, commit to delivery
   until empty. Depositing and cat escape take priority. This trip can clear
   blocking dirt from cargo when some food remains to deliver.
3. Otherwise, return when starting the turn with **at least 20 cheese**.
   A smaller load continues collecting. Return eligibility is recomputed each
   turn; normal returns do not have the emergency trip's commitment flag.
4. Try available collection or banking before optional actions. Once home
   scanning is complete, healthy empty workers can engage a nearby cat before
   economic facing hides it. A protected approach requires permanent terrain
   that prevents a cat footprint landing; scratches remain possible.
5. With no observed cat, offer bounded Rat Trap placement and local PvP.
   Returning workers first face home and try to bank. After betrayal, defense
   can interrupt an ordinary trip; an emergency food trip keeps priority.
6. Try other useful construction and legal cat bites, then escape an observed
   cat. A blocked escape owns the turn instead of allowing an unsafe move.
7. Continue the existing collection/return route or explore. Check again for
   legal combat opportunities after movement. Pickup and movement have different
   engine cooldowns, so an action may follow a pickup in the same turn.

The first four successful king births prefer north, east, south and west, with
immediate legal fallback when blocked. A worker's first exploration target lies
outward from its known home; later targets use a robot-ID-seeded random sequence.
Visible cheese takes priority. This creates no permanent scout role or attack wave.

The worker's current robot scan is reused only while location, facing, round and
action cooldown stay unchanged. Retained combat observations preserve a cat seen
before turning. PvP deduplicates repeated robot IDs before counting local support.

## Combat and construction

During cooperation, a Baby Rat may initiate a free bite from round 100 onward
only when empty, at least 75 health, the bank exceeds 200, and no cat or enemy
king is observed. Its local estimate requires at least two allied babies, an
extra allied body and twice the observed enemy health. These are policy gates,
not a prediction of the full enemy army. The king never initiates betrayal.

After cooperation ends, workers bite, approach a locally advantageous fight,
or retreat from a disadvantage. A fresh king-selected enemy ID can coordinate
targets, but workers still need local observations and legal actions. A free
finishing bite takes priority. Signals never create pursuit of an unseen enemy.
Tests exercise shared targeting; an independent match-win benefit has not been
established.

Each worker can successfully place at most one of each construction kind:

| Kind | Policy |
| --- | --- |
| Rat Trap | After betrayal; empty worker with at least 75 health; near an observed enemy baby; preserve the food reserve and a free finishing bite |
| Cat Trap | Legal nearby tile toward an observed cat; preserve the food reserve |
| Dirt screen | Use dirt inventory; cardinal cat-facing tile with local bypass space; avoid cheese, mines, traps, the direct objective step and the remembered home area |

Route clearing removes dirt blocking a known objective when the budget permits;
it also supplies inventory for screens. Friendly traps do not block our rats.
Dirt blocks movement for both teams, and local bypass checks do not prove global
connectivity. The bot does not seal its king behind walls. For the sprint demo,
show **two distinct construction kinds**; conditions may prevent them in a short game.

## Food and policy values

Cargo feeds a king only after transfer. The engine consumes two banked cheese
per king per round. Optional spending leaves 300 in the bank. The king supports
an opening of 16 living babies, including replacements while below that cost
band; later births need earned growth credit. Positive net bank changes add
credit, losses reduce it, and each successful birth debits twice its purchase
price. Initial funds do not count as income. Upkeep and worker spending can
hide deliveries, so this is conservative net accounting.

The table lists strategy choices, not engine constants or optimal values:

| Policy | Value and purpose |
| --- | --- |
| Normal return | At least 20 cargo; avoid indefinitely holding exactly one 20-cheese pile |
| Emergency return / spending reserve | Bank at most 200 / retain 300; about 100 / 150 upkeep rounds for one king before other spending |
| Opening / growth investment | 16 living babies / twice the purchase price; balance map presence and food |
| Cheese target timeout / retry pause | 80 / 40 rounds; bound travel toward inaccessible targets |
| Exploration waypoint timeout | 30 rounds; retry an unproductive direction |
| Signal freshness | At most three rounds; tolerate two-round timestamp buckets and turn order |
| Cat engagement | Empty, at least 75 health, within distance squared 9 of the footprint |
| Trap / dirt-screen cat proximity | Distance squared 4 / 9; local placement estimates |
| Dirt-screen home clearance | Distance squared 18; leave space near the remembered king |
| Worker cat escape weights | 10,000 crush risk, 1,000 vision risk, distance tie-break capped at 25 |

King evasion retains the baseline scoring weights while correctly including
neutral cats. Cat safety can make the king move away from collectors. Enemy
baby-only evasion retains the delivery-range constraint; a hostile king or cat
can override it. Shared home coordinates reduce stale-home travel but cannot
guarantee delivery through blocked terrain.

The bank thresholds, population size, local force gates and spending bounds
are measured choices for this single-king bot. They do not guarantee food or
worker survival against stronger opponents. There is no worker promotion,
rat carrying/throwing, remembered mine strategy or revealed global map.

## Run, inspect and change one thing

With JDK 21 and Python 3.10+ available, use the checked-in wrapper:

```powershell
.\gradlew.bat clean test jacocoTestReport jacocoTestCoverageVerification verify
.\gradlew.bat sim -PsimPreset=full -PsimCandidate=HEAD
.\gradlew.bat zipForSubmit
```

On macOS/Linux use `./gradlew`. Keep the exact candidate and baseline, maps,
sides, logs and replays for a comparison. `full` means seven predefined maps
on both sides, not every map or seed. See [testing](TESTING.md) for report paths
and [the recorded results](SPRINT_2_RESULTS.md) for benefits and regressions.
Submission packaging creates `submission.zip`; it does not upload it.

For the demo, show test/coverage HTML, then a replay with cat bites, two distinct
construction kinds and continued deliveries. Course ranking/scrimmage evidence,
GitHub activity and the PM's Trello/reflection are separate demo materials.
