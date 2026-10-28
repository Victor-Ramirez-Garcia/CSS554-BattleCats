package battlecats;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.GameConstants;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.Team;
import battlecode.common.UnitType;
import java.util.Arrays;

/** Local rat combat with conservative betrayal gates and locally verified shared focus. */
public final class PvPTactics {
  // Experiments, not game rules: allow the economy 100 rounds, then commit only healthy workers.
  private static final int EARLIEST_BETRAYAL_ROUND = 100;
  private static final int MIN_INITIATING_HEALTH = 75;
  // Experiment: inspect defenders within two diagonal steps, not the whole visible army.
  private static final int LOCAL_FORCE_RADIUS_SQUARED = 8;
  // Experiments: require at least two allies, one extra body, and twice the observed enemy health.
  private static final int MIN_ALLIED_BABIES = 2;
  private static final int HEALTH_ADVANTAGE_MULTIPLIER = 2;

  private PvPTactics() {}

  /** Uses local target ordering when the caller has no shared focus target. */
  public static boolean engage(RobotController rc, RobotInfo[] observations)
      throws GameActionException {
    return engage(rc, observations, -1);
  }

  /**
   * Uses current-turn observations, with later duplicate IDs replacing earlier sightings. Returns
   * true when local combat consumed an action/move or holds a threatened worker in place. Callers
   * should give immediate cheese delivery and cat evasion priority, then skip ordinary navigation
   * when this returns true. Kings only bite after cooperation ends; this method never moves a king.
   * Visible force estimates cannot account for hidden units or allies' action cooldowns.
   *
   * <p>After betrayal, a free-bite finish takes priority over the designated focus ID. Otherwise a
   * focus target must occur in these observations and offer a legal bite or safe local approach;
   * an unknown ID never creates pursuit. The caller owns the shared signal's freshness check.
   */
  public static boolean engage(RobotController rc, RobotInfo[] observations, int focusId)
      throws GameActionException {
    Team enemyTeam = rc.getTeam().opponent();
    boolean enemySeen = false;
    for (RobotInfo robot : observations) {
      if (isEnemyRat(robot, enemyTeam)) {
        enemySeen = true;
        break;
      }
    }
    if (!enemySeen) {
      return false;
    }

    UnitType type = rc.getType();
    if (type != UnitType.BABY_RAT && type != UnitType.RAT_KING) {
      return false;
    }
    // Read the engine phase for each invocation, including calls after another bite this turn.
    boolean cooperating = rc.isCooperation();
    // These own-state vetoes need no deduplication or observation-dependent force estimates.
    if (cooperating
        && (type == UnitType.RAT_KING
            || rc.getRoundNum() < EARLIEST_BETRAYAL_ROUND
            || rc.getRawCheese() != 0
            || FoodBudget.needsDelivery(rc.getGlobalCheese())
            || rc.getHealth() < MIN_INITIATING_HEALTH)) {
      return false;
    }
    RobotInfo[] robots = latestSightings(observations, rc.getID());
    if (cooperating) {
      if (containsCatOrEnemyKing(robots, enemyTeam)) {
        return false;
      }
      RobotInfo target = attackTarget(rc, robots, enemyTeam, true, -1);
      return target != null && bite(rc, target);
    }

    RobotInfo attackTarget = attackTarget(rc, robots, enemyTeam, false, focusId);
    boolean attacked = attackTarget != null && bite(rc, attackTarget);
    if (type == UnitType.RAT_KING) {
      return attacked;
    }

    RobotInfo nearbyEnemy = nearestLocalEnemy(rc.getLocation(), robots, enemyTeam);
    if (nearbyEnemy == null) {
      return attacked;
    }
    if (!hasLocalAdvantage(rc, robots, nearbyEnemy.getLocation(), enemyTeam, false)) {
      retreat(rc, robots, enemyTeam);
      // A blocked retreat still owns movement: ordinary navigation must not walk into this fight.
      return true;
    }
    if (attacked) {
      return true;
    }
    if (rc.getRawCheese() != 0) {
      return false;
    }
    // Stay in melee during cooldowns. Otherwise take at most one step toward a visible local foe.
    if (distanceToRobot(rc.getLocation(), nearbyEnemy) > GameConstants.ATTACK_DISTANCE_SQUARED) {
      if (FoodBudget.needsDelivery(rc.getGlobalCheese())) {
        return false;
      }
      RobotInfo approachTarget = nearbyEnemy;
      if (focusId >= 0 && focusId != nearbyEnemy.getID()) {
        for (RobotInfo robot : robots) {
          if (robot.getID() == focusId
              && isEnemyRat(robot, enemyTeam)
              && distanceToRobot(rc.getLocation(), robot) <= LOCAL_FORCE_RADIUS_SQUARED
              && hasLocalAdvantage(rc, robots, robot.getLocation(), enemyTeam, false)) {
            // Keep the nearer threat's safety check above. A signal cannot excuse a bad fight.
            approachTarget = robot;
            break;
          }
        }
      }
      boolean moved = approach(rc, robots, approachTarget);
      if (!moved && approachTarget != nearbyEnemy) {
        moved = approach(rc, robots, nearbyEnemy);
      }
      RobotInfo afterMoving = attackTarget(rc, robots, enemyTeam, false, focusId);
      boolean bitAfterMoving = afterMoving != null && bite(rc, afterMoving);
      // A wall must not turn a distant contact into an idle collector. Melee holds and blocked
      // disadvantaged retreats still own the turn, but an optional approach must make progress.
      return moved || bitAfterMoving;
    }
    return true;
  }

  private static RobotInfo[] latestSightings(RobotInfo[] observations, int selfId) {
    // Measured engine bytecodes favor primitive slots over HashMap's boxing and node allocations.
    // Keep load at most one half. Slots hold output indexes plus one; zero means unused.
    long requiredCapacity = 2L * observations.length;
    int capacity = 1;
    for (int bits = 0; capacity < requiredCapacity && bits < 30; bits++) {
      capacity <<= 1;
    }
    if (capacity < requiredCapacity) {
      throw new IllegalArgumentException("Too many robot observations");
    }
    int[] slots = new int[capacity];
    RobotInfo[] latest = new RobotInfo[observations.length];
    int mask = capacity - 1;
    int count = 0;
    for (RobotInfo robot : observations) {
      int id = robot.getID();
      if (id == selfId) {
        continue;
      }
      int slot = (id ^ (id >>> 16)) & mask;
      int probes = 0;
      for (; probes < capacity; probes++) {
        int entry = slots[slot];
        if (entry == 0) {
          latest[count] = robot;
          slots[slot] = ++count;
          break;
        }
        if (latest[entry - 1].getID() == id) {
          latest[entry - 1] = robot;
          break;
        }
        slot = (slot + 1) & mask;
      }
      if (probes == capacity) {
        throw new IllegalStateException("Robot sighting table is full");
      }
    }
    // Counts/minima are order independent; target and distance ties use explicit robot IDs.
    // Thus first-seen output order preserves decisions while later sightings replace each value.
    return count == latest.length ? latest : Arrays.copyOf(latest, count);
  }

  private static boolean isEnemyRat(RobotInfo robot, Team enemyTeam) {
    return robot.getTeam() == enemyTeam
        && (robot.getType() == UnitType.BABY_RAT || robot.getType() == UnitType.RAT_KING);
  }

  private static boolean containsCatOrEnemyKing(RobotInfo[] robots, Team enemyTeam) {
    for (RobotInfo robot : robots) {
      if (robot.getType() == UnitType.CAT
          || (robot.getTeam() == enemyTeam && robot.getType() == UnitType.RAT_KING)) {
        return true;
      }
    }
    return false;
  }

  private static RobotInfo attackTarget(
      RobotController rc, RobotInfo[] robots, Team enemyTeam, boolean initiating, int focusId) {
    if (!rc.isActionReady()) {
      return null;
    }
    RobotInfo best = null;
    int bestPriority = Integer.MAX_VALUE;
    for (RobotInfo robot : robots) {
      if (!isEnemyRat(robot, enemyTeam)) {
        continue;
      }
      // Finishing removes an enemy action; otherwise shared focus prevents split damage when
      // workers see different weak targets. Basic bites use this engine damage without spending.
      int priority = 2;
      if (robot.getHealth() <= GameConstants.RAT_BITE_DAMAGE) {
        priority = 0;
      } else if (robot.getID() == focusId) {
        priority = 1;
      }
      // A worse-ranked target cannot replace an already legal candidate.
      if (best != null
          && (priority > bestPriority
              || (priority == bestPriority
                  && (robot.getHealth() > best.getHealth()
                      || (robot.getHealth() == best.getHealth()
                          && robot.getID() >= best.getID()))))) {
        continue;
      }
      if (reachableAttackTile(rc, robot) == null
          || (initiating
              && !hasLocalAdvantage(rc, robots, robot.getLocation(), enemyTeam, true))) {
        continue;
      }
      best = robot;
      bestPriority = priority;
    }
    return best;
  }

  private static MapLocation reachableAttackTile(RobotController rc, RobotInfo target) {
    MapLocation here = rc.getLocation();
    int range = rc.getType() == UnitType.RAT_KING
        ? GameConstants.RAT_KING_ATTACK_DISTANCE_SQUARED
        : GameConstants.ATTACK_DISTANCE_SQUARED;
    if (target.getType() == UnitType.BABY_RAT) {
      // The engine allocates a footprint array even for a one-tile baby.
      MapLocation part = target.getLocation();
      int distance = here.distanceSquaredTo(part);
      if (distance == 0 || distance > range) {
        return null;
      }
      if (rc.canAttack(part)) {
        return part;
      }
      return !rc.canSenseLocation(part) && rc.canTurn(here.directionTo(part)) ? part : null;
    }
    MapLocation afterTurning = null;
    for (MapLocation part : target.getType().getAllTypeLocations(target.getLocation())) {
      int distance = here.distanceSquaredTo(part);
      if (distance == 0 || distance > range) {
        continue;
      }
      if (rc.canAttack(part)) {
        return part;
      }
      if (afterTurning == null
          && !rc.canSenseLocation(part)
          && rc.canTurn(here.directionTo(part))) {
        afterTurning = part;
      }
    }
    return afterTurning;
  }

  private static boolean bite(RobotController rc, RobotInfo target) throws GameActionException {
    MapLocation tile = reachableAttackTile(rc, target);
    if (tile == null) {
      return false;
    }
    if (!rc.canAttack(tile)) {
      Direction direction = rc.getLocation().directionTo(tile);
      if (rc.canTurn(direction)) {
        rc.turn(direction);
      }
    }
    if (!rc.canAttack(tile)) {
      return false;
    }
    rc.attack(tile);
    rc.setIndicatorString("PVP_BITE id=" + target.getID());
    return true;
  }

  private static boolean hasLocalAdvantage(
      RobotController rc, RobotInfo[] robots, MapLocation center, Team enemyTeam,
      boolean initiating) {
    int alliedCount = 0;
    int alliedHealth = 0;
    int enemyCount = 0;
    int enemyHealth = 0;
    Team ownTeam = rc.getTeam();
    int allyRadius = initiating
        ? GameConstants.ATTACK_DISTANCE_SQUARED : LOCAL_FORCE_RADIUS_SQUARED;
    if (rc.getLocation().distanceSquaredTo(center) <= allyRadius) {
      alliedCount = 1;
      alliedHealth = rc.getHealth();
    }
    for (RobotInfo robot : robots) {
      int distance = robot.getLocation().distanceSquaredTo(center);
      if (robot.getTeam() == ownTeam && robot.getType() == UnitType.BABY_RAT) {
        // During betrayal, support must already be healthy, empty and in actual bite range.
        if (distance <= allyRadius
            && (!initiating
                || (robot.getHealth() >= MIN_INITIATING_HEALTH
                    && robot.getRawCheeseAmount() == 0))) {
          alliedCount++;
          alliedHealth += robot.getHealth();
        }
      } else if (isEnemyRat(robot, enemyTeam) && distance <= LOCAL_FORCE_RADIUS_SQUARED) {
        // A king's defensive health matters after betrayal, even though only babies support us.
        enemyCount++;
        enemyHealth += robot.getHealth();
      }
    }
    return alliedCount >= MIN_ALLIED_BABIES
        && alliedCount >= enemyCount + 1
        && alliedHealth >= HEALTH_ADVANTAGE_MULTIPLIER * enemyHealth;
  }

  private static RobotInfo nearestLocalEnemy(
      MapLocation here, RobotInfo[] robots, Team enemyTeam) {
    RobotInfo best = null;
    int bestDistance = LOCAL_FORCE_RADIUS_SQUARED + 1;
    for (RobotInfo robot : robots) {
      if (!isEnemyRat(robot, enemyTeam)) {
        continue;
      }
      int distance = distanceToRobot(here, robot);
      if (distance < bestDistance
          || (distance == bestDistance && best != null && robot.getID() < best.getID())) {
        best = robot;
        bestDistance = distance;
      }
    }
    return best;
  }

  private static void retreat(RobotController rc, RobotInfo[] robots, Team enemyTeam)
      throws GameActionException {
    MapLocation here = rc.getLocation();
    int bestDistance = distanceToEnemies(here, robots, enemyTeam);
    Direction best = Direction.CENTER;
    for (Direction direction : Direction.values()) {
      MapLocation next = here.add(direction);
      if (direction == Direction.CENTER || !rc.canMove(direction) || !safeFromCats(next, robots)) {
        continue;
      }
      int distance = distanceToEnemies(next, robots, enemyTeam);
      if (distance > bestDistance) {
        best = direction;
        bestDistance = distance;
      }
    }
    if (best != Direction.CENTER) {
      rc.move(best);
      rc.setIndicatorString("PVP_RETREAT");
    }
  }

  private static boolean approach(RobotController rc, RobotInfo[] robots, RobotInfo target)
      throws GameActionException {
    MapLocation here = rc.getLocation();
    int bestDistance = distanceToRobot(here, target);
    Direction best = Direction.CENTER;
    for (Direction direction : Direction.values()) {
      MapLocation next = here.add(direction);
      if (direction == Direction.CENTER || !rc.canMove(direction) || !safeFromCats(next, robots)) {
        continue;
      }
      int distance = distanceToRobot(next, target);
      if (distance < bestDistance) {
        best = direction;
        bestDistance = distance;
      }
    }
    if (best != Direction.CENTER) {
      rc.move(best);
      rc.setIndicatorString("PVP_APPROACH id=" + target.getID());
      return true;
    }
    return false;
  }

  private static int distanceToEnemies(MapLocation location, RobotInfo[] robots, Team enemyTeam) {
    int distance = Integer.MAX_VALUE;
    for (RobotInfo robot : robots) {
      if (isEnemyRat(robot, enemyTeam)) {
        distance = Math.min(distance, distanceToRobot(location, robot));
      }
    }
    return distance;
  }

  private static int distanceToRobot(MapLocation location, RobotInfo robot) {
    if (robot.getType() == UnitType.BABY_RAT) {
      return location.distanceSquaredTo(robot.getLocation());
    }
    int distance = Integer.MAX_VALUE;
    for (MapLocation part : robot.getType().getAllTypeLocations(robot.getLocation())) {
      distance = Math.min(distance, location.distanceSquaredTo(part));
    }
    return distance;
  }

  private static boolean safeFromCats(MapLocation location, RobotInfo[] robots) {
    for (RobotInfo robot : robots) {
      if (robot.getType() != UnitType.CAT) {
        continue;
      }
      for (MapLocation part : UnitType.CAT.getAllTypeLocations(robot.getLocation())) {
        if (location.isAdjacentTo(part)) {
          return false;
        }
      }
      if (robot.getLocation().isWithinDistanceSquared(
          location, UnitType.CAT.visionConeRadiusSquared, robot.getDirection(),
          UnitType.CAT.visionConeAngle, UnitType.CAT.usesBottomLeftLocationForDistance())) {
        return false;
      }
    }
    return true;
  }
}
