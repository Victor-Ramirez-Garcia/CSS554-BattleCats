package battlecats;

import battlecode.common.*;

/** Sprint 1 collector: discover cheese, pick it up, return, and deliver. */
public class BabyRat {
    private static final int TARGET_TIMEOUT = 80;
    private static final int RETURN_CHEESE = 80;
    private static final int MAX_FORAGE_TURNS = 60;
    private final Navigation navigation;
    private final int returnThreshold;
    private final int maxForageTurns;
    private MapLocation kingLocation;
    private MapLocation cheeseTarget;
    private int targetRound;
    private MapLocation ignoredCheese;
    private int ignoreUntilRound;
    private int firstCargoRound = -1;
    private boolean returning;
    private int deliveries;

    public BabyRat(int robotId) {
        this(robotId, RETURN_CHEESE, MAX_FORAGE_TURNS);
    }

    /** Configurable collection policy for local experiments. Threshold is a soft limit per pickup. */
    public BabyRat(int robotId, int returnThreshold, int maxForageTurns) {
        if (returnThreshold <= 0 || maxForageTurns <= 0) {
            throw new IllegalArgumentException("Collection threshold and timeout must be positive");
        }
        navigation = new Navigation(robotId);
        this.returnThreshold = returnThreshold;
        this.maxForageTurns = maxForageTurns;
    }

    public void takeTurn(RobotController rc) throws GameActionException {
        if (rc.isBeingCarried() || rc.isBeingThrown()) {
            return;
        }
        rememberKing(rc);
        int rawCheese = rc.getRawCheese();
        if (rawCheese == 0) {
            firstCargoRound = -1;
            returning = false;
        } else {
            if (firstCargoRound < 0) {
                firstCargoRound = rc.getRoundNum();
            }
            // Unload a small batch when already beside a king; no extra trip is needed.
            if (deliver(rc)) {
                return;
            }
            returning |= rawCheese >= returnThreshold
                    || rc.getRoundNum() - firstCargoRound >= maxForageTurns;
        }
        if (returning) {
            returnCheese(rc);
            return;
        }
        findCheese(rc);
        if (cheeseTarget == null) {
            rc.setIndicatorString("SEARCH raw=" + rc.getRawCheese() + " delivered=" + deliveries);
            navigation.explore(rc);
        } else {
            face(rc, cheeseTarget);
            if (!pickUp(rc)) {
                navigation.moveToward(rc, cheeseTarget);
                pickUp(rc);
            }
            rc.setIndicatorString("COLLECT raw=" + rc.getRawCheese()
                    + " delivered=" + deliveries);
        }
    }

    private void findCheese(RobotController rc) throws GameActionException {
        if (cheeseTarget != null && rc.getRoundNum() - targetRound >= TARGET_TIMEOUT) {
            ignoredCheese = cheeseTarget;
            ignoreUntilRound = rc.getRoundNum() + 40;
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
            if (firstCargoRound < 0 && rc.getRawCheese() > 0) {
                firstCargoRound = rc.getRoundNum();
            }
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
                firstCargoRound = -1;
                returning = false;
                deliveries++;
                rc.setIndicatorString("DELIVER amount=" + amount + " delivered=" + deliveries);
                System.out.println("DELIVER rat=" + rc.getID() + " cheese=" + amount);
                return true;
            }
        }
        return false;
    }

    private void rememberKing(RobotController rc) throws GameActionException {
        MapLocation shared = SharedKingLocations.nearest(rc);
        // A stopped heartbeat invalidates a cached broadcast even outside our vision.
        if (shared != null || SharedKingLocations.hasPublishedLocations(rc)) {
            kingLocation = shared;
        }
        if (kingLocation != null && rc.canSenseLocation(kingLocation)) {
            RobotInfo king = rc.senseRobotAtLocation(kingLocation);
            if (king == null || king.getTeam() != rc.getTeam() || king.getType() != UnitType.RAT_KING) {
                kingLocation = null;
            } else {
                kingLocation = king.getLocation();
            }
        }
        int nearest = kingLocation == null ? Integer.MAX_VALUE : rc.getLocation().distanceSquaredTo(kingLocation);
        for (RobotInfo robot : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (robot.getType() == UnitType.RAT_KING) {
                int distance = rc.getLocation().distanceSquaredTo(robot.getLocation());
                if (distance <= nearest) {
                    kingLocation = robot.getLocation();
                    nearest = distance;
                }
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
