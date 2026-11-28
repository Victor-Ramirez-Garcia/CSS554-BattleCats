package battlecats;

import battlecode.common.Clock;
import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.GameConstants;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.Team;
import battlecode.common.UnitType;

/**
 * One-turn fighting against enemy Baby Rats: chooses a move, a facing and a bite together.
 *
 * <p>A position is threatened when a visible enemy rat that sees it could step next to it and
 * bite, unless we would win that trade: the enemy has no more health than we do and is in our
 * view. The planner prefers bites from unthreatened positions, then approaching without
 * standing in a threat. Cats and kings are left to the existing tactics. Modelled on the Sprint
 * 2 champion's rat micro, which beat us 32-0 while we died to bites from outside our view.
 *
 * <p>Accepted risk, kept from the champion and covered by the measured results: an enemy counts
 * only if its current facing sees the tile. Turning and moving have separate cooldowns, so an
 * adjacent enemy facing away can still turn and bite this turn. Counting every enemy within one
 * tile as well is untested and would make most close fights look threatened.
 *
 * <p>{@link #plan} is pure and leaves its choice in the public fields; {@link #act} reads the
 * robot controller, plans and performs the choice.
 */
public final class CombatMicro {

  // Engine rules: Baby Rat vision is a 90-degree cone of radius squared 20 (see sees), and a
  // bite reaches an adjacent tile in that cone.
  private static final int VISION_RADIUS_SQUARED = UnitType.BABY_RAT.visionConeRadiusSquared;
  private static final int BITE_DAMAGE = GameConstants.RAT_BITE_DAMAGE;
  // Bytecode bounds, measured on thunderdome and knifefight against the champion: scoring every
  // reachable target with four facings cost up to 16,000 of the 17,500 limit. Threats come from
  // the nearest four enemies, bites go to the nearest two in reach, and an approach keeps its
  // facing or turns toward the target.
  static final int MAX_ENEMIES = 4;
  private static final int MAX_BITE_TARGETS = 2;
  private static final int APPROACH_FACINGS = 2;
  // Bytecode bound: the search stops with the best option so far when fewer than this remain,
  // leaving room to act and for the rest of the turn.
  static final int MIN_BYTECODES_LEFT = 4000;
  private static final int MAX_HEALTH = UnitType.BABY_RAT.health;

  // Experimental weights, after the champion's and not tuned further:
  // a threatened position outweighs any bite or approach;
  private static final int THREAT_PENALTY = 100;
  // a kill is worth three ordinary bites, since it removes a biter for good;
  private static final int BITE_VALUE = 10;
  private static final int KILL_BONUS = 30;
  // each 5 health a target is missing adds 1, so between equal bites the weaker target wins
  // (at most 20, below a kill);
  private static final int MISSING_HEALTH_PER_POINT = 5;
  // closing in outweighs keeping the target in view, so an approach does not stall to look;
  private static final int APPROACH_VALUE = 30;
  private static final int UNSEEN_TARGET_PENALTY = 5;
  // an 18-cooldown strafe costs a little more than a 10-cooldown forward move.
  private static final int STRAFE_PENALTY = 3;

  // Engine order: the eight compass directions, then CENTER; index s below is this order.
  private static final Direction[] ALL_DIRECTIONS = Direction.allDirections();

  /** Remaining bytecode this turn; the engine's Clock in play, unlimited in tests. */
  public interface BytecodeClock {
    int left();
  }

  private static final class EngineClock implements BytecodeClock {
    @Override
    public int left() {
      return Clock.getBytecodesLeft();
    }
  }

  private static final BytecodeClock ENGINE_CLOCK = new EngineClock();

  /** Chosen move, or CENTER to stay. */
  public Direction move;
  /** Facing at the end of the turn. */
  public Direction facing;
  /** Enemy to bite, or null. */
  public MapLocation biteTarget;
  /** True to bite before moving, false to bite after. */
  public boolean biteFirst;
  /** Score of the choice. */
  public int score;
  /** Enemies threatening the chosen end position. */
  public int threatCount;
  /**
   * Set by {@link #act} when it saw enemy rats but declined to approach them because every
   * approach was threatened; the caller should not walk in either.
   */
  public boolean declined;

  private final RobotInfo[] enemies = new RobotInfo[MAX_ENEMIES];
  private final boolean[] canMove = new boolean[9];
  // Per-turn scratch from findReach, reused to avoid allocating in turn code.
  private final MapLocation[] enemyAt = new MapLocation[MAX_ENEMIES];
  private final MapLocation[] nextAt = new MapLocation[9];
  private final int[] strongReach = new int[9];
  private final int[] weakReach = new int[9];
  // Threat count per end tile and facing (index s * 8 + facing), -1 until computed.
  private final int[] riskCache = new int[72];
  private BytecodeClock clock;

  /**
   * Plans and performs this turn's fight. Returns false, having done nothing, when no enemy Baby
   * Rat is visible, when the bytecode ran out before any option was scored, or when no safe bite
   * exists and either {@code retreatUnlessBiting} is set or every approach is threatened;
   * {@link #declined} then tells the caller not to approach.
   */
  public boolean act(RobotController rc, RobotInfo[] robots, boolean retreatUnlessBiting)
      throws GameActionException {
    return act(rc, robots, retreatUnlessBiting, ENGINE_CLOCK);
  }

  /** {@link #act} with an explicit clock; outside the engine Clock reports no bytecode left. */
  boolean act(RobotController rc, RobotInfo[] robots, boolean retreatUnlessBiting,
      BytecodeClock clock) throws GameActionException {
    declined = false;
    MapLocation here = rc.getLocation();
    int count = nearestEnemyRats(rc.getTeam(), here, robots, enemies);
    if (count == 0) {
      return false;
    }
    for (Direction direction : ALL_DIRECTIONS) {
      canMove[direction.ordinal()] = direction == Direction.CENTER || rc.canMove(direction);
    }
    plan(here, rc.getDirection(), rc.getHealth(), rc.isMovementReady(), rc.canTurn(),
        rc.isActionReady(), canMove, enemies, count, clock);
    if (score == Integer.MIN_VALUE) {
      // Not declined: the caller's older tactics run, as in the measured version (one turn in
      // 773 on the bytecode-heaviest maps).
      return false;
    }
    if (biteTarget == null && (retreatUnlessBiting || threatCount > 0)) {
      declined = threatCount > 0;
      return false;
    }
    // Guarded like the bite after moving, in case the observations passed in are stale.
    if (biteTarget != null && biteFirst && rc.canAttack(biteTarget)) {
      rc.attack(biteTarget);
    }
    perform(rc, move, facing);
    if (biteTarget != null && !biteFirst && rc.canAttack(biteTarget)) {
      rc.attack(biteTarget);
    }
    rc.setIndicatorString("FIGHT micro move=" + move + " face=" + facing + " bite=" + biteTarget
        + " score=" + score);
    return true;
  }

  /**
   * Chooses the best bite, else the best approach to the nearest enemy. {@code canMove} is
   * indexed by Direction ordinal, CENTER included; {@code enemies} holds {@code count} enemy rats,
   * nearest first. When the clock runs low the best option so far stands; score stays
   * Integer.MIN_VALUE if there was none.
   */
  public void plan(MapLocation here, Direction currentFacing, int health, boolean moveReady,
      boolean turnReady, boolean actionReady, boolean[] canMove, RobotInfo[] enemies,
      int count, BytecodeClock clock) {
    // Rats always face a compass direction; CENTER would break the riskCache index and sees.
    assert currentFacing != Direction.CENTER;
    this.clock = clock;
    move = Direction.CENTER;
    facing = currentFacing;
    biteTarget = null;
    biteFirst = false;
    score = Integer.MIN_VALUE;
    threatCount = Integer.MAX_VALUE;
    findReach(here, health, moveReady, canMove, enemies, count);
    if (actionReady) {
      int targets = 0;
      for (int i = 0; i < count && targets < MAX_BITE_TARGETS; i++) {
        if (considerBites(here, currentFacing, turnReady, enemies[i], i)) {
          targets++;
        }
      }
      // As in the champion, a bite is taken only from a position no enemy threatens.
      if (biteTarget != null && threatCount == 0) {
        return;
      }
      biteTarget = null;
      score = Integer.MIN_VALUE;
      threatCount = Integer.MAX_VALUE;
    }
    MapLocation target = enemyAt[0];
    int distanceNow = here.distanceSquaredTo(target);
    for (int s = 0; s < 9; s++) {
      MapLocation next = nextAt[s];
      if (next == null) {
        continue;
      }
      if (clock.left() < MIN_BYTECODES_LEFT) {
        return;
      }
      Direction step = ALL_DIRECTIONS[s];
      Direction toward = towards(next, target);
      for (int f = 0; f < APPROACH_FACINGS; f++) {
        Direction face = candidateFacing(f, currentFacing, toward);
        if (face == null || face != currentFacing && !turnReady) {
          continue;
        }
        int risks = risks(s, face, 0);
        int value = -THREAT_PENALTY * risks;
        if (next.distanceSquaredTo(target) < distanceNow) {
          value += APPROACH_VALUE;
        }
        if (!sees(next, face, target)) {
          value -= UNSEEN_TARGET_PENALTY;
        }
        value -= strafeCost(step, face, currentFacing);
        if (value > score) {
          score = value;
          threatCount = risks;
          move = step;
          facing = face;
        }
      }
    }
  }

  /**
   * Records each reachable end tile and, per tile, the enemies that see it and could step next to
   * it, split into those with more health than ours and the rest. Facing does not change this,
   * so it is computed once per move rather than per facing.
   */
  private void findReach(MapLocation here, int health, boolean moveReady, boolean[] canMove,
      RobotInfo[] enemies, int count) {
    for (int i = 0; i < count; i++) {
      enemyAt[i] = enemies[i].getLocation();
    }
    for (int s = 0; s < 9; s++) {
      Direction step = ALL_DIRECTIONS[s];
      if (step != Direction.CENTER && !(moveReady && canMove[step.ordinal()])) {
        nextAt[s] = null;
        continue;
      }
      MapLocation next = here.add(step);
      nextAt[s] = next;
      for (int f = 0; f < 8; f++) {
        riskCache[s * 8 + f] = -1;
      }
      int strong = 0;
      int weak = 0;
      for (int i = 0; i < count; i++) {
        MapLocation at = enemyAt[i];
        int dx = at.x - next.x;
        int dy = at.y - next.y;
        if (dx <= 2 && dx >= -2 && dy <= 2 && dy >= -2
            && sees(at, enemies[i].getDirection(), next)) {
          if (enemies[i].getHealth() > health) {
            strong |= 1 << i;
          } else {
            weak |= 1 << i;
          }
        }
      }
      strongReach[s] = strong;
      weakReach[s] = weak;
    }
  }

  /**
   * Counts enemies, other than those in {@code ignored}, that could reach end tile {@code s} and
   * win the trade: more health than ours, or outside our view facing {@code face}.
   */
  private int risks(int s, Direction face, int ignored) {
    int key = s * 8 + face.ordinal();
    if (ignored == 0 && riskCache[key] >= 0) {
      return riskCache[key];
    }
    int risks = Integer.bitCount(strongReach[s] & ~ignored);
    int weak = weakReach[s] & ~ignored;
    for (int i = 0; weak != 0; i++, weak >>= 1) {
      if ((weak & 1) != 0 && !sees(nextAt[s], face, enemyAt[i])) {
        risks++;
      }
    }
    if (ignored == 0) {
      riskCache[key] = risks;
    }
    return risks;
  }

  /** Scores bites of one enemy; returns false when it is beyond reach this turn. */
  private boolean considerBites(MapLocation here, Direction currentFacing, boolean turnReady,
      RobotInfo target, int index) {
    MapLocation at = enemyAt[index];
    // One move then a bite of an adjacent tile cannot reach farther than two tiles.
    int dx = at.x - here.x;
    int dy = at.y - here.y;
    if (dx > 2 || dx < -2 || dy > 2 || dy < -2) {
      return false;
    }
    boolean kills = BITE_DAMAGE >= target.getHealth();
    int targetValue = BITE_VALUE + (kills ? KILL_BONUS : 0)
        + (MAX_HEALTH - target.getHealth()) / MISSING_HEALTH_PER_POINT;
    int ignored = kills ? 1 << index : 0;
    boolean biteNow = here.distanceSquaredTo(at) <= 2 && sees(here, currentFacing, at);
    for (int s = 0; s < 9; s++) {
      MapLocation next = nextAt[s];
      if (next == null) {
        continue;
      }
      boolean biteAfter = next.distanceSquaredTo(at) <= 2;
      if (!biteNow && !biteAfter) {
        continue;
      }
      if (clock.left() < MIN_BYTECODES_LEFT) {
        return true;
      }
      Direction step = ALL_DIRECTIONS[s];
      Direction toward = towards(next, at);
      for (int f = 0; f < 4; f++) {
        Direction face = candidateFacing(f, currentFacing, toward);
        if (face == null || face != currentFacing && !turnReady) {
          continue;
        }
        boolean after = biteAfter && sees(next, face, at);
        if (!biteNow && !after) {
          continue;
        }
        int risks = risks(s, face, ignored);
        int value = targetValue - THREAT_PENALTY * risks - strafeCost(step, face, currentFacing);
        if (value > score) {
          score = value;
          threatCount = risks;
          move = step;
          facing = face;
          biteTarget = at;
          biteFirst = !after;
        }
      }
    }
    return true;
  }

  /**
   * Whether a Baby Rat at {@code from} facing {@code face} sees {@code target}. Integer form of
   * the engine's 90-degree cone, which matches MapLocation.isWithinDistanceSquared for every
   * offset and facing; the engine's angle arithmetic made a turn's micro cost up to 16,000
   * bytecode of the 17,500 limit.
   */
  static boolean sees(MapLocation from, Direction face, MapLocation target) {
    int dx = target.x - from.x;
    int dy = target.y - from.y;
    if (dx * dx + dy * dy > VISION_RADIUS_SQUARED) {
      return false;
    }
    int fx = face.dx;
    int fy = face.dy;
    if (fx == 0 || fy == 0) {
      int across = dx * fy - dy * fx;
      return dx * fx + dy * fy >= (across < 0 ? -across : across);
    }
    return dx * fx >= 0 && dy * fy >= 0;
  }

  /**
   * Same result as MapLocation.directionTo within vision range, without its floating point:
   * a 2:1 slope splits the octants, where the engine uses 2.414:1, and the two agree on every
   * offset up to six tiles. Plain integer code keeps the planner within the bytecode budget.
   */
  static Direction towards(MapLocation from, MapLocation to) {
    int dx = to.x - from.x;
    int dy = to.y - from.y;
    if (dx == 0 && dy == 0) {
      return Direction.CENTER;
    }
    int ax = dx < 0 ? -dx : dx;
    int ay = dy < 0 ? -dy : dy;
    if (ax > 2 * ay) {
      return dx > 0 ? Direction.EAST : Direction.WEST;
    }
    if (ay > 2 * ax) {
      return dy > 0 ? Direction.NORTH : Direction.SOUTH;
    }
    if (dx > 0) {
      return dy > 0 ? Direction.NORTHEAST : Direction.SOUTHEAST;
    }
    return dy > 0 ? Direction.NORTHWEST : Direction.SOUTHWEST;
  }

  /** The current facing, then toward the target and 45 degrees either side; null for repeats. */
  private static Direction candidateFacing(int index, Direction current, Direction toward) {
    if (toward == Direction.CENTER) {
      return index == 0 ? current : null;
    }
    Direction face = switch (index) {
      case 0 -> current;
      case 1 -> toward;
      // Engine rotateLeft copies Direction.values() on every call; index the cached array.
      case 2 -> ALL_DIRECTIONS[(toward.ordinal() + 7) % 8];
      default -> ALL_DIRECTIONS[(toward.ordinal() + 1) % 8];
    };
    return index > 0 && face == current ? null : face;
  }

  /** Engine rule: a move other than straight ahead is a strafe, 18 cooldown instead of 10. */
  private static int strafeCost(Direction step, Direction face, Direction current) {
    return step != Direction.CENTER && step != current && step != face ? STRAFE_PENALTY : 0;
  }

  /** Turns first when moving toward the new facing, so the move is forward rather than a strafe. */
  private static void perform(RobotController rc, Direction step, Direction face)
      throws GameActionException {
    Direction current = rc.getDirection();
    if (step != Direction.CENTER && step == face && face != current && rc.canTurn()) {
      rc.turn(face);
    }
    if (step != Direction.CENTER && rc.canMove(step)) {
      rc.move(step);
    }
    if (rc.getDirection() != face && rc.canTurn()) {
      rc.turn(face);
    }
  }

  /** Copies up to MAX_ENEMIES enemy Baby Rats into {@code out}, nearest first; returns count. */
  static int nearestEnemyRats(Team us, MapLocation here, RobotInfo[] robots, RobotInfo[] out) {
    int count = 0;
    for (RobotInfo robot : robots) {
      if (robot.getTeam() == us || robot.getType() != UnitType.BABY_RAT) {
        continue;
      }
      int distance = here.distanceSquaredTo(robot.getLocation());
      int slot = count < out.length ? count++ : out.length;
      while (slot > 0 && here.distanceSquaredTo(out[slot - 1].getLocation()) > distance) {
        if (slot < out.length) {
          out[slot] = out[slot - 1];
        }
        slot--;
      }
      if (slot < out.length) {
        out[slot] = robot;
      }
    }
    return count;
  }
}
