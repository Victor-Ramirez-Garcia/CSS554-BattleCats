package battlecats;

import battlecode.common.*;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.Assert.*;

/** Behavioral scenarios; the controller exposes only the engine's real vision cone. */
public class CollectorTest {
    @Test
    public void collectsDeliversAndStartsAnotherTrip() throws Exception {
        World world = collectorWorld();
        world.cheese.put(at(9, 4), 35);
        BabyRat baby = immediateCollector(world);

        runUntilDelivered(baby, world, 35, 35);
        assertEquals(0, world.rawCheese);
        assertEquals(Arrays.asList(at(9, 4)), world.pickups);

        // New cheese appears in the collector's view after its first delivery.
        world.cheese.put(at(10, 6), 25);
        assertTrue(world.visible(at(10, 6)));
        runUntilDelivered(baby, world, 60, 35);

        assertEquals(0, world.rawCheese);
        assertEquals(Arrays.asList(at(9, 4), at(10, 6)), world.pickups);
        assertEquals(2, world.transfers.size());
    }

    @Test
    public void newbornUsesSharedKingLocationWithoutScanningBeforeForaging() throws Exception {
        World world = new World(at(7, 5), Direction.EAST);
        world.robots.add(king(101, Team.A, at(4, 5)));
        publishKing(world, 101, at(4, 5));
        world.cheese.put(at(10, 5), 30);
        BabyRat baby = immediateCollector(world);
        assertFalse(world.visible(at(4, 5)));

        world.takeTurn(baby);

        assertTrue("A published home lets a newborn start foraging immediately", world.moves > 0);
        assertEquals("The king was learned from communication rather than sight", 0, world.kingSightings);
    }

    @Test
    public void replacesCheeseTargetWhenAnotherRatHasEmptiedIt() throws Exception {
        World world = collectorWorld();
        MapLocation depleted = at(9, 4);
        MapLocation replacement = at(7, 4);
        world.cheese.put(depleted, 20);
        BabyRat baby = immediateCollector(world);
        world.takeTurn(baby);
        assertEquals(0, world.rawCheese);

        world.cheese.remove(depleted);
        world.cheese.put(replacement, 15);
        assertTrue(world.visible(depleted));
        assertTrue(world.visible(replacement));
        world.takeTurn(baby);

        assertEquals(15, world.rawCheese);
        assertEquals(Arrays.asList(replacement), world.pickups);
    }

    @Test
    public void deliversAtReachableKingEdgeWithoutReachingItsCenter() throws Exception {
        World world = new World(at(5, 5), Direction.EAST);
        MapLocation center = at(9, 5);
        world.robots.add(king(101, Team.A, center));
        world.rawCheese = 40;
        BabyRat baby = immediateCollector(world);
        assertTrue(world.location.distanceSquaredTo(center) > GameConstants.CHEESE_TRANSFER_RADIUS_SQUARED);

        world.takeTurn(baby);

        assertEquals(40, world.delivered);
        assertEquals(at(5, 5), world.location);
        assertNotEquals(center, world.transfers.get(0));
    }

    @Test
    public void retainsCargoAndFindsAnotherFriendlyKingWhenHomeIsLost() throws Exception {
        World world = collectorWorld();
        publishKing(world, 101, at(8, 7));
        world.cheese.put(at(9, 4), 30);
        BabyRat baby = immediateCollector(world);
        for (int i = 0; i < 15 && world.rawCheese == 0; i++) {
            world.takeTurn(baby);
        }
        assertEquals(30, world.rawCheese);

        world.robots.clear();
        world.robots.add(king(201, Team.B, at(8, 7)));
        RobotInfo replacement = king(102, Team.A, at(12, 5));
        world.robots.add(replacement);
        publishKing(world, replacement.getID(), replacement.getLocation());
        world.takeTurn(baby);
        assertEquals("Cargo must survive discovery that the old king is gone", 30, world.rawCheese);
        runWithPublishedKingUntilDelivered(baby, world, replacement.getID(), replacement.getLocation(), 30, 35);

        assertEquals(0, world.rawCheese);
        assertEquals(Arrays.asList(replacement.getID()), world.recipientIds);
    }

    @Test
    public void navigationDetoursAroundWall() throws Exception {
        World world = new World(at(4, 4), Direction.EAST);
        world.walls.addAll(Arrays.asList(at(5, 3), at(5, 4), at(5, 5)));
        Navigation navigation = new Navigation(world.id);
        MapLocation destination = at(8, 4);

        for (int i = 0; i < 20 && !world.location.equals(destination); i++) {
            navigation.moveToward(world.rc, destination);
            world.nextRound();
        }
        assertEquals(destination, world.location);
        assertTrue("The route must include a detour", world.moves > 4);
    }

    @Test
    public void triesDifferentCheeseAfterAnUnreachableTarget() throws Exception {
        World world = collectorWorld();
        MapLocation inaccessible = at(8, 4);
        MapLocation alternative = at(9, 5);
        world.cheese.put(inaccessible, 20);
        world.cheese.put(alternative, 25);
        for (Direction direction : Direction.values()) {
            if (direction != Direction.CENTER) world.walls.add(world.location.add(direction));
        }
        BabyRat baby = immediateCollector(world);

        for (int i = 0; i < 82; i++) world.takeTurn(baby);
        assertEquals(0, world.rawCheese);
        world.walls.clear();
        for (int i = 0; i < 8 && world.rawCheese == 0; i++) world.takeTurn(baby);

        assertEquals("An unreachable pile should not prevent trying another observed pile", 25, world.rawCheese);
        assertEquals(Arrays.asList(alternative), world.pickups);
        assertEquals(Integer.valueOf(20), world.cheese.get(inaccessible));
    }

    @Test
    public void navigationRecoversAfterTemporaryTrafficClears() throws Exception {
        World world = new World(at(5, 5), Direction.EAST);
        world.walls.addAll(Arrays.asList(at(6, 5), at(6, 4), at(5, 4), at(4, 4)));
        Navigation navigation = new Navigation(world.id);
        MapLocation destination = at(15, 5);
        navigation.moveToward(world.rc, destination);
        world.nextRound();
        world.walls.clear();

        for (int i = 0; i < 24 && !world.location.equals(destination); i++) {
            navigation.moveToward(world.rc, destination);
            world.nextRound();
        }
        assertEquals("Cleared traffic must not leave the rat walking in a cycle", destination, world.location);
    }

    @Test
    public void followsUpdatedKingBroadcastOutsideVisionAndDelivers() throws Exception {
        World world = new World(at(14, 14), Direction.EAST);
        MapLocation oldHome = at(2, 2);
        world.robots.add(king(101, Team.A, oldHome));
        world.rawCheese = 30;
        publishKing(world, 101, oldHome);
        BabyRat baby = immediateCollector(world);
        world.takeTurn(baby);

        MapLocation newHome = at(20, 18);
        world.robots.clear();
        world.robots.add(king(101, Team.A, newHome));
        publishKing(world, 101, newHome);
        assertFalse(world.visible(newHome));
        int distanceBeforeUpdate = world.location.distanceSquaredTo(newHome);
        world.takeTurn(baby);
        assertTrue("A returning rat must follow the new broadcast rather than its old home",
                world.location.distanceSquaredTo(newHome) < distanceBeforeUpdate);

        runWithPublishedKingUntilDelivered(baby, world, 101, newHome, 30, 25);
        assertEquals(Arrays.asList(101), world.recipientIds);
    }

    @Test
    public void staleBroadcastFallsBackToVisibleFriendlyKing() throws Exception {
        World world = new World(at(5, 5), Direction.EAST);
        publishKing(world, 101, at(2, 2));
        world.round += SharedKingLocations.MAX_AGE + 1;
        world.robots.add(king(102, Team.A, at(8, 7)));
        world.rawCheese = 30;
        assertNull(SharedKingLocations.nearest(world.rc));
        assertTrue("A stale registry is distinguishable from one never initialized",
                SharedKingLocations.hasPublishedLocations(world.rc));

        world.takeTurn(immediateCollector(world));

        assertEquals(30, world.delivered);
        assertEquals(Arrays.asList(102), world.recipientIds);
    }

    @Test
    public void retainsCargoWithoutBroadcastsAndUsesVisibleReplacementKing() throws Exception {
        World world = collectorWorld();
        world.cheese.put(at(6, 5), 30);
        BabyRat baby = immediateCollector(world);
        world.takeTurn(baby);
        assertEquals(30, world.rawCheese);

        world.robots.clear();
        world.takeTurn(baby);
        assertEquals("Losing the only known king must not lose the cargo", 30, world.rawCheese);
        assertEquals(0, world.delivered);

        // A new king enters the existing vision cone after the old home was lost.
        MapLocation replacementLocation = world.location.add(world.direction)
                .add(world.direction).add(world.direction);
        world.robots.add(king(102, Team.A, replacementLocation));
        assertTrue(world.visible(replacementLocation));
        assertFalse(SharedKingLocations.hasPublishedLocations(world.rc));
        int sightingsBefore = world.kingSightings;
        world.takeTurn(baby);

        assertTrue("Fallback must actually sense the replacement", world.kingSightings > sightingsBefore);
        assertEquals(30, world.delivered);
        assertEquals(Arrays.asList(102), world.recipientIds);
    }

    @Test
    public void gathersTwoSmallPilesBeforeMakingOneReturnTrip() throws Exception {
        World world = new World(at(12, 12), Direction.EAST);
        MapLocation home = at(2, 2);
        world.robots.add(king(101, Team.A, home));
        publishKing(world, 101, home);
        world.cheese.put(at(13, 12), 20);
        world.cheese.put(at(14, 12), 20);
        BabyRat baby = new BabyRat(world.id, 40, 60);

        world.takeTurn(baby);
        assertEquals("The first small pile should stay in the rat's cargo", 20, world.rawCheese);
        world.takeTurn(baby);
        assertEquals("The collector should seek another nearby pile before returning", 40, world.rawCheese);
        assertEquals(Arrays.asList(at(13, 12), at(14, 12)), world.pickups);
        assertEquals(0, world.delivered);

        runWithPublishedKingUntilDelivered(baby, world, 101, home, 40, 30);
        assertEquals("The batch should use a single delivery", 1, world.transfers.size());
    }

    @Test
    public void returnsSmallCargoAfterBoundedForagingInsteadOfWaitingForThreshold() throws Exception {
        World world = new World(at(12, 12), Direction.EAST);
        MapLocation home = at(2, 2);
        world.robots.add(king(101, Team.A, home));
        publishKing(world, 101, home);
        world.cheese.put(at(13, 12), 5);
        world.cheese.put(at(14, 12), 20);
        BabyRat baby = new BabyRat(world.id, 100, 2);
        world.takeTurn(baby);
        world.takeTurn(baby);
        assertEquals(25, world.rawCheese);

        int distanceBeforeTimeout = world.location.distanceSquaredTo(home);
        world.takeTurn(baby);
        assertTrue("The forage timeout should start the return below the cargo threshold",
                world.location.distanceSquaredTo(home) < distanceBeforeTimeout);
        runWithPublishedKingUntilDelivered(baby, world, 101, home, 25, 30);
    }

    @Test
    public void forageDeadlineResetsAfterDeliveryForTheNextBatch() throws Exception {
        World world = new World(at(12, 12), Direction.EAST);
        MapLocation oldHome = at(2, 2);
        world.robots.add(king(101, Team.A, oldHome));
        publishKing(world, 101, oldHome);
        world.cheese.put(at(13, 12), 5);
        BabyRat baby = new BabyRat(world.id, 100, 2);
        runWithPublishedKingUntilDelivered(baby, world, 101, oldHome, 5, 30);

        // Moving the king puts the next small batch beyond opportunistic transfer range.
        MapLocation newHome = at(20, 20);
        world.robots.clear();
        world.robots.add(king(101, Team.A, newHome));
        publishKing(world, 101, newHome);
        MapLocation firstPile = world.location.add(world.direction);
        MapLocation secondPile = firstPile.add(world.direction);
        world.cheese.put(firstPile, 5);
        world.cheese.put(secondPile, 20);
        world.takeTurn(baby);
        assertEquals(5, world.rawCheese);
        world.takeTurn(baby);

        assertEquals("The previous trip's age must not force an immediate return on a new trip",
                25, world.rawCheese);
        assertEquals(5, world.delivered);
    }

    @Test
    public void carriedTurnsStillCountTowardTheForageDeadline() throws Exception {
        World world = new World(at(12, 12), Direction.EAST);
        MapLocation home = at(2, 2);
        world.robots.add(king(101, Team.A, home));
        publishKing(world, 101, home);
        world.cheese.put(at(13, 12), 5);
        BabyRat baby = new BabyRat(world.id, 100, 2);
        world.takeTurn(baby);
        world.beingCarried = true;
        for (int i = 0; i < 3; i++) world.takeTurn(baby);
        assertEquals(at(12, 12), world.location);
        assertEquals(5, world.rawCheese);

        world.beingCarried = false;
        publishKing(world, 101, home);
        int distanceBeforeReturn = world.location.distanceSquaredTo(home);
        world.takeTurn(baby);
        assertTrue("A carried collector must notice that its wall-clock forage deadline passed",
                world.location.distanceSquaredTo(home) < distanceBeforeReturn);
    }

    @Test
    public void unloadsSmallCargoWhenAlreadyWithinKingTransferRange() throws Exception {
        World world = collectorWorld();
        world.rawCheese = 5;
        BabyRat baby = new BabyRat(world.id, 100, 60);

        world.takeTurn(baby);

        assertEquals("An opportunistic delivery must not wait for a full batch", 5, world.delivered);
        assertEquals(0, world.rawCheese);
        assertEquals(0, world.moves);
    }

    @Test
    public void largePileIsCollectedWholeAndThenTriggersReturn() throws Exception {
        World world = new World(at(12, 12), Direction.EAST);
        MapLocation home = at(2, 2);
        world.robots.add(king(101, Team.A, home));
        publishKing(world, 101, home);
        world.cheese.put(at(13, 12), 75);
        BabyRat baby = new BabyRat(world.id, 40, 60);

        world.takeTurn(baby);
        assertEquals("The threshold is a soft trigger, not a per-pickup carrying limit", 75, world.rawCheese);
        assertFalse(world.cheese.containsKey(at(13, 12)));
        int distanceBeforeReturn = world.location.distanceSquaredTo(home);
        world.takeTurn(baby);
        assertTrue(world.location.distanceSquaredTo(home) < distanceBeforeReturn);
        runWithPublishedKingUntilDelivered(baby, world, 101, home, 75, 30);
    }

    @Test
    public void registryTracksFiveKingsAndCanReuseExpiredRecord() throws Exception {
        World world = new World(at(12, 12), Direction.EAST);
        int[] ids = {101, 102, 103, 104, Integer.MAX_VALUE};
        MapLocation[] homes = {at(2, 2), at(8, 2), at(14, 2), at(20, 2), at(20, 20)};
        for (int i = 0; i < ids.length; i++) publishKing(world, ids[i], homes[i]);
        for (MapLocation home : homes) {
            world.location = home;
            assertEquals("Each king needs its own record", home, SharedKingLocations.nearest(world.rc));
        }

        world.location = at(12, 12);
        publishKing(world, 105, world.location);
        assertNotEquals("A new publisher cannot overwrite one of five fresh kings",
                world.location, SharedKingLocations.nearest(world.rc));
        world.round += SharedKingLocations.MAX_AGE + 1;
        assertNull(SharedKingLocations.nearest(world.rc));
        publishKing(world, 105, at(18, 18));
        assertEquals("A dead king's expired slot must become reusable", at(18, 18),
                SharedKingLocations.nearest(world.rc));
    }

    @Test
    public void sharedKingHeartbeatCrosses1024WithoutResurrectingDeadRecords() throws Exception {
        World world = new World(at(12, 12), Direction.EAST);
        world.round = 1023;
        publishKing(world, Integer.MAX_VALUE, at(0, 0));
        world.round = 1024;
        assertEquals("Zero coordinates are a valid location", at(0, 0), SharedKingLocations.nearest(world.rc));
        publishKing(world, Integer.MAX_VALUE, at(23, 23));
        world.round = 1025;
        assertEquals(at(23, 23), SharedKingLocations.nearest(world.rc));
        world.round = 2000;
        assertNull("Absolute timestamps prevent stale records becoming fresh after wrapping",
                SharedKingLocations.nearest(world.rc));
        publishKing(world, Integer.MAX_VALUE, at(20, 20));
        assertEquals(at(20, 20), SharedKingLocations.nearest(world.rc));

        World longDead = new World(at(12, 12), Direction.EAST);
        publishKing(longDead, 101, at(2, 2));
        longDead.round = 1025;
        assertNull(SharedKingLocations.nearest(longDead.rc));
    }

    @Test
    public void kingSpawnsPastFourAndReplacesDeadCollectorsUsingCurrentCost() throws Exception {
        World world = kingWorld();
        world.allCheese = 5000;
        RatKing king = new RatKing();
        for (int i = 0; i < 6; i++) {
            king.takeTurn(world.rc);
            world.nextRound();
        }
        assertEquals("There must be no four-lifetime-spawn cap", 6, world.builds.size());
        assertEquals(Arrays.asList(10, 10, 10, 10, 20, 20), world.spawnCosts);

        world.robots.removeIf(robot -> robot.getType() == UnitType.BABY_RAT);
        int cheeseBeforeReplacement = world.allCheese;
        king.takeTurn(world.rc);
        assertEquals(7, world.builds.size());
        assertEquals("Replacing lost collectors must use the live population's cost", 10,
                cheeseBeforeReplacement - world.allCheese);
    }

    @Test
    public void kingPreservesStartingFoodAndFundsFurtherGrowthFromDeliveries() throws Exception {
        World world = kingWorld();
        world.allCheese = GameConstants.INITIAL_TEAM_CHEESE;
        RatKing king = new RatKing();
        for (int turn = 0; turn < 100; turn++) {
            // The real engine consumes king upkeep before the king acts, including round one.
            world.allCheese -= GameConstants.RAT_KING_CHEESE_CONSUMPTION;
            king.takeTurn(world.rc);
            if (turn < 99) world.nextRound();
        }
        assertEquals("Starting cheese alone should only buy the four-worker bootstrap", 4, world.builds.size());
        assertEquals("After 100 rounds, preserve food after 200 upkeep and 40 worker spending", 2260, world.allCheese);

        world.nextRound();
        world.allCheese -= GameConstants.RAT_KING_CHEESE_CONSUMPTION;
        world.allCheese += 20; // An actual delivery funds the next live-population-priced worker.
        king.takeTurn(world.rc);
        assertEquals("The bootstrap budget must not become a lifetime population cap", 5, world.builds.size());
        assertEquals(Integer.valueOf(20), world.spawnCosts.get(4));
        assertEquals(2258, world.allCheese);

        world.robots.removeIf(robot -> robot.getType() == UnitType.BABY_RAT);
        world.nextRound();
        world.allCheese -= GameConstants.RAT_KING_CHEESE_CONSUMPTION;
        world.allCheese += 10;
        king.takeTurn(world.rc);
        assertEquals("New income must also permit replacements after the original four workers die", 6, world.builds.size());
        assertEquals(Integer.valueOf(10), world.spawnCosts.get(5));
        assertEquals(2256, world.allCheese);
    }

    @Test
    public void kingPublishesLocationEvenWhenReservePreventsSpawning() throws Exception {
        World world = kingWorld();
        world.allCheese = 109;
        RatKing king = new RatKing();

        king.takeTurn(world.rc);

        assertTrue(world.builds.isEmpty());
        assertEquals(world.location, SharedKingLocations.nearest(world.rc));
        world.location = at(12, 12);
        world.nextRound();
        world.allCheese = 2466;
        king.takeTurn(world.rc);
        assertEquals("Exactly enough cheese for the reserve plus cost should allow spawning", 1, world.builds.size());
        assertEquals("Early food reserves should remain after spawning", 2456, world.allCheese);
        assertEquals("A moved king must publish even before choosing whether to spawn",
                world.location, SharedKingLocations.nearest(world.rc));
    }

    @Test
    public void kingSpendingUsesGlobalBankAndKeepsTheLateGameReserveFloor() throws Exception {
        World early = kingWorld();
        early.allCheese = 2458;
        early.rawCheese = 100;
        new RatKing().takeTurn(early.rc);
        assertTrue("Carried cheese must not masquerade as spendable food in the global bank", early.builds.isEmpty());

        World late = kingWorld();
        late.round = 1200;
        late.allCheese = 109;
        RatKing king = new RatKing();
        king.takeTurn(late.rc);
        assertTrue("A mature economy must still preserve its minimum food reserve", late.builds.isEmpty());
        late.nextRound();
        late.allCheese = 110;
        king.takeTurn(late.rc);
        assertEquals(1, late.builds.size());
        assertEquals(100, late.allCheese);
    }

    private static BabyRat immediateCollector(World world) {
        return new BabyRat(world.id, 1, 60);
    }

    private static World kingWorld() {
        World world = new World(101, UnitType.RAT_KING, at(8, 7), Direction.EAST,
                new int[GameConstants.SHARED_ARRAY_SIZE]);
        world.robots.add(king(world.id, Team.A, world.location));
        return world;
    }

    private static void publishKing(World reader, int id, MapLocation location) throws Exception {
        World publisher = new World(id, UnitType.RAT_KING, location, Direction.EAST, reader.sharedArray);
        publisher.round = reader.round;
        SharedKingLocations.publish(publisher.rc);
    }

    private static void runWithPublishedKingUntilDelivered(BabyRat baby, World world,
            int kingId, MapLocation home, int total, int maxTurns) throws Exception {
        for (int i = 0; i < maxTurns && world.delivered < total; i++) {
            publishKing(world, kingId, home);
            world.takeTurn(baby);
        }
        assertEquals("The collector must complete its cheese round trip", total, world.delivered);
    }

    private static World collectorWorld() {
        World world = new World(at(5, 5), Direction.EAST);
        world.robots.add(king(101, Team.A, at(8, 7)));
        return world;
    }

    private static void runUntilDelivered(BabyRat baby, World world, int total, int maxTurns) throws Exception {
        for (int i = 0; i < maxTurns && world.delivered < total; i++) {
            world.takeTurn(baby);
        }
        assertEquals("The collector must complete its cheese round trip", total, world.delivered);
    }

    private static MapLocation at(int x, int y) {
        return new MapLocation(x, y);
    }

    private static RobotInfo king(int id, Team team, MapLocation center) {
        return new RobotInfo(id, team, UnitType.RAT_KING, 600, center, Direction.EAST, 0, 0, null);
    }

    /** Minimal scenario world, not a second strategy implementation or an engine substitute. */
    private static final class World implements InvocationHandler {
        final int id;
        final UnitType type;
        final int size = 24;
        final int[] sharedArray;
        final Map<MapLocation, Integer> cheese = new LinkedHashMap<>();
        final Set<MapLocation> walls = new HashSet<>();
        final List<RobotInfo> robots = new ArrayList<>();
        final List<MapLocation> pickups = new ArrayList<>();
        final List<MapLocation> transfers = new ArrayList<>();
        final List<Integer> recipientIds = new ArrayList<>();
        final List<MapLocation> builds = new ArrayList<>();
        final List<Integer> spawnCosts = new ArrayList<>();
        final RobotController rc;
        MapLocation location;
        Direction direction;
        int round = 1;
        int rawCheese;
        int allCheese;
        int delivered;
        int turns;
        int moves;
        int kingSightings;
        boolean movementReady = true;
        boolean turningReady = true;
        boolean actionReady = true;
        boolean beingCarried;

        World(MapLocation location, Direction direction) {
            this(2, UnitType.BABY_RAT, location, direction, new int[GameConstants.SHARED_ARRAY_SIZE]);
        }

        World(int id, UnitType type, MapLocation location, Direction direction, int[] sharedArray) {
            this.id = id;
            this.type = type;
            this.location = location;
            this.direction = direction;
            this.sharedArray = sharedArray;
            rc = (RobotController) Proxy.newProxyInstance(RobotController.class.getClassLoader(),
                    new Class<?>[] {RobotController.class}, this);
        }

        void takeTurn(BabyRat baby) throws Exception {
            baby.takeTurn(rc);
            nextRound();
        }

        void nextRound() {
            round++;
            movementReady = turningReady = actionReady = true;
        }

        boolean onMap(MapLocation target) {
            return target.x >= 0 && target.y >= 0 && target.x < size && target.y < size;
        }

        boolean visible(MapLocation target) {
            return onMap(target) && location.isWithinDistanceSquared(target,
                    UnitType.BABY_RAT.visionConeRadiusSquared, direction, UnitType.BABY_RAT.visionConeAngle, false);
        }

        RobotInfo robotAt(MapLocation target) {
            for (RobotInfo robot : robots) {
                for (MapLocation part : robot.getType().getAllTypeLocations(robot.getLocation())) {
                    if (part.equals(target)) return robot;
                }
            }
            return null;
        }

        MapInfo info(MapLocation target) {
            boolean wall = walls.contains(target);
            return new MapInfo(target, !wall, null, wall, false,
                    cheese.getOrDefault(target, 0), TrapType.NONE, false);
        }

        boolean canMove(Direction step) {
            MapLocation target = location.add(step);
            return movementReady && step != Direction.CENTER && onMap(target)
                    && !walls.contains(target) && robotAt(target) == null;
        }

        boolean canPickUp(MapLocation target) {
            return visible(target) && location.distanceSquaredTo(target) <= GameConstants.CHEESE_PICK_UP_RADIUS_SQUARED
                    && cheese.getOrDefault(target, 0) > 0;
        }

        boolean canTransfer(MapLocation target, int amount) {
            RobotInfo recipient = robotAt(target);
            return actionReady && visible(target) && amount > 0 && rawCheese >= amount
                    && location.distanceSquaredTo(target) <= GameConstants.CHEESE_TRANSFER_RADIUS_SQUARED
                    && recipient != null && recipient.getTeam() == Team.A;
        }

        int currentRatCost() {
            int livingBabies = 0;
            for (RobotInfo robot : robots) {
                if (robot.getTeam() == Team.A && robot.getType() == UnitType.BABY_RAT) livingBabies++;
            }
            return GameConstants.BUILD_ROBOT_BASE_COST + GameConstants.BUILD_ROBOT_COST_INCREASE
                    * (livingBabies / GameConstants.NUM_ROBOTS_FOR_COST_INCREASE);
        }

        boolean canBuild(MapLocation target) {
            return type == UnitType.RAT_KING && actionReady && onMap(target)
                    && location.distanceSquaredTo(target) <= GameConstants.BUILD_ROBOT_RADIUS_SQUARED
                    && !walls.contains(target) && robotAt(target) == null && allCheese >= currentRatCost();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getID": return id;
                case "getRoundNum": return round;
                case "getMapWidth": case "getMapHeight": return size;
                case "getTeam": return Team.A;
                case "getType": return type;
                case "getLocation": return location;
                case "getDirection": return direction;
                case "getRawCheese": return rawCheese;
                case "getAllCheese": return allCheese + rawCheese;
                case "getGlobalCheese": return allCheese;
                case "getCurrentRatCost": return currentRatCost();
                case "isActionReady": return actionReady;
                case "isBeingCarried": return beingCarried;
                case "isBeingThrown": return false;
                case "isMovementReady": return movementReady;
                case "canSenseLocation": return visible((MapLocation) args[0]);
                case "readSharedArray": {
                    int index = (Integer) args[0];
                    assertTrue("Shared index is outside the engine's array", index >= 0 && index < sharedArray.length);
                    return sharedArray[index];
                }
                case "writeSharedArray": {
                    assertEquals("Only kings can publish shared data", UnitType.RAT_KING, type);
                    int index = (Integer) args[0];
                    int value = (Integer) args[1];
                    assertTrue("Shared index is outside the engine's array", index >= 0 && index < sharedArray.length);
                    assertTrue("Shared values must fit the engine's communication limit",
                            value >= 0 && value <= GameConstants.COMM_ARRAY_MAX_VALUE);
                    sharedArray[index] = value;
                    return null;
                }
                case "getAllLocationsWithinRadiusSquared": {
                    MapLocation center = (MapLocation) args[0];
                    int radius = (Integer) args[1];
                    List<MapLocation> locations = new ArrayList<>();
                    for (int x = 0; x < size; x++) {
                        for (int y = 0; y < size; y++) {
                            MapLocation target = at(x, y);
                            if (center.distanceSquaredTo(target) <= radius) locations.add(target);
                        }
                    }
                    return locations.toArray(new MapLocation[0]);
                }
                case "canBuildRat": return canBuild((MapLocation) args[0]);
                case "buildRat": {
                    MapLocation target = (MapLocation) args[0];
                    assertTrue("Illegal rat spawn", canBuild(target));
                    int cost = currentRatCost();
                    spawnCosts.add(cost);
                    allCheese -= cost;
                    builds.add(target);
                    robots.add(new RobotInfo(1000 + builds.size(), Team.A, UnitType.BABY_RAT,
                            100, target, direction, 0, 0, null));
                    actionReady = false;
                    return null;
                }
                case "senseRobotAtLocation": {
                    MapLocation target = (MapLocation) args[0];
                    assertTrue("Code attempted to sense outside its FOV", visible(target));
                    return robotAt(target);
                }
                case "senseNearbyRobots": {
                    List<RobotInfo> visibleRobots = new ArrayList<>();
                    Team team = args == null || args.length < 2 ? null : (Team) args[1];
                    for (RobotInfo robot : robots) {
                        if (team != null && robot.getTeam() != team) continue;
                        for (MapLocation part : robot.getType().getAllTypeLocations(robot.getLocation())) {
                            if (visible(part)) {
                                visibleRobots.add(robot);
                                if (robot.getType() == UnitType.RAT_KING && robot.getTeam() == Team.A) kingSightings++;
                                break;
                            }
                        }
                    }
                    return visibleRobots.toArray(new RobotInfo[0]);
                }
                case "senseMapInfo": {
                    MapLocation target = (MapLocation) args[0];
                    assertTrue("Code attempted to sense outside its FOV", visible(target));
                    return info(target);
                }
                case "senseNearbyMapInfos": {
                    List<MapInfo> sensed = new ArrayList<>();
                    for (int x = 0; x < size; x++) {
                        for (int y = 0; y < size; y++) {
                            MapLocation target = at(x, y);
                            if (visible(target)) sensed.add(info(target));
                        }
                    }
                    return sensed.toArray(new MapInfo[0]);
                }
                case "canTurn": return turningReady && (args == null || args.length == 0 || args[0] != direction);
                case "turn":
                    assertTrue("Turn attempted during cooldown", turningReady);
                    assertNotEquals(direction, args[0]);
                    direction = (Direction) args[0];
                    turningReady = false;
                    turns++;
                    return null;
                case "canMove": return canMove((Direction) args[0]);
                case "move":
                    assertTrue("Move attempted into an occupied/walled tile or during cooldown", canMove((Direction) args[0]));
                    location = location.add((Direction) args[0]);
                    movementReady = false;
                    moves++;
                    return null;
                case "canPickUpCheese": return canPickUp((MapLocation) args[0]);
                case "pickUpCheese": {
                    MapLocation target = (MapLocation) args[0];
                    assertTrue("Illegal cheese pickup", canPickUp(target));
                    rawCheese += cheese.remove(target);
                    pickups.add(target);
                    return null;
                }
                case "canTransferCheese": return canTransfer((MapLocation) args[0], (Integer) args[1]);
                case "transferCheese": {
                    MapLocation target = (MapLocation) args[0];
                    int amount = (Integer) args[1];
                    assertTrue("Illegal cheese transfer", canTransfer(target, amount));
                    assertEquals("Collectors must deliver to kings", UnitType.RAT_KING, robotAt(target).getType());
                    rawCheese -= amount;
                    delivered += amount;
                    actionReady = false;
                    transfers.add(target);
                    recipientIds.add(robotAt(target).getID());
                    return null;
                }
                case "setIndicatorString": return null;
                default: throw new AssertionError("Unsupported controller operation: " + method.getName());
            }
        }
    }
}
