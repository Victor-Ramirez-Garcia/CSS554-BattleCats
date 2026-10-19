package battlecats;

import battlecode.common.*;

/** Team-wide king locations, refreshed by each king before its spawning policy runs. */
public final class SharedKingLocations {
    public static final int MAX_AGE = 3;

    // Reserve slots 0..41. Slots 0/1 retain the legacy latest-publisher x/y pair.
    // Five records begin at 2, with eight slots each:
    // [ID bits 0..9, 10..19, 20..29, 30; x; y; round bits 0..9, 10..19].
    // IDs are positive 31-bit integers. Round 0 means unpublished; timestamps
    // are absolute, so an old record cannot become fresh again after round 1024.
    private static final int FIRST_RECORD = 2;
    private static final int RECORD_SIZE = 8;
    private static final int KING_COUNT = GameConstants.MAX_NUMBER_OF_RAT_KINGS;
    private static final int CHUNK_BITS = 10;
    private static final int CHUNK_MASK = (1 << CHUNK_BITS) - 1;
    private static final int MAX_TIMESTAMP = (1 << (2 * CHUNK_BITS)) - 1;

    private SharedKingLocations() {
    }

    public static void publish(RobotController rc) throws GameActionException {
        MapLocation location = rc.getLocation();
        rc.writeSharedArray(0, location.x);
        rc.writeSharedArray(1, location.y);

        int id = rc.getID();
        if (id <= 0) {
            throw new IllegalArgumentException("King IDs must be positive");
        }
        int round = rc.getRoundNum();
        if (round <= 0 || round > MAX_TIMESTAMP) {
            return;
        }
        int record = -1;
        int available = -1;
        for (int i = 0; i < KING_COUNT; i++) {
            int start = FIRST_RECORD + i * RECORD_SIZE;
            int owner = readOwner(rc, start);
            if (owner == id) {
                record = start;
                break;
            }
            if (available == -1 && (owner == 0 || readLocation(rc, start, round) == null)) {
                available = start;
            }
        }
        if (record == -1) {
            record = available;
        }
        if (record == -1) {
            // Every slot belongs to another fresh king; never overwrite it.
            return;
        }

        // Invalidate before writing, so a partially written record is not read
        // as a fresh location if bytecode exhaustion interrupts the update.
        rc.writeSharedArray(record + 6, 0);
        rc.writeSharedArray(record + 7, 0);
        for (int i = 0; i < 4; i++) {
            rc.writeSharedArray(record + i, (id >>> (i * CHUNK_BITS)) & CHUNK_MASK);
        }
        rc.writeSharedArray(record + 4, location.x);
        rc.writeSharedArray(record + 5, location.y);
        rc.writeSharedArray(record + 6, round & CHUNK_MASK);
        rc.writeSharedArray(record + 7, round >>> CHUNK_BITS);
    }

    /** Returns the closest fresh, on-map king center, or null if none is known. */
    public static MapLocation nearest(RobotController rc) throws GameActionException {
        MapLocation nearest = null;
        int bestDistance = Integer.MAX_VALUE;
        int round = rc.getRoundNum();
        for (int i = 0; i < KING_COUNT; i++) {
            int start = FIRST_RECORD + i * RECORD_SIZE;
            if (readOwner(rc, start) == 0) {
                continue;
            }
            MapLocation location = readLocation(rc, start, round);
            if (location == null) {
                continue;
            }
            int distance = rc.getLocation().distanceSquaredTo(location);
            if (distance < bestDistance) {
                nearest = location;
                bestDistance = distance;
            }
        }
        return nearest;
    }

    /** Whether the registry has been initialized, including records whose kings expired. */
    public static boolean hasPublishedLocations(RobotController rc) throws GameActionException {
        for (int i = 0; i < KING_COUNT; i++) {
            if (readOwner(rc, FIRST_RECORD + i * RECORD_SIZE) != 0) {
                return true;
            }
        }
        return false;
    }

    private static int readOwner(RobotController rc, int start) throws GameActionException {
        int owner = 0;
        for (int i = 0; i < 4; i++) {
            int chunk = rc.readSharedArray(start + i);
            if (chunk < 0 || chunk > CHUNK_MASK || (i == 3 && chunk > 1)) {
                return 0;
            }
            owner |= chunk << (i * CHUNK_BITS);
        }
        return owner;
    }

    private static MapLocation readLocation(RobotController rc, int start, int now)
            throws GameActionException {
        int stamp = rc.readSharedArray(start + 6) | (rc.readSharedArray(start + 7) << CHUNK_BITS);
        if (stamp == 0 || stamp > now || now - stamp > MAX_AGE) {
            return null;
        }
        int x = rc.readSharedArray(start + 4);
        int y = rc.readSharedArray(start + 5);
        if (x < 0 || y < 0 || x >= rc.getMapWidth() || y >= rc.getMapHeight()) {
            return null;
        }
        return new MapLocation(x, y);
    }
}
