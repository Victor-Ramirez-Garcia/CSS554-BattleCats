package battlecats;

import battlecode.common.*;

/** Team entry point. Each robot receives its own controller and behavior state. */
public class RobotPlayer {
    public static void run(RobotController rc) {
        BabyRat baby = rc.getType() == UnitType.BABY_RAT ? new BabyRat(rc.getID()) : null;
        RatKing king = null;
        while (true) {
            try {
                // A Baby Rat that calls becomeRatKing keeps this controller and loop, so the
                // current type, not the type at creation, decides the behavior each turn.
                if (rc.getType() == UnitType.RAT_KING) {
                    if (king == null) {
                        king = new RatKing();
                    }
                    king.takeTurn(rc);
                } else if (baby != null) {
                    baby.takeTurn(rc);
                }
            } catch (GameActionException e) {
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }
}
