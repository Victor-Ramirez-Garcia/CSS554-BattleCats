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

public class RatKing {

  //PRIVATE VARS
  private final FoodBudget foodBudget = new FoodBudget();
  private int spawned;
  // Set once this king publishes; a second king publishes only after the owner's signal lapses.
  private boolean ownsSignal;
  // Late-game rally centre chosen by this king, reused while it stays passable.
  private MapLocation rally;

  //PUBLIC METHODS
  /** Runs the king's food accounting, combat, evasion, spawning, and signaling for one turn. */
  public void takeTurn(RobotController rc) throws GameActionException {
    foodBudget.observeStart(rc.getGlobalCheese());
    rc.setIndicatorString("KING spawned=" + spawned);
    CatTactics.tryBite(rc, rc.senseNearbyRobots(-1, Team.NEUTRAL));
    avoidEnemyThreats(rc);
    spawnRat(rc);
    if (ownsSignal || !KingSignal.homeIsFresh(rc)) {
      ownsSignal = true;
      KingSignal.publish(rc);
      KingSignal.publishPromotionFunding(rc, foodBudget.canFeedAnotherKing());
      if (rc.getRoundNum() >= BabyRat.LATE_PROMOTION_ROUND) {
        KingSignal.publishRally(rc, chooseRally(rc));
      }
    }
    foodBudget.finishTurn(rc.getGlobalCheese());
  }

  //PRIVATE METHODS
  /**
   * Picks the centre of a fully passable 3x3 three tiles from this king, so its square never
   * overlaps the king's own 3x3 (an engine refusal). Keeps a valid choice to avoid re-sensing.
   */
  private MapLocation chooseRally(RobotController rc) throws GameActionException {
    if (rally != null && passableSquare(rc, rally)) {
      return rally;
    }
    MapLocation here = rc.getLocation();
    rally = null;
    for (Direction direction : Direction.allDirections()) {
      if (direction == Direction.CENTER) {
        continue;
      }
      MapLocation centre = here.translate(3 * direction.dx, 3 * direction.dy);
      if (passableSquare(rc, centre)) {
        rally = centre;
        break;
      }
    }
    return rally;
  }

  private static boolean passableSquare(RobotController rc, MapLocation centre)
      throws GameActionException {
    for (int dx = -1; dx <= 1; dx++) {
      for (int dy = -1; dy <= 1; dy++) {
        MapLocation tile = centre.translate(dx, dy);
        if (!rc.onTheMap(tile) || !rc.canSenseLocation(tile)
            || !rc.senseMapInfo(tile).isPassable()) {
          return false;
        }
      }
    }
    return true;
  }

  /** Spawns at most one Baby Rat when the food budget permits an adjacent empty location. */
  private void spawnRat(RobotController rc) throws GameActionException {
    int globalCheese = rc.getGlobalCheese();
    int ratCost = rc.getCurrentRatCost();
    boolean canSpawn = foodBudget.canSpawn(globalCheese, ratCost);
    if (!canSpawn) return;
    MapLocation[] locations = rc.getAllLocationsWithinRadiusSquared(rc.getLocation(), 8);
    for (MapLocation location : locations) {
        if (rc.canBuildRat(location)) {
            rc.buildRat(location);
            foodBudget.recordBirth(ratCost);
            spawned++;
            break;
        }
    }
  }

  /** Moves to the safest legal adjacent location while preserving delivery range to allies. */
  private void avoidEnemyThreats(RobotController rc) throws GameActionException {
    List<RobotInfo> threats = senseThreats(rc);
    if (threats.isEmpty()) return;

    List<RobotInfo> allies = senseTeamAllies(rc);
    MapLocation safestLocation = null;
    long safestLocationScore = Long.MIN_VALUE;
    MapLocation here = rc.getLocation();

    MapLocation[] movableLocations = rc.getAllLocationsWithinRadiusSquared(here, 2);
    for (MapLocation location : movableLocations) {
        Direction direction = here.directionTo(location);
        if (direction == Direction.CENTER || !rc.canMove(direction)) continue;
        if (!allies.isEmpty() && !teamAlliesWithinDeliveryRange(location, allies)) continue;
        long locationCandidateScore = scoreLocationCandidateBySafety(location, threats);
        if (locationCandidateScore > safestLocationScore) {
            safestLocationScore = locationCandidateScore;
            safestLocation = location;
        }
    }
    if (safestLocation != null) {
        rc.move(here.directionTo(safestLocation));
    }
  }

  /** Returns nearby cats and enemy Baby Rats that the king should treat as threats. */
  private List<RobotInfo> senseThreats(RobotController rc) throws GameActionException {
    Team enemy = rc.getTeam() == Team.A ? Team.B : Team.A;
    RobotInfo[] sensed = rc.senseNearbyRobots();
    List<RobotInfo> threats = new ArrayList<>();
    for (RobotInfo robot : sensed) {
      if (robot.getType() == UnitType.CAT
              || (robot.getTeam() == enemy && robot.getType() == UnitType.BABY_RAT)) {
        threats.add(robot);
      }
    }
    return threats;
  }

  /** Returns nearby allied Baby Rats that may need to receive delivered cheese. */
  private List<RobotInfo> senseTeamAllies(RobotController rc) throws GameActionException {
    List<RobotInfo> allies = new ArrayList<>();
    for (RobotInfo robot : rc.senseNearbyRobots(-1, rc.getTeam())) {
        if (robot.getType() == UnitType.BABY_RAT) allies.add(robot);
    }
    return allies;
  }

  /** Checks whether a candidate location remains within cheese-delivery range of an ally. */
  private boolean teamAlliesWithinDeliveryRange(MapLocation location, List<RobotInfo> allies) {
      for (RobotInfo ally : allies) {
          if (location.distanceSquaredTo(ally.getLocation())
                  <= GameConstants.CHEESE_TRANSFER_RADIUS_SQUARED) return true;
      }
      return false;
  }

  /** Scores a candidate by rewarding distance from threats and penalizing threat vision. */
  private long scoreLocationCandidateBySafety(MapLocation location, List<RobotInfo> threats) {
    long locationScore = 0;
    for (RobotInfo threat : threats) {
      boolean cat = threat.getType() == UnitType.CAT;
      long priority = cat ? 1_000_000L : 1L;
      if (locationCandidateIsInThreatVision(location, threat)) {
        locationScore -= cat ? 1_000_000_000L : 1_000L;
      }
      locationScore += priority * location.distanceSquaredTo(threat.getLocation());
    }
    return locationScore;
  }

  /** Checks whether a candidate location is within a threat's current vision cone. */
  private boolean locationCandidateIsInThreatVision(MapLocation location, RobotInfo threat) {
    UnitType type = threat.getType();
    MapLocation threatLocation = threat.getLocation();
    Direction threatFacing = threat.getDirection();
    return threatLocation.isWithinDistanceSquared(
        location, type.visionConeRadiusSquared, threatFacing, type.visionConeAngle, false);
  }
}
