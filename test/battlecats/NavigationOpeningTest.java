package battlecats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import battlecode.common.Direction;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.Test;

/** Exercises worker exploration through legal moves rather than exposing navigation state. */
public class NavigationOpeningTest {
  @Test
  public void workersOnFourSidesOfHomeStartExploringDifferentSectors() throws Exception {
    MapLocation home = at(12, 12);
    Direction[] sectors = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    for (Direction sector : sectors) {
      MapLocation start = home.add(sector).add(sector).add(sector);
      World world = new World(start, 24, 24);
      Navigation navigation = new Navigation(2);

      for (int turn = 0; turn < 3; turn++) {
        MapLocation expected = world.location.add(sector);
        navigation.explore(world.rc, home);
        assertEquals("Worker should continue outward in " + sector, expected, world.location);
        world.nextRound();
      }
    }
  }

  @Test
  public void collectingBeforeExplorationDoesNotConsumeTheOpening() throws Exception {
    MapLocation home = at(12, 12);
    World world = new World(at(15, 12), 24, 24);
    Navigation navigation = new Navigation(2);
    navigation.moveToward(world.rc, at(15, 14));
    world.nextRound();

    navigation.explore(world.rc, home);

    // The engine rounds the shallow angle from home to the eastward sector.
    assertEquals(at(16, 13), world.location);
  }

  @Test
  public void openingEndsWhenWorkerReachesTheEdgeSector() throws Exception {
    MapLocation home = at(17, 10);
    World world = new World(at(20, 10), 24, 24);
    Navigation navigation = new Navigation(2);
    navigation.explore(world.rc, home);
    world.nextRound();
    navigation.explore(world.rc, home);
    assertEquals(at(22, 10), world.location);
    world.nextRound();

    navigation.explore(world.rc, home);

    // ID 2's random target is (4, 12); the engine rounds that shallow angle to west.
    assertEquals(
        "Arrival should release the worker to explore elsewhere", at(21, 10), world.location);
  }

  @Test
  public void openingTimesOutAfterThirtyRoundsEvenWhenMovementWasUnavailable() throws Exception {
    MapLocation home = at(7, 10);
    World world = new World(at(10, 10), 24, 24);
    Navigation navigation = new Navigation(2);
    world.movementReady = false;
    navigation.explore(world.rc, home);
    assertEquals(at(10, 10), world.location);

    world.round = 30;
    world.movementReady = true;
    navigation.explore(world.rc, home);
    assertEquals("Before the deadline the worker still heads east", at(11, 10), world.location);
    world.nextRound();

    navigation.explore(world.rc, home);

    assertEquals(
        "The deadline should release the worker to a random target", at(10, 10), world.location);
  }

  @Test
  public void unknownOrCoincidentHomeUsesSeededExploration() throws Exception {
    MapLocation[] homes = {null, at(10, 10)};
    for (MapLocation home : homes) {
      World world = new World(at(10, 10), 24, 24);

      new Navigation(2).explore(world.rc, home);

      assertEquals(
          "A missing outward direction must still permit exploration", at(9, 10), world.location);
    }
  }

  @Test
  public void boundaryAndNarrowMapsKeepEveryMoveOnTheMap() throws Exception {
    World[] worlds = {
      new World(at(0, 0), 24, 14),
      new World(at(23, 13), 24, 14),
      new World(at(0, 12), 1, 24),
      new World(at(12, 0), 24, 1),
      new World(at(0, 0), 1, 1)
    };
    MapLocation[] homes = {at(1, 1), at(22, 12), at(0, 10), at(10, 0), at(0, 0)};
    for (int scenario = 0; scenario < worlds.length; scenario++) {
      World world = worlds[scenario];
      Navigation navigation = new Navigation(2);
      for (int turn = 0; turn < 40; turn++) {
        navigation.explore(world.rc, homes[scenario]);
        assertTrue(world.onMap(world.location));
        world.nextRound();
      }
    }
  }

  private static MapLocation at(int x, int y) {
    return new MapLocation(x, y);
  }

  /** Open terrain with independent movement and turning cooldowns; illegal actions fail tests. */
  private static final class World implements InvocationHandler {
    final RobotController rc;
    final int width;
    final int height;
    MapLocation location;
    Direction direction = Direction.EAST;
    int round = 1;
    boolean movementReady = true;
    boolean turningReady = true;

    World(MapLocation location, int width, int height) {
      this.location = location;
      this.width = width;
      this.height = height;
      rc =
          (RobotController)
              Proxy.newProxyInstance(
                  RobotController.class.getClassLoader(),
                  new Class<?>[] {RobotController.class},
                  this);
    }

    void nextRound() {
      round++;
      movementReady = true;
      turningReady = true;
    }

    boolean onMap(MapLocation target) {
      return target.x >= 0 && target.y >= 0 && target.x < width && target.y < height;
    }

    private boolean canMove(Direction step) {
      return movementReady && step != Direction.CENTER && onMap(location.add(step));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      switch (method.getName()) {
        case "getLocation":
          return location;
        case "getDirection":
          return direction;
        case "getMapWidth":
          return width;
        case "getMapHeight":
          return height;
        case "getRoundNum":
          return round;
        case "isMovementReady":
          return movementReady;
        case "canMove":
          return canMove((Direction) args[0]);
        case "move":
          Direction step = (Direction) args[0];
          assertTrue("Move must be legal and off cooldown", canMove(step));
          location = location.add(step);
          movementReady = false;
          return null;
        case "canTurn":
          return turningReady && (args == null || args.length == 0 || args[0] != direction);
        case "turn":
          assertTrue("Turn must be off cooldown", turningReady);
          assertFalse("Turning must change heading", direction == args[0]);
          direction = (Direction) args[0];
          turningReady = false;
          return null;
        default:
          throw new AssertionError("Unsupported controller operation: " + method.getName());
      }
    }
  }
}
