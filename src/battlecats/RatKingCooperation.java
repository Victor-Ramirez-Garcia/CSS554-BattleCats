package battlecats;

import battlecode.common.GameActionException;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.Team;
import battlecode.common.UnitType;

/** Cooperation-mode actions for a rat king. */
final class RatKingCooperation {
    private RatKingCooperation() {}

    static boolean isCooperationMode(RobotController rc) throws GameActionException {
        for (RobotInfo robot : rc.senseNearbyRobots()) {
            if (robot.getType() == UnitType.CAT && robot.getTeam() == Team.NEUTRAL) {
                return true;
            }
        }
        return false;
    }

    static void attackCatsInRange(RobotController rc) throws GameActionException {
        for (RobotInfo robot : rc.senseNearbyRobots()) {
            if (robot.getType() == UnitType.CAT && rc.canAttack(robot.getLocation())) {
                rc.attack(robot.getLocation());
                return;
            }
        }
    }
}
