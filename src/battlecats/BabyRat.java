package battlecats;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.GameConstants;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.Team;
import battlecode.common.UnitType;
import java.util.Arrays;

/** Worker-scout: keep cheese flowing, take protected cat fights and build locally. */
public class BabyRat {
  //PUBLIC VARS

  //PRIVATE VARS
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

  //PUBLIC METHODS
  public BabyRat(int robotId) {
    navigation = new Navigation(robotId);
  }

  public void takeTurn(RobotController rc) throws GameActionException {
    //if the BR is being carried
    if (rc.isBeingCarried() || rc.isBeingThrown()) {
      return;
    }
    //reset what the BR senses (kingLocation and currentRobots)
    currentRobots = null;
    rememberKing(rc);
    RobotInfo[] observations = visibleRobots(rc);

    if (tryBecomeRatKing(rc)) {
      return;
    }

    //determine if it needs to return food
    if (returnFoodIfNeeded(rc, observations)) {
      return;
    }

    //if we know where a king is but don't have enough cheese to return
    //then the BR should find cheese
    boolean returning = rc.getRawCheese() >= RETURN_CHEESE_THRESHOLD;
    boolean scanningForHome = kingLocation == null && homeScans < 4;
    if (!returning && !scanningForHome) {
      findCheese(rc);
    }

    //
    MapLocation objective = returning ? kingLocation : cheeseTarget;
    if (scanningForHome) {
      objective = null;
    }
    //if we're not returning but have a cheese target that we can pick up
    boolean pickedUp = !returning && pickUp(rc);
    if (pickedUp && returnFoodIfNeeded(rc, observations)) {
      return;
    }

    // Cat engagement gets the current sighting before economic navigation can turn it out of view.
    // Immediate pickups and food trips still take priority; only empty workers may approach.
    //don't interrupt if we're currently attacking
    if (!scanningForHome && catTactics.engage(rc, observations)) {
      return;
    }
    //if we have an objective (kingLovation, cheese, others to be added?), face the objective
    if (objective != null) {
      face(rc, objective);
    }
    // Turning changes our 90-degree view, but a cat seen before the turn still matters.
    observations = combineObservations(observations, visibleRobots(rc));

    // Banking cargo gets the action before optional construction or combat.
    //if we're returning
    if (returning) {
      rememberKing(rc);
      //try to deliver the cheese to the king
      if (deliver(rc)) {
        //?
        CatTactics.retreat(rc, observations, kingLocation);
        return;
      }
    //if we're not returning
    //try to pick up cheese
    } else if (!pickedUp) {
      pickedUp = pickUp(rc);
    }
    //decide if we need to return food
    if (returnFoodIfNeeded(rc, observations)) {
      return;
    }
    //try to build something
    construction.tryBuild(rc, kingLocation, objective, observations);
    //ATTACK!!! KILL!!!
    CatTactics.tryBite(rc, observations);
    //RUN AWAY!!!
    if (CatTactics.retreat(rc, observations, objective)) {
      return;
    }
    //if we did pick up cheese, but didn't do anything else, don't do any thing more
    if (pickedUp) {
      return;
    }

    // A newborn inherits its king's facing; the king can initially be behind it.
    //determine the area around it at it's birth for the best initial direction
    if (scanningForHome) {
      scanForHome(rc);
      return;
    }
    //if we're returning, continue to do that
    if (returning) {
      returnCheese(rc);

    //else search for cheese
    } else if (cheeseTarget == null) {
      rc.setIndicatorString("SEARCH delivered=" + deliveries);
      navigation.explore(rc);
    
    //or move towards cheese we've found
    } else {
      navigation.moveToward(rc, cheeseTarget);
      pickUp(rc);
      rc.setIndicatorString((rc.getRawCheese() > 0 ? "RETURN" : "COLLECT")
          + " delivered=" + deliveries);
    }
    // Movement can bring a cat edge into range; canAttack prevents a second action.
    RobotInfo[] afterMovement = combineObservations(observations, visibleRobots(rc));
    //after movement, if we have cheese, don't do anything
    if (returnFoodIfNeeded(rc, afterMovement)) {
      return;
    }
    //after movement, if we don't have cheese, ATTACK!!!
    CatTactics.tryBite(rc, afterMovement);
  }

  //PRIVATE METHODS

  /**
   * Becomes a Rat King when the engine allows it here. The engine owns the legality rules: seven
   * of our Baby Rats in this 3x3 counting this one, every tile on the map and passable, no cat or
   * Rat King in it, and the team king cap. It absorbs the other rats in the square.
   * Policy, not engine rules: at most one promotion per game, none after round 1200, keep the
   * spending reserve, and only while a king reports income that could feed another king. Every
   * king eats food each round and runs its own spawn budget, and each promotion turns seven
   * workers into one king. In the 7-map comparison against team main e82c077, promotion without
   * the income check starved every king on minimaze and safelycontained, even when capped at one.
   */
  private boolean tryBecomeRatKing(RobotController rc) throws GameActionException {
    if (rc.getRoundNum() > GameConstants.RAT_KING_CUTOFF_ROUND
        || !FoodBudget.canSpend(rc.getGlobalCheese(), GameConstants.RAT_KING_UPGRADE_CHEESE_COST)
        || KingSignal.promotionRecorded(rc)
        || !KingSignal.promotionFunded(rc)
        || !rc.canBecomeRatKing()) {
      return false;
    }
    rc.becomeRatKing();
    KingSignal.recordPromotion(rc);
    return true;
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
    // Keep cat survival while optional spending yields to a committed food delivery.
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

  //used at beginning of babyRats life as part of determining if there's a best direction to go
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
    // Cat helpers tolerate duplicate sightings; retain cats seen before a turn changes vision.
    RobotInfo[] combined = Arrays.copyOf(before, before.length + after.length);
    System.arraycopy(after, 0, combined, before.length, after.length);
    return combined;
  }

  /** Reuse a scan until movement, facing, an action or a new engine round can change its data. */
  //returns all robots in current sensory range
  private RobotInfo[] visibleRobots(RobotController rc) throws GameActionException {
    MapLocation location = rc.getLocation();
    Direction direction = rc.getDirection();
    int round = rc.getRoundNum();
    int actionCooldown = rc.getActionCooldownTurns();
    if (currentRobots == null  //if the vars aren't set yet (beginning of game or something)
        || !location.equals(visionLocation)   
        || direction != visionDirection       
        || round != visionRound               
        || actionCooldown != visionActionCooldown) {  
      currentRobots = rc.senseNearbyRobots(); //set all those vars
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
      navigation.explore(rc);
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
    //if the kingLocation var is filled with something (we don't trust it to be accurate) and we can sense that location
    //makes sure there are at least one king in sensory range
    if (kingLocation != null && rc.canSenseLocation(kingLocation)) {
      RobotInfo king = rc.senseRobotAtLocation(kingLocation);       //this is for a specific king for when we have more than one king
      if (king == null                  //there's been an error in the kingLocatoin var, (it's died or warsn't overwritten after movement)
        || king.getTeam() != ownTeam    //this shouldn't happen, but no harm in checking
        || king.getType() != UnitType.RAT_KING) { //more error checking for the kingLocatoin var
        kingLocation = null;
      } else {
        //set the kingLocation var, now we trust it for the rest of this method
        kingLocation = king.getLocation();
        locallyVerified = true;
      }
    }
    //finds nearest king if there are multiple kings in sensory range
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
    //the above hasnt worked, and we need to have kingSignal write to kingLocation
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
