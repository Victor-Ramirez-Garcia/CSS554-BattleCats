package battlecats;

import battlecode.common.*;
import java.util.Random;

/** Minimal runnable team bot. Sprint 1 spawning and cheese behavior come next. */
public class RobotPlayer {
    private static final Direction[] DIRECTIONS = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
    };

    public static void run(RobotController rc) throws GameActionException {
        Random random = new Random(rc.getID());
        while (true) {
            try {
                rc.setIndicatorString("BattleCats starter");
                if (rc.canMoveForward()) {
                    rc.moveForward();
                } else {
                    Direction direction = DIRECTIONS[random.nextInt(DIRECTIONS.length)];
                    if (rc.canTurn(direction)) {
                        rc.turn(direction);
                    }
                }
            } catch (GameActionException e) {
                // Keep the robot alive and leave a useful diagnostic in verbose matches.
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }
}
