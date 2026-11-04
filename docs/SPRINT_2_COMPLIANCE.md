# Sprint 2 gameplay: bites, Cat Traps and Dirt

The earlier main changes improved delivery, king sensing, home broadcasts and
coverage. They did not add the required combat or construction actions. This
integration adds those actions to the real Baby Rat turn loop and adds the
food budget needed to support optional spending.

## Requirement checklist

| Requirement | Implementation | Public behavior tests |
| --- | --- | --- |
| Baby Rats collect and deliver cheese to Rat Kings | Existing collector and transfers remain; normal return starts at 20 | `CollectorTest` round trips and boundary cases |
| Bite cats | `CatTactics.engage` and `tryBite`, called by Baby Rats; kings can bite after production | `CatTacticsTest`; collector bite/escape scenarios |
| Place two distinct kinds | `Construction.tryBuild` places **Cat Traps and Dirt** | `workerAcquiresDirtAndPlacesTwoDistinctConstructionKinds` |
| Move Dirt | Remove the next useful route obstruction, acquire inventory, then place a screen | Collector acquisition/placement scenario and `ConstructionTest` |
| Unit tests and line coverage above 20% | Existing JaCoCo report and 21% gate include every production team class | Clean test/report/gate commands below |

Two placements of Cat Trap alone do not satisfy the requirement. The two kinds
here are Cat Trap and Dirt; Rat Traps are not part of this integration. Baby Rat
promotion is not needed for Sprint 2.

## Read the implementation

1. `FoodBudget.java`: pure spending checks and the king-owned growth ledger.
2. `CatTactics.java`: free bites, protected approaches and local escape.
3. `Construction.java`: bounded Cat Trap placement, useful digging and Dirt screens.
4. `BabyRat.takeTurn`: priorities connecting these helpers to collection/delivery.
5. `RatKing.takeTurn`: budgeted births, remaining-action cat bite and home publication.

The integration comes from the preserved full Sprint 2 bot. PvP, shared focus,
Rat Traps, outward scouting and later navigation experiments are separate work.
Main's navigation, king evasion and spawn-location order are retained, including
the teammate's latest navigation comments. The current home protocol's paused-
reader guard is retained too.

## Worker priorities and interactions

An existing emergency food trip gets priority. Carriers also recheck food needs
immediately after picking up cheese and after moving. At a low bank, any positive
cargo can start an emergency return; that trip stays committed until empty even
if another worker refills the bank in the meantime.

Outside emergencies, the worker discovers home, identifies its economic target
and attempts an immediately available pickup. Healthy empty workers may bite a
nearby cat or approach it through a terrain-certified position. Otherwise, a
normal returning worker attempts an immediate deposit before optional actions.

Optional construction runs only for **empty workers**. The engine spends cargo
before the bank, so even a bank-funded optional dig could shrink a 20-cheese
return load below its threshold. A public regression reproduces that failure;
loaded workers now defer all optional construction. Emergency route clearing is
the exception: it may spend some cargo to free a delivery, but retains cheese
to deliver and keeps the trip committed. Normal navigation can still detour
around an obstruction without digging it.

Construction prefers a legal nearby Cat Trap, then removes a useful route
obstruction, then considers a Dirt screen. A free cat bite uses any action left
over. Escape movement remains available after a bite or placement. Movement
gets another food check and another chance for a legal cat bite. Current vision
scans are reused until pose, action cooldown or round changes; earlier cat
sightings are retained through turns that change facing.

## Food choices

These are inherited **experimental policies**, not game capacity limits:

- Request deliveries at a global bank of **200 or less**.
- Optional spending preserves **300 global cheese**. Carried cheese is not king food.
- Bootstrap or replace roughly **16 living workers**, inferred from engine cost bands.
- Later births require earned growth credit of **twice their purchase price**.
  The ledger observes net bank changes; the initial bank is not earned income.

The engine's cost bands determine current rat price. Each king consumes two
global cheese per round in engine 1.2.6; the thresholds assume this bot's single
king. Failed births do not consume growth credit. Snapshots advance before
spending so an interrupted turn cannot count the same income twice. These
guards cannot guarantee survival after a worker wipe or under stronger pressure.

## Cat and construction choices

Bites use the engine's zero-cheese `attack(location)` action. Every attempted
attack first checks legality; cat edges are considered across its **2-by-2
footprint**, rather than assuming only its anchor can be hit.

Active approaches require an empty worker, at least **75 health**, a bank above
the emergency threshold, and a cat within squared footprint distance **9**.
Only permanent walls or map edges certify that all four cat landings covering
the destination are blocked. Dirt and occupied tiles are not permanent proof;
certification prevents crushing, not scratches or attacks by unseen units.

Escape uses local experimental scores: **10,000** for immediate crush risk,
**1,000** for cat vision and distance capped at **25** as a tie-break. It does
not predict the cat's next AI action or chase across the map.

Each worker can place at most **one Cat Trap and one Dirt screen**. Failed
placements can retry; successful placement budgets do not refill after a trap
triggers. The engine still owns occupancy, range, cooldown and team trap caps.

Construction's experimental distances are squared distances, not engine trigger
radii: trap candidates must be within **4** of a cat edge; screens within **9**.
Dirt stays more than squared distance **18** from home, off the direct economic
step, resources, mines and existing traps. Only cardinal screens with visible
passable side tiles and a far tile are placed, providing local bypasses. Dirt
comes from removing real route obstacles; the worker does not dig for inventory
alone. Optional cheese spending needs the protected bank; emergency digging may
use cargo only when more than the dig cost remains.

## Build, test and view a replay

With JDK 21, from the repository root:

```powershell
.\gradlew.bat clean test jacocoTestReport jacocoTestCoverageVerification verify zipForSubmit
.\gradlew.bat run
```

Use `./gradlew` on macOS/Linux. Open the reports:

- `build/reports/tests/test/index.html`
- `build/reports/jacoco/test/html/index.html`

The normal match writes a `.bc26` replay under `matches/`; open it in the
Battlecode client. `zipForSubmit` writes `submission.zip` but does not upload it.
Construction is situational: enough bank, legal terrain, inventory and relevant
cats must exist. A single arbitrary match need not show both placement kinds.

For the demo, use the recorded comparison replay and action timeline described
in the results below. Show a Baby Rat bite, a Cat Trap placement and a Dirt
placement as distinct features, plus the test and coverage reports. Scrimmage
ranking, GitHub statistics and PM/Trello/reflection remain separate demo items.

## Committed comparison and actual engine evidence - November 2, 2026

- Candidate: `0f7bfec2eb2e72fd4d248f5636c78fa13835c843`.
- Fresh team-main baseline: `f75f539db6328c8727f6adfe16dd56a5104b97b7`.
- Engine 1.2.6, JDK 21; DefaultSmall, DefaultMedium, DefaultLarge, cheesefarm,
  minimaze, safelycontained and pipes, both starting sides, one fixed seed per map.
- **14/14 games complete; 14 wins, zero losses and zero coin flips.** Nine wins
  are on points and five by king destruction. Our king survives every game.
- Clean **107 tests**, coverage report/gate, engine verifier and submission
  packaging pass. Production line coverage is **548/567 = 96.65%**. The
  package contains the eight team Java files, excluding examples and test code.

The [action CSV](sprint2-compliance-actions.csv) counts actual candidate actions
decoded from the engine replays: **2,567 Baby Rat cat bites, 54 king cat bites,
349 Cat Traps, 231 Dirt placements and 577 digs**. Some maps have no useful cat
engagement or placements; the policies do not fabricate actions to meet a quota.

Replay auditing records zero candidate exception deaths, zero turns recorded
at or above the applicable unit bytecode limit, and a maximum recorded turn of
**6,825 bytecodes**. These are assembled-bot budget checks, not a speedup claim.
All 28 log/replay hashes match the manifest. All sampled checkpoints report
cooperation; this suite does not establish PvP behavior or strong-pool ranking.

**Economic limitation:** final deliveries total **87,820 / 100,100**, mixing
different game lengths. In the ten games reaching round 1,000, deliveries are
**39,400 / 53,180 (-25.9%)**. This integration changes food-funded growth and
cat/construction priorities together; its wins do not demonstrate improved
collector efficiency. Income optimization and the stronger opponent pool need
separate evaluation. Final candidate banks remain positive, but king survival
on this suite does not guarantee safety against stronger opponents.

The [final-game CSV](sprint2-compliance-results.csv) records both teams' metrics
and seeds. The [validation record](sprint2-compliance-validation.json) retains
exact source/JAR/engine hashes, per-game replay/log hashes, tests, coverage and
demo markers. Raw replays, checkpoints and the auxiliary read-only action audit
are local, ignored artifacts in
`matches/sprint2-compliance-release-20261102/`. They are not included in a
clone. Earlier runs are exploratory evidence before the two cargo boundary fixes
and latest-main refresh; they are not the final evidence. Later documentation commits leave
the tested production code and tests unchanged.

### Short demo replay

Use `DefaultSmall-A.bc26` from that final directory. It contains **326 Baby Rat
cat bites, 49 Cat Traps and 32 Dirt placements**. The replay schema calls a Baby
Rat `RAT`; the IDs below identify the units in the viewer.

| Round | Baby Rat ID | Feature |
| --- | ---: | --- |
| 14 | 10125 | Remove Dirt and acquire inventory |
| 16 | 10611 | Place a Cat Trap |
| 18 | 13917 | Place a Dirt screen (second distinct kind) |
| 20 | 13917 | Bite a cat |

To regenerate that side-swapped map comparison with Python 3.10+ and JDK 21:

```powershell
.\gradlew.bat sim -PsimMaps=DefaultSmall -PsimCandidate=0f7bfec2eb2e72fd4d248f5636c78fa13835c843 -PsimBaseline=f75f539db6328c8727f6adfe16dd56a5104b97b7 -PsimOffline=true '-PsimOutput=matches/sprint2-compliance-demo'
```

Open `matches/sprint2-compliance-demo/DefaultSmall-A.bc26` in the client and
inspect rounds 14-20. Use a fresh output name for another comparison. For all
seven maps, replace `-PsimMaps=DefaultSmall` with `-PsimPreset=full`.
