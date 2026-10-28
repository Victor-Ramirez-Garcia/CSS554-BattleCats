package battlecats;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.Team;
import battlecode.common.UnitType;
import java.util.Arrays;

/** Worker-scout: keep cheese flowing, take protected cat fights and build locally. */
public class BabyRat {
  private static final int TARGET_TIMEOUT_ROUNDS = 80;
  private static final int IGNORE_TARGET_ROUNDS = 40;
  // Experimental cargo target, not an engine limit: bank a full 20-cheese load too.
  private static final int RETURN_CHEESE_THRESHOLD = 20;
  private final Navigation navigation;
  private final Construction construction = new Construction();
  private final CatTactics catTactics = new CatTactics();
  private MapLocation kingLocation;
  private MapLocation cheeseTarget;
  private int targetRound;
  private MapLocation ignoredCheese;
  private int ignoreUntilRound;
  private int homeScans;
  private int deliveries;
  private boolean returningFood;
  // Only current visibility is cached here; observations retained for combat stay separate.
  private RobotInfo[] currentRobots;
  private MapLocation visionLocation;
  private Direction visionDirection;
  private int visionRound;
  private int visionActionCooldown;

  public BabyRat(int robotId) {
    navigation = new Navigation(robotId);
  }

  public void takeTurn(RobotController rc) throws GameActionException {
    if (rc.isBeingCarried() || rc.isBeingThrown()) {
      return;
    }
    currentRobots = null;
    rememberKing(rc);
    RobotInfo[] observations = visibleRobots(rc);
    if (returnFoodIfNeeded(rc, observations)) {
      return;
    }
    int focusId = FocusSignal.read(rc);
    boolean returning = rc.getRawCheese() >= RETURN_CHEESE_THRESHOLD;
    boolean scanningForHome = kingLocation == null && homeScans < 4;
    if (!returning && !scanningForHome) {
      findCheese(rc);
    }
    MapLocation objective = returning ? kingLocation : cheeseTarget;
    if (scanningForHome) {
      objective = null;
    }
    boolean pickedUp = !returning && pickUp(rc);
    if (pickedUp && returnFoodIfNeeded(rc, observations)) {
      return;
    }
    // Cat engagement gets the current sighting before economic navigation can turn it out of view.
    // Immediate pickups and food trips still take priority; only empty workers may approach.
    if (!scanningForHome && catTactics.engage(rc, observations)) {
      return;
    }
    // Offer PvP the current facing before economic navigation can turn an enemy out of view.
    if (!returning && !containsCat(observations)) {
      construction.tryRatTrap(rc, observations);
      if (PvPTactics.engage(rc, observations, focusId)) {
        return;
      }
    }
    if (objective != null) {
      face(rc, objective);
    }
    // Turning changes our 90-degree view, but a cat seen before the turn still matters.
    observations = combineObservations(observations, visibleRobots(rc));

    // Banking cargo gets the action before optional construction or combat.
    if (returning) {
      rememberKing(rc);
      if (deliver(rc)) {
        if (!CatTactics.retreat(rc, observations, kingLocation) && !containsCat(observations)) {
          PvPTactics.engage(rc, observations, focusId);
        }
        return;
      }
    } else if (!pickedUp) {
      pickedUp = pickUp(rc);
    }

    if (returnFoodIfNeeded(rc, observations)) {
      return;
    }
    if (!containsCat(observations)) {
      construction.tryRatTrap(rc, observations);
      if (PvPTactics.engage(rc, observations, focusId)) {
        return;
      }
    }
    construction.tryBuild(rc, kingLocation, objective, observations);
    CatTactics.tryBite(rc, observations);
    if (CatTactics.retreat(rc, observations, objective)) {
      return;
    }
    if (pickedUp) {
      return;
    }

    // A newborn inherits its king's facing; the king can initially be behind it.
    if (scanningForHome) {
      scanForHome(rc);
      return;
    }
    if (returning) {
      returnCheese(rc);
    } else if (cheeseTarget == null) {
      rc.setIndicatorString("SEARCH delivered=" + deliveries);
      navigation.explore(rc, kingLocation);
    } else {
      navigation.moveToward(rc, cheeseTarget);
      pickUp(rc);
      rc.setIndicatorString((rc.getRawCheese() > 0 ? "RETURN" : "COLLECT")
          + " delivered=" + deliveries);
    }
    // Movement can bring a cat edge into range; canAttack prevents a second action.
    RobotInfo[] afterMovement = combineObservations(observations, visibleRobots(rc));
    if (returnFoodIfNeeded(rc, afterMovement)) {
      return;
    }
    CatTactics.tryBite(rc, afterMovement);
    if (!containsCat(afterMovement)) {
      construction.tryRatTrap(rc, afterMovement);
      PvPTactics.engage(rc, afterMovement, focusId);
    }
  }

  /** Banked food is global; cargo becomes food only when delivered. Keep the trip until empty. */
  private boolean returnFoodIfNeeded(RobotController rc, RobotInfo[] observations)
      throws GameActionException {
    if (rc.getRawCheese() == 0) {
      returningFood = false;
      return false;
    }
    returningFood |= FoodBudget.needsDelivery(rc.getGlobalCheese());
    if (!returningFood) {
      return false;
    }
    if (kingLocation != null) {
      face(rc, kingLocation);
      rememberKing(rc);
      observations = combineObservations(observations, visibleRobots(rc));
      if (deliver(rc)) {
        CatTactics.retreat(rc, observations, kingLocation);
        return true;
      }
    }
    // Keep cat survival, but do not let PvP retreat or optional spending own a food carrier's turn.
    if (!CatTactics.retreat(rc, observations, kingLocation)) {
      if (kingLocation == null && scanForHome(rc)) {
        return true;
      }
      if (kingLocation != null) {
        construction.clearDeliveryRoute(rc, kingLocation);
      }
      returnCheese(rc);
    }
    if (rc.getRawCheese() > 0) {
      rc.setIndicatorString("FOOD_RETURN raw=" + rc.getRawCheese());
    }
    return true;
  }

  private boolean scanForHome(RobotController rc) throws GameActionException {
    if (kingLocation != null || homeScans >= 4) {
      return false;
    }
    rc.setIndicatorString("SCAN_HOME");
    if (rc.canTurn()) {
      rc.turn(rc.getDirection().rotateRight().rotateRight());
      homeScans++;
    }
    return true;
  }

  private boolean containsCat(RobotInfo[] observations) {
    for (RobotInfo robot : observations) {
      if (robot.getType() == UnitType.CAT) {
        return true;
      }
    }
    return false;
  }

  private RobotInfo[] combineObservations(RobotInfo[] before, RobotInfo[] after) {
    if (before == after) {
      return before;
    }
    if (before.length == 0) {
      return after;
    }
    if (after.length == 0) {
      return before;
    }
    // Cat helpers tolerate duplicates; PvP force estimates deduplicate IDs before counting support.
    RobotInfo[] combined = Arrays.copyOf(before, before.length + after.length);
    System.arraycopy(after, 0, combined, before.length, after.length);
    return combined;
  }

  /** Reuse a scan until movement, facing, an action or a new engine round can change its data. */
  private RobotInfo[] visibleRobots(RobotController rc) throws GameActionException {
    MapLocation location = rc.getLocation();
    Direction direction = rc.getDirection();
    int round = rc.getRoundNum();
    int actionCooldown = rc.getActionCooldownTurns();
    if (currentRobots == null
        || !location.equals(visionLocation)
        || direction != visionDirection
        || round != visionRound
        || actionCooldown != visionActionCooldown) {
      currentRobots = rc.senseNearbyRobots();
      visionLocation = location;
      visionDirection = direction;
      visionRound = round;
      visionActionCooldown = actionCooldown;
    }
    return currentRobots;
  }

  private void findCheese(RobotController rc) throws GameActionException {
    if (cheeseTarget != null && rc.getRoundNum() - targetRound >= TARGET_TIMEOUT_ROUNDS) {
      ignoredCheese = cheeseTarget;
      ignoreUntilRound = rc.getRoundNum() + IGNORE_TARGET_ROUNDS;
      cheeseTarget = null;
    }
    if (cheeseTarget != null && rc.canSenseLocation(cheeseTarget)
        && rc.senseMapInfo(cheeseTarget).getCheeseAmount() == 0) {
      cheeseTarget = null;
    }
    MapLocation closest = cheeseTarget;
    int bestDistance = closest == null ? Integer.MAX_VALUE : rc.getLocation().distanceSquaredTo(closest);
    for (MapInfo info : rc.senseNearbyMapInfos()) {
      if (info.getCheeseAmount() <= 0) {
        continue;
      }
      MapLocation location = info.getMapLocation();
      if (location.equals(ignoredCheese) && rc.getRoundNum() < ignoreUntilRound) {
        continue;
      }
      int distance = rc.getLocation().distanceSquaredTo(location);
      if (distance < bestDistance) {
        closest = location;
        bestDistance = distance;
      }
    }
    if (closest != null && !closest.equals(cheeseTarget)) {
      cheeseTarget = closest;
      targetRound = rc.getRoundNum();
    }
  }

  private boolean pickUp(RobotController rc) throws GameActionException {
    if (cheeseTarget != null && rc.canPickUpCheese(cheeseTarget)) {
      rc.pickUpCheese(cheeseTarget);
      cheeseTarget = null;
      return true;
    }
    return false;
  }

  private void returnCheese(RobotController rc) throws GameActionException {
    rc.setIndicatorString("RETURN raw=" + rc.getRawCheese() + " delivered=" + deliveries);
    if (kingLocation == null) {
      navigation.explore(rc, kingLocation);
      return;
    }
    face(rc, kingLocation);
    rememberKing(rc);
    if (deliver(rc)) {
      return;
    }
    if (kingLocation != null) {
      navigation.moveToward(rc, kingLocation);
      rememberKing(rc);
      deliver(rc);
    }
  }

  private boolean deliver(RobotController rc) throws GameActionException {
    int amount = rc.getRawCheese();
    if (kingLocation == null || amount == 0) {
      return false;
    }
    // Kings occupy 3x3: an edge may be reachable before the center is reachable.
    for (MapLocation part : UnitType.RAT_KING.getAllTypeLocations(kingLocation)) {
      if (rc.canTransferCheese(part, amount)) {
        rc.transferCheese(part, amount);
        deliveries++;
        rc.setIndicatorString("DELIVER amount=" + amount + " delivered=" + deliveries);
        System.out.println("DELIVER rat=" + rc.getID() + " cheese=" + amount);
        return true;
      }
    }
    return false;
  }

  private void rememberKing(RobotController rc) throws GameActionException {
    Team ownTeam = rc.getTeam();
    boolean locallyVerified = false;
    if (kingLocation != null && rc.canSenseLocation(kingLocation)) {
      RobotInfo king = rc.senseRobotAtLocation(kingLocation);
      if (king == null || king.getTeam() != ownTeam || king.getType() != UnitType.RAT_KING) {
        kingLocation = null;
      } else {
        kingLocation = king.getLocation();
        locallyVerified = true;
      }
    }
    int nearest = kingLocation == null ? Integer.MAX_VALUE : rc.getLocation().distanceSquaredTo(kingLocation);
    for (RobotInfo robot : visibleRobots(rc)) {
      if (robot.getTeam() == ownTeam && robot.getType() == UnitType.RAT_KING) {
        int distance = rc.getLocation().distanceSquaredTo(robot.getLocation());
        if (distance <= nearest) {
          kingLocation = robot.getLocation();
          nearest = distance;
          locallyVerified = true;
        }
      }
    }
    if (!locallyVerified) {
      MapLocation published = KingSignal.read(rc);
      if (published != null) {
        kingLocation = published;
      }
    }
  }

  private void face(RobotController rc, MapLocation location) throws GameActionException {
    if (!rc.getLocation().equals(location)) {
      Direction direction = rc.getLocation().directionTo(location);
      if (rc.canTurn(direction)) {
        rc.turn(direction);
      }
    }
  }
}
