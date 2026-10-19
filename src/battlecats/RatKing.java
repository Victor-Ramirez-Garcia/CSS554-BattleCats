package battlecats;

import battlecode.common.*;

/** Small stationary spawning baseline so the Baby Rat collector can run in matches. */
public class RatKing {
    private static final int strategic_reserve = 100;
   private int spawned;

    public void takeTurn(RobotController rc) throws GameActionException {
        rc.setIndicatorString("KING spawned=" + spawned);


        if (rc.getAllCheese() > rc.getCurrentRatCost() + strategic_reserve){
            MapLocation[] potentialSpawnLocations = rc.getAllLocationsWithinRadiusSquared(rc.getLocation(), 8);

            for (MapLocation loc : potentialSpawnLocations){
                if (rc.canBuildRat(loc)){
                    rc.buildRat(loc);
                    break;
                }
            }
        }
        rc.writeSharedArray(0,rc.getLocation().x);
        rc.writeSharedArray(1,rc.getLocation().y);
    }
}
