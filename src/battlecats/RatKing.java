package battlecats;

import battlecode.common.*;

/** Small stationary spawning baseline so the Baby Rat collector can run in matches. */
public class RatKing {
    private static final int SPAWN_LIMIT = 4;
    private int spawned;

    public void takeTurn(RobotController rc) throws GameActionException {
        rc.setIndicatorString("KING spawned=" + spawned);
        // Keep the king at its spawn location so new collectors can learn their home.
        // A later king strategy can replace this baseline independently of BabyRat.
        if (spawned >= SPAWN_LIMIT || rc.getAllCheese() < rc.getCurrentRatCost() + 100) {
            return;
        }
        for (MapLocation location : rc.getAllLocationsWithinRadiusSquared(rc.getLocation(), 8)) {
            if (rc.canBuildRat(location)) {
                rc.buildRat(location);
                spawned++;
                break;
            }
        }
    }
}
