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
  // Experiment: through round 250 opponents delivered about 36 cheese per trip and we delivered
  // 21. A worker holding 20 to 39 cheese may keep collecting close, safe piles before going home.
  // Untuned experimental cap, not an engine limit: about two 20-cheese piles per trip.
  private static final int TOP_UP_CHEESE_LIMIT = 40;
  // Untuned experiment: r^2 16 (four tiles) keeps each detour to a few moves from the worker.
  private static final int TOP_UP_RANGE_SQUARED = 16;
  // Experiment: bounds the whole detour, including several small piles or one that walls or other
  // rats make unreachable.
  private static final int TOP_UP_MAX_ROUNDS = 10;
  // Experiment: three more bites kill a Baby Rat at this health.
  private static final int RETREAT_HEALTH = 30;
  // Experiment: on default maps 3mice kept 0-4 raiders just inside our half and most deaths on
  // both sides happened there, while its other workers collected untouched. One worker in eight
  // holds that band after cooperation ends instead of collecting.
  private static final int FIGHTER_EVERY = 8;
  // Experiment: the front lies three quarters of the way from our king to the map center, which
  // on symmetric maps is about 37% of the way to the enemy king.
  private static final int FRONT_NUMERATOR = 3;
  private static final int FRONT_DENOMINATOR = 4;
  // Engine rule: at round 2000 the winner is scored on shares of cat damage, living Rat Kings
  // and delivered cheese, and after round 1200 a team may only add a king while it has fewer
  // than two. Experiment: from round 1850 workers gather at the king so a second king forms
  // before the end; 3mice promotes at 1950 and won every long game 2 kings to 1.
  static final int LATE_PROMOTION_ROUND = 1850;
  // Engine rule: matches end after round 2000.
  private static final int LAST_ROUND = 2000;
  private final Navigation navigation;
  private final Construction construction = new Construction();
  private final CatTactics catTactics = new CatTactics();
  private final boolean fighter;
  private MapLocation kingLocation;
  private MapLocation cheeseTarget;
  private int targetRound;
  private MapLocation ignoredCheese;
  private int ignoreUntilRound;
  private int homeScans;
  private int deliveries;
  private boolean returningFood;
  private int topUpStartRound = -1;
  // Only current visibility is cached here; observations retained for combat stay separate.
  private RobotInfo[] currentRobots;
  private MapLocation visionLocation;
  private Direction visionDirection;
  private int visionRound;
  private int visionActionCooldown;
  // Health at the end of our previous turn; a drop with no enemy in view means an attacker behind.
  private int healthLastTurn = -1;

  //PUBLIC METHODS
  public BabyRat(int robotId) {
    // Engine IDs are not sequential per team, so this gives about one fighter in eight, not
    // exactly; with 16 workers roughly one game in eight has no fighter at all.
    this(robotId, robotId % FIGHTER_EVERY == FIGHTER_EVERY - 1);
  }

  BabyRat(int robotId, boolean fighter) {
    navigation = new Navigation(robotId);
    this.fighter = fighter;
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
    observations = lookForUnseenAttacker(rc, observations);

    if (tryBecomeRatKing(rc)) {
      return;
    }

    //determine if it needs to return food
    if (returnFoodIfNeeded(rc, observations)) {
      return;
    }

    //if we know where a king is but don't have enough cheese to return
    //then the BR should find cheese
    boolean toppingUp = shouldTopUp(rc, observations);
    boolean returning = rc.getRawCheese() >= RETURN_CHEESE_THRESHOLD && !toppingUp;
    boolean scanningForHome = kingLocation == null && homeScans < 4;
    if (!returning && !scanningForHome && !toppingUp) {
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
    // Late in the game, gather at the king so seven workers can share a 3x3 and promote.
    // Rally only when a promotion can succeed: the bank pays for it, no second king is alive
    // (the engine refuses a third after round 1200) and the king found a passable square.
    if (!returning && !toppingUp && isLatePromotionWindow(rc)
        && !KingSignal.latePromotionRecorded(rc)
        && lateKingAffordable(rc) && !KingSignal.secondKingAlive(rc)) {
      MapLocation rally = KingSignal.readRally(rc);
      if (rally != null) {
        if (!tryBiteEnemyRat(rc, observations)) {
          CatTactics.tryBite(rc, observations);
        }
        navigation.moveToward(rc, rally);
        rc.setIndicatorString("RALLY for late king at " + rally);
        return;
      }
    }
    // After a betrayal, empty workers close on enemy rats together instead of collecting past them.
    if (!scanningForHome && !returning && !pickedUp && engageEnemyRats(rc, observations)) {
      return;
    }
    if (fighter && !scanningForHome && !returning && !rc.isCooperation()
        && holdFront(rc, observations)) {
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
    // An explorer has no cheese objective, but its waypoint can lie behind dirt. On pipes the
    // king starts in a dirt-walled pocket without a mine, and workers that never dig starve it.
    MapLocation buildObjective = objective;
    if (buildObjective == null && !returning && !scanningForHome) {
      buildObjective = navigation.explorationTarget(rc);
    }
    construction.tryBuild(rc, kingLocation, buildObjective, observations);
    //ATTACK!!! KILL!!!
    if (!tryBiteEnemyRat(rc, observations)) {
      CatTactics.tryBite(rc, observations);
    }
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
    if (!tryBiteEnemyRat(rc, afterMovement)) {
      CatTactics.tryBite(rc, afterMovement);
    }
  }

  /////////////////////////////////
  /////////////////////////////////
  /////////////////////////////////

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
    if (isLatePromotionWindow(rc)) {
      return tryLatePromotion(rc);
    }
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

  private static boolean isLatePromotionWindow(RobotController rc) {
    return rc.getRoundNum() >= LATE_PROMOTION_ROUND;
  }

  /** The bank covers the upgrade and two kings' upkeep until the last round. */
  private static boolean lateKingAffordable(RobotController rc) {
    int upkeepToEnd = 2 * GameConstants.RAT_KING_CHEESE_CONSUMPTION
        * (LAST_ROUND - rc.getRoundNum());
    return rc.getGlobalCheese() >= GameConstants.RAT_KING_UPGRADE_CHEESE_COST + upkeepToEnd;
  }

  /**
   * Promotes regardless of the income signal and the one-per-game policy; the engine refuses
   * once we already have two kings. The bank must still feed two kings until the last round.
   */
  private boolean tryLatePromotion(RobotController rc) throws GameActionException {
    if (!lateKingAffordable(rc) || !rc.canBecomeRatKing()) {
      return false;
    }
    rc.becomeRatKing();
    KingSignal.recordLatePromotion(rc);
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

  // Reuse a scan until movement, facing, an action or a new engine round can change its data. 
  /**returns all robots in current sensory range */
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

  /**
   * Returns whether a loaded worker should detour to a close pile before going home. It may take
   * several piles while it holds 20 to 39 cheese, for at most TOP_UP_MAX_ROUNDS in total. It never
   * delays cargo when food is low, the king is unknown, or a cat or enemy is in view.
   */
  private boolean shouldTopUp(RobotController rc, RobotInfo[] robots) throws GameActionException {
    int cargo = rc.getRawCheese();
    if (cargo < RETURN_CHEESE_THRESHOLD || cargo >= TOP_UP_CHEESE_LIMIT) {
      topUpStartRound = -1;
      return false;
    }
    int round = rc.getRoundNum();
    if (topUpStartRound < 0) {
      topUpStartRound = round;
    }
    // A worker already in transfer range banks its load instead of walking away from the king.
    if (round - topUpStartRound >= TOP_UP_MAX_ROUNDS || kingLocation == null
        || kingInTransferRange(rc)
        || FoodBudget.needsDelivery(rc.getGlobalCheese())) {
      cheeseTarget = null;
      return false;
    }
    Team us = rc.getTeam();
    for (RobotInfo robot : robots) {
      if (robot.getTeam() != us) {
        cheeseTarget = null;
        return false;
      }
    }
    findCheese(rc);
    if (cheeseTarget == null
        || rc.getLocation().distanceSquaredTo(cheeseTarget) > TOP_UP_RANGE_SQUARED) {
      cheeseTarget = null;
      return false;
    }
    return true;
  }

  private boolean kingInTransferRange(RobotController rc) {
    MapLocation here = rc.getLocation();
    for (MapLocation part : UnitType.RAT_KING.getAllTypeLocations(kingLocation)) {
      if (here.distanceSquaredTo(part) <= GameConstants.CHEESE_TRANSFER_RADIUS_SQUARED) {
        return true;
      }
    }
    return false;
  }

  private void findCheese(RobotController rc) throws GameActionException {

    //if we have a cheese target and it's late in the game, ignore that cheese until later
    if (cheeseTarget != null && rc.getRoundNum() - targetRound >= TARGET_TIMEOUT_ROUNDS) {
      ignoredCheese = cheeseTarget;
      ignoreUntilRound = rc.getRoundNum() + IGNORE_TARGET_ROUNDS;
      cheeseTarget = null;
    }

    //if we can see that our cheese target is empty, forget that cheese target
    if (cheeseTarget != null && rc.canSenseLocation(cheeseTarget)
        && rc.senseMapInfo(cheeseTarget).getCheeseAmount() == 0) {
      cheeseTarget = null;
    }

    //find the closest cheese in vision
    MapLocation closest = cheeseTarget;
    int bestDistance = closest == null ? Integer.MAX_VALUE : rc.getLocation().distanceSquaredTo(closest);
    //search every location in vision
    for (MapInfo info : rc.senseNearbyMapInfos()) {
      //if it has no cheese, ignore it
      if (info.getCheeseAmount() <= 0) {
        continue;
      }
      //if it's the ignored cheese but it's not time yet, ignore it
      MapLocation location = info.getMapLocation();
      if (location.equals(ignoredCheese) && rc.getRoundNum() < ignoreUntilRound) {
        continue;
      }
      //the code only reaches here if the spot has cheese, if the loc is closer than our cheese target, switch targets
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

  /**
   * After cooperation, turns around when we lost health since our last turn but see no hostile
   * robot, because a 90-degree vision cone hides attackers behind us. A visible cat or enemy
   * explains the damage, so we keep facing it. Returns the fresh view.
   */
  private RobotInfo[] lookForUnseenAttacker(RobotController rc, RobotInfo[] observations)
      throws GameActionException {
    int health = rc.getHealth();
    boolean hurt = healthLastTurn >= 0 && health < healthLastTurn;
    healthLastTurn = health;
    if (!hurt || rc.isCooperation() || !rc.canTurn()) {
      return observations;
    }
    Team us = rc.getTeam();
    for (RobotInfo robot : observations) {
      if (robot.getTeam() != us) {
        return observations;
      }
    }
    rc.turn(rc.getDirection().opposite());
    return visibleRobots(rc);
  }

  /**
   * Moves toward the nearest visible enemy rat and bites, when we are not outnumbered in sight.
   * Experiment: counting only visible robots keeps this cheap; a lone worker that meets a group
   * keeps collecting instead of feeding the enemy one rat at a time.
   */
  private boolean engageEnemyRats(RobotController rc, RobotInfo[] robots)
      throws GameActionException {
    if (rc.isCooperation()) {
      return false;
    }
    Team us = rc.getTeam();
    MapLocation here = rc.getLocation();
    MapLocation nearest = null;
    int nearestDistance = Integer.MAX_VALUE;
    int allies = 1;
    int enemies = 0;
    for (RobotInfo robot : robots) {
      UnitType type = robot.getType();
      if (type != UnitType.BABY_RAT && type != UnitType.RAT_KING) {
        continue;
      }
      if (robot.getTeam() == us) {
        allies++;
        continue;
      }
      enemies++;
      int distance = here.distanceSquaredTo(robot.getLocation());
      if (distance < nearestDistance) {
        nearestDistance = distance;
        nearest = robot.getLocation();
      }
    }
    if (nearest == null) {
      return false;
    }
    // Experiment: outnumbered or badly hurt workers fall back to the king, which now bites
    // enemy rats, instead of dying alone at a mine.
    if ((allies < enemies || rc.getHealth() <= RETREAT_HEALTH) && kingLocation != null) {
      tryBiteEnemyRat(rc, robots);
      navigation.moveToward(rc, kingLocation);
      rc.setIndicatorString("FALL BACK allies=" + allies + " enemies=" + enemies);
      return true;
    }
    if (allies < enemies) {
      return false;
    }
    boolean bit = tryBiteEnemyRat(rc, robots);
    navigation.moveToward(rc, nearest);
    if (!bit) {
      tryBiteEnemyRat(rc, visibleRobots(rc));
    }
    rc.setIndicatorString("FIGHT allies=" + allies + " enemies=" + enemies);
    return true;
  }

  /**
   * Moves a fighter with no enemy in view toward the front and keeps looking around there.
   * Visible enemies are handled earlier by engageEnemyRats, which fights or falls back. A cat
   * that catTactics declined to engage still gets the same evasion a collector would use.
   * If the front tile is a wall the fighter waits beside it; that case is not handled.
   */
  private boolean holdFront(RobotController rc, RobotInfo[] robots) throws GameActionException {
    if (kingLocation == null) {
      return false;
    }
    MapLocation front = frontLocation(kingLocation, rc.getMapWidth(), rc.getMapHeight());
    if (!tryBiteEnemyRat(rc, robots)) {
      CatTactics.tryBite(rc, robots);
    }
    if (CatTactics.retreat(rc, robots, front)) {
      return true;
    }
    if (rc.getLocation().distanceSquaredTo(front) > 2) {
      navigation.moveToward(rc, front);
    } else if (rc.canTurn()) {
      // A 90-degree view sees little of the band; sweep it while waiting.
      rc.turn(rc.getDirection().rotateRight().rotateRight());
    }
    rc.setIndicatorString("FRONT at " + front);
    return true;
  }

  static MapLocation frontLocation(MapLocation king, int mapWidth, int mapHeight) {
    int centerX = mapWidth / 2;
    int centerY = mapHeight / 2;
    return new MapLocation(
        king.x + (centerX - king.x) * FRONT_NUMERATOR / FRONT_DENOMINATOR,
        king.y + (centerY - king.y) * FRONT_NUMERATOR / FRONT_DENOMINATOR);
  }

  /**
   * After cooperation ends, bites the weakest enemy rat in reach. Engine rule: biting an enemy
   * during cooperation is a backstab, so this never starts PvP. Without it our workers took
   * 360-440 bites from 3mice and killed none of its rats.
   */
  private boolean tryBiteEnemyRat(RobotController rc, RobotInfo[] robots)
      throws GameActionException {
    if (rc.isCooperation() || !rc.isActionReady()) {
      return false;
    }
    Team enemy = rc.getTeam().opponent();
    MapLocation target = null;
    int weakest = Integer.MAX_VALUE;
    MapLocation here = rc.getLocation();
    for (RobotInfo robot : robots) {
      if (robot.getTeam() != enemy || robot.getHealth() >= weakest) {
        continue;
      }
      UnitType type = robot.getType();
      if (type != UnitType.BABY_RAT && type != UnitType.RAT_KING) {
        continue;
      }
      // A king covers a 3x3; any adjacent part can be bitten.
      for (MapLocation part : type.getAllTypeLocations(robot.getLocation())) {
        if (here.distanceSquaredTo(part) <= GameConstants.ATTACK_DISTANCE_SQUARED
            && !here.equals(part)) {
          target = part;
          weakest = robot.getHealth();
          break;
        }
      }
    }
    if (target == null) {
      return false;
    }
    // Engine rule: a rat can bite only a tile in its vision cone, so face the target first.
    if (!rc.canAttack(target) && rc.canTurn()) {
      rc.turn(here.directionTo(target));
    }
    if (!rc.canAttack(target)) {
      return false;
    }
    rc.attack(target);
    rc.setIndicatorString("BITE_RAT at " + target);
    return true;
  }

  //function for picking up a cheeseTarget that we're right next to 
  //CHANGE: consider expanding it for also pick up enemy baby rats so we don't need 2 functions
  private boolean pickUp(RobotController rc) throws GameActionException {
    if (cheeseTarget != null && rc.canPickUpCheese(cheeseTarget)) {
      rc.pickUpCheese(cheeseTarget);
      cheeseTarget = null;
      return true;
    }
    return false;
  }

  //function for 
  private void returnCheese(RobotController rc) throws GameActionException {
    rc.setIndicatorString("RETURN raw=" + rc.getRawCheese() + " delivered=" + deliveries);
    
    //I think this code will probably never execute
    if (kingLocation == null) {
      navigation.explore(rc);
      return;
    }
    //end of pointless code

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
