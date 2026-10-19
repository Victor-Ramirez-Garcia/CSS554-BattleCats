package battlecats;

import battlecode.common.*;

/** Stationary spawning baseline; movement strategy can be changed independently. */
public class RatKing {
    private static final int MIN_CHEESE_RESERVE = 100;
    // Spend a little starting cheese on workers; fund later growth from deliveries.
    // This is a spending budget, not a lifetime or living baby population limit.
    private static final int STARTUP_SPAWN_BUDGET = 40;

    public void takeTurn(RobotController rc) throws GameActionException {
        SharedKingLocations.publish(rc);
        rc.setIndicatorString("KING ratCost=" + rc.getCurrentRatCost());
        // Preserve the starting food stock projected after one king's upkeep.
        // Extra kings make this estimate conservative because they eat more.
        int reserve = Math.max(MIN_CHEESE_RESERVE, GameConstants.INITIAL_TEAM_CHEESE
                - STARTUP_SPAWN_BUDGET - rc.getRoundNum() * GameConstants.RAT_KING_CHEESE_CONSUMPTION);
        if (rc.getGlobalCheese() < rc.getCurrentRatCost() + reserve) {
            return;
        }
        for (MapLocation location : rc.getAllLocationsWithinRadiusSquared(rc.getLocation(), 8)) {
            if (rc.canBuildRat(location)) {
                rc.buildRat(location);
                break;
            }
        }
    }
}
