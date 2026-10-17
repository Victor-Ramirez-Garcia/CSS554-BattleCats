package battlecats;

import battlecode.common.*;
import java.util.Random;

/** Bounded wall-following navigation; uses legal moves rather than a revealed map. */
public class Navigation {
    private final Random random;
    private final boolean clockwise;
    private MapLocation destination;
    private MapLocation waypoint;
    private int waypointRound;
    private boolean followingWall;
    private Direction wallDirection;
    private int hitDistance;
    private int wallSteps;

    public Navigation(int robotId) {
        random = new Random(robotId);
        clockwise = (robotId & 1) == 0;
    }

    public void explore(RobotController rc) throws GameActionException {
        if (waypoint == null || rc.getLocation().distanceSquaredTo(waypoint) <= 2
                || rc.getRoundNum() - waypointRound >= 30) {
            waypoint = new MapLocation(random.nextInt(rc.getMapWidth()), random.nextInt(rc.getMapHeight()));
            waypointRound = rc.getRoundNum();
        }
        moveToward(rc, waypoint);
        // Look around even while waiting for a movement cooldown.
        if (rc.getRoundNum() % 4 == 0 && rc.canTurn()) {
            rc.turn(rc.getDirection().rotateRight().rotateRight());
        }
    }

    public void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        MapLocation here = rc.getLocation();
        if (here.equals(target) || !rc.isMovementReady()) {
            return;
        }
        if (!target.equals(destination)) {
            destination = target;
            followingWall = false;
            wallSteps = 0;
        }
        // Moving robots are temporary obstacles. Periodically retry the direct
        // route instead of circling forever around a blockage that has gone away.
        if (followingWall && wallSteps >= 8) {
            followingWall = false;
            wallSteps = 0;
        }
        Direction direct = here.directionTo(target);
        int distance = here.distanceSquaredTo(target);
        if ((!followingWall || distance < hitDistance) && rc.canMove(direct)) {
            followingWall = false;
            step(rc, direct);
            return;
        }
        if (!followingWall) {
            followingWall = true;
            hitDistance = distance;
            wallDirection = direct;
            wallSteps = 0;
        }
        Direction candidate = wallDirection;
        for (int i = 0; i < 8; i++) {
            if (rc.canMove(candidate)) {
                step(rc, candidate);
                wallSteps++;
                wallDirection = clockwise
                        ? candidate.rotateLeft().rotateLeft()
                        : candidate.rotateRight().rotateRight();
                return;
            }
            candidate = clockwise ? candidate.rotateRight() : candidate.rotateLeft();
        }
    }

    private void step(RobotController rc, Direction direction) throws GameActionException {
        if (rc.canTurn(direction)) {
            rc.turn(direction);
        }
        if (rc.canMove(direction)) {
            rc.move(direction);
        }
    }
}
