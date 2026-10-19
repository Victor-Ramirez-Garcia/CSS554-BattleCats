package testfuncsplayer;

import battlecode.common.*;
import battlecode.world.control.RobotControlProvider;

import java.lang.constant.DirectMethodHandleDesc;
import java.util.Map;
import java.util.Random;

/**
 * RobotPlayer is the class that describes your main robot strategy.
 * The run() method inside this class is like your main function: this is what we'll call once your robot
 * is created!
 */
public class RobotPlayer {
    /**
     * We will use this variable to count the number of turns this robot has been alive.
     * You can use static variables like this to save any information you want. Keep in mind that even though
     * these variables are static, in Battlecode they aren't actually shared between your robots.
     */
    static int turnCount = 0;

    /**
     * A random number generator.
     * We will use this RNG to make some random moves. The Random class is provided by the java.util.Random
     * import at the top of this file. Here, we *seed* the RNG with a constant number (6147); this makes sure
     * we get the same sequence of numbers every time this code is run. This is very useful for debugging!
     */
    static final Random rand = new Random(6147);

    /** Array containing all the possible movement directions. */
    static final Direction[] directions = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST,
    };

    public static enum State {
        INITIALIZE,
        FIND_CHEESE,
        RETURN_TO_KING,
        EXPLORE_AND_ATTACK,
        //BUILD_TRAPS
    }
    public static State currentState = State.INITIALIZE;
    public static int numRatsSpawned = 0;
    public static int turnsSinceCarry = 0;

    /**
     * run() is the method that is called when a robot is instantiated in the Battlecode world.
     * It is like the main function for your robot. If this method returns, the robot dies!
     *
     * @param rc  The RobotController object. You use it to perform actions from this robot, and to get
     *            information on its current status. Essentially your portal to interacting with the world.
     **/
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        System.out.println("I'm alive");

        rc.setIndicatorString("Hello world!");

        while (true) {
             turnCount += 1;  // We have now been alive for one more turn!

             try {
                //Rat King
                if(rc.getType().isRatKingType()){
                    runRatKing(rc);
                } 
                //Baby Rat
                else {
                    turnsSinceCarry++;

                    switch (currentState){
                        case INITIALIZE:
                            if (rc.getRoundNum() < 30 || rc.getCurrentRatCost() <= 10){
                                currentState = State.FIND_CHEESE;
                            } else {
                                currentState = State.EXPLORE_AND_ATTACK;
                            }
                            //what if it's after turn 30 or RatCost is above 10?
                            break;
                        case FIND_CHEESE:
//                            runFindCheese(rc);
                            break;

                        case RETURN_TO_KING:
//                            runReturnToKing(rc);
                            break;
                        case EXPLORE_AND_ATTACK:
//                            runExploreAndAttack(rc);
                            break;

                    }
                }
                // The same run() function is called for every robot on your team, even if they are
                // different types. Here, we separate the control depending on the UnitType, so we can
                // use different strategies on different robots. If you wish, you are free to rewrite
                // this into a different control structure!

                // Every 10 turns, print out what type of robot we are.
                if (turnCount % 100 == 0) {
                    System.out.println("Turn " + turnCount + ": I am a " + rc.getType().toString());
                }

                // Try to move forward one step.
                if (rc.canMoveForward()) {
                    System.out.println("Turn " + turnCount + ": Trying to move " + rc.getDirection());
                    rc.moveForward();
                } else {
                    System.out.println("Couldn't move forward on turn " + turnCount + " at location " + rc.getLocation() + " facing " + rc.getDirection());
                    // If we can't move forward, try to turn a random direction.
                    int randomDirection = rand.nextInt(8);
                    Direction dir = directions[randomDirection];

                    if (rc.canTurn(dir)) {
                        rc.turn(dir);
                    }
                }
            } catch (GameActionException e) {
                // Oh no! It looks like we did something illegal in the Battlecode world. You should
                // handle GameActionExceptions judiciously, in case unexpected events occur in the game
                // world. Remember, uncaught exceptions cause your robot to explode!
                System.out.println("GameActionException");
                e.printStackTrace();
            } catch (Exception e) {
                // Oh no! It looks like our code tried to do something bad. This isn't a
                // GameActionException, so it's more likely to be a bug in our code.
                System.out.println("Exception");
                e.printStackTrace();
            } finally {
                // Signify we've done everything we want to do, thereby ending our turn.
                // This will make our code wait until the next turn, and then perform this loop again.
                Clock.yield();
            }
            // End of loop: go back to the top. Clock.yield() has ended, so it's time for another turn!
        }

        // Your code should never reach here (unless it's intentional)! Self-destruction imminent...
    }

    public static void moveRandom(RobotController rc) throws GameActionException {
        MapLocation forwardLoc = rc.adjacentLocation(rc.getDirection());
        if(rc.canMoveForward()){
            rc.moveForward();
        } else{
            Direction random = directions[rand.nextInt(directions.length)];
            if (rc.canTurn()){
                rc.turn(random);
            }
        }
    }

    public static void runRatKing(RobotController rc) throws GameActionException{
        int currentCost = rc.getCurrentRatCost();

        if (currentCost <= 10 || rc.getAllCheese() > currentCost + 2500){
            MapLocation[] potentialSpawnLocations = rc.getAllLocationsWithinRadiusSquared(rc.getLocation(), 8);

            for (MapLocation loc : potentialSpawnLocations){
                if (rc.canBuildRat(loc)){
                    rc.buildRat(loc);
                    break;
                }
            }
        }
        moveRandom(rc);
        rc.writeSharedArray(0,rc.getLocation().x);
        rc.writeSharedArray(1,rc.getLocation().y);
        
    }

    public static void runFindCheese(RobotController rc) throws GameActionException{
        MapInfo[] nearbyInfos = rc.senseNearbyMapInfos();

        for(MapInfo info : nearbyInfos){
            if (info.getCheeseAmount() > 0){
                Direction toCheese = rc.getLocation().directionTo(info.getMapLocation());

                if (rc.canTurn()){
                    rc.turn(toCheese);
                    break;
                }
            }
        }
        for (Direction dir : directions){
            MapLocation loc = rc.getLocation().add(dir);

            if(rc.canPickUpCheese(loc)){
                rc.pickUpCheese(loc);
                
                if (rc.getRawCheese() >= 10) {
                    currentState = State.RETURN_TO_KING;
                }
            }
        }
    }

    public static void runReturnToKing(RobotController rc) throws GameActionException{
        MapLocation kingLoc = new MapLocation(rc.readSharedArray(0), rc.readSharedArray(1));
        Direction toKing = rc.getLocation().directionTo(kingLoc);
        MapLocation nextLoc = rc.getLocation().add(toKing);

        if (rc.canTurn()){
            rc.turn(toKing);
        }
        if (rc.canMove(toKing)){
            rc.move(toKing);
        }
        int rawCheese = rc.getRawCheese();
        if (rawCheese == 0){
            currentState = State.FIND_CHEESE;
        }
        if (rc.canSenseLocation(kingLoc)){
            RobotInfo[] kingLocations = rc.senseNearbyRobots(kingLoc, 8, rc.getTeam());

            for(RobotInfo robotInfo : kingLocations){
                if (robotInfo.getType().isRatKingType()){
                    MapLocation actualKingLoc = robotInfo.getLocation();

                    if (rc.canTransferCheese(actualKingLoc, rawCheese)){
                        rc.transferCheese(actualKingLoc, rawCheese);
                        currentState = State.FIND_CHEESE;
                    }
                    break;
                }
            }
        }
    }

}
