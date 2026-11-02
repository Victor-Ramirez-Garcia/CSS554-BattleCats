package battlecats;

import battlecode.common.GameActionException;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.UnitType;

/** Backstabbing-mode target selection for a rat king. */
final class RatKingBackstabbing {
    private RatKingBackstabbing() {}

    static boolean isBackstabbingMode(RobotController rc) throws GameActionException {
        for (RobotInfo robot : rc.senseNearbyRobots()) {
            if (robot.getType() == UnitType.BABY_RAT && robot.getTeam() != rc.getTeam()) {
                return true;
            }
        }
        return false;
    }

    static void attackNearestEnemy(RobotController rc) throws GameActionException {
        RobotInfo target = null;
        for (RobotInfo robot : rc.senseNearbyRobots()) {
            if (!isEnemy(robot, rc) || !rc.canAttack(robot.getLocation())) {
                continue;
            }
            if (target == null || isPreferred(robot, target, rc)) {
                target = robot;
            }
        }
        if (target != null) {
            rc.attack(target.getLocation());
        }
    }

    private static boolean isEnemy(RobotInfo robot, RobotController rc) {
        return robot.getType() == UnitType.BABY_RAT && robot.getTeam() != rc.getTeam()
                || robot.getType() == UnitType.CAT;
    }

    private static boolean isPreferred(RobotInfo candidate, RobotInfo current, RobotController rc) {
        int candidateDistance = rc.getLocation().distanceSquaredTo(candidate.getLocation());
        int currentDistance = rc.getLocation().distanceSquaredTo(current.getLocation());
        if (candidateDistance != currentDistance) {
            return candidateDistance < currentDistance;
        }
        if (candidate.getType() != current.getType()) {
            return candidate.getType() == UnitType.CAT;
        }
        return candidate.getID() < current.getID();
    }
}
