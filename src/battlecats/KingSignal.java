package battlecats;

import battlecode.common.GameActionException;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.UnitType;

/**
 * Fresh home coordinates and promotion funding from one king. With two kings, only the king that
 * owns the signal publishes, so every rat is told the same home.
 */
public final class KingSignal {

  //PUBLIC VARS


  //PRIVATE VARS
  private static final int X_SLOT = 0;
  private static final int Y_SLOT = 1;
  private static final int STAMP_SLOT = 2;    //A validation var, for if something tries to read the sharred array
                                                //after RK was cut off for using too much bytecode
  // Experimental freshness window: accept the previous king turn plus timestamp quantization.
  private static final int MAX_AGE_ROUNDS = 3;
  // Set by the first promoted king and never cleared, so a team forms at most one extra king
  // per game even if that king later dies.
  private static final int PROMOTION_SLOT = 3;
  // PROMOTION_SLOT value written by a late promotion; 1 marks an earlier, income-funded one.
  private static final int LATE_PROMOTION = 2;
  // Two-round stamp, as in STAMP_SLOT, while a king reports income that could feed another king.
  // Zero means not funded; a stale stamp means no king has confirmed it recently.
  private static final int FUNDING_SLOT = 4;
  // Late-game rally centre for a second king, stored as x + 1 and y + 1 so zero means none.
  private static final int RALLY_X_SLOT = 5;
  private static final int RALLY_Y_SLOT = 6;


  //PUBLIC METHODS
  /**
   * Returns whether some king published the home within the freshness window. A king that does
   * not own the signal takes it over only when this is false, for example after the owner died.
   */
  public static boolean homeIsFresh(RobotController rc) throws GameActionException {
    return isFresh(rc.getRoundNum(), rc.readSharedArray(STAMP_SLOT));
  }

  /** Publishes after king movement. Engine 1.2.6 only permits kings to write shared memory. */
  public static void publish(RobotController rc) throws GameActionException {
    // A bytecode pause between coordinate writes must not expose a mixed home as fresh.
    rc.writeSharedArray(STAMP_SLOT, 0);
    rc.writeSharedArray(X_SLOT, rc.getLocation().x);
    rc.writeSharedArray(Y_SLOT, rc.getLocation().y);
    // Shared values are at most 1023. Two-round buckets fit all 2000 game rounds;
    // zero remains an unpublished marker, including when the king stands at (0,0).
    rc.writeSharedArray(STAMP_SLOT, rc.getRoundNum() / 2 + 1);
  }

  /** Returns a fresh, valid home, or null. A visible tile takes precedence over the broadcast. */
  public static MapLocation read(RobotController rc) throws GameActionException {
    int round = rc.getRoundNum();
    int stamp = rc.readSharedArray(STAMP_SLOT);
    //make sure it's a valid write and the bytecode hasn't been interrupted or cut off
    if (stamp == 0) {
      return null;
    }
    
    if (!isFresh(round, stamp)) {
      return null;
    }
    int x = rc.readSharedArray(X_SLOT);
    int y = rc.readSharedArray(Y_SLOT);
    if (x >= rc.getMapWidth() || y >= rc.getMapHeight()) {
      return null;
    }
    MapLocation location = new MapLocation(x, y);
    if (rc.canSenseLocation(location)) {
      RobotInfo robot = rc.senseRobotAtLocation(location);
      location = robot != null && robot.getTeam() == rc.getTeam()
              && robot.getType() == UnitType.RAT_KING
          ? robot.getLocation() : null;
    }
    // A paused reader can resume after the king starts another publication. Discard that read.
    return rc.getRoundNum() == round ? location : null;
  }

  
  /**
   * Records that this team has promoted a Baby Rat. Call it right after becomeRatKing: the robot
   * is a king from that moment, which the engine requires for shared-memory writes.
   */
  public static void recordPromotion(RobotController rc) throws GameActionException {
    rc.writeSharedArray(PROMOTION_SLOT, 1);
  }

  /** Publishes whether this king's recent income could feed another king. Kings only. */
  public static void publishPromotionFunding(RobotController rc, boolean funded)
      throws GameActionException {
    rc.writeSharedArray(FUNDING_SLOT, funded ? rc.getRoundNum() / 2 + 1 : 0);
  }

  /** Returns whether a king recently reported income that could feed another king. */
  public static boolean promotionFunded(RobotController rc) throws GameActionException {
    return isFresh(rc.getRoundNum(), rc.readSharedArray(FUNDING_SLOT));
  }

  /** Publishes the centre of a passable 3x3 where workers gather to form a late king. */
  public static void publishRally(RobotController rc, MapLocation centre) throws GameActionException {
    rc.writeSharedArray(RALLY_X_SLOT, centre == null ? 0 : centre.x + 1);
    rc.writeSharedArray(RALLY_Y_SLOT, centre == null ? 0 : centre.y + 1);
  }

  /** Returns the published rally centre, or null when no king has chosen one. */
  public static MapLocation readRally(RobotController rc) throws GameActionException {
    int x = rc.readSharedArray(RALLY_X_SLOT);
    int y = rc.readSharedArray(RALLY_Y_SLOT);
    return x == 0 || y == 0 ? null : new MapLocation(x - 1, y - 1);
  }

  /** Records a promotion in the late window; workers then stop gathering at the king. */
  public static void recordLatePromotion(RobotController rc) throws GameActionException {
    rc.writeSharedArray(PROMOTION_SLOT, LATE_PROMOTION);
  }

  /** Returns whether a Baby Rat became a king during the late promotion window. */
  public static boolean latePromotionRecorded(RobotController rc) throws GameActionException {
    return rc.readSharedArray(PROMOTION_SLOT) == LATE_PROMOTION;
  }

  /** Returns whether any Baby Rat on this team has already become a Rat King this game. */
  public static boolean promotionRecorded(RobotController rc) throws GameActionException {
    return rc.readSharedArray(PROMOTION_SLOT) != 0;
  }


  //PRIVATE METHODS
  private static boolean isFresh(int round, int stamp) {
    if (stamp == 0) {
      return false;
    }
    int age = round - 2 * (stamp - 1);
    return age >= 0 && age <= MAX_AGE_ROUNDS;
  }

  private KingSignal() {}
}
