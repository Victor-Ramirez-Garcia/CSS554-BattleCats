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
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Acceptance coverage for cooperation-mode rat king attacks. */
public class CooperationRatKingAttackAcceptanceTest {
    private static final MapLocation KING = new MapLocation(10, 10);

    /*
     * SPEC: A cooperation-mode rat king attacks a cat within attacking range.
     * VALUE: The king defends itself against a sensed cat.
     * SCENARIO: A neutral cat is adjacent to the king.
     * PREVENTS: Leaving an attackable cat unharmed.
     */
    @Test
    public void attacksCatWithinRange() throws Exception {
        MapLocation cat = new MapLocation(11, 10);
        World world = world(robot(cat, Team.NEUTRAL));
        new RatKing().takeTurn(world.rc);
        assertEquals(cat, world.attackedLocation);
    }

    /*
     * SPEC: A cooperation-mode rat king does not attack a cat outside range.
     * VALUE: The king only attempts legal attacks.
     * SCENARIO: A neutral cat is sensed beyond the attacking range.
     * PREVENTS: Invalid or wasteful attack attempts.
     */
    @Test
    public void doesNotAttackCatOutsideRange() throws Exception {
        MapLocation cat = new MapLocation(15, 10);
        World world = world(robot(cat, Team.NEUTRAL));
        new RatKing().takeTurn(world.rc);
        assertNull(world.attackedLocation);
    }

    private static RobotInfo robot(MapLocation location, Team team) {
        return new RobotInfo(location.x * 100 + location.y, team, UnitType.CAT, 1,
                location, Direction.EAST, 0, 0, null);
    }

    private static World world(RobotInfo... robots) {
        World world = new World();
        Collections.addAll(world.robots, robots);
        return world;
    }

    private static final class World implements InvocationHandler {
        final RobotController rc;
        final List<RobotInfo> robots = new ArrayList<>();
        MapLocation location = KING;
        MapLocation attackedLocation;

        World() {
            rc = (RobotController) Proxy.newProxyInstance(
                    RobotController.class.getClassLoader(),
                    new Class<?>[] {RobotController.class}, this);
        }

        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getLocation": return location;
                case "getTeam": return Team.A;
                case "getType": return UnitType.RAT_KING;
                case "getAllCheese": case "getCurrentRatCost": return 0;
                case "senseNearbyRobots":
                    return robots.stream().filter(robot -> location.distanceSquaredTo(robot.getLocation()) <= 100)
                            .toArray(RobotInfo[]::new);
                case "canMove": return true;
                case "move": location = location.add((Direction) args[0]); return null;
                case "canAttack": return location.distanceSquaredTo((MapLocation) args[0]) <= 2;
                case "attack": attackedLocation = (MapLocation) args[0]; return null;
                case "setIndicatorString": case "writeSharedArray": return null;
                default: return method.getReturnType() == boolean.class ? false
                        : method.getReturnType() == int.class ? 0 : null;
            }
        }
    }
}
