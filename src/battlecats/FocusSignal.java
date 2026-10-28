package battlecats;

import battlecode.common.GameActionException;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.Team;
import battlecode.common.UnitType;

/** One fresh, king-selected hostile ID. Workers still require their own local attack opportunity. */
public final class FocusSignal {
  // Slots 0..2 belong to KingSignal. Each shared word holds only ten bits.
  private static final int ID_SLOT = 3;
  private static final int STAMP_SLOT = 7;
  // Experimental: cover one king turn plus two-round timestamp quantization, never chase memory.
  private static final int MAX_AGE_ROUNDS = 3;

  private FocusSignal() {}

  /** King-only publication; returns the chosen ID, or -1 when no hostile was observed. */
  public static int publish(RobotController rc, RobotInfo[] observations)
      throws GameActionException {
    RobotInfo target = null;
    Team enemy = rc.getTeam().opponent();
    if (!rc.isCooperation()) {
      for (RobotInfo robot : observations) {
        if (robot.getTeam() == enemy
            && (robot.getType() == UnitType.BABY_RAT || robot.getType() == UnitType.RAT_KING)
            && (target == null || robot.getHealth() < target.getHealth()
                || (robot.getHealth() == target.getHealth() && robot.getID() < target.getID()))) {
          target = robot;
        }
      }
    }
    // An interrupted publication must not leave a valid stamp on partially updated data.
    rc.writeSharedArray(STAMP_SLOT, 0);
    if (target == null) {
      return -1;
    }
    int id = target.getID();
    // IDs are positive ints, not bounded to two shared words by the engine's ID generator.
    for (int limb = 0; limb < 4; limb++) {
      rc.writeSharedArray(ID_SLOT + limb, (id >>> (10 * limb)) & 1023);
    }
    rc.writeSharedArray(STAMP_SLOT, rc.getRoundNum() / 2 + 1);
    return id;
  }

  /** Returns a fresh ID or -1. This does not authorize attacking an unseen or friendly unit. */
  public static int read(RobotController rc) throws GameActionException {
    if (rc.isCooperation()) {
      return -1;
    }
    int stamp = rc.readSharedArray(STAMP_SLOT);
    int age = rc.getRoundNum() - 2 * (stamp - 1);
    if (stamp == 0 || age < 0 || age > MAX_AGE_ROUNDS) {
      return -1;
    }
    int high = rc.readSharedArray(ID_SLOT + 3);
    if (high > 1) {
      return -1;
    }
    int id = rc.readSharedArray(ID_SLOT)
        | (rc.readSharedArray(ID_SLOT + 1) << 10)
        | (rc.readSharedArray(ID_SLOT + 2) << 20)
        | (high << 30);
    return id > 0 ? id : -1;
  }
}
