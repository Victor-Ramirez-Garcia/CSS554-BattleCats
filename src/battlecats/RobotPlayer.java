package battlecats;

import battlecode.common.*;

/** Team entry point. Each robot receives its own controller and behavior state. */
public class RobotPlayer {
    public static void run(RobotController rc) {
        BabyRat baby = rc.getType() == UnitType.BABY_RAT ? new BabyRat(rc.getID()) : null;
        RatKing king = rc.getType() == UnitType.RAT_KING ? new RatKing() : null;
        while (true) {
            try {
                if (baby != null) {
                    baby.takeTurn(rc);
                } else if (king != null) {
                    king.takeTurn(rc);
                }
            } catch (GameActionException e) {
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }
}
