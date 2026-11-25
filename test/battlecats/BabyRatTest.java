package battlecats;

import static org.junit.Assert.assertEquals;
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
import java.util.List;
import org.junit.Test;

/** Acceptance tests for a Baby Rat becoming a Rat King where it stands. */
public class BabyRatTest {
  private static final MapLocation CENTER = new MapLocation(10, 10);

  @Test
  public void becomesARatKingWhenTheEngineAllowsIt() throws Exception {
    // SPEC: A Baby Rat can become a Rat King.
    // VALUE: The rat upgrades through becomeRatKing and pays exactly 50 cheese.
    // SCENARIO: Seven allied Baby Rats including this one fill an open 3x3 on round 100.
    // PREVENTS: Calling the spawn API, which only Rat Kings may use, so no king ever forms.
    World world = validWorld();

    new BabyRat(1).takeTurn(world.rc);

    assertEquals(1, world.upgrades);
    assertEquals(400 - GameConstants.RAT_KING_UPGRADE_CHEESE_COST, world.cheese);
  }

  @Test
  public void waitsWhileTheEngineRefusesTheUpgrade() throws Exception {
    // SPEC: The engine decides whether this 3x3 is legal (rats, terrain, cats, kings, cap).
    // VALUE: A refused upgrade spends nothing and the rat carries on with its turn.
    // SCENARIO: Only six allied Baby Rats occupy the square.
    // PREVENTS: Promoting in an illegal square or duplicating the engine's rules locally.
    World world = validWorld();
    world.robots.remove(world.robots.size() - 1);

    new BabyRat(1).takeTurn(world.rc);

    assertEquals(0, world.upgrades);
    assertEquals(400, world.cheese);
  }

  @Test
  public void aTeamPromotesAtMostOneBabyRatPerGame() throws Exception {
    // SPEC: Team policy: one extra king per game; the new king records it in shared memory.
    // VALUE: The promoted rat writes the flag, and a later rat in a valid square stays a worker.
    // SCENARIO: The first rat promotes; a second rat with a fresh valid square then takes a turn.
    // PREVENTS: Repeated promotions starving every king, as uncapped promotion did on minimaze.
    World first = validWorld();
    new BabyRat(1).takeTurn(first.rc);
    assertEquals(1, first.upgrades);
    assertEquals(1, first.shared[3]);

    World second = validWorld();
    second.shared[3] = first.shared[3];
    new BabyRat(2).takeTurn(second.rc);

    assertEquals(0, second.upgrades);
    assertEquals(400, second.cheese);
  }

  @Test
  public void promotesOnlyWhileAKingRecentlyReportedSurplusIncome() throws Exception {
    // SPEC: Team policy: promote only while a king's recent income could feed another king.
    // VALUE: The rat promotes on a fresh report and stays a worker without one or on a stale one.
    // SCENARIO: The king's two-round report stamp is missing, 0, 2 or 4 rounds old, or from
    // a later round.
    // PREVENTS: An extra king starving the team on poor maps such as minimaze.
    Object[][] cases = {
      {"no report", null, 0},
      {"reported this round", 100, 1},
      {"reported two rounds ago", 98, 1},
      {"reported four rounds ago", 96, 0},
      {"stamp from a later round", 104, 0},
    };
    for (Object[] c : cases) {
      World world = validWorld();
      world.shared[4] = 0;
      if (c[1] != null) {
        world.fundPromotionAt((int) c[1]);
      }

      new BabyRat(1).takeTurn(world.rc);

      assertEquals((String) c[0], (int) c[2], world.upgrades);
    }
  }

  @Test
  public void promotionPolicyStopsAfterRound1200AndKeepsTheSpendingReserve() throws Exception {
    // SPEC: Team policy, not engine rules: no promotion after round 1200, and the upgrade
    // must leave the 300-cheese spending reserve because each king eats food every round.
    Object[][] cases = {
      {"round 1200", 1200, 350, 1},
      {"round 1201", 1201, 400, 0},
      {"exactly the reserve after paying", 100, 350, 1},
      {"one below the reserve after paying", 100, 349, 0},
    };
    for (Object[] c : cases) {
      World world = validWorld();
      world.round = (int) c[1];
      world.cheese = (int) c[2];
      world.fundPromotionAt(world.round);

      new BabyRat(1).takeTurn(world.rc);

      assertEquals((String) c[0], (int) c[3], world.upgrades);
    }
  }

  @Test
  public void anExplorerDigsDirtOnTheWayToItsWaypoint() throws Exception {
    // SPEC: A worker with no cheese in sight explores toward a waypoint and may clear dirt.
    // VALUE: The dirt tile toward the waypoint is removed; on pipes our king starts in a
    // dirt-walled pocket with no mine, and workers that never dug starved it.
    // SCENARIO: The king is in view, no cheese is visible and dirt blocks the first step.
    World world = new World();
    world.robots.add(robot(UnitType.RAT_KING, Team.A, new MapLocation(20, 20)));
    MapLocation waypoint = new Navigation(1).explorationTarget(world.rc);
    MapLocation blocked = CENTER.add(CENTER.directionTo(waypoint));
    world.dirt.add(blocked);

    new BabyRat(1).takeTurn(world.rc);

    assertEquals(List.of(blocked), world.dug);
  }

  private static World validWorld() {
    World world = new World();
    world.fundPromotionAt(world.round);
    // Six neighbors plus this rat make the engine's seven.
    int[][] offsets = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}};
    for (int i = 0; i < offsets.length; i++) {
      world.robots.add(robot(UnitType.BABY_RAT, Team.A,
          new MapLocation(CENTER.x + offsets[i][0], CENTER.y + offsets[i][1])));
    }
    return world;
  }

  private static RobotInfo robot(UnitType type, Team team, MapLocation location) {
    return new RobotInfo(100 + location.x + location.y, team, type, type.health, location,
        Direction.NORTH, 0, 0, null);
  }

  private static final class World implements InvocationHandler {
    final RobotController rc = (RobotController) Proxy.newProxyInstance(
        RobotController.class.getClassLoader(), new Class<?>[] {RobotController.class}, this);
    final List<RobotInfo> robots = new ArrayList<>();
    final java.util.Set<MapLocation> walls = new java.util.HashSet<>();
    final java.util.Set<MapLocation> dirt = new java.util.HashSet<>();
    final List<MapLocation> dug = new ArrayList<>();
    final int[] shared = new int[64];
    int cheese = 400;
    int round = 100;
    int upgrades;

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      String name = method.getName();
      switch (name) {
        case "getLocation": return CENTER;
        case "getTeam": return Team.A;
        case "getType": return upgrades > 0 ? UnitType.RAT_KING : UnitType.BABY_RAT;
        case "readSharedArray": return shared[(Integer) args[0]];
        case "writeSharedArray":
          assertTrue("Only rat kings can write to the shared array", upgrades > 0);
          shared[(Integer) args[0]] = (Integer) args[1];
          return null;
        case "getRoundNum": return round;
        case "getMapWidth":
        case "getMapHeight": return 30;
        case "getRawCheese": return 0;
        case "getGlobalCheese": return cheese;
        case "getAllCheese": return cheese;
        case "isBeingCarried":
        case "isBeingThrown":
        case "canTurn": return false;
        case "isActionReady": return true;
        case "getActionCooldownTurns": return 0;
        case "senseNearbyRobots": return robots.toArray(new RobotInfo[0]);
        case "senseNearbyMapInfos": return mapInfos();
        case "canSenseLocation": return true;
        case "senseMapInfo": return info((MapLocation) args[0]);
        case "canBuildRat":
        case "buildRat":
          throw new AssertionError("Only rat kings can spawn other rats");
        case "canBecomeRatKing": return engineAllowsUpgrade();
        case "becomeRatKing":
          assertTrue("Upgrade must be legal", engineAllowsUpgrade());
          upgrades++;
          cheese -= GameConstants.RAT_KING_UPGRADE_CHEESE_COST;
          return null;
        case "getDirection": return Direction.NORTH;
        case "canRemoveDirt": return dirt.contains((MapLocation) args[0]);
        case "removeDirt":
          assertTrue("Only dirt can be removed", dirt.remove((MapLocation) args[0]));
          dug.add((MapLocation) args[0]);
          return null;
        case "getID": return 1;
        default: return defaultValue(method.getReturnType());
      }
    }

    /** The engine's rules for this fixture: seven allied Baby Rats in the 3x3 counting this one. */
    /** Writes the king's income report as KingSignal stamps it on the given round. */
    void fundPromotionAt(int reportRound) {
      shared[4] = reportRound / 2 + 1;
    }

    private boolean engineAllowsUpgrade() {
      if (upgrades > 0 || cheese < GameConstants.RAT_KING_UPGRADE_CHEESE_COST) {
        return false;
      }
      int rats = 1;
      for (RobotInfo robot : robots) {
        MapLocation at = robot.getLocation();
        boolean inSquare = Math.abs(at.x - CENTER.x) <= 1 && Math.abs(at.y - CENTER.y) <= 1;
        if (inSquare && robot.getTeam() == Team.A && robot.getType() == UnitType.BABY_RAT) {
          rats++;
        }
      }
      return rats >= 7;
    }

    private MapInfo[] mapInfos() {
      List<MapInfo> infos = new ArrayList<>();
      for (int x = CENTER.x - 1; x <= CENTER.x + 1; x++) {
        for (int y = CENTER.y - 1; y <= CENTER.y + 1; y++) {
          infos.add(info(new MapLocation(x, y)));
        }
      }
      return infos.toArray(new MapInfo[0]);
    }

    private MapInfo info(MapLocation location) {
      boolean wall = walls.contains(location);
      boolean isDirt = dirt.contains(location);
      return new MapInfo(location, !wall && !isDirt, null, wall, isDirt, 0, TrapType.NONE, false);
    }

    private static Object defaultValue(Class<?> type) {
      if (!type.isPrimitive()) return null;
      if (type == boolean.class) return false;
      if (type == int.class) return 0;
      if (type == long.class) return 0L;
      if (type == float.class) return 0F;
      if (type == double.class) return 0D;
      if (type == byte.class) return (byte) 0;
      if (type == short.class) return (short) 0;
      if (type == char.class) return (char) 0;
      return null;
    }
  }
}
