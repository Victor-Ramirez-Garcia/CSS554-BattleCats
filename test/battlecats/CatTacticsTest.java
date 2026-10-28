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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Local combat scenarios using the engine's footprint and vision geometry. */
public class CatTacticsTest {
  @Test
  public void activeWorkerTurnsToBiteThenRetreatsFromAnUnprotectedTile() throws Exception {
    RobotInfo cat = cat(at(8, 8), Direction.EAST);
    World world = new World(at(10, 8), cat);

    assertTrue(new CatTactics().engage(world.rc, new RobotInfo[] {cat}));

    assertEquals(1, world.turns);
    assertEquals(1, world.attacks.size());
    assertEquals(1, world.moves);
    assertTrue(world.location.distanceSquaredTo(at(9, 8)) > 2);
  }

  @Test
  public void twoCertifiedStepsCanStageAnApproachThenBite() throws Exception {
    RobotInfo cat = cat(at(8, 8), Direction.EAST);
    World world = new World(at(12, 8), cat);
    world.direction = Direction.WEST;
    world.allowedMoves = EnumSet.of(Direction.WEST);
    world.walls.addAll(Arrays.asList(at(10, 7), at(10, 9), at(11, 7), at(11, 9)));
    // Experimental gate boundaries: 75 health and a bank above 200 are eligible.
    world.health = 75;
    world.globalCheese = 201;
    CatTactics tactics = new CatTactics();

    assertTrue(tactics.engage(world.rc, new RobotInfo[] {cat}));

    assertEquals(at(11, 8), world.location);
    assertTrue(world.attacks.isEmpty());
    world.nextTurn();

    assertTrue(tactics.engage(world.rc, new RobotInfo[] {cat}));

    assertEquals(at(10, 8), world.location);
    assertEquals(2, world.moves);
    assertEquals(1, world.attacks.size());
  }

  @Test
  public void certifiedFarmTileStaysSafeWhenTurningHidesItsPermanentWalls() throws Exception {
    RobotInfo cat = cat(at(8, 8), Direction.EAST);
    World world = protectedApproach(cat);
    CatTactics tactics = new CatTactics();
    assertTrue(tactics.engage(world.rc, new RobotInfo[] {cat}));
    assertEquals(at(10, 8), world.location);
    world.nextTurn();
    world.direction = Direction.EAST;
    world.hidden.addAll(world.walls);
    int terrainReads = world.terrainReads;

    assertTrue(tactics.engage(world.rc, new RobotInfo[] {cat}));

    assertEquals(at(10, 8), world.location);
    assertEquals("Farming does not retreat from a certified tile", 1, world.moves);
    assertEquals(2, world.attacks.size());
    assertEquals("The same permanent proof survives a facing change", terrainReads,
        world.terrainReads);
  }

  @Test
  public void openHiddenOrDirtBoundariesNeverCertifyAnApproach() throws Exception {
    RobotInfo cat = cat(at(8, 8), Direction.EAST);
    for (int variant = 0; variant < 3; variant++) {
      World world = protectedApproach(cat);
      if (variant == 0) {
        world.walls.clear();
      } else if (variant == 1) {
        world.hidden.addAll(world.walls);
      } else {
        world.dirtTiles.addAll(world.walls);
        world.walls.clear();
      }

      assertFalse(new CatTactics().engage(world.rc, new RobotInfo[] {cat}));

      assertEquals(at(11, 8), world.location);
      assertEquals(0, world.moves);
      assertEquals(0, world.turns);
      assertTrue(world.attacks.isEmpty());
    }
  }

  @Test
  public void approachRequiresEveryCatLandingToBeBlocked() throws Exception {
    RobotInfo cat = cat(at(8, 8), Direction.EAST);
    for (MapLocation missing : new MapLocation[] {at(10, 7), at(10, 9)}) {
      World world = protectedApproach(cat);
      world.walls.remove(missing);

      assertFalse(new CatTactics().engage(world.rc, new RobotInfo[] {cat}));

      assertEquals(0, world.moves);
      assertTrue(world.attacks.isEmpty());
    }
  }

  @Test
  public void mapBoundaryCanSupplyPartOfThePermanentCrushProof() throws Exception {
    RobotInfo cat = cat(at(1, 7), Direction.WEST);
    World world = new World(at(0, 10), cat);
    world.direction = Direction.SOUTHEAST;
    world.allowedMoves = EnumSet.of(Direction.SOUTH);
    world.walls.addAll(Arrays.asList(at(0, 8), at(1, 10)));

    assertTrue(new CatTactics().engage(world.rc, new RobotInfo[] {cat}));

    assertEquals(at(0, 9), world.location);
    assertEquals(1, world.moves);
    assertEquals(1, world.attacks.size());
  }

  @Test
  public void activeEngagementLeavesFoodCarriersLowHealthAndKingsToExistingPolicies()
      throws Exception {
    RobotInfo cat = cat(at(8, 8), Direction.EAST);
    for (int variant = 0; variant < 5; variant++) {
      World world = protectedApproach(cat);
      switch (variant) {
        case 0:
          world.cargo = 1;
          break;
        case 1:
          world.health = 74;
          break;
        case 2:
          world.globalCheese = 200;
          break;
        case 3:
          world.type = UnitType.RAT_KING;
          break;
        default:
          world.actionReady = false;
      }

      assertFalse(new CatTactics().engage(world.rc, new RobotInfo[] {cat}));

      assertEquals(0, world.moves);
      assertEquals(0, world.turns);
      assertTrue(world.attacks.isEmpty());
    }
  }

  @Test
  public void blockedOrCoolingMovementDoesNotStartAnApproach() throws Exception {
    RobotInfo cat = cat(at(8, 8), Direction.EAST);
    for (boolean blocked : new boolean[] {true, false}) {
      World world = protectedApproach(cat);
      if (blocked) {
        world.allowedMoves.clear();
      } else {
        world.movementReady = false;
      }

      assertFalse(new CatTactics().engage(world.rc, new RobotInfo[] {cat}));

      assertEquals(0, world.moves);
      assertEquals(0, world.turns);
      assertTrue(world.attacks.isEmpty());
    }
  }

  @Test
  public void engagementDoesNotChaseCatsBeyondItsLocalRadius() throws Exception {
    RobotInfo cat = cat(at(8, 8), Direction.EAST);
    World world = new World(at(13, 8), cat);
    world.direction = Direction.WEST;

    assertFalse(new CatTactics().engage(world.rc, new RobotInfo[] {cat}));

    assertEquals(0, world.moves);
    assertEquals(0, world.turns);
  }

  @Test
  public void equallyCloseCatsUseIdRegardlessOfObservationOrder() throws Exception {
    RobotInfo west = cat(20, at(8, 8), Direction.EAST);
    RobotInfo east = cat(10, at(11, 8), Direction.WEST);
    for (RobotInfo[] observations : new RobotInfo[][] {{west, east}, {east, west}}) {
      World world = new World(at(10, 8), observations);
      world.direction = Direction.SOUTH;
      world.allowedMoves.clear();

      assertTrue(new CatTactics().engage(world.rc, observations));

      assertEquals(Direction.EAST, world.direction);
      assertEquals(at(11, 8), world.attacks.get(0));
    }
  }

  @Test
  public void turnCooldownStillAllowsAnAlreadyFacingBiteButNeverAnIllegalTurn() throws Exception {
    RobotInfo cat = cat(at(8, 8), Direction.EAST);
    for (Direction facing : new Direction[] {Direction.EAST, Direction.WEST}) {
      World world = new World(at(10, 8), cat);
      world.direction = facing;
      world.turningReady = false;
      world.allowedMoves.clear();

      assertEquals(facing == Direction.WEST,
          new CatTactics().engage(world.rc, new RobotInfo[] {cat}));

      assertEquals(facing == Direction.WEST ? 1 : 0, world.attacks.size());
      assertEquals(0, world.turns);
      assertEquals(0, world.moves);
    }
  }

  @Test
  public void activeEngagementIgnoresRatsAndAnEmptyObservationList() throws Exception {
    RobotInfo rat = rat(Team.B, at(11, 8));
    World world = new World(at(10, 8), rat);

    assertFalse(new CatTactics().engage(world.rc, new RobotInfo[] {rat}));
    assertFalse(new CatTactics().engage(world.rc, new RobotInfo[0]));

    assertTrue(world.attacks.isEmpty());
    assertEquals(0, world.moves);
    assertEquals(0, world.turns);
  }

  @Test
  public void hostileRatsAfterBetrayalVetoCatEngagementButAlliesAndCooperationDoNot()
      throws Exception {
    RobotInfo cat = cat(at(8, 8), Direction.EAST);
    for (int variant = 0; variant < 3; variant++) {
      RobotInfo rat = rat(variant == 1 ? Team.A : Team.B, at(9, 10));
      World world = protectedApproach(cat, rat);
      world.cooperation = variant == 2;

      assertEquals(variant != 0,
          new CatTactics().engage(world.rc, new RobotInfo[] {cat, rat}));

      assertEquals(variant == 0 ? 0 : 1, world.moves);
      assertEquals(variant == 0 ? 0 : 1, world.attacks.size());
    }
  }

  @Test
  public void bitesReachableCatEdgeWithZeroCheeseWhenAnchorIsTooFarAway() throws Exception {
    RobotInfo cat = cat(at(9, 8), Direction.EAST);
    World world = new World(at(11, 8), cat);
    world.direction = Direction.WEST;
    assertFalse(world.canAttack(cat.getLocation()));

    assertTrue(CatTactics.tryBite(world.rc, new RobotInfo[] {cat}));

    assertEquals(1, world.attacks.size());
    MapLocation attacked = world.attacks.get(0);
    assertTrue(
        Arrays.asList(UnitType.CAT.getAllTypeLocations(cat.getLocation())).contains(attacked));
    assertTrue(world.location.distanceSquaredTo(attacked) <= GameConstants.ATTACK_DISTANCE_SQUARED);
    assertFalse("A bite consumes the action cooldown", world.actionReady);
    // The controller rejects both cheese-spending overloads, including a redundant explicit zero.
    assertEquals("BITE_CAT id=" + cat.getID(), world.indicator);
  }

  @Test
  public void ignoresFriendlyAndOpposingRatsEvenWhenTheyArePassedAsTargets() throws Exception {
    RobotInfo friendly = rat(Team.A, at(11, 8));
    RobotInfo enemy = rat(Team.B, at(11, 9));
    World world = new World(at(10, 8), friendly, enemy);
    assertTrue("Enemy rat is otherwise legally biteable", world.canAttack(enemy.getLocation()));

    assertFalse(CatTactics.tryBite(world.rc, new RobotInfo[] {friendly, enemy}));
    assertFalse(CatTactics.retreat(world.rc, new RobotInfo[] {friendly, enemy}, null));

    assertTrue(world.attacks.isEmpty());
    assertEquals(0, world.moves);
  }

  @Test
  public void doesNotAttackDuringCooldown() throws Exception {
    RobotInfo cat = cat(at(11, 8), Direction.EAST);
    World world = new World(at(10, 8), cat);
    world.actionReady = false;

    assertFalse(CatTactics.tryBite(world.rc, new RobotInfo[] {cat}));

    assertTrue(world.attacks.isEmpty());
  }

  @Test
  public void doesNotBiteOutsideRangeOrOutsideTheWorkersFacing() throws Exception {
    RobotInfo[] cats = {
      cat(at(14, 8), Direction.WEST),
      cat(at(8, 8), Direction.EAST)
    };
    for (RobotInfo cat : cats) {
      World world = new World(at(10, 8), cat);

      assertFalse(CatTactics.tryBite(world.rc, new RobotInfo[] {cat}));

      assertTrue(world.attacks.isEmpty());
    }
  }

  @Test
  public void emptyCatListLeavesNormalNavigationInCharge() throws Exception {
    World world = new World(at(10, 8));

    assertFalse(CatTactics.tryBite(world.rc, new RobotInfo[0]));
    assertFalse(CatTactics.retreat(world.rc, new RobotInfo[0], at(20, 8)));

    assertTrue(world.attacks.isEmpty());
    assertEquals(0, world.moves);
  }

  @Test
  public void workerInFrontOfCatMovesBeyondImmediateCrushingRange() throws Exception {
    RobotInfo cat = cat(at(8, 8), Direction.EAST);
    World world = new World(at(10, 8), cat);
    assertTrue(catSees(cat, world.location));

    assertTrue(CatTactics.retreat(world.rc, new RobotInfo[] {cat}, null));

    assertEquals(1, world.moves);
    for (MapLocation part : UnitType.CAT.getAllTypeLocations(cat.getLocation())) {
      assertTrue(
          "Escape must leave adjacent crushing range", world.location.distanceSquaredTo(part) > 2);
    }
    assertEquals("Retreat preserves the current bite facing", Direction.EAST, world.direction);
  }

  @Test
  public void blockedOrCoolingDownWorkerStaysPutWithoutAttemptingIllegalActions() throws Exception {
    RobotInfo cat = cat(at(8, 8), Direction.EAST);
    for (boolean blocked : new boolean[] {true, false}) {
      World world = new World(at(10, 8), cat);
      if (blocked) {
        world.allowedMoves.clear();
      } else {
        world.movementReady = false;
      }

      assertTrue(CatTactics.retreat(world.rc, new RobotInfo[] {cat}, at(20, 8)));

      assertEquals(at(10, 8), world.location);
      assertEquals(0, world.moves);
    }
  }

  @Test
  public void safeWorkerBehindOrFarFromCatContinuesItsOwnTask() throws Exception {
    RobotInfo cat = cat(at(8, 8), Direction.EAST);
    MapLocation[] safeLocations = {at(6, 8), at(22, 22)};
    for (MapLocation location : safeLocations) {
      World world = new World(location, cat);
      assertFalse(catSees(cat, location));

      assertFalse(CatTactics.retreat(world.rc, new RobotInfo[] {cat}, at(20, 8)));

      assertEquals(location, world.location);
      assertEquals(0, world.moves);
    }
  }

  @Test
  public void catVisionUsesItsFootprintCenterRatherThanItsAnchor() throws Exception {
    RobotInfo cat = cat(at(8, 8), Direction.EAST);
    World world = new World(at(8, 12), cat);
    // Cat center is (8.5, 8.5): this rat is behind its east-facing 180-degree cone.
    assertFalse(catSees(cat, world.location));

    assertFalse(CatTactics.retreat(world.rc, new RobotInfo[] {cat}, null));

    assertEquals(0, world.moves);
  }

  @Test
  public void equallySafeMovesPreferProgressTowardTheExistingObjective() throws Exception {
    RobotInfo cat = cat(at(8, 8), Direction.EAST);
    World world = new World(at(11, 8), cat);
    world.allowedMoves = EnumSet.of(Direction.EAST, Direction.NORTHEAST);

    assertTrue(CatTactics.retreat(world.rc, new RobotInfo[] {cat}, at(14, 8)));

    // Both choices are three tiles from the cat's nearest edge and remain in its vision.
    assertEquals(
        "The straight move is closer to the delivery objective", at(12, 8), world.location);
  }

  private static boolean catSees(RobotInfo cat, MapLocation location) {
    return cat.getLocation()
        .isWithinDistanceSquared(
            location,
            UnitType.CAT.visionConeRadiusSquared,
            cat.getDirection(),
            UnitType.CAT.visionConeAngle,
            UnitType.CAT.usesBottomLeftLocationForDistance());
  }

  private static MapLocation at(int x, int y) {
    return new MapLocation(x, y);
  }

  private static RobotInfo cat(MapLocation location, Direction direction) {
    return cat(1000 + location.x * 30 + location.y, location, direction);
  }

  private static RobotInfo cat(int id, MapLocation location, Direction direction) {
    return new RobotInfo(
        id,
        Team.NEUTRAL,
        UnitType.CAT,
        UnitType.CAT.health,
        location,
        direction,
        0,
        0,
        null);
  }

  private static World protectedApproach(RobotInfo... robots) {
    World world = new World(at(11, 8), robots);
    world.direction = Direction.WEST;
    world.allowedMoves = EnumSet.of(Direction.WEST);
    world.walls.addAll(Arrays.asList(at(10, 7), at(10, 9)));
    return world;
  }

  private static RobotInfo rat(Team team, MapLocation location) {
    return new RobotInfo(
        location.x * 30 + location.y,
        team,
        UnitType.BABY_RAT,
        UnitType.BABY_RAT.health,
        location,
        Direction.EAST,
        0,
        0,
        null);
  }

  /** Legal baby movement and zero-cheese bites on a small open map. */
  private static final class World implements InvocationHandler {
    final RobotController rc;
    final List<RobotInfo> robots;
    final List<MapLocation> attacks = new ArrayList<>();
    final Set<MapLocation> walls = new HashSet<>();
    final Set<MapLocation> dirtTiles = new HashSet<>();
    final Set<MapLocation> hidden = new HashSet<>();
    Set<Direction> allowedMoves = EnumSet.allOf(Direction.class);
    MapLocation location;
    Direction direction = Direction.EAST;
    UnitType type = UnitType.BABY_RAT;
    int health = UnitType.BABY_RAT.health;
    int cargo;
    int globalCheese = 1_000;
    boolean actionReady = true;
    boolean cooperation = true;
    boolean movementReady = true;
    boolean turningReady = true;
    int moves;
    int turns;
    int terrainReads;
    String indicator;

    World(MapLocation location, RobotInfo... robots) {
      this.location = location;
      this.robots = Arrays.asList(robots);
      rc =
          (RobotController)
              Proxy.newProxyInstance(
                  RobotController.class.getClassLoader(),
                  new Class<?>[] {RobotController.class},
                  this);
    }

    void nextTurn() {
      actionReady = true;
      movementReady = true;
      turningReady = true;
    }

    private boolean onMap(MapLocation target) {
      return target.x >= 0 && target.y >= 0 && target.x < 30 && target.y < 30;
    }

    private RobotInfo robotAt(MapLocation target) {
      for (RobotInfo robot : robots) {
        for (MapLocation part : robot.getType().getAllTypeLocations(robot.getLocation())) {
          if (part.equals(target)) {
            return robot;
          }
        }
      }
      return null;
    }

    private boolean canMove(Direction step) {
      MapLocation target = location.add(step);
      return movementReady
          && step != Direction.CENTER
          && allowedMoves.contains(step)
          && onMap(target)
          && !walls.contains(target)
          && !dirtTiles.contains(target)
          && robotAt(target) == null;
    }

    private boolean visible(MapLocation target) {
      return onMap(target) && !hidden.contains(target)
          && location.isWithinDistanceSquared(
              target, type.visionConeRadiusSquared, direction, type.visionConeAngle,
              type.usesBottomLeftLocationForDistance());
    }

    boolean canAttack(MapLocation target) {
      RobotInfo victim = robotAt(target);
      return actionReady
          && onMap(target)
          && !location.equals(target)
          && location.distanceSquaredTo(target) <= GameConstants.ATTACK_DISTANCE_SQUARED
          && location.isWithinDistanceSquared(
              target,
              UnitType.BABY_RAT.visionConeRadiusSquared,
              direction,
              UnitType.BABY_RAT.visionConeAngle,
              UnitType.BABY_RAT.usesBottomLeftLocationForDistance())
          && victim != null
          && victim.getTeam() != Team.A;
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
        case "isCooperation":
          return cooperation;
        case "getHealth":
          return health;
        case "getRawCheese":
          return cargo;
        case "getGlobalCheese":
          return globalCheese;
        case "getDirection":
          return direction;
        case "isActionReady":
          return actionReady;
        case "onTheMap":
          return onMap((MapLocation) args[0]);
        case "canSenseLocation":
          return visible((MapLocation) args[0]);
        case "senseMapInfo":
          MapLocation sensed = (MapLocation) args[0];
          assertTrue("Terrain must be sensed before using it as evidence", visible(sensed));
          terrainReads++;
          return new MapInfo(
              sensed, !walls.contains(sensed) && !dirtTiles.contains(sensed), null,
              walls.contains(sensed), dirtTiles.contains(sensed), 0, TrapType.NONE, false);
        case "canTurn":
          return turningReady && args[0] != direction && args[0] != Direction.CENTER;
        case "turn":
          assertTrue("Turning must be legal and off cooldown", turningReady);
          assertTrue(args[0] != direction && args[0] != Direction.CENTER);
          direction = (Direction) args[0];
          turningReady = false;
          turns++;
          return null;
        case "canAttack":
          assertEquals("Only the zero-cheese overload is allowed", 1, method.getParameterCount());
          return canAttack((MapLocation) args[0]);
        case "attack":
          assertEquals("Only the zero-cheese overload is allowed", 1, method.getParameterCount());
          MapLocation target = (MapLocation) args[0];
          assertTrue("Bite must satisfy real facing, range and cooldown", canAttack(target));
          attacks.add(target);
          actionReady = false;
          return null;
        case "canMove":
          return canMove((Direction) args[0]);
        case "move":
          Direction step = (Direction) args[0];
          assertTrue("Escape must be legal and off cooldown", canMove(step));
          location = location.add(step);
          movementReady = false;
          moves++;
          return null;
        case "setIndicatorString":
          indicator = (String) args[0];
          return null;
        default:
          throw new AssertionError("Unsupported controller operation: " + method.getName());
      }
    }
  }
}
