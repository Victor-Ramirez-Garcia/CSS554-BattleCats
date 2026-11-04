package battlecats;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.GameConstants;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.Team;
import battlecode.common.UnitType;

/** Local cat engagement and worker escape; never attacks another team. */
public final class CatTactics {
  // Experiments: use healthy, empty workers only, and pursue no farther than three tiles.
  // A terrain certificate prevents crushing, not the cat's 20-damage scratches.
  private static final int MIN_ENGAGING_HEALTH = 75;
  private static final int ENGAGEMENT_RADIUS_SQUARED = 9;
  // Experimental priorities: avoid immediate crushing, then leave the cat's vision.
  // Distance breaks ties locally; this does not predict the cat's next AI action.
  private static final int CRUSH_RISK_PENALTY = 10_000;
  private static final int VISION_RISK_PENALTY = 1_000;
  private static final int DISTANCE_TIEBREAK_LIMIT = 25;

  // Only the last certified worker tile is remembered. Permanent walls make this proof reusable
  // after a turn changes what the worker can sense; cat positions are never remembered here.
  private MapLocation certifiedLocation;

  public CatTactics() {}

  /**
   * Uses current-turn cat observations to bite, or take one crush-proof step toward a nearby cat.
   * Call after immediate food pickup/delivery. Returns true after handling an action, turn or move;
   * ordinary navigation should then wait. Scratch damage and unseen cats remain possible.
   */
  public boolean engage(RobotController rc, RobotInfo[] observations) throws GameActionException {
    if (rc.getType() != UnitType.BABY_RAT
        || rc.getRawCheese() != 0
        || FoodBudget.needsDelivery(rc.getGlobalCheese())
        || rc.getHealth() < MIN_ENGAGING_HEALTH
        || !rc.isActionReady()) {
      return false;
    }
    if (!rc.isCooperation()) {
      Team enemy = rc.getTeam().opponent();
      for (RobotInfo robot : observations) {
        if (robot.getTeam() == enemy
            && (robot.getType() == UnitType.BABY_RAT || robot.getType() == UnitType.RAT_KING)) {
          // A cat-proof nook gives no protection from hostile rats; keep defensive policy free.
          return false;
        }
      }
    }

    MapLocation here = rc.getLocation();
    RobotInfo cat = nearestCat(here, observations);
    if (cat == null || distanceToCat(here, cat) > ENGAGEMENT_RADIUS_SQUARED) {
      return false;
    }

    int currentDistance = distanceToCat(here, cat);
    if (currentDistance <= GameConstants.ATTACK_DISTANCE_SQUARED) {
      boolean protectedHere = isCertified(rc, here);
      if (faceAndBite(rc, cat)) {
        if (protectedHere) {
          certifiedLocation = here;
        } else {
          retreat(rc, observations, null);
        }
        return true;
      }
    }

    Direction best = Direction.CENTER;
    int bestDistance = currentDistance;
    for (Direction direction : Direction.values()) {
      if (direction == Direction.CENTER || !rc.canMove(direction)) {
        continue;
      }
      MapLocation next = here.add(direction);
      int distance = distanceToCat(next, cat);
      if (distance < bestDistance && isCertified(rc, next)) {
        best = direction;
        bestDistance = distance;
      }
    }
    if (best == Direction.CENTER) {
      return false;
    }

    // Choose and prove the destination before changing facing or committing movement.
    faceCat(rc, cat);
    rc.move(best);
    certifiedLocation = rc.getLocation();
    rc.setIndicatorString("APPROACH_CAT id=" + cat.getID());
    faceAndBite(rc, cat);
    return true;
  }

  private boolean isCertified(RobotController rc, MapLocation location)
      throws GameActionException {
    if (location.equals(certifiedLocation)) {
      return true;
    }
    // Engine rule: a cat has a 2x2 footprint anchored at its bottom-left tile. Normal movement
    // and pounce can crush babies. Block all four landings containing this tile, rather than
    // assuming the cat cannot jump across a wall. Dirt and occupied tiles can change, so neither
    // is evidence. A cat cooldown is not exposed in RobotInfo and cannot be assumed.
    for (int anchorX = location.x - 1; anchorX <= location.x; anchorX++) {
      for (int anchorY = location.y - 1; anchorY <= location.y; anchorY++) {
        boolean blocked = false;
        for (int dx = 0; dx < 2 && !blocked; dx++) {
          for (int dy = 0; dy < 2; dy++) {
            MapLocation part = new MapLocation(anchorX + dx, anchorY + dy);
            if (!rc.onTheMap(part)
                || (rc.canSenseLocation(part) && rc.senseMapInfo(part).isWall())) {
              blocked = true;
              break;
            }
          }
        }
        if (!blocked) {
          return false;
        }
      }
    }
    return true;
  }

  private static RobotInfo nearestCat(MapLocation here, RobotInfo[] observations) {
    RobotInfo nearest = null;
    int nearestDistance = Integer.MAX_VALUE;
    for (RobotInfo robot : observations) {
      if (robot.getType() != UnitType.CAT) {
        continue;
      }
      int distance = distanceToCat(here, robot);
      if (nearest == null || distance < nearestDistance
          || (distance == nearestDistance && robot.getID() < nearest.getID())) {
        nearest = robot;
        nearestDistance = distance;
      }
    }
    return nearest;
  }

  private static int distanceToCat(MapLocation here, RobotInfo cat) {
    MapLocation anchor = cat.getLocation();
    int dx = here.x - Math.max(anchor.x, Math.min(here.x, anchor.x + 1));
    int dy = here.y - Math.max(anchor.y, Math.min(here.y, anchor.y + 1));
    return dx * dx + dy * dy;
  }

  private static MapLocation nearestCatPart(MapLocation here, RobotInfo cat) {
    MapLocation anchor = cat.getLocation();
    return new MapLocation(
        Math.max(anchor.x, Math.min(here.x, anchor.x + 1)),
        Math.max(anchor.y, Math.min(here.y, anchor.y + 1)));
  }

  private static boolean faceCat(RobotController rc, RobotInfo cat) throws GameActionException {
    Direction direction = rc.getLocation().directionTo(nearestCatPart(rc.getLocation(), cat));
    if (direction != Direction.CENTER && direction != rc.getDirection() && rc.canTurn(direction)) {
      rc.turn(direction);
      return true;
    }
    return false;
  }

  private static boolean faceAndBite(RobotController rc, RobotInfo cat)
      throws GameActionException {
    if (tryBiteCat(rc, cat)) {
      return true;
    }
    boolean turned = faceCat(rc, cat);
    return tryBiteCat(rc, cat) || turned;
  }

  /** Uses the zero-cheese bite only. The engine checks range, facing and action cooldown. */
  public static boolean tryBite(RobotController rc, RobotInfo[] cats)
      throws GameActionException {
    for (RobotInfo cat : cats) {
      if (cat.getType() != UnitType.CAT) {
        continue;
      }
      if (tryBiteCat(rc, cat)) {
        return true;
      }
    }
    return false;
  }

  private static boolean tryBiteCat(RobotController rc, RobotInfo cat) throws GameActionException {
    // A cat occupies four tiles. Its nearest edge can be legal when its anchor is not.
    for (MapLocation part : UnitType.CAT.getAllTypeLocations(cat.getLocation())) {
      if (rc.canAttack(part)) {
        rc.attack(part);
        rc.setIndicatorString("BITE_CAT id=" + cat.getID());
        return true;
      }
    }
    return false;
  }

  /**
   * Handles movement when a worker is threatened, including when every escape is blocked.
   * Returns false only when ordinary navigation may safely choose its next move.
   */
  public static boolean retreat(RobotController rc, RobotInfo[] cats, MapLocation objective)
      throws GameActionException {
    MapLocation here = rc.getLocation();
    int currentRisk = risk(here, cats);
    if (currentRisk < 0) {
      return false;
    }
    Direction best = Direction.CENTER;
    int bestRisk = currentRisk;
    int bestProgress = objective == null ? 0 : here.distanceSquaredTo(objective);
    for (Direction direction : Direction.values()) {
      if (direction == Direction.CENTER || !rc.canMove(direction)) {
        continue;
      }
      MapLocation next = here.add(direction);
      int nextRisk = risk(next, cats);
      int progress = objective == null ? 0 : next.distanceSquaredTo(objective);
      if (nextRisk < bestRisk || (nextRisk == bestRisk && progress < bestProgress)) {
        best = direction;
        bestRisk = nextRisk;
        bestProgress = progress;
      }
    }
    if (best != Direction.CENTER) {
      // Keep the current facing: moving does not require turning, and a cat can remain biteable.
      rc.move(best);
      rc.setIndicatorString("EVADE_CAT");
    }
    return true;
  }

  private static int risk(MapLocation location, RobotInfo[] cats) {
    int worst = -DISTANCE_TIEBREAK_LIMIT;
    for (RobotInfo cat : cats) {
      if (cat.getType() != UnitType.CAT) {
        continue;
      }
      int distance = Integer.MAX_VALUE;
      for (MapLocation part : UnitType.CAT.getAllTypeLocations(cat.getLocation())) {
        distance = Math.min(distance, location.distanceSquaredTo(part));
      }
      int score = -Math.min(distance, DISTANCE_TIEBREAK_LIMIT);
      // A one-step translation of a 2x2 cat can crush an adjacent baby.
      if (distance <= 2) {
        score += CRUSH_RISK_PENALTY;
      }
      if (cat.getLocation().isWithinDistanceSquared(
          location, UnitType.CAT.visionConeRadiusSquared, cat.getDirection(),
          UnitType.CAT.visionConeAngle, UnitType.CAT.usesBottomLeftLocationForDistance())) {
        score += VISION_RISK_PENALTY;
      }
      worst = Math.max(worst, score);
    }
    return worst;
  }
}
