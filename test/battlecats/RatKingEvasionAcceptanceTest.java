package battlecats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import battlecode.common.Direction;
import battlecode.common.GameConstants;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.Team;
import battlecode.common.UnitType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Public-turn scenarios with neutral cats and a deterministic in-memory controller. */
public class RatKingEvasionAcceptanceTest {
  private static final MapLocation KING = new MapLocation(10, 10);
  private static final MapLocation CAT_WEST = new MapLocation(7, 10);

  @Test
  public void friendlyKingDoesNotBecomeACollectorConstraintAfterBetrayal() throws Exception {
    World world = world(
        robot(UnitType.RAT_KING, new MapLocation(15, 10), Team.A),
        robot(UnitType.BABY_RAT, new MapLocation(8, 10), Team.B));
    world.cooperation = false;

    new RatKing().takeTurn(world.rc);

    assertEquals(1, world.moves);
    assertTrue(world.location.x > KING.x);
  }

  @Test
  public void enemyKingBecomesAMovementThreatOnlyAfterBetrayal() throws Exception {
    for (boolean cooperating : new boolean[] {true, false}) {
      World world = world(robot(UnitType.RAT_KING, new MapLocation(13, 10), Team.B));
      world.cooperation = cooperating;

      new RatKing().takeTurn(world.rc);

      assertEquals(cooperating ? 0 : 1, world.moves);
      if (!cooperating) {
        assertTrue("Move away from the hostile king", world.location.x < KING.x);
      }
    }
  }

  @Test
  public void kingDefendsBeforeProductionCanConsumeEveryAction() throws Exception {
    RobotInfo attacker = robot(UnitType.BABY_RAT, new MapLocation(12, 10), Team.B);
    World world = world(attacker);
    world.cooperation = false;
    world.allowPvP = true;
    world.teamCheese = 1_000;
    world.buildable.add(outsideKing(Direction.NORTH));

    new RatKing().takeTurn(world.rc);

    assertEquals(Collections.singletonList(attacker.getLocation()), world.attacks);
    assertTrue("Defense and spawning share the action cooldown", world.births.isEmpty());
  }

  @Test
  public void escapesNeutralCatRatherThanFilteringItOutAsAnotherTeam() throws Exception {
    World world = world(robot(UnitType.CAT, CAT_WEST, Team.NEUTRAL));

    new RatKing().takeTurn(world.rc);

    assertTrue(world.location.distanceSquaredTo(CAT_WEST) > KING.distanceSquaredTo(CAT_WEST));
    assertEquals(1, world.moves);
    assertEquals(world.location.x, world.sharedArray[0]);
    assertEquals(world.location.y, world.sharedArray[1]);
  }

  @Test
  public void escapesCatEvenWhenNoEscapeStaysNearACollector() throws Exception {
    MapLocation collector = new MapLocation(15, 15);
    World world = world(
        robot(UnitType.CAT, CAT_WEST, Team.NEUTRAL),
        robot(UnitType.BABY_RAT, collector, Team.A));

    new RatKing().takeTurn(world.rc);

    assertEquals("Distant collectors must not prevent the king escaping a cat", 1, world.moves);
    assertTrue(world.location.distanceSquaredTo(collector)
        > GameConstants.CHEESE_TRANSFER_RADIUS_SQUARED);
  }

  @Test
  public void keepsDeliveryAccessWhenAvoidingOnlyAnEnemyBaby() throws Exception {
    MapLocation collector = new MapLocation(10, 11);
    World world = world(
        robot(UnitType.BABY_RAT, CAT_WEST, Team.B),
        robot(UnitType.BABY_RAT, collector, Team.A));

    new RatKing().takeTurn(world.rc);

    assertEquals(1, world.moves);
    assertTrue(world.location.distanceSquaredTo(CAT_WEST) > KING.distanceSquaredTo(CAT_WEST));
    assertTrue(world.location.distanceSquaredTo(collector)
        <= GameConstants.CHEESE_TRANSFER_RADIUS_SQUARED);
  }

  @Test
  public void staysStillWithoutThreats() throws Exception {
    World[] scenarios = {
      world(),
      world(robot(UnitType.BABY_RAT, new MapLocation(10, 11), Team.A)),
      world(robot(UnitType.RAT_KING, new MapLocation(15, 15), Team.B))
    };
    for (World world : scenarios) {
      new RatKing().takeTurn(world.rc);
      assertEquals(KING, world.location);
      assertEquals(0, world.moves);
    }
  }

  @Test
  public void remainsValidWhenNoEscapeMoveIsLegal() throws Exception {
    World world = world(robot(UnitType.CAT, CAT_WEST, Team.NEUTRAL));
    world.legalMoves.clear();

    new RatKing().takeTurn(world.rc);

    assertEquals(KING, world.location);
    assertEquals(0, world.moves);
    assertEquals(KING.x, world.sharedArray[0]);
  }

  @Test
  public void firstFourSuccessfulBirthsPreferDifferentCardinalDirections() throws Exception {
    World world = world();
    world.teamCheese = 1_000; // Test control: enough cheese to exercise spawning every turn.
    List<MapLocation> expected = Arrays.asList(
        outsideKing(Direction.NORTH), outsideKing(Direction.EAST),
        outsideKing(Direction.SOUTH), outsideKing(Direction.WEST));
    world.buildable.addAll(expected);
    RatKing king = new RatKing();

    for (int turn = 0; turn < 4; turn++) {
      world.nextTurn();
      king.takeTurn(world.rc);
    }

    assertEquals(expected, world.births);
  }

  @Test
  public void blockedOpeningTileFallsBackWithoutWaiting() throws Exception {
    World world = world();
    world.teamCheese = 1_000;
    MapLocation fallback = new MapLocation(12, 12);
    world.fallbackLocations = new MapLocation[] {KING, fallback};
    world.buildable.add(fallback);

    new RatKing().takeTurn(world.rc);

    assertEquals(Collections.singletonList(fallback), world.births);
  }

  @Test
  public void failedSpawnTurnDoesNotAdvanceTheOpening() throws Exception {
    World world = world();
    world.teamCheese = 1_000;
    RatKing king = new RatKing();
    king.takeTurn(world.rc);
    assertTrue(world.births.isEmpty());

    world.buildable.add(outsideKing(Direction.NORTH));
    world.nextTurn();
    king.takeTurn(world.rc);

    assertEquals(Collections.singletonList(outsideKing(Direction.NORTH)), world.births);
  }

  @Test
  public void resumesBaselineSpawnOrderAfterFourBirths() throws Exception {
    World world = world();
    world.teamCheese = 1_000;
    for (Direction direction : new Direction[] {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
      world.buildable.add(outsideKing(direction));
    }
    MapLocation baselineFirst = new MapLocation(8, 8);
    world.fallbackLocations = new MapLocation[] {baselineFirst, outsideKing(Direction.NORTH)};
    world.buildable.add(baselineFirst);
    RatKing king = new RatKing();

    for (int turn = 0; turn < 5; turn++) {
      world.nextTurn();
      king.takeTurn(world.rc);
    }

    assertEquals(5, world.births.size());
    assertEquals(baselineFirst, world.births.get(4));
  }

  @Test
  public void allBirthsPreserveTheGlobalFoodReserveIncludingEquality() throws Exception {
    for (int cheese : new int[] {0, 299, 309, 310, 311}) {
      World world = world();
      world.teamCheese = cheese;
      world.buildable.add(outsideKing(Direction.NORTH));

      new RatKing().takeTurn(world.rc);

      assertEquals("Spawn threshold with cheese=" + cheese, cheese >= 310 ? 1 : 0,
          world.births.size());
    }
  }

  @Test
  public void carriedCheeseDoesNotFundKingFoodOrProduction() throws Exception {
    World world = world();
    world.teamCheese = 309;
    world.carriedCheese = 1000;
    world.buildable.add(outsideKing(Direction.NORTH));

    new RatKing().takeTurn(world.rc);

    assertTrue(world.births.isEmpty());
  }

  @Test
  public void initialBankDoesNotFundGrowthAboveSixteenLivingBabies() throws Exception {
    World world = fundedProductionWorld();
    RatKing king = new RatKing();

    for (int turn = 0; turn < 20; turn++) {
      king.takeTurn(world.rc);
      world.nextTurn();
    }

    assertEquals(16, world.births.size());
    assertEquals(16, world.livingBabies);
    assertTrue("Unspent initial funds remain available as food", world.teamCheese > 2000);
  }

  @Test
  public void demonstratedNetIncomeFundsGrowthAcrossMultipleCostBands() throws Exception {
    World world = fundedProductionWorld();
    RatKing king = new RatKing();
    produceOpening(king, world);

    for (int birth = 0; birth < 5; birth++) {
      // Test income covers this turn's food plus the purchase and matching food investment.
      world.teamCheese += 2 * world.currentRatCost() + GameConstants.RAT_KING_CHEESE_CONSUMPTION;
      king.takeTurn(world.rc);
      world.nextTurn();
    }

    assertEquals("Income-funded growth has no lifetime birth cap", 21, world.births.size());
    assertEquals(21, world.livingBabies);
  }

  @Test
  public void failedGrowthBuildDoesNotConsumeEarnedCredit() throws Exception {
    World world = fundedProductionWorld();
    RatKing king = new RatKing();
    produceOpening(king, world);
    world.buildable.clear();
    world.teamCheese += 102; // 100 net income after the two-cheese king food charge.
    king.takeTurn(world.rc);
    assertEquals(16, world.births.size());
    world.nextTurn();
    world.teamCheese += 2; // Offset food only; do not add more growth credit.
    world.buildable.add(outsideKing(Direction.NORTH));

    king.takeTurn(world.rc);

    assertEquals(17, world.births.size());
  }

  @Test
  public void bankLossReducesGrowthCreditBeforeTheNextBuildOpportunity() throws Exception {
    World world = fundedProductionWorld();
    RatKing king = new RatKing();
    produceOpening(king, world);
    world.buildable.clear();
    world.teamCheese += 102;
    king.takeTurn(world.rc);
    world.nextTurn();
    world.teamCheese--;
    world.buildable.add(outsideKing(Direction.NORTH));

    king.takeTurn(world.rc);

    assertEquals("A negative bank delta cannot be ignored", 16, world.births.size());
  }

  @Test
  public void healthyBankCanReplaceWorkersAfterAllOpeningBabiesDie() throws Exception {
    World world = fundedProductionWorld();
    RatKing king = new RatKing();
    produceOpening(king, world);
    world.livingBabies = 0;

    king.takeTurn(world.rc);

    assertEquals("The bootstrap is a living-population band, not a lifetime cap", 17,
        world.births.size());
    assertEquals(1, world.livingBabies);
  }

  private static World fundedProductionWorld() {
    World world = world();
    world.teamCheese = 2500;
    world.buildable.add(outsideKing(Direction.NORTH));
    world.fallbackLocations = new MapLocation[] {outsideKing(Direction.NORTH)};
    return world;
  }

  private static void produceOpening(RatKing king, World world) throws Exception {
    for (int turn = 0; turn < 16; turn++) {
      king.takeTurn(world.rc);
      world.nextTurn();
    }
    assertEquals(16, world.births.size());
  }

  private static MapLocation outsideKing(Direction direction) {
    return KING.add(direction).add(direction);
  }

  private static RobotInfo robot(UnitType type, MapLocation location, Team team) {
    return new RobotInfo(
        location.x * 100 + location.y, team, type, 1, location, Direction.EAST, 0, 0, null);
  }

  private static World world(RobotInfo... robots) {
    World world = new World();
    Collections.addAll(world.robots, robots);
    return world;
  }

  private static final class World implements InvocationHandler {
    final RobotController rc;
    final List<RobotInfo> robots = new ArrayList<>();
    final Set<Direction> legalMoves = EnumSet.allOf(Direction.class);
    final Set<MapLocation> buildable = new HashSet<>();
    final List<MapLocation> births = new ArrayList<>();
    final List<MapLocation> attacks = new ArrayList<>();
    final int[] sharedArray = new int[GameConstants.SHARED_ARRAY_SIZE];
    MapLocation[] fallbackLocations = new MapLocation[0];
    MapLocation location = KING;
    int teamCheese;
    int carriedCheese;
    int livingBabies;
    int round = 1;
    int moves;
    boolean movedThisTurn;
    boolean builtThisTurn;
    boolean attackedThisTurn;
    boolean cooperation = true;
    boolean allowPvP;

    World() {
      rc = (RobotController) Proxy.newProxyInstance(
          RobotController.class.getClassLoader(), new Class<?>[] {RobotController.class}, this);
    }

    void nextTurn() {
      builtThisTurn = false;
      attackedThisTurn = false;
      movedThisTurn = false;
      round++;
      teamCheese = Math.max(0, teamCheese - GameConstants.RAT_KING_CHEESE_CONSUMPTION);
    }

    int currentRatCost() {
      return GameConstants.BUILD_ROBOT_BASE_COST
          + GameConstants.BUILD_ROBOT_COST_INCREASE
              * (livingBabies / GameConstants.NUM_ROBOTS_FOR_COST_INCREASE);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      switch (method.getName()) {
        case "getLocation":
          return location;
        case "getTeam":
          return Team.A;
        case "getType":
          return UnitType.RAT_KING;
        case "isCooperation":
          return cooperation;
        case "getID":
          return 1;
        case "getHealth":
          return 600;
        case "getRawCheese":
          return carriedCheese;
        case "getRoundNum":
          return round;
        case "getDirection":
          return Direction.EAST;
        case "canTurn":
          return false; // The king has full-circle vision already.
        case "canSenseLocation":
          return location.distanceSquaredTo((MapLocation) args[0])
              <= UnitType.RAT_KING.visionConeRadiusSquared;
        case "isActionReady":
          return !builtThisTurn && !attackedThisTurn;
        case "isMovementReady":
          return !movedThisTurn;
        case "getAllCheese":
          return teamCheese + carriedCheese;
        case "getGlobalCheese":
          return teamCheese;
        case "getCurrentRatCost":
          return currentRatCost();
        case "senseNearbyRobots":
          Team requested = args != null && args.length > 1 ? (Team) args[1] : null;
          return robots.stream()
              .filter(robot -> requested == null || robot.getTeam() == requested)
              .filter(robot -> location.distanceSquaredTo(robot.getLocation()) <= 100)
              .toArray(RobotInfo[]::new);
        case "canMove":
          return !movedThisTurn && legalMoves.contains((Direction) args[0]);
        case "move":
          assertTrue(!movedThisTurn && legalMoves.contains((Direction) args[0]));
          location = location.add((Direction) args[0]);
          movedThisTurn = true;
          moves++;
          return null;
        case "getAllLocationsWithinRadiusSquared":
          return fallbackLocations;
        case "canBuildRat":
          return !builtThisTurn && !attackedThisTurn && teamCheese >= currentRatCost()
              && buildable.contains((MapLocation) args[0]);
        case "canAttack":
          MapLocation target = (MapLocation) args[0];
          return allowPvP && !builtThisTurn && !attackedThisTurn
              && location.distanceSquaredTo(target)
                  <= GameConstants.RAT_KING_ATTACK_DISTANCE_SQUARED
              && robots.stream().anyMatch(robot -> robot.getTeam() != Team.A
                  && robot.getLocation().equals(target));
        case "attack":
          assertEquals("Only free bites are part of this policy", 1, args.length);
          assertTrue(!builtThisTurn && !attackedThisTurn);
          attacks.add((MapLocation) args[0]);
          attackedThisTurn = true;
          return null;
        case "buildRat":
          assertTrue(!builtThisTurn && !attackedThisTurn && teamCheese >= currentRatCost());
          assertTrue(buildable.contains((MapLocation) args[0]));
          births.add((MapLocation) args[0]);
          teamCheese -= currentRatCost();
          livingBabies++;
          builtThisTurn = true;
          return null;
        case "writeSharedArray":
          sharedArray[(Integer) args[0]] = (Integer) args[1];
          return null;
        case "setIndicatorString":
          return null;
        default:
          throw new UnsupportedOperationException("Unhandled controller call: " + method.getName());
      }
    }
  }
}
