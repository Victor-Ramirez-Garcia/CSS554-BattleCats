package battlecats;

import battlecode.common.Direction;
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
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Acceptance coverage for backstabbing-mode rat king attacks. */
public class BackstabbingRatKingAttackAcceptanceTest {
    private static final MapLocation KING = new MapLocation(10, 10);

    /*
     * SPEC: Attack the closest sensed enemy within attacking distance.
     * VALUE: The king defends itself against the nearest threat.
     * SCENARIO: An enemy rat is closer than an enemy cat.
     * PREVENTS: Attacking a more distant threat first.
     */
    @Test
    public void attacksClosestEnemy() throws Exception {
        RobotInfo rat = robot(1, UnitType.BABY_RAT, new MapLocation(11, 10), Team.B);
        RobotInfo cat = robot(2, UnitType.CAT, new MapLocation(12, 10), Team.NEUTRAL);
        World world = world(rat, cat);

        new RatKing().takeTurn(world.rc);

        assertEquals(rat.getLocation(), world.attackedLocation);
    }

    /*
     * SPEC: Prefer a cat when a cat and enemy rat are equally close.
     * VALUE: Cat threats receive priority at the closest distance.
     * SCENARIO: An enemy rat and neutral cat are both adjacent.
     * PREVENTS: Choosing an enemy rat over an equally close cat.
     */
    @Test
    public void prefersCatAtEqualDistance() throws Exception {
        RobotInfo rat = robot(1, UnitType.BABY_RAT, new MapLocation(11, 10), Team.B);
        RobotInfo cat = robot(2, UnitType.CAT, new MapLocation(10, 11), Team.NEUTRAL);
        World world = world(rat, cat);

        new RatKing().takeTurn(world.rc);

        assertEquals(cat.getLocation(), world.attackedLocation);
    }

    /*
     * SPEC: Prefer the enemy rat that arrived first when rats tie on distance.
     * VALUE: Equal-distance rat targets have deterministic arrival-order priority.
     * SCENARIO: Two enemy rats are equally close and the lower ID arrived first.
     * PREVENTS: Unstable target selection between equal rat threats.
     */
    @Test
    public void prefersFirstArrivedRatAtEqualDistance() throws Exception {
        RobotInfo first = robot(1, UnitType.BABY_RAT, new MapLocation(11, 10), Team.B);
        RobotInfo second = robot(2, UnitType.BABY_RAT, new MapLocation(10, 11), Team.B);
        World world = world(first, second);

        new RatKing().takeTurn(world.rc);

        assertEquals(first.getLocation(), world.attackedLocation);
    }

    private static RobotInfo robot(int id, UnitType type, MapLocation location, Team team) {
        return new RobotInfo(id, team, type, 1, location, Direction.EAST, 0, 0, null);
    }

    private static World world(RobotInfo... robots) {
        World world = new World();
        world.robots.addAll(Arrays.asList(robots));
        return world;
    }

    private static final class World implements InvocationHandler {
        final RobotController rc;
        final List<RobotInfo> robots = new ArrayList<>();
        MapLocation attackedLocation;

        World() {
            rc = (RobotController) Proxy.newProxyInstance(
                    RobotController.class.getClassLoader(),
                    new Class<?>[] {RobotController.class}, this);
        }

        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getLocation": return KING;
                case "getTeam": return Team.A;
                case "getType": return UnitType.RAT_KING;
                case "getAllCheese": case "getCurrentRatCost": return 0;
                case "senseNearbyRobots": return robots.toArray(new RobotInfo[0]);
                case "canMove": return true;
                case "move": return null;
                case "canAttack": return KING.distanceSquaredTo((MapLocation) args[0]) <= 2;
                case "attack": attackedLocation = (MapLocation) args[0]; return null;
                case "setIndicatorString": case "writeSharedArray": return null;
                default: return method.getReturnType() == boolean.class ? false
                        : method.getReturnType() == int.class ? 0 : null;
            }
        }
    }
}
