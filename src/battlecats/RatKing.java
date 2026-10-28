package battlecats;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.GameConstants;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.Team;
import battlecode.common.UnitType;
import java.util.ArrayList;
import java.util.List;

/** Produces worker-scouts while keeping the king away from visible threats. */
public class RatKing {
  // Experimental opening: spread the first four successful births around the king.
  // A blocked preferred tile falls back to the baseline legal-tile order immediately.
  private static final Direction[] OPENING_SPAWN_DIRECTIONS = {
    Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
  };

  // Retain the baseline evasion weights. Cat safety dominates enemy-rat distance.
  private static final long CAT_DISTANCE_WEIGHT = 1_000_000L;
  private static final long CAT_VISION_PENALTY = 1_000_000_000L;
  private static final long ENEMY_RAT_VISION_PENALTY = 1_000L;

  private final FoodBudget foodBudget = new FoodBudget();
  private int spawned;

  public void takeTurn(RobotController rc) throws GameActionException {
    foodBudget.observeStart(rc.getGlobalCheese());
    rc.setIndicatorString("KING spawned=" + spawned);
    // Once cooperation ends, defense gets the action before production can consume it.
    // The PvP helper never moves a king or initiates betrayal.
    RobotInfo[] observations = rc.senseNearbyRobots();
    int focusId = FocusSignal.publish(rc, observations);
    PvPTactics.engage(rc, observations, focusId);
    avoidEnemyThreats(rc);

    int ratCost = rc.getCurrentRatCost();
    if (foodBudget.canSpawn(rc.getGlobalCheese(), ratCost) && spawnWorker(rc)) {
      foodBudget.recordBirth(ratCost);
    }
    CatTactics.tryBite(rc, rc.senseNearbyRobots(-1, Team.NEUTRAL));
    KingSignal.publish(rc);
    foodBudget.finishTurn(rc.getGlobalCheese());
  }

  private boolean spawnWorker(RobotController rc) throws GameActionException {
    if (spawned < OPENING_SPAWN_DIRECTIONS.length) {
      Direction preferredDirection = OPENING_SPAWN_DIRECTIONS[spawned];
      // The king occupies a 3-by-3 footprint; two steps reaches its outer edge.
      MapLocation preferred = rc.getLocation().add(preferredDirection).add(preferredDirection);
      if (rc.canBuildRat(preferred)) {
        rc.buildRat(preferred);
        spawned++;
        return true;
      }
    }

    // Preserve the original spawn order after the opening and when its preference is blocked.
    MapLocation[] locations = rc.getAllLocationsWithinRadiusSquared(rc.getLocation(), 8);
    for (MapLocation location : locations) {
      if (rc.canBuildRat(location)) {
        rc.buildRat(location);
        spawned++;
        return true;
      }
    }
    return false;
  }

  private void avoidEnemyThreats(RobotController rc) throws GameActionException {
    Team enemy = rc.getTeam() == Team.A ? Team.B : Team.A;
    List<RobotInfo> allies = new ArrayList<>();
    List<RobotInfo> threats = new ArrayList<>();
    boolean catNearby = false;
    boolean hostileKingNearby = false;
    boolean cooperating = rc.isCooperation();
    // Cats belong to NEUTRAL, so filtering this query to the opposing team hides them.
    for (RobotInfo robot : rc.senseNearbyRobots()) {
      if (robot.getType() == UnitType.CAT) {
        threats.add(robot);
        catNearby = true;
      } else if (robot.getType() == UnitType.BABY_RAT
          || (!cooperating && robot.getType() == UnitType.RAT_KING)) {
        if (robot.getTeam() == enemy) {
          threats.add(robot);
          hostileKingNearby |= robot.getType() == UnitType.RAT_KING;
        } else if (robot.getTeam() == rc.getTeam() && robot.getType() == UnitType.BABY_RAT) {
          allies.add(robot);
        }
      }
    }
    if (threats.isEmpty()) {
      return;
    }

    Direction safest = null;
    long safestDirectionScore = Long.MIN_VALUE;
    MapLocation here = rc.getLocation();
    for (Direction direction : Direction.values()) {
      if (direction == Direction.CENTER || !rc.canMove(direction)) {
        continue;
      }
      MapLocation next = here.add(direction);
      // Cats and hostile kings take priority over staying near collectors. Preserve the
      // original delivery constraint when the only visible threats are enemy babies.
      if (!catNearby && !hostileKingNearby && !allies.isEmpty()
          && !withinDeliveryRange(next, allies)) {
        continue;
      }
      long directionScore = 0;
      for (RobotInfo threat : threats) {
        boolean cat = threat.getType() == UnitType.CAT;
        if (isInThreatVision(next, threat)) {
          directionScore -= cat ? CAT_VISION_PENALTY : ENEMY_RAT_VISION_PENALTY;
        }
        long distanceWeight = cat ? CAT_DISTANCE_WEIGHT : 1L;
        directionScore += distanceWeight * next.distanceSquaredTo(threat.getLocation());
      }
      if (directionScore > safestDirectionScore) {
        safestDirectionScore = directionScore;
        safest = direction;
      }
    }
    if (safest != null) {
      rc.move(safest);
    }
  }

  private boolean withinDeliveryRange(MapLocation location, List<RobotInfo> allies) {
    for (RobotInfo ally : allies) {
      if (location.distanceSquaredTo(ally.getLocation())
          <= GameConstants.CHEESE_TRANSFER_RADIUS_SQUARED) {
        return true;
      }
    }
    return false;
  }

  private boolean isInThreatVision(MapLocation location, RobotInfo threat) {
    UnitType type = threat.getType();
    return threat.getLocation().isWithinDistanceSquared(
        location, type.visionConeRadiusSquared, threat.getDirection(), type.visionConeAngle,
        type.usesBottomLeftLocationForDistance());
  }
}
