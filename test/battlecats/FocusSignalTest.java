package battlecats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.GameActionExceptionType;
import battlecode.common.GameConstants;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.Team;
import battlecode.common.UnitType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.Test;

/** Exercises the actual shared-word limits and king-only publication boundary. */
public class FocusSignalTest {
  @Test
  public void roundTripsPositiveIdsAcrossWordAndGameRoundBoundaries() throws Exception {
    int[] ids = {1, 1023, 1024, 1 << 20, 1 << 30, Integer.MAX_VALUE};
    int[] rounds = {0, 1, 1023, 1024, 1999, 2000};
    for (int id : ids) {
      for (int round : rounds) {
        World world = new World();
        world.round = round;
        assertEquals(id, FocusSignal.publish(world.rc, new RobotInfo[] {enemy(id, 80)}));
        world.type = UnitType.BABY_RAT;

        assertEquals("ID=" + id + " round=" + round, id, FocusSignal.read(world.rc));
        for (int slot = 0; slot < 3; slot++) {
          assertEquals("Home signal slots must be independent", 0, world.shared[slot]);
        }
      }
    }
  }

  @Test
  public void kingSelectsLowestHealthThenIdFromHostileRats() throws Exception {
    World world = new World();
    RobotInfo[] observations = {
      enemy(301, 100), enemy(302, 50), enemy(303, 50),
      robot(1, Team.A, UnitType.BABY_RAT, 1),
      robot(2, Team.NEUTRAL, UnitType.CAT, 1)
    };

    assertEquals(302, FocusSignal.publish(world.rc, observations));
    assertEquals(302, FocusSignal.read(world.rc));
    assertEquals(4, FocusSignal.publish(world.rc,
        new RobotInfo[] {robot(4, Team.B, UnitType.RAT_KING, 30)}));
  }

  @Test
  public void noEnemyOrCooperationClearsPreviousDesignation() throws Exception {
    World world = new World();
    FocusSignal.publish(world.rc, new RobotInfo[] {enemy(301, 100)});
    assertEquals(-1, FocusSignal.publish(world.rc, new RobotInfo[0]));
    assertEquals(-1, FocusSignal.read(world.rc));
    FocusSignal.publish(world.rc, new RobotInfo[] {enemy(301, 100)});
    world.cooperation = true;
    assertEquals(-1, FocusSignal.read(world.rc));
    assertEquals(-1, FocusSignal.publish(world.rc, new RobotInfo[] {enemy(301, 100)}));
    assertEquals(0, world.shared[7]);
  }

  @Test
  public void rejectsUnpublishedStaleFutureAndMalformedSignals() throws Exception {
    World world = new World();
    assertEquals(-1, FocusSignal.read(world.rc));
    FocusSignal.publish(world.rc, new RobotInfo[] {enemy(301, 100)});
    for (int age = 0; age <= 4; age++) {
      world.round = 100 + age;
      assertEquals("Age=" + age, age <= 3 ? 301 : -1, FocusSignal.read(world.rc));
    }
    world.round = 99;
    assertEquals(-1, FocusSignal.read(world.rc));
    world.round = 100;
    world.shared[6] = 2;
    assertEquals(-1, FocusSignal.read(world.rc));
    world.shared[6] = 0;
    world.shared[3] = 0;
    assertEquals(-1, FocusSignal.read(world.rc));
  }

  @Test
  public void oddRoundTimestampExpiresUsingDecodedBucket() throws Exception {
    World world = new World();
    world.round = 101;
    FocusSignal.publish(world.rc, new RobotInfo[] {enemy(301, 100)});
    world.round = 103;
    assertEquals(301, FocusSignal.read(world.rc));
    world.round = 104;
    assertEquals(-1, FocusSignal.read(world.rc));
  }

  @Test
  public void failedPublicationCannotExposePartialId() throws Exception {
    for (int failAfter = 1; failAfter <= 5; failAfter++) {
      World world = new World();
      FocusSignal.publish(world.rc, new RobotInfo[] {enemy(301, 100)});
      world.writes = 0;
      world.failAfter = failAfter;
      try {
        FocusSignal.publish(world.rc, new RobotInfo[] {enemy(Integer.MAX_VALUE, 100)});
        fail("Expected injected shared-memory failure");
      } catch (GameActionException expected) {
        assertEquals(GameActionExceptionType.CANT_DO_THAT, expected.getType());
      }
      assertEquals(-1, FocusSignal.read(world.rc));
    }
  }

  private static RobotInfo enemy(int id, int health) {
    return robot(id, Team.B, UnitType.BABY_RAT, health);
  }

  private static RobotInfo robot(int id, Team team, UnitType type, int health) {
    return new RobotInfo(id, team, type, health, new MapLocation(12, 10), Direction.WEST,
        0, 0, null);
  }

  private static final class World implements InvocationHandler {
    final int[] shared = new int[GameConstants.SHARED_ARRAY_SIZE];
    final RobotController rc = (RobotController) Proxy.newProxyInstance(
        RobotController.class.getClassLoader(), new Class<?>[] {RobotController.class}, this);
    UnitType type = UnitType.RAT_KING;
    int round = 100;
    boolean cooperation;
    int writes;
    int failAfter = Integer.MAX_VALUE;

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws GameActionException {
      switch (method.getName()) {
        case "getTeam":
          return Team.A;
        case "isCooperation":
          return cooperation;
        case "getRoundNum":
          return round;
        case "readSharedArray":
          return shared[(Integer) args[0]];
        case "writeSharedArray":
          assertEquals("Only kings may write", UnitType.RAT_KING, type);
          if (writes++ == failAfter) {
            throw new GameActionException(GameActionExceptionType.CANT_DO_THAT, "Injected failure");
          }
          int value = (Integer) args[1];
          assertTrue(value >= 0 && value <= GameConstants.COMM_ARRAY_MAX_VALUE);
          shared[(Integer) args[0]] = value;
          return null;
        default:
          throw new AssertionError("Unsupported operation: " + method.getName());
      }
    }
  }
}
