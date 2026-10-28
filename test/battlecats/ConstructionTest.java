package battlecats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import battlecode.common.Direction;
import battlecode.common.GameConstants;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.Team;
import battlecode.common.TrapType;
import battlecode.common.UnitType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** Construction scenarios inspect changed terrain, resources and actions, not internal flags. */
public class ConstructionTest {
  @Test
  public void ratTrapRequiresTheEntireCostAboveTheKingFoodReserve() throws Exception {
    int required = 300 + TrapType.RAT_TRAP.buildCost;
    for (int balance : new int[] {required - 1, required}) {
      World world = ratTrapWorld();
      world.globalCheese = balance;
      world.health = 75;

      boolean acted = new Construction().tryRatTrap(world.rc, world.cats());

      assertEquals(balance == required, acted);
      assertEquals(balance == required ? 300 : balance, world.globalCheese);
      assertEquals(0, world.rawCheese);
      assertEquals(balance == required ? 1 : 0, world.actions.size());
      if (acted) {
        assertEquals(TrapType.RAT_TRAP, world.traps.get(at(11, 10)));
        assertFalse(world.actionReady);
      }
    }
  }

  @Test
  public void ratTrapRequiresAnEmptyHealthyReadyBabyAfterBetrayal() throws Exception {
    World[] worlds = {
      ratTrapWorld(), ratTrapWorld(), ratTrapWorld(), ratTrapWorld(), ratTrapWorld(), ratTrapWorld()
    };
    worlds[0].rawCheese = 1;
    worlds[1].health = 74;
    worlds[2].actionReady = false;
    worlds[3].cooperation = true;
    worlds[4].type = UnitType.RAT_KING;
    worlds[5].globalCheese = 200;
    for (World world : worlds) {
      int bank = world.globalCheese;
      int cargo = world.rawCheese;

      assertFalse(new Construction().tryRatTrap(world.rc, world.cats()));

      assertEquals(bank, world.globalCheese);
      assertEquals(cargo, world.rawCheese);
      assertTrue(world.actions.isEmpty());
      assertTrue(world.traps.isEmpty());
    }
  }

  @Test
  public void failedRatTrapPlacementCanSucceedLaterWithoutUsingItsBudget() throws Exception {
    World world = ratTrapWorld();
    world.trapsAllowed = false;
    Construction construction = new Construction();

    assertFalse(construction.tryRatTrap(world.rc, world.cats()));
    assertEquals(500, world.globalCheese);
    assertTrue(world.actions.isEmpty());

    world.trapsAllowed = true;
    assertTrue(construction.tryRatTrap(world.rc, world.cats()));
    assertEquals(500 - TrapType.RAT_TRAP.buildCost, world.globalCheese);
    assertEquals(1, world.actions.size());
  }

  @Test
  public void ratTrapBudgetDoesNotRefillAfterTheWorkersFirstTrapTriggers() throws Exception {
    World world = ratTrapWorld();
    Construction construction = new Construction();
    assertTrue(construction.tryRatTrap(world.rc, world.cats()));
    world.traps.clear();
    world.actionReady = true;

    assertFalse(construction.tryRatTrap(world.rc, world.cats()));

    assertEquals(1, world.actions.size());
    assertEquals(500 - TrapType.RAT_TRAP.buildCost, world.globalCheese);
  }

  @Test
  public void ratTrapRespectsTheEngineTeamCapAndRetriesWhenCapacityReturns() throws Exception {
    World world = ratTrapWorld();
    for (int i = 0; i < TrapType.RAT_TRAP.maxCount; i++) {
      world.traps.put(at(i, 0), TrapType.RAT_TRAP);
    }
    Construction construction = new Construction();

    assertFalse(construction.tryRatTrap(world.rc, world.cats()));
    assertEquals(500, world.globalCheese);

    world.traps.remove(at(0, 0));
    assertTrue(construction.tryRatTrap(world.rc, world.cats()));
    assertEquals(TrapType.RAT_TRAP.maxCount, world.traps.size());
    assertEquals(1, world.actions.size());
  }

  @Test
  public void ratTrapNeverTakesAnAvailableFreeFinishingBite() throws Exception {
    for (boolean first : new boolean[] {false, true}) {
      World world = ratTrapWorld();
      RobotInfo weak = robot(1, UnitType.BABY_RAT, Team.B, 10, at(11, 10));
      world.robots.add(first ? 0 : world.robots.size(), weak);

      assertFalse(new Construction().tryRatTrap(world.rc, world.cats()));

      assertEquals(500, world.globalCheese);
      assertTrue(world.actions.isEmpty());
    }
  }

  @Test
  public void ratTrapPreservesAFreeFinishOnTheEnemyKingsReachableEdge() throws Exception {
    World world = ratTrapWorld();
    RobotInfo king = robot(1, UnitType.RAT_KING, Team.B, 10, at(8, 10));
    world.robots.add(king);
    assertFalse(world.canAttack(king.getLocation()));
    assertTrue(world.canAttack(at(9, 10)));

    assertFalse(new Construction().tryRatTrap(world.rc, world.cats()));

    assertEquals(500, world.globalCheese);
    assertTrue(world.actions.isEmpty());
  }

  @Test
  public void ratTrapReservesAFreeFinishAfterTurningWithoutActuallyTurning() throws Exception {
    for (boolean canTurn : new boolean[] {false, true}) {
      World world = ratTrapWorld();
      world.robots.add(robot(1, UnitType.BABY_RAT, Team.B, 10, at(9, 10)));
      world.hidden.add(at(9, 10));
      world.turningReady = canTurn;

      assertEquals(!canTurn, new Construction().tryRatTrap(world.rc, world.cats()));

      assertEquals(canTurn ? 0 : 1, world.actions.size());
      assertEquals(canTurn ? 500 : 500 - TrapType.RAT_TRAP.buildCost, world.globalCheese);
    }
  }

  @Test
  public void inaccessibleWeakEnemyDoesNotPreventAUsefulTrap() throws Exception {
    World world = ratTrapWorld();
    world.robots.add(robot(1, UnitType.BABY_RAT, Team.B, 10, at(10, 15)));

    assertTrue(new Construction().tryRatTrap(world.rc, world.cats()));

    assertEquals(TrapType.RAT_TRAP, world.traps.get(at(11, 10)));
  }

  @Test
  public void ratTrapNeedsAnObservedNearbyEnemyBaby() throws Exception {
    World[] worlds = {
      new World(), new World(), new World(), new World(), new World(), new World()
    };
    worlds[1].robots.add(robot(UnitType.BABY_RAT, Team.A, at(12, 10)));
    worlds[2].robots.add(robot(UnitType.RAT_KING, Team.B, at(12, 10)));
    worlds[3].addCat(at(12, 10));
    worlds[4].robots.add(robot(UnitType.BABY_RAT, Team.NEUTRAL, at(12, 10)));
    worlds[5].robots.add(robot(UnitType.BABY_RAT, Team.B, at(14, 10)));
    for (World world : worlds) {
      assertFalse(new Construction().tryRatTrap(world.rc, world.cats()));

      assertTrue(world.actions.isEmpty());
      assertEquals(500, world.globalCheese);
    }
  }

  @Test
  public void nearestRatTrapTargetAndIdTieBreakIgnoreObservationOrderAndDuplicates()
      throws Exception {
    for (boolean reverse : new boolean[] {false, true}) {
      World world = new World();
      RobotInfo lowId = robot(1, UnitType.BABY_RAT, Team.B, 100, at(10, 12));
      RobotInfo highId = robot(2, UnitType.BABY_RAT, Team.B, 100, at(12, 10));
      world.robots.add(reverse ? highId : lowId);
      world.robots.add(reverse ? lowId : highId);
      world.robots.add(lowId);
      world.robots.add(robot(0, UnitType.BABY_RAT, Team.B, 100, at(13, 10)));

      assertTrue(new Construction().tryRatTrap(world.rc, world.cats()));

      assertEquals(TrapType.RAT_TRAP, world.traps.get(at(10, 11)));
      assertEquals(1, world.actions.size());
    }
  }

  @Test
  public void ratTrapSkipsResourcesAndUnavailableTerrainWithoutSensingHiddenTiles()
      throws Exception {
    World[] worlds = {
      ratTrapWorld(), ratTrapWorld(), ratTrapWorld(), ratTrapWorld(), ratTrapWorld(), ratTrapWorld()
    };
    worlds[0].looseCheese.put(at(11, 10), 4);
    worlds[1].mines.add(at(11, 10));
    worlds[2].traps.put(at(11, 10), TrapType.CAT_TRAP);
    worlds[3].walls.add(at(11, 10));
    worlds[4].dirtTiles.add(at(11, 10));
    worlds[5].hidden.add(at(11, 10));
    for (World world : worlds) {
      assertTrue(new Construction().tryRatTrap(world.rc, world.cats()));

      assertEquals(TrapType.RAT_TRAP, world.traps.get(at(11, 11)));
      assertEquals(1, world.actions.size());
    }
    assertEquals(Integer.valueOf(4), worlds[0].looseCheese.get(at(11, 10)));
    assertEquals(TrapType.CAT_TRAP, worlds[2].traps.get(at(11, 10)));
  }

  @Test
  public void ratTrapRequiresAFreeCandidateTheEnemyCanReachInOneStep() throws Exception {
    World world = ratTrapWorld();
    world.robots.add(robot(UnitType.BABY_RAT, Team.A, at(11, 10)));
    world.walls.add(at(11, 11));
    world.walls.add(at(11, 9));

    assertFalse(new Construction().tryRatTrap(world.rc, world.cats()));

    assertTrue(world.actions.isEmpty());
    assertEquals(500, world.globalCheese);
  }

  @Test
  public void doesNotSealADiagonalPassageWithADirtScreen() throws Exception {
    World world = screenWorld();
    world.robots.clear();
    world.addCat(at(13, 13));
    world.walls.add(at(10, 11));
    world.walls.add(at(11, 10));
    world.walls.add(at(11, 12));
    world.walls.add(at(12, 11));

    assertFalse(new Construction().tryBuild(world.rc, at(2, 10), at(5, 10), world.cats()));

    assertFalse(world.dirtTiles.contains(at(11, 11)));
    assertTrue(world.actions.isEmpty());
  }

  @Test
  public void clearsOnlyTheNextUsefulRouteObstacleWithoutSpendingTheReserve() throws Exception {
    int[] balances = {304, 305};
    for (int balance : balances) {
      World world = new World();
      world.globalCheese = balance;
      world.dirtTiles.add(at(11, 10));
      world.dirtTiles.add(at(12, 10));

      boolean acted = new Construction().tryBuild(world.rc, null, at(20, 10), world.cats());

      assertEquals("Five cheese must remain available above the reserve", balance == 305, acted);
      assertEquals(balance == 305 ? 300 : 304, world.globalCheese);
      assertEquals(balance == 305 ? 1 : 0, world.dirtStock);
      assertEquals(balance != 305, world.dirtTiles.contains(at(11, 10)));
      assertTrue("Only one obstacle may be cleared per turn", world.dirtTiles.contains(at(12, 10)));
      assertEquals(balance == 305 ? 1 : 0, world.actions.size());
    }
  }

  @Test
  public void doesNotDigForInventoryWithoutAnActualRouteObstacle() throws Exception {
    MapLocation[] objectives = {null, at(10, 10), at(20, 10)};
    for (MapLocation objective : objectives) {
      World world = new World();
      world.dirtTiles.add(at(10, 11));

      assertFalse(new Construction().tryBuild(world.rc, null, objective, world.cats()));

      assertTrue(world.actions.isEmpty());
      assertEquals(500, world.globalCheese);
    }
  }

  @Test
  public void unavailableAndUnseenDiggingWaitsWithoutLosingTheObstacle() throws Exception {
    for (boolean unseen : new boolean[] {false, true}) {
      World world = new World();
      world.dirtTiles.add(at(11, 10));
      if (unseen) {
        world.hidden.add(at(11, 10));
      } else {
        world.digAllowed = false;
      }

      assertFalse(new Construction().tryBuild(world.rc, null, at(20, 10), world.cats()));

      assertTrue(world.dirtTiles.contains(at(11, 10)));
      assertTrue(world.actions.isEmpty());
    }
  }

  @Test
  public void trapBudgetIncludesTheEntireConstructionCost() throws Exception {
    for (int balance : new int[] {309, 310}) {
      World world = new World();
      world.globalCheese = balance;
      world.addCat(at(13, 10));

      boolean acted = new Construction().tryBuild(world.rc, null, null, world.cats());

      assertEquals(balance == 310, acted);
      assertEquals(balance == 310 ? 300 : 309, world.globalCheese);
      assertEquals(balance == 310 ? 1 : 0, world.traps.size());
    }
  }

  @Test
  public void oneWorkerCannotKeepSpendingOnTrapsAfterItsFirstSuccess() throws Exception {
    World world = new World();
    world.addCat(at(13, 10));
    Construction construction = new Construction();
    assertTrue(construction.tryBuild(world.rc, null, null, world.cats()));
    assertEquals(TrapType.CAT_TRAP, world.traps.get(at(11, 10)));
    world.actionReady = true;
    world.traps.clear(); // Even an already triggered trap does not replenish this worker's budget.

    assertFalse(construction.tryBuild(world.rc, null, null, world.cats()));

    assertEquals(1, world.actions.size());
    assertEquals(490, world.globalCheese);
  }

  @Test
  public void failedTrapPermissionCanBeRetriedWithoutSpendingOrUsingTheBudget() throws Exception {
    World world = new World();
    world.addCat(at(13, 10));
    world.trapsAllowed = false; // Covers engine refusal during backstabbing or at its trap cap.
    Construction construction = new Construction();
    assertFalse(construction.tryBuild(world.rc, null, null, world.cats()));
    assertEquals(500, world.globalCheese);
    world.trapsAllowed = true;

    assertTrue(construction.tryBuild(world.rc, null, null, world.cats()));

    assertEquals(1, world.actions.size());
  }

  @Test
  public void trapPlacementUsesTheCatFootprintAndSkipsOccupiedTiles() throws Exception {
    World world = new World();
    world.addCat(at(8, 9)); // The closest part is (9,10), rather than the anchor (8,9).

    assertTrue(new Construction().tryBuild(world.rc, null, null, world.cats()));

    assertEquals(TrapType.CAT_TRAP, world.traps.get(at(9, 11)));
    assertEquals(1, world.actions.size());
  }

  @Test
  public void absentDistantAndNonCatTargetsDoNotCauseSpeculativeTraps() throws Exception {
    World[] worlds = {new World(), new World(), new World()};
    worlds[1].addCat(at(20, 20));
    worlds[2].robots.add(robot(UnitType.BABY_RAT, Team.B, at(12, 10)));
    for (World world : worlds) {
      assertFalse(new Construction().tryBuild(world.rc, null, null, world.cats()));

      assertTrue(world.actions.isEmpty());
      assertEquals(500, world.globalCheese);
    }
  }

  @Test
  public void trapDoesNotCoverCheeseThatACollectorCouldRetrieve() throws Exception {
    World world = new World();
    world.addCat(at(12, 10)); // Side alternatives remain close enough to be useful.
    world.looseCheese.put(at(11, 10), 10);

    assertTrue(new Construction().tryBuild(world.rc, null, null, world.cats()));

    assertFalse(world.traps.containsKey(at(11, 10)));
    assertEquals(Integer.valueOf(10), world.looseCheese.get(at(11, 10)));
  }

  @Test
  public void screensAReachableCatWhileLeavingTwoPassableWaysAroundTheDirt() throws Exception {
    World world = screenWorld();
    Construction construction = new Construction();

    assertTrue(construction.tryBuild(world.rc, at(2, 10), at(5, 10), world.cats()));

    assertTrue(world.dirtTiles.contains(at(11, 10)));
    assertTrue(world.info(at(11, 9)).isPassable());
    assertTrue(world.info(at(11, 11)).isPassable());
    assertEquals(0, world.dirtStock);
    assertEquals("Dirt placement itself costs no cheese", 500, world.globalCheese);

    world.actionReady = true;
    world.dirtStock = 1;
    world.dirtTiles.clear();
    assertFalse(construction.tryBuild(world.rc, at(2, 10), at(5, 10), world.cats()));
    assertEquals("One worker should not leave a trail of screens", 1, world.actions.size());
  }

  @Test
  public void dirtPreservesHomeClearanceAndTheDirectCollectionRoute() throws Exception {
    MapLocation[] homes = {null, at(7, 10), at(2, 10)};
    MapLocation[] objectives = {at(5, 10), at(5, 10), at(20, 10)};
    for (int i = 0; i < homes.length; i++) {
      World world = screenWorld();

      assertFalse(new Construction().tryBuild(world.rc, homes[i], objectives[i], world.cats()));

      assertTrue(world.actions.isEmpty());
      assertEquals(1, world.dirtStock);
    }
  }

  @Test
  public void dirtNeverCoversResourcesTrapsOrClosesANarrowCorridor() throws Exception {
    World[] worlds = {
      screenWorld(), screenWorld(), screenWorld(), screenWorld(), screenWorld(), screenWorld()
    };
    worlds[0].looseCheese.put(at(11, 10), 5);
    worlds[1].mines.add(at(11, 10));
    worlds[2].traps.put(at(11, 10), TrapType.CAT_TRAP);
    worlds[3].walls.add(at(11, 9));
    worlds[4].walls.add(at(11, 11));
    worlds[5].hidden.add(at(11, 9));
    for (World world : worlds) {
      assertFalse(new Construction().tryBuild(world.rc, at(2, 10), at(5, 10), world.cats()));

      assertFalse(world.dirtTiles.contains(at(11, 10)));
      assertTrue(world.actions.isEmpty());
    }
  }

  @Test
  public void dirtRequiresInventoryVisibleTerrainAndARelevantCat() throws Exception {
    World[] worlds = {screenWorld(), screenWorld(), screenWorld(), screenWorld()};
    worlds[0].dirtStock = 0;
    worlds[1].hidden.add(at(11, 10));
    worlds[2].robots.clear();
    worlds[2].addCat(at(15, 10));
    worlds[3].robots.clear();
    worlds[3].addCat(at(10, 10)); // Cannot screen an already overlapping cat.
    for (World world : worlds) {
      assertFalse(new Construction().tryBuild(world.rc, at(2, 10), null, world.cats()));

      assertTrue(world.actions.isEmpty());
    }
  }

  @Test
  public void unavailableActionNeverAttemptsConstructionOrDigging() throws Exception {
    World world = screenWorld();
    world.actionReady = false;
    world.dirtTiles.add(at(9, 10));

    assertFalse(new Construction().tryBuild(world.rc, at(2, 10), at(5, 10), world.cats()));

    assertTrue(world.actions.isEmpty());
    assertTrue(world.dirtTiles.contains(at(9, 10)));
  }

  @Test
  public void emergencyDeliveryDigUsesCargoWithoutTouchingAnEmptyBank() throws Exception {
    World world = new World();
    world.globalCheese = 0;
    world.rawCheese = 40;
    world.dirtTiles.add(at(11, 10));

    assertTrue(new Construction().clearDeliveryRoute(world.rc, at(20, 10)));

    assertEquals(35, world.rawCheese);
    assertEquals(0, world.globalCheese);
    assertEquals(1, world.dirtStock);
    assertFalse(world.dirtTiles.contains(at(11, 10)));
    assertEquals(1, world.actions.size());
  }

  @Test
  public void emergencyDigRetainsDeliverableCargoAndProtectsTheBank() throws Exception {
    for (int cargo : new int[] {0, 4, 5}) {
      World world = new World();
      world.globalCheese = 300;
      world.rawCheese = cargo;
      world.dirtTiles.add(at(11, 10));

      assertFalse(new Construction().clearDeliveryRoute(world.rc, at(20, 10)));

      assertEquals(cargo, world.rawCheese);
      assertEquals(300, world.globalCheese);
      assertTrue(world.dirtTiles.contains(at(11, 10)));
      assertTrue(world.actions.isEmpty());
    }
  }

  @Test
  public void optionalTrapCannotTreatCarriedCheeseAsKingFood() throws Exception {
    World world = new World();
    world.globalCheese = 0;
    world.rawCheese = 1000;
    world.addCat(at(13, 10));

    assertFalse(new Construction().tryBuild(world.rc, null, null, world.cats()));

    assertEquals(1000, world.rawCheese);
    assertEquals(0, world.globalCheese);
    assertTrue(world.traps.isEmpty());
    assertTrue(world.actions.isEmpty());
  }

  @Test
  public void legalOptionalConstructionPaysFromCargoBeforeTheGlobalBank() throws Exception {
    World world = new World();
    world.globalCheese = 310;
    world.rawCheese = 4;
    world.addCat(at(13, 10));

    assertTrue(new Construction().tryBuild(world.rc, null, null, world.cats()));

    assertEquals(0, world.rawCheese);
    assertEquals(304, world.globalCheese);
    assertEquals(1, world.traps.size());
  }

  private static World screenWorld() {
    World world = new World();
    world.trapsAllowed = false;
    world.dirtStock = 1;
    world.addCat(at(14, 10));
    return world;
  }

  private static World ratTrapWorld() {
    World world = new World();
    world.robots.add(robot(UnitType.BABY_RAT, Team.B, at(12, 10)));
    return world;
  }

  private static MapLocation at(int x, int y) {
    return new MapLocation(x, y);
  }

  private static RobotInfo robot(UnitType type, Team team, MapLocation location) {
    return robot(100, type, team, 100, location);
  }

  private static RobotInfo robot(
      int id, UnitType type, Team team, int health, MapLocation location) {
    return new RobotInfo(id, team, type, health, location, Direction.WEST, 0, 0, null);
  }

  /** Finite terrain and real resource deductions; unsupported controller calls fail immediately. */
  private static final class World implements InvocationHandler {
    final RobotController rc;
    final MapLocation location = at(10, 10);
    final Set<MapLocation> dirtTiles = new HashSet<>();
    final Set<MapLocation> walls = new HashSet<>();
    final Set<MapLocation> hidden = new HashSet<>();
    final Set<MapLocation> mines = new HashSet<>();
    final Map<MapLocation, Integer> looseCheese = new HashMap<>();
    final Map<MapLocation, TrapType> traps = new HashMap<>();
    final List<RobotInfo> robots = new ArrayList<>();
    final List<String> actions = new ArrayList<>();
    int globalCheese = 500;
    int rawCheese;
    int dirtStock;
    int health = 100;
    UnitType type = UnitType.BABY_RAT;
    boolean cooperation;
    boolean turningReady = true;
    boolean actionReady = true;
    boolean trapsAllowed = true;
    boolean digAllowed = true;

    World() {
      rc =
          (RobotController)
              Proxy.newProxyInstance(
                  RobotController.class.getClassLoader(),
                  new Class<?>[] {RobotController.class},
                  this);
    }

    void addCat(MapLocation anchor) {
      robots.add(robot(UnitType.CAT, Team.NEUTRAL, anchor));
    }

    RobotInfo[] cats() {
      return robots.toArray(new RobotInfo[0]);
    }

    boolean visible(MapLocation target) {
      return target.x >= 0 && target.x < 30 && target.y >= 0 && target.y < 30
          && !hidden.contains(target);
    }

    MapInfo info(MapLocation target) {
      return new MapInfo(
          target,
          !walls.contains(target) && !dirtTiles.contains(target),
          null,
          walls.contains(target),
          dirtTiles.contains(target),
          looseCheese.getOrDefault(target, 0),
          traps.getOrDefault(target, TrapType.NONE),
          mines.contains(target));
    }

    boolean occupied(MapLocation target) {
      if (target.equals(location)) {
        return true;
      }
      for (RobotInfo robot : robots) {
        for (MapLocation part : robot.getType().getAllTypeLocations(robot.getLocation())) {
          if (part.equals(target)) {
            return true;
          }
        }
      }
      return false;
    }

    boolean canActAt(MapLocation target) {
      return actionReady && visible(target)
          && location.distanceSquaredTo(target) <= GameConstants.BUILD_DISTANCE_SQUARED;
    }

    boolean canTrap(MapLocation target, TrapType trapType) {
      int count = 0;
      for (TrapType trap : traps.values()) {
        if (trap == trapType) {
          count++;
        }
      }
      return canActAt(target) && trapsAllowed && count < trapType.maxCount
          && (long) globalCheese + rawCheese >= trapType.buildCost
          && info(target).isPassable() && !occupied(target) && !mines.contains(target)
          && !traps.containsKey(target);
    }

    boolean canAttack(MapLocation target) {
      if (!canActAt(target)) {
        return false;
      }
      for (RobotInfo robot : robots) {
        if (robot.getTeam() != Team.B) {
          continue;
        }
        for (MapLocation part : robot.getType().getAllTypeLocations(robot.getLocation())) {
          if (part.equals(target)) {
            return true;
          }
        }
      }
      return false;
    }

    boolean canPlaceDirt(MapLocation target) {
      return canActAt(target) && dirtStock > 0 && info(target).isPassable()
          && !occupied(target) && !mines.contains(target);
    }

    boolean canDig(MapLocation target) {
      return canActAt(target) && digAllowed && dirtTiles.contains(target)
          && (long) globalCheese + rawCheese >= GameConstants.DIG_DIRT_CHEESE_COST;
    }

    private void spend(int cost) {
      int fromCargo = Math.min(rawCheese, cost);
      rawCheese -= fromCargo;
      globalCheese -= cost - fromCargo;
      assertTrue("Legal construction cannot overdraw the global bank", globalCheese >= 0);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      switch (method.getName()) {
        case "getLocation":
          return location;
        case "getType":
          return type;
        case "getTeam":
          return Team.A;
        case "getHealth":
          return health;
        case "isCooperation":
          return cooperation;
        case "getAllCheese":
          return globalCheese + rawCheese;
        case "getGlobalCheese":
          return globalCheese;
        case "getRawCheese":
          return rawCheese;
        case "getDirt":
          return dirtStock;
        case "isActionReady":
          return actionReady;
        case "canSenseLocation":
          return visible((MapLocation) args[0]);
        case "senseMapInfo":
          MapLocation sensed = (MapLocation) args[0];
          assertTrue("Construction must never sense unavailable terrain", visible(sensed));
          return info(sensed);
        case "canAttack":
          return canAttack((MapLocation) args[0]);
        case "canTurn":
          return turningReady && args[0] != Direction.CENTER;
        case "canPlaceRatTrap":
          return canTrap((MapLocation) args[0], TrapType.RAT_TRAP);
        case "placeRatTrap":
          MapLocation ratTrap = (MapLocation) args[0];
          assertTrue("Rat Trap placement must be legal", canTrap(ratTrap, TrapType.RAT_TRAP));
          traps.put(ratTrap, TrapType.RAT_TRAP);
          spend(TrapType.RAT_TRAP.buildCost);
          actionReady = false;
          actions.add("RAT_TRAP " + ratTrap);
          return null;
        case "canPlaceCatTrap":
          return canTrap((MapLocation) args[0], TrapType.CAT_TRAP);
        case "placeCatTrap":
          MapLocation trap = (MapLocation) args[0];
          assertTrue("Trap placement must be legal", canTrap(trap, TrapType.CAT_TRAP));
          traps.put(trap, TrapType.CAT_TRAP);
          spend(TrapType.CAT_TRAP.buildCost);
          actionReady = false;
          actions.add("CAT_TRAP " + trap);
          return null;
        case "canPlaceDirt":
          return canPlaceDirt((MapLocation) args[0]);
        case "placeDirt":
          MapLocation dirt = (MapLocation) args[0];
          assertTrue("Dirt placement must be legal", canPlaceDirt(dirt));
          dirtTiles.add(dirt);
          dirtStock--;
          actionReady = false;
          actions.add("DIRT " + dirt);
          return null;
        case "canRemoveDirt":
          return canDig((MapLocation) args[0]);
        case "removeDirt":
          MapLocation obstacle = (MapLocation) args[0];
          assertTrue("Dirt removal must be legal", canDig(obstacle));
          dirtTiles.remove(obstacle);
          dirtStock++;
          spend(GameConstants.DIG_DIRT_CHEESE_COST);
          actionReady = false;
          actions.add("DIG " + obstacle);
          return null;
        default:
          throw new AssertionError("Unsupported controller operation: " + method.getName());
      }
    }
  }
}
