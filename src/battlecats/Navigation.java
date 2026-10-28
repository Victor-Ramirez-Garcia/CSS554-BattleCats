package battlecats;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import java.util.Random;

/** Bounded wall-following navigation; uses legal moves rather than a revealed map. */
public class Navigation {
  // Existing exploration policy, not game rules: give a target 30 rounds before trying elsewhere.
  private static final int WAYPOINT_TIMEOUT_ROUNDS = 30;

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

  /** Explores only when the collector has no delivery or visible cheese to pursue. */
  public void explore(RobotController rc, MapLocation home) throws GameActionException {
    if (waypoint == null) {
      waypoint = openingWaypoint(rc, home);
      waypointRound = rc.getRoundNum();
    } else if (rc.getLocation().distanceSquaredTo(waypoint) <= 2
        || rc.getRoundNum() - waypointRound >= WAYPOINT_TIMEOUT_ROUNDS) {
      waypoint = randomWaypoint(rc);
      waypointRound = rc.getRoundNum();
    }
    moveToward(rc, waypoint);
    // Look around even while waiting for a movement cooldown.
    if (rc.getRoundNum() % 4 == 0 && rc.canTurn()) {
      rc.turn(rc.getDirection().rotateRight().rotateRight());
    }
  }

  private MapLocation openingWaypoint(RobotController rc, MapLocation home) {
    MapLocation here = rc.getLocation();
    if (home == null || home.equals(here)) {
      return randomWaypoint(rc);
    }
    // Experiment: let spawn position fan workers outward before resuming random exploration.
    // Collecting still takes priority, and the normal timeout bounds any trip toward the edge.
    Direction outward = home.directionTo(here);
    int x = outward.dx < 0 ? 0 : outward.dx > 0 ? rc.getMapWidth() - 1 : here.x;
    int y = outward.dy < 0 ? 0 : outward.dy > 0 ? rc.getMapHeight() - 1 : here.y;
    MapLocation target = new MapLocation(x, y);
    return here.distanceSquaredTo(target) <= 2 ? randomWaypoint(rc) : target;
  }

  private MapLocation randomWaypoint(RobotController rc) {
    return new MapLocation(random.nextInt(rc.getMapWidth()), random.nextInt(rc.getMapHeight()));
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
        wallDirection =
            clockwise
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
