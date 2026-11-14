package battlecats;

import static org.junit.Assert.assertEquals;

import battlecode.common.Direction;
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

/** Acceptance tests for a Baby Rat upgrading at its own center. */
public class BabyRatTest {
  private static final MapLocation CENTER = new MapLocation(10, 10);

  @Test
  public void upgradesAtItsCenterWhenAllRequirementsAreMet() throws Exception {
    // SPEC: A baby rat can become a rat king.
    // VALUE: The center baby rat upgrades and spends exactly 50 cheese.
    // SCENARIO: Seven allied baby rats surround an open center on round 100.
    // PREVENTS: A valid rat-king opportunity being ignored.
    World world = validWorld();

    new BabyRat(1).takeTurn(world.rc);

    assertEquals(1, world.upgrades);
    assertEquals(0, world.cheese);
  }

  @Test
  public void requiresAtLeastSevenAlliedRatsInTheSurroundingSquare() throws Exception {
    // SPEC: At least seven allied rats must occupy the surrounding 3x3 square.
    // VALUE: Six allied rats are insufficient.
    // SCENARIO: The otherwise valid square contains only six allied baby rats.
    // PREVENTS: Creating a king without enough nearby allied rats.
    World world = validWorld();
    world.robots.remove(world.robots.size() - 1);

    new BabyRat(1).takeTurn(world.rc);

    assertEquals(0, world.upgrades);
  }

  @Test
  public void rejectsAnImpassableTileInTheSurroundingSquare() throws Exception {
    // SPEC: Every tile in the surrounding 3x3 square must be passable.
    // VALUE: One impassable tile rejects the upgrade.
    // SCENARIO: Seven allies are present, but one adjacent tile is a wall.
    // PREVENTS: A king occupying or being surrounded by impassable terrain.
    World world = validWorld();
    world.walls.add(new MapLocation(9, 9));

    new BabyRat(1).takeTurn(world.rc);

    assertEquals(0, world.upgrades);
  }

  @Test
  public void rejectsAThreeByThreeSquareOverlappingARatKingOrCat() throws Exception {
    // SPEC: The 3x3 square cannot intersect a rat king or cat.
    // VALUE: An existing cat or rat king in the square rejects the upgrade.
    // SCENARIO: Seven allies are present and one blocking unit occupies an adjacent tile.
    // PREVENTS: Overlapping enemy and newly created king footprints.
    for (UnitType blocker : new UnitType[] {UnitType.CAT, UnitType.RAT_KING}) {
      World world = validWorld();
      world.robots.add(robot(blocker, Team.B, new MapLocation(9, 9)));

      new BabyRat(1).takeTurn(world.rc);

      assertEquals(blocker.toString(), 0, world.upgrades);
    }
  }

  @Test
  public void rejectsCreationWhenFiveRatKingsAlreadyExist() throws Exception {
    // SPEC: The team may have no more than five rat kings.
    // VALUE: A sixth rat king is not created.
    // SCENARIO: Five allied rat kings already exist in the sensed robots.
    // PREVENTS: Exceeding the rat-king population cap.
    World world = validWorld();
    for (int i = 0; i < 5; i++) {
      world.robots.add(robot(UnitType.RAT_KING, Team.A, new MapLocation(20 + i, 20)));
    }

    new BabyRat(1).takeTurn(world.rc);

    assertEquals(0, world.upgrades);
  }

  @Test
  public void rejectsCreationAfterRound1200() throws Exception {
    // SPEC: No rat king may be created after round 1200.
    // VALUE: Round 1201 rejects the upgrade.
    // SCENARIO: All spatial and population requirements are otherwise valid.
    // PREVENTS: Late-game rat-king creation.
    World world = validWorld();
    world.round = 1201;

    new BabyRat(1).takeTurn(world.rc);

    assertEquals(0, world.upgrades);
  }

  @Test
  public void requiresFiftyCheeseForTheUpgrade() throws Exception {
    // SPEC: Upgrading costs 50 cheese.
    // VALUE: Forty-nine cheese cannot fund the upgrade.
    // SCENARIO: All spatial, population and round requirements are valid.
    // PREVENTS: Creating a rat king without paying its full cost.
    World world = validWorld();
    world.cheese = 49;

    new BabyRat(1).takeTurn(world.rc);

    assertEquals(0, world.upgrades);
  }

  private static World validWorld() {
    World world = new World();
    int[][] offsets = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}};
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
    final List<MapLocation> builtAt = new ArrayList<>();
    int cheese = 50;
    int round = 100;
    int upgrades;

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      String name = method.getName();
      switch (name) {
        case "getLocation": return CENTER;
        case "getTeam": return Team.A;
        case "getType": return UnitType.BABY_RAT;
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
        case "canBuildRat": return cheese >= 50 && builtAt.isEmpty();
        case "buildRat":
          upgrades++;
          cheese -= 50;
          builtAt.add((MapLocation) args[0]);
          return null;
        case "getDirection": return Direction.NORTH;
        case "getID": return 1;
        default: return defaultValue(method.getReturnType());
      }
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
      boolean passable = !walls.contains(location);
      return new MapInfo(location, passable, null, !passable, false, 0, TrapType.NONE, false);
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
