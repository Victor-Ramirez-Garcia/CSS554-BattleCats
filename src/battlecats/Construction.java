package battlecats;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.GameConstants;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.Team;
import battlecode.common.TrapType;
import battlecode.common.UnitType;

/** Opportunistic construction that keeps collection routes and the king's exits open. */
public class Construction {
  // Experiments: spend on traps only within two tiles of a cat; screens may delay a cat
  // three tiles away. These are reach estimates, not engine attack or trap-trigger radii.
  private static final int TRAP_CAT_DISTANCE_SQUARED = 4;
  private static final int SCREEN_CAT_DISTANCE_SQUARED = 9;
  // Experiment: protect more than the king's footprint and immediate spawn ring.
  private static final int HOME_CLEARANCE_SQUARED = 18;
  // Experiment: injured workers should keep their action for biting or retreating.
  private static final int RAT_TRAP_MIN_HEALTH = 75;
  // Engine movement reaches one neighboring tile, including a diagonal, in a single step.
  private static final int BABY_STEP_DISTANCE_SQUARED = 2;

  // One successful placement of each kind per worker bounds spending and scattered obstacles.
  private boolean placedCatTrap;
  private boolean placedRatTrap;
  private boolean placedScreen;

  /**
   * Places one local combat trap after betrayal without spending carried food or a finishing bite.
   * The enemy can reach the chosen tile in one step, but need not choose to move there. Friendly
   * rats can cross their own traps safely, so the dirt screen's home clearance does not apply.
   */
  public boolean tryRatTrap(RobotController rc, RobotInfo[] observations)
      throws GameActionException {
    if (placedRatTrap
        || rc.getType() != UnitType.BABY_RAT
        || rc.isCooperation()
        || !rc.isActionReady()
        || rc.getRawCheese() != 0
        || rc.getHealth() < RAT_TRAP_MIN_HEALTH
        || !FoodBudget.canSpend(rc.getGlobalCheese(), TrapType.RAT_TRAP.buildCost)) {
      return false;
    }
    MapLocation here = rc.getLocation();
    Team enemyTeam = rc.getTeam().opponent();
    RobotInfo nearest = null;
    int nearestDistance = Integer.MAX_VALUE;
    for (RobotInfo robot : observations) {
      if (robot.getTeam() != enemyTeam
          || (robot.getType() != UnitType.BABY_RAT && robot.getType() != UnitType.RAT_KING)) {
        continue;
      }
      if (robot.getHealth() <= GameConstants.RAT_BITE_DAMAGE && canFinishBite(rc, here, robot)) {
        return false;
      }
      if (robot.getType() != UnitType.BABY_RAT) {
        continue;
      }
      int distance = here.distanceSquaredTo(robot.getLocation());
      if (nearest == null
          || distance < nearestDistance
          || (distance == nearestDistance && robot.getID() < nearest.getID())) {
        nearest = robot;
        nearestDistance = distance;
      }
    }
    if (nearest == null) {
      return false;
    }
    Direction towardEnemy = here.directionTo(nearest.getLocation());
    Direction[] candidates = {towardEnemy, towardEnemy.rotateLeft(), towardEnemy.rotateRight()};
    for (Direction direction : candidates) {
      MapLocation target = here.add(direction);
      // Adjacent enemy reach is a conservative placement experiment, not immediate trap damage.
      if (direction == Direction.CENTER
          || target.distanceSquaredTo(nearest.getLocation()) > BABY_STEP_DISTANCE_SQUARED
          || !rc.canSenseLocation(target)) {
        continue;
      }
      MapInfo terrain = rc.senseMapInfo(target);
      if (terrain.isPassable()
          && terrain.getCheeseAmount() == 0
          && !terrain.hasCheeseMine()
          && terrain.getTrap() == TrapType.NONE
          && rc.canPlaceRatTrap(target)) {
        // Engine permission also checks occupancy, action range and the team's active trap cap.
        rc.placeRatTrap(target);
        placedRatTrap = true;
        return true;
      }
    }
    return false;
  }

  private boolean canFinishBite(RobotController rc, MapLocation here, RobotInfo robot) {
    if (robot.getType() == UnitType.BABY_RAT) {
      return reachableBiteTile(rc, here, robot.getLocation());
    }
    for (MapLocation part : UnitType.RAT_KING.getAllTypeLocations(robot.getLocation())) {
      if (reachableBiteTile(rc, here, part)) {
        return true;
      }
    }
    return false;
  }

  private boolean reachableBiteTile(RobotController rc, MapLocation here, MapLocation target) {
    int distance = here.distanceSquaredTo(target);
    if (distance == 0 || distance > GameConstants.ATTACK_DISTANCE_SQUARED) {
      return false;
    }
    // A pre-turn observation may be outside the current cone; PvP can turn before biting it.
    return rc.canAttack(target)
        || (!rc.canSenseLocation(target) && rc.canTurn(here.directionTo(target)));
  }

  /**
   * Uses at most one action and never moves or pursues a cat. The collector should first
   * attempt immediate delivery/pickup, and still evaluate escape movement after this call.
   * Returns true only when a trap, dirt screen or useful dirt removal consumed the action.
   */
  public boolean tryBuild(
      RobotController rc, MapLocation home, MapLocation objective, RobotInfo[] visibleCats)
      throws GameActionException {
    if (!rc.isActionReady()) {
      return false;
    }
    MapLocation catPart = nearestCatPart(rc.getLocation(), visibleCats);
    if (catPart != null && tryCatTrap(rc, catPart)) {
      return true;
    }
    if (tryClearRoute(rc, objective, false)) {
      return true;
    }
    return catPart != null && tryScreen(rc, home, objective, catPart);
  }

  private boolean tryCatTrap(RobotController rc, MapLocation catPart)
      throws GameActionException {
    if (placedCatTrap || !FoodBudget.canSpend(rc.getGlobalCheese(), TrapType.CAT_TRAP.buildCost)) {
      return false;
    }
    MapLocation here = rc.getLocation();
    Direction towardCat = here.directionTo(catPart);
    Direction[] candidates = {towardCat, towardCat.rotateLeft(), towardCat.rotateRight()};
    for (Direction direction : candidates) {
      MapLocation target = here.add(direction);
      if (direction != Direction.CENTER
          && target.distanceSquaredTo(catPart) <= TRAP_CAT_DISTANCE_SQUARED
          && rc.canSenseLocation(target)
          && rc.senseMapInfo(target).getCheeseAmount() == 0
          && rc.canPlaceCatTrap(target)) {
        // The engine checks occupancy, mines, the team trap limit and backstab restrictions.
        rc.placeCatTrap(target);
        placedCatTrap = true;
        return true;
      }
    }
    return false;
  }

  /** Removes only the next dirt obstruction when doing so can release carried food to the bank. */
  public boolean clearDeliveryRoute(RobotController rc, MapLocation home)
      throws GameActionException {
    return tryClearRoute(rc, home, true);
  }

  private boolean tryClearRoute(RobotController rc, MapLocation objective, boolean deliveringFood)
      throws GameActionException {
    int cost = GameConstants.DIG_DIRT_CHEESE_COST;
    // An emergency dig may use cargo, retaining some cheese to deliver, without touching the bank.
    boolean usesCargo = deliveringFood && rc.getRawCheese() > cost;
    if (objective == null
        || objective.equals(rc.getLocation())
        || (!usesCargo && !FoodBudget.canSpend(rc.getGlobalCheese(), cost))) {
      return false;
    }
    MapLocation next = rc.getLocation().add(rc.getLocation().directionTo(objective));
    if (rc.canSenseLocation(next)
        && rc.senseMapInfo(next).isDirt()
        && rc.canRemoveDirt(next)) {
      // Removing a real obstacle also supplies the team's dirt inventory for later screens.
      rc.removeDirt(next);
      return true;
    }
    return false;
  }

  private boolean tryScreen(
      RobotController rc, MapLocation home, MapLocation objective, MapLocation catPart)
      throws GameActionException {
    if (placedScreen || home == null || rc.getDirt() <= 0) {
      return false;
    }
    MapLocation here = rc.getLocation();
    Direction towardCat = here.directionTo(catPart);
    MapLocation target = here.add(towardCat);
    if (towardCat == Direction.CENTER
        || (towardCat.dx != 0 && towardCat.dy != 0)
        || target.distanceSquaredTo(catPart) > SCREEN_CAT_DISTANCE_SQUARED
        || target.distanceSquaredTo(home) <= HOME_CLEARANCE_SQUARED
        || (objective != null && target.equals(here.add(here.directionTo(objective))))
        || !rc.canSenseLocation(target)) {
      return false;
    }
    MapInfo terrain = rc.senseMapInfo(target);
    if (terrain.getCheeseAmount() > 0
        || terrain.hasCheeseMine()
        || terrain.getTrap() != TrapType.NONE
        || !rc.canPlaceDirt(target)) {
      return false;
    }
    // Cardinal screens only: each side tile is a diagonal step from here and from beyond.
    // Check the far tile too, giving workers a local two-step bypass on either side.
    Direction side = towardCat.rotateLeft().rotateLeft();
    if (!openTerrain(rc, target.add(side))
        || !openTerrain(rc, target.add(side.opposite()))
        || !openTerrain(rc, target.add(towardCat))) {
      return false;
    }
    rc.placeDirt(target);
    placedScreen = true;
    return true;
  }

  private boolean openTerrain(RobotController rc, MapLocation location)
      throws GameActionException {
    return rc.canSenseLocation(location) && rc.senseMapInfo(location).isPassable();
  }

  private MapLocation nearestCatPart(MapLocation here, RobotInfo[] visibleCats) {
    MapLocation closest = null;
    int nearestDistance = Integer.MAX_VALUE;
    for (RobotInfo robot : visibleCats) {
      if (robot.getType() != UnitType.CAT) {
        continue;
      }
      for (MapLocation part : UnitType.CAT.getAllTypeLocations(robot.getLocation())) {
        int distance = here.distanceSquaredTo(part);
        if (distance < nearestDistance) {
          nearestDistance = distance;
          closest = part;
        }
      }
    }
    return closest;
  }
}
