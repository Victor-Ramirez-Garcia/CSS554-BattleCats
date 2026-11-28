package battlecats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import battlecode.common.Direction;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.Team;
import battlecode.common.UnitType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class CombatMicroTest {

  private static final MapLocation HERE = new MapLocation(10, 10);
  private static final CombatMicro.BytecodeClock UNLIMITED = () -> Integer.MAX_VALUE;

  @Test
  public void plansSafeBitesAndApproaches() {
    // SPEC: Bite from a position no enemy threatens, preferring kills; with no safe bite, approach
    // the nearest enemy without ending in reach of an enemy that would win the trade.
    Object[][] cases = {
      // name, my health, action ready, enemies, expected bite target (null: none), facing or null
      {"adjacent equal enemy in view", 100, true,
          new RobotInfo[] {enemy(1, 10, 11, Direction.SOUTH, 100)}, loc(10, 11), null},
      {"weak enemy two tiles ahead", 100, true,
          new RobotInfo[] {enemy(1, 10, 12, Direction.SOUTH, 50)}, loc(10, 12), null},
      {"stronger enemy watching", 50, true,
          new RobotInfo[] {enemy(1, 10, 12, Direction.SOUTH, 100)}, null, null},
      {"kill over ordinary bite", 100, true,
          new RobotInfo[] {enemy(1, 10, 11, Direction.SOUTH, 100),
              enemy(2, 11, 11, Direction.SOUTH, 8)}, loc(11, 11), null},
      {"enemy behind: turn and bite", 100, true,
          new RobotInfo[] {enemy(1, 10, 9, Direction.NORTH, 100)}, loc(10, 9), Direction.SOUTH},
      {"bite not ready: approach", 100, false,
          new RobotInfo[] {enemy(1, 10, 14, Direction.EAST, 100)}, null, null},
      {"weaker enemy unseen behind, bite not ready", 100, false,
          new RobotInfo[] {enemy(1, 10, 9, Direction.NORTH, 50)}, null, null},
      {"killing the only threat makes the bite safe", 5, true,
          new RobotInfo[] {enemy(1, 10, 11, Direction.SOUTH, 8)}, loc(10, 11), null},
    };
    for (Object[] c : cases) {
      String name = (String) c[0];
      int health = (int) c[1];
      RobotInfo[] enemies = (RobotInfo[]) c[3];
      CombatMicro micro = new CombatMicro();
      micro.plan(HERE, Direction.NORTH, health, true, true, (boolean) c[2], allMoves(), enemies,
          enemies.length, UNLIMITED);

      assertEquals(name + " bite", c[4], micro.biteTarget);
      if (c[5] != null) {
        assertEquals(name + " facing", c[5], micro.facing);
      }
      assertEquals(name + " unthreatened", 0, micro.threatCount);
      MapLocation end = HERE.add(micro.move);
      assertEquals(name + " end is safe", 0,
          threats(end, micro.facing, health, enemies, micro.biteTarget));
      if (micro.biteTarget != null) {
        assertTrue(name + " can bite", micro.biteFirst
            ? HERE.distanceSquaredTo(micro.biteTarget) <= 2
            : end.distanceSquaredTo(micro.biteTarget) <= 2
                && CombatMicro.sees(end, micro.facing, micro.biteTarget));
      }
    }
  }

  @Test
  public void aStrongerWatchingEnemyIsKeptOutOfReach() {
    RobotInfo strong = enemy(1, 10, 12, Direction.SOUTH, 100);
    CombatMicro micro = new CombatMicro();
    micro.plan(HERE, Direction.NORTH, 50, true, true, true, allMoves(),
        new RobotInfo[] {strong}, 1, UNLIMITED);

    MapLocation end = HERE.add(micro.move);
    assertTrue("must leave reach", Math.abs(end.y - 12) > 2);
  }

  @Test
  public void withoutAnySafeOptionTheChoiceIsThreatened() {
    // Two stronger enemies watching from either side, and no move or turn available.
    RobotInfo[] enemies = {
      enemy(1, 10, 12, Direction.SOUTH, 100), enemy(2, 10, 8, Direction.NORTH, 100)};
    CombatMicro micro = new CombatMicro();
    micro.plan(HERE, Direction.NORTH, 50, false, false, true, allMoves(), enemies, 2, UNLIMITED);

    assertNull(micro.biteTarget);
    assertTrue(micro.threatCount > 0);
    assertEquals(Direction.CENTER, micro.move);
  }

  @Test
  public void anEnemyFacingAwayIsNotCountedAsAThreat() {
    // Accepted risk (see the class comment): it could turn and bite this turn.
    RobotInfo[] enemies = {enemy(1, 10, 11, Direction.NORTH, 100)};
    CombatMicro micro = new CombatMicro();
    micro.plan(HERE, Direction.NORTH, 50, false, false, false, allMoves(), enemies, 1, UNLIMITED);

    assertEquals(Direction.CENTER, micro.move);
    assertEquals(0, micro.threatCount);
  }

  @Test
  public void approachingHeadsTowardTheTarget() {
    RobotInfo far = enemy(1, 10, 14, Direction.EAST, 100);
    CombatMicro micro = new CombatMicro();
    micro.plan(HERE, Direction.NORTH, 100, true, true, false, allMoves(),
        new RobotInfo[] {far}, 1, UNLIMITED);

    assertNotEquals(Direction.CENTER, micro.move);
    assertTrue(HERE.add(micro.move).distanceSquaredTo(far.getLocation())
        < HERE.distanceSquaredTo(far.getLocation()));
  }

  @Test
  public void aLowClockKeepsTheBestOptionSoFarOrNone() {
    RobotInfo[] enemies = {enemy(1, 10, 11, Direction.SOUTH, 100)};
    CombatMicro micro = new CombatMicro();
    micro.plan(HERE, Direction.NORTH, 100, true, true, true, allMoves(), enemies, 1,
        () -> CombatMicro.MIN_BYTECODES_LEFT - 1);

    assertEquals(Integer.MIN_VALUE, micro.score);
    assertNull(micro.biteTarget);
  }

  @Test
  public void aClockRunningOutPartwayKeepsTheBestOptionSoFar() {
    RobotInfo[] enemies = {enemy(1, 10, 14, Direction.EAST, 100)};
    int[] checks = {0};
    // Enough for the first approach step (NORTH, in engine order) only.
    CombatMicro.BytecodeClock clock = () -> checks[0]++ == 0 ? Integer.MAX_VALUE : 0;
    CombatMicro micro = new CombatMicro();
    micro.plan(HERE, Direction.NORTH, 100, true, true, false, allMoves(), enemies, 1, clock);

    assertNotEquals(Integer.MIN_VALUE, micro.score);
    assertEquals(Direction.NORTH, micro.move);
    assertEquals(0, micro.threatCount);
  }

  @Test
  public void actDeclinesWithoutActingWhenEveryOptionIsThreatened() throws Exception {
    RobotInfo[] enemies = {
      enemy(1, 10, 12, Direction.SOUTH, 100), enemy(2, 10, 8, Direction.NORTH, 100)};
    List<String> actions = new ArrayList<>();
    RobotController rc = fakeController(50, false, false, true, actions);
    CombatMicro micro = new CombatMicro();

    assertFalse(micro.act(rc, enemies, false, UNLIMITED));
    assertTrue(micro.declined);
    assertEquals(List.of(), actions);
  }

  @Test
  public void actSkipsABiteTheEngineWouldRefuse() throws Exception {
    RobotInfo[] enemies = {enemy(1, 10, 11, Direction.SOUTH, 100)};
    List<String> actions = new ArrayList<>();
    RobotController rc = fakeController(100, false, false, true, actions);
    CombatMicro micro = new CombatMicro();

    assertTrue(micro.act(rc, enemies, false, UNLIMITED));
    assertEquals(loc(10, 11), micro.biteTarget);
    assertEquals(List.of(), actions);
  }

  @Test
  public void actWithNoEnemyRatsDoesNothing() throws Exception {
    List<String> actions = new ArrayList<>();
    CombatMicro micro = new CombatMicro();

    assertFalse(micro.act(fakeController(100, true, true, true, actions), new RobotInfo[0], false,
        UNLIMITED));
    assertFalse(micro.declined);
    assertEquals(List.of(), actions);
  }

  /**
   * A rat at HERE facing north; every move is allowed when movement is ready, attacks are always
   * refused, and move, turn and attack calls are recorded.
   */
  private static RobotController fakeController(int health, boolean movementReady,
      boolean turnReady, boolean actionReady, List<String> actions) {
    return (RobotController) Proxy.newProxyInstance(
        RobotController.class.getClassLoader(),
        new Class<?>[] {RobotController.class},
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "getLocation": return HERE;
            case "getTeam": return Team.A;
            case "getDirection": return Direction.NORTH;
            case "getHealth": return health;
            case "isMovementReady": return movementReady;
            case "canMove": return movementReady;
            case "canTurn": return turnReady;
            case "isActionReady": return actionReady;
            case "canAttack": return false;
            case "move":
            case "turn":
            case "attack":
              actions.add(method.getName() + " " + Arrays.toString(args));
              return null;
            case "setIndicatorString": return null;
            default: throw new UnsupportedOperationException(method.getName());
          }
        });
  }

  @Test
  public void keepsTheNearestEnemyRatsOnly() {
    RobotInfo[] robots = {
      enemy(1, 15, 10, Direction.NORTH, 100),
      new RobotInfo(2, Team.A, UnitType.BABY_RAT, 100, loc(10, 11), Direction.NORTH, 0, 0, null),
      new RobotInfo(3, Team.NEUTRAL, UnitType.CAT, 100, loc(11, 10), Direction.NORTH, 0, 0, null),
      enemy(4, 11, 10, Direction.NORTH, 100),
      enemy(5, 13, 10, Direction.NORTH, 100),
      enemy(6, 12, 10, Direction.NORTH, 100),
      enemy(7, 14, 10, Direction.NORTH, 100),
    };
    RobotInfo[] out = new RobotInfo[CombatMicro.MAX_ENEMIES];

    int count = CombatMicro.nearestEnemyRats(Team.A, HERE, robots, out);

    assertEquals(4, count);
    assertEquals("[4, 6, 5, 7]", Arrays.toString(
        Arrays.stream(out).mapToInt(RobotInfo::getID).toArray()));
    assertEquals(0, CombatMicro.nearestEnemyRats(Team.A, HERE, new RobotInfo[0], out));
  }

  @Test
  public void integerDirectionMatchesTheEngineWithinSixTiles() {
    for (int dx = -6; dx <= 6; dx++) {
      for (int dy = -6; dy <= 6; dy++) {
        MapLocation target = HERE.translate(dx, dy);
        assertEquals(dx + "," + dy, HERE.directionTo(target), CombatMicro.towards(HERE, target));
      }
    }
  }

  @Test
  public void integerConeMatchesTheEngine() {
    for (Direction face : Direction.allDirections()) {
      if (face == Direction.CENTER) {
        continue;
      }
      for (int dx = -6; dx <= 6; dx++) {
        for (int dy = -6; dy <= 6; dy++) {
          MapLocation target = HERE.translate(dx, dy);
          assertEquals(face + " " + dx + "," + dy,
              HERE.isWithinDistanceSquared(target, 20, face, 90, false),
              CombatMicro.sees(HERE, face, target));
        }
      }
    }
  }

  /**
   * Independent count of enemies that see {@code end}, could step next to it and would win the
   * trade; an enemy at {@code bitten} with at most 10 health dies first and is skipped.
   */
  private static int threats(MapLocation end, Direction face, int health, RobotInfo[] enemies,
      MapLocation bitten) {
    int threats = 0;
    for (RobotInfo enemy : enemies) {
      MapLocation at = enemy.getLocation();
      boolean killed = at.equals(bitten) && enemy.getHealth() <= 10;
      if (!killed && Math.abs(at.x - end.x) <= 2 && Math.abs(at.y - end.y) <= 2
          && at.isWithinDistanceSquared(end, 20, enemy.getDirection(), 90, false)
          && (enemy.getHealth() > health
              || !end.isWithinDistanceSquared(at, 20, face, 90, false))) {
        threats++;
      }
    }
    return threats;
  }

  private static boolean[] allMoves() {
    boolean[] moves = new boolean[9];
    Arrays.fill(moves, true);
    return moves;
  }

  private static RobotInfo enemy(int id, int x, int y, Direction facing, int health) {
    return new RobotInfo(id, Team.B, UnitType.BABY_RAT, health, loc(x, y), facing, 0, 0, null);
  }

  private static MapLocation loc(int x, int y) {
    return new MapLocation(x, y);
  }
}
