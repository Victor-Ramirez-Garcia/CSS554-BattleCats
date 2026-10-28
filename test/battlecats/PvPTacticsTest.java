package battlecats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import battlecode.common.Direction;
import battlecode.common.GameConstants;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.Team;
import battlecode.common.UnitType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Behavioral combat cases with real unit footprints, facing cones and independent cooldowns. */
public class PvPTacticsTest {
  @Test
  public void startsBetrayalAtTheExactRoundAndTwoToOneHealthBoundary() throws Exception {
    World world = opening(100, 100);

    assertTrue(PvPTactics.engage(world.rc, world.observations()));

    assertEquals(Arrays.asList(20), world.attackedIds);
    assertFalse(world.cooperation);
    assertEquals(0, world.moves);
    assertEquals(0, world.cargo);
  }

  @Test
  public void acceptsHealthySupportAtMinimumHealthAndDiagonalBiteRange() throws Exception {
    World world = opening(75, 75);
    world.health = 75;
    world.replace(worker(2, Team.A, at(12, 11), 75, 0));

    assertTrue(PvPTactics.engage(world.rc, world.observations()));

    assertEquals(Arrays.asList(20), world.attackedIds);
  }

  @Test
  public void cooperationCannotBreakWhileTheGlobalFoodBankNeedsDelivery() throws Exception {
    for (int bank : new int[] {0, 199, 200, 201}) {
      World world = opening(100, 100);
      world.globalCheese = bank;

      assertEquals(bank > 200, PvPTactics.engage(world.rc, world.observations(), 20));

      assertEquals(bank > 200 ? 1 : 0, world.attackedIds.size());
      assertEquals(bank <= 200, world.cooperation);
      assertEquals(0, world.moves);
    }
  }

  @Test
  public void anEmptyFoodBankDoesNotPreventRetaliationAfterBetrayal() throws Exception {
    World world = new World(worker(20, Team.B, at(11, 10), 100, 0));
    world.globalCheese = 0;
    world.cooperation = false;

    assertTrue(PvPTactics.engage(world.rc, world.observations()));

    assertEquals(Arrays.asList(20), world.attackedIds);
    assertEquals(1, world.moves);
    assertEquals(0, world.globalCheese);
  }

  @Test
  public void cooperationRequiresEveryInitiationGate() throws Exception {
    String[] gates = {
      "early round", "own cargo", "own injury", "ally injury", "ally cargo",
      "ally outside melee", "insufficient health", "extra defender", "visible cat",
      "visible enemy king", "no ally"
    };
    for (String gate : gates) {
      World world = opening(100, 100);
      switch (gate) {
        case "early round":
          world.round = 99;
          break;
        case "own cargo":
          world.cargo = 1;
          break;
        case "own injury":
          world.health = 74;
          world.replace(worker(20, Team.B, at(11, 10), 50, 0));
          break;
        case "ally injury":
          world.replace(worker(2, Team.A, at(11, 11), 74, 0));
          world.replace(worker(20, Team.B, at(11, 10), 50, 0));
          break;
        case "ally cargo":
          world.replace(worker(2, Team.A, at(11, 11), 100, 1));
          break;
        case "ally outside melee":
          world.replace(worker(2, Team.A, at(13, 10), 100, 0));
          break;
        case "insufficient health":
          world.replace(worker(2, Team.A, at(11, 11), 99, 0));
          break;
        case "extra defender":
          world.robots.add(worker(21, Team.B, at(13, 12), 100, 0));
          break;
        case "visible cat":
          world.robots.add(cat(at(12, 8)));
          break;
        case "visible enemy king":
          world.robots.add(king(30, Team.B, at(13, 11), 600));
          break;
        case "no ally":
          world.robots.removeIf(robot -> robot.getTeam() == Team.A);
          break;
        default:
          throw new AssertionError(gate);
      }

      assertFalse(gate, PvPTactics.engage(world.rc, world.observations(), 20));

      assertTrue(gate, world.attackedIds.isEmpty());
      assertEquals(gate, 0, world.moves);
      assertTrue(gate, world.cooperation);
    }
  }

  @Test
  public void defenderExactlyInsideLocalRadiusCountsButOneOutsideDoesNot() throws Exception {
    World inside = opening(100, 100);
    inside.robots.add(worker(21, Team.B, at(13, 12), 100, 0));
    World outside = opening(100, 100);
    outside.robots.add(worker(21, Team.B, at(14, 10), 100, 0));

    assertFalse(PvPTactics.engage(inside.rc, inside.observations()));
    assertTrue(PvPTactics.engage(outside.rc, outside.observations()));

    assertTrue(inside.attackedIds.isEmpty());
    assertEquals(Arrays.asList(20), outside.attackedIds);
  }

  @Test
  public void friendlyKingsDoNotReplaceBabySupport() throws Exception {
    World world = new World(worker(20, Team.B, at(11, 10), 100, 0));
    world.robots.add(king(3, Team.A, at(10, 12), 600));

    assertFalse(PvPTactics.engage(world.rc, world.observations()));

    assertTrue(world.attackedIds.isEmpty());
  }

  @Test
  public void duplicateSightingsAndSelfNeverInflateTheFightingForce() throws Exception {
    World unsupported = new World(worker(20, Team.B, at(11, 10), 75, 0));
    RobotInfo self = worker(unsupported.id, Team.A, unsupported.location, 100, 0);
    unsupported.robots.add(self);
    unsupported.robots.add(self);
    World defended = opening(75, 100);
    defended.robots.add(worker(21, Team.B, at(13, 12), 75, 0));
    defended.robots.add(worker(2, Team.A, at(11, 11), 100, 0));
    defended.robots.add(worker(2, Team.A, at(11, 11), 100, 0));

    assertFalse(PvPTactics.engage(unsupported.rc, unsupported.observations()));
    assertFalse(PvPTactics.engage(defended.rc, defended.observations()));

    assertTrue(unsupported.attackedIds.isEmpty());
    assertTrue(defended.attackedIds.isEmpty());
  }

  @Test
  public void duplicateEnemiesAreOneDefenderAndLatestAlliedPositionWins() throws Exception {
    World repeated = opening(100, 100);
    repeated.robots.add(worker(20, Team.B, at(11, 10), 100, 0));
    World movedAlly = opening(100, 100);
    movedAlly.robots.add(worker(2, Team.A, at(13, 10), 100, 0));

    assertTrue(PvPTactics.engage(repeated.rc, repeated.observations()));
    assertFalse(PvPTactics.engage(movedAlly.rc, movedAlly.observations()));

    assertEquals(Arrays.asList(20), repeated.attackedIds);
    assertTrue(movedAlly.attackedIds.isEmpty());
  }

  @Test
  public void selectsLowestHealthReachableEnemyThenLowestIdAndTurnsIfNeeded() throws Exception {
    World world = new World(
        worker(20, Team.B, at(11, 10), 100, 0),
        worker(12, Team.B, at(11, 11), 5, 0),
        worker(11, Team.B, at(10, 11), 5, 0),
        worker(10, Team.B, at(14, 10), 1, 0));
    world.cooperation = false;

    assertTrue(PvPTactics.engage(world.rc, world.observations()));

    assertEquals(Arrays.asList(11), world.attackedIds);
    assertEquals(1, world.turns);
    assertEquals(Direction.NORTH, world.direction);
  }

  @Test
  public void skipsBlockedLowHealthTargetInsteadOfTurningAtAnIllegalBite() throws Exception {
    World world = new World(
        worker(20, Team.B, at(11, 10), 100, 0),
        worker(21, Team.B, at(11, 11), 1, 0));
    world.cooperation = false;
    world.walls.add(at(11, 11));

    assertTrue(PvPTactics.engage(world.rc, world.observations()));

    assertEquals(Arrays.asList(20), world.attackedIds);
    assertEquals(0, world.turns);
  }

  @Test
  public void sharedFocusUnitesWorkersWithDifferentLocallyWeakerTargets() throws Exception {
    for (boolean designated : new boolean[] {false, true}) {
      RobotInfo focus = worker(40, Team.B, at(11, 10), 90, 0);
      World left = new World(
          focus,
          worker(20, Team.B, at(10, 11), 60, 0),
          worker(2, Team.A, at(12, 10), 100, 0));
      World right = new World(
          focus,
          worker(21, Team.B, at(12, 11), 50, 0),
          worker(1, Team.A, at(10, 10), 100, 0));
      right.id = 2;
      right.location = at(12, 10);
      right.direction = Direction.WEST;
      left.cooperation = false;
      right.cooperation = false;
      int focusId = designated ? 40 : -1;

      assertTrue(PvPTactics.engage(left.rc, left.observations(), focusId));
      assertTrue(PvPTactics.engage(right.rc, right.observations(), focusId));

      assertEquals(Arrays.asList(designated ? 40 : 20), left.attackedIds);
      assertEquals(Arrays.asList(designated ? 40 : 21), right.attackedIds);
    }
  }

  @Test
  public void missingFriendlyAndSelfFocusIdsFallBackToLocalTargetOrdering() throws Exception {
    for (int focusId : new int[] {-1, 999, 2, 1}) {
      World world = new World(
          worker(20, Team.B, at(11, 10), 80, 0),
          worker(21, Team.B, at(11, 11), 50, 0),
          worker(2, Team.A, at(12, 11), 100, 0));
      world.cooperation = false;

      assertTrue(PvPTactics.engage(world.rc, world.observations(), focusId));

      assertEquals(Arrays.asList(21), world.attackedIds);
    }
  }

  @Test
  public void unreachableFocusNeverVetoesAnotherLegalFreeBite() throws Exception {
    for (String obstruction : new String[] {"range", "wall", "turn cooldown"}) {
      MapLocation focusLocation = obstruction.equals("range") ? at(12, 10) : at(10, 11);
      World world = new World(
          worker(40, Team.B, focusLocation, 90, 0),
          worker(20, Team.B, at(11, 10), 50, 0));
      world.cooperation = false;
      if (obstruction.equals("wall")) {
        world.walls.add(focusLocation);
        world.direction = Direction.NORTH;
      } else if (obstruction.equals("turn cooldown")) {
        world.turningReady = false;
      }

      assertTrue(obstruction, PvPTactics.engage(world.rc, world.observations(), 40));

      assertEquals(obstruction, Arrays.asList(20), world.attackedIds);
    }
  }

  @Test
  public void turnsTowardALegalFocusBeforeBiting() throws Exception {
    World world = new World(
        worker(40, Team.B, at(10, 11), 90, 0),
        worker(20, Team.B, at(11, 10), 50, 0));
    world.cooperation = false;

    assertTrue(PvPTactics.engage(world.rc, world.observations(), 40));

    assertEquals(Arrays.asList(40), world.attackedIds);
    assertEquals(Direction.NORTH, world.direction);
    assertEquals(1, world.turns);
  }

  @Test
  public void latestFocusSightingMustStillBeWithinBiteRange() throws Exception {
    World world = new World(
        worker(40, Team.B, at(11, 10), 90, 0),
        worker(20, Team.B, at(11, 11), 50, 0),
        worker(40, Team.B, at(14, 10), 90, 0));
    world.cooperation = false;

    assertTrue(PvPTactics.engage(world.rc, world.observations(), 40));

    assertEquals(Arrays.asList(20), world.attackedIds);
  }

  @Test
  public void guaranteedFreeBiteFinishOverridesFocusAtExactDamageBoundary() throws Exception {
    int damage = GameConstants.RAT_BITE_DAMAGE;
    for (int health : new int[] {damage, damage + 1}) {
      World world = new World(
          worker(40, Team.B, at(11, 10), 90, 0),
          worker(20, Team.B, at(10, 11), health, 0));
      world.cooperation = false;
      boolean finishes = health == GameConstants.RAT_BITE_DAMAGE;

      assertTrue(PvPTactics.engage(world.rc, world.observations(), 40));

      assertEquals(Arrays.asList(finishes ? 20 : 40), world.attackedIds);
      assertEquals(finishes ? 1 : 0, world.turns);
    }
  }

  @Test
  public void focusDoesNotChangeCooperativeTargetOrdering() throws Exception {
    World world = new World(
        worker(40, Team.B, at(11, 10), 50, 0),
        worker(20, Team.B, at(11, 11), 30, 0),
        worker(2, Team.A, at(12, 10), 100, 0),
        worker(3, Team.A, at(12, 11), 100, 0),
        worker(4, Team.A, at(10, 11), 100, 0));

    assertTrue(PvPTactics.engage(world.rc, world.observations(), 40));

    assertEquals(Arrays.asList(20), world.attackedIds);
  }

  @Test
  public void actionAndTurningCooldownsCannotInitiateBetrayal() throws Exception {
    World actionCooldown = opening(100, 100);
    actionCooldown.actionReady = false;
    World turnCooldown = opening(100, 100);
    turnCooldown.direction = Direction.WEST;
    turnCooldown.turningReady = false;

    assertFalse(PvPTactics.engage(actionCooldown.rc, actionCooldown.observations()));
    assertFalse(PvPTactics.engage(turnCooldown.rc, turnCooldown.observations()));

    assertTrue(actionCooldown.attackedIds.isEmpty());
    assertTrue(turnCooldown.attackedIds.isEmpty());
    assertEquals(0, turnCooldown.turns);
  }

  @Test
  public void afterBetrayalInjuredLoadedWorkerBitesThenRetreatsWhenOutnumbered() throws Exception {
    World world = new World(
        worker(20, Team.B, at(11, 10), 100, 0),
        worker(21, Team.B, at(11, 11), 100, 0));
    world.cooperation = false;
    world.round = 1;
    world.health = 5;
    world.cargo = 30;

    assertTrue(PvPTactics.engage(world.rc, world.observations()));

    assertEquals(Arrays.asList(20), world.attackedIds);
    assertEquals(1, world.moves);
    assertTrue(world.location.distanceSquaredTo(at(11, 10)) > 1);
    assertTrue(world.location.distanceSquaredTo(at(11, 11)) > 2);
    assertEquals("Defensive bites spend no cargo", 30, world.cargo);
  }

  @Test
  public void outnumberedWorkerCanRetreatDuringActionCooldownAndHoldWhenBlocked() throws Exception {
    for (boolean blocked : new boolean[] {false, true}) {
      World world = new World(worker(20, Team.B, at(11, 10), 100, 0));
      world.cooperation = false;
      world.actionReady = false;
      if (blocked) {
        world.allowedMoves.clear();
      }

      assertTrue(PvPTactics.engage(world.rc, world.observations()));

      assertTrue(world.attackedIds.isEmpty());
      assertEquals(blocked ? 0 : 1, world.moves);
    }
  }

  @Test
  public void advantagedWorkerStepsIntoRangeAndBitesWithoutASecondMove() throws Exception {
    World world = approachWorld();

    assertTrue(PvPTactics.engage(world.rc, world.observations()));
    assertEquals(at(11, 10), world.location);
    assertEquals(Arrays.asList(20), world.attackedIds);
    assertTrue(PvPTactics.engage(world.rc, world.observations()));

    assertEquals(1, world.moves);
    assertEquals(
        "The independent action cooldown prevents a second bite", 1, world.attackedIds.size());
  }

  @Test
  public void actionCooldownAllowsApproachWhileCargoKeepsDeliveryPriority() throws Exception {
    World cooling = approachWorld();
    cooling.actionReady = false;
    World loaded = approachWorld();
    loaded.cargo = 1;

    assertTrue(PvPTactics.engage(cooling.rc, cooling.observations()));
    assertFalse(PvPTactics.engage(loaded.rc, loaded.observations()));

    assertEquals(1, cooling.moves);
    assertTrue(cooling.attackedIds.isEmpty());
    assertEquals(0, loaded.moves);
    assertTrue(loaded.attackedIds.isEmpty());
  }

  @Test
  public void localFocusApproachUsesOneSafeStepWithIndependentActionCooldown() throws Exception {
    for (boolean ready : new boolean[] {false, true}) {
      World world = focusApproachWorld();
      world.actionReady = ready;

      assertTrue(PvPTactics.engage(world.rc, world.observations(), 40));

      assertEquals(at(10, 11), world.location);
      assertEquals(1, world.moves);
      assertEquals(ready ? Arrays.asList(40) : Arrays.asList(), world.attackedIds);
    }
  }

  @Test
  public void blockedFocusApproachFallsBackToSafeNearestTarget() throws Exception {
    World world = focusApproachWorld();
    world.allowedMoves = EnumSet.of(Direction.EAST);

    assertTrue(PvPTactics.engage(world.rc, world.observations(), 40));

    assertEquals(at(11, 10), world.location);
    assertEquals(1, world.moves);
    assertEquals(Arrays.asList(20), world.attackedIds);
  }

  @Test
  public void completelyBlockedOptionalApproachLeavesOrdinaryWorkAvailable() throws Exception {
    World world = focusApproachWorld();
    world.allowedMoves.clear();

    assertFalse(PvPTactics.engage(world.rc, world.observations(), 40));

    assertEquals(at(10, 10), world.location);
    assertEquals(0, world.moves);
    assertTrue(world.attackedIds.isEmpty());
  }

  @Test
  public void cargoAndFoodBankVetoOptionalFocusApproachAtExactBoundary() throws Exception {
    for (int bank : new int[] {0, 199, 200, 201}) {
      for (int cargo : new int[] {0, 1}) {
        World world = focusApproachWorld();
        world.globalCheese = bank;
        world.cargo = cargo;
        boolean mayApproach = bank > 200 && cargo == 0;

        assertEquals(mayApproach, PvPTactics.engage(world.rc, world.observations(), 40));

        assertEquals(mayApproach ? at(10, 11) : at(10, 10), world.location);
        assertEquals(mayApproach ? 1 : 0, world.moves);
        assertEquals(mayApproach ? Arrays.asList(40) : Arrays.asList(), world.attackedIds);
        assertEquals(bank, world.globalCheese);
        assertEquals(cargo, world.cargo);
      }
    }
  }

  @Test
  public void loadedWorkerWithEmptyFoodBankCanStillTakeSharedFreeBite() throws Exception {
    World world = new World(
        worker(40, Team.B, at(11, 10), 90, 0),
        worker(20, Team.B, at(10, 11), 50, 0));
    world.cooperation = false;
    world.globalCheese = 0;
    world.cargo = 30;

    assertTrue(PvPTactics.engage(world.rc, world.observations(), 40));

    assertEquals(Arrays.asList(40), world.attackedIds);
    assertEquals(0, world.globalCheese);
    assertEquals(30, world.cargo);
    assertTrue("Movement must escape the contact rather than approach", world.location.x < 10);
  }

  @Test
  public void unsupportedFocusCannotRedirectAnOtherwiseSafeLocalApproach() throws Exception {
    World world = new World(
        worker(40, Team.B, at(8, 12), 50, 0),
        worker(20, Team.B, at(12, 10), 50, 0),
        worker(2, Team.A, at(12, 11), 100, 0));
    world.cooperation = false;

    assertTrue(PvPTactics.engage(world.rc, world.observations(), 40));

    assertEquals(at(11, 10), world.location);
    assertEquals(Arrays.asList(20), world.attackedIds);
  }

  @Test
  public void focusSupportCannotExcuseAnUnsafeNearerThreat() throws Exception {
    World world = new World(
        worker(40, Team.B, at(8, 12), 20, 0),
        worker(20, Team.B, at(12, 10), 100, 0),
        worker(2, Team.A, at(8, 11), 100, 0));
    world.cooperation = false;

    assertTrue(PvPTactics.engage(world.rc, world.observations(), 40));

    assertEquals(at(9, 9), world.location);
    assertTrue(world.attackedIds.isEmpty());
  }

  @Test
  public void defensiveForceIncludesInjuredAndLoadedNearbyAllies() throws Exception {
    World world = approachWorld();
    world.replace(worker(2, Team.A, at(12, 11), 50, 20));
    world.replace(worker(20, Team.B, at(12, 10), 75, 0));

    assertTrue(PvPTactics.engage(world.rc, world.observations(), 20));

    assertEquals(at(11, 10), world.location);
    assertEquals(Arrays.asList(20), world.attackedIds);
  }

  @Test
  public void approachCannotEnterKnownCatCrushingRangeOrVision() throws Exception {
    World world = approachWorld();
    world.robots.add(cat(at(12, 8)));
    world.allowedMoves = EnumSet.of(Direction.EAST);

    assertFalse(PvPTactics.engage(world.rc, world.observations(), 20));

    assertEquals(at(10, 10), world.location);
    assertEquals(0, world.moves);
    assertTrue(world.attackedIds.isEmpty());
  }

  @Test
  public void visibleEnemyBeyondLocalRadiusDoesNotCreateAPursuit() throws Exception {
    World world = new World(
        worker(20, Team.B, at(14, 10), 100, 0),
        worker(2, Team.A, at(13, 11), 100, 0));
    world.cooperation = false;

    assertFalse(PvPTactics.engage(world.rc, world.observations(), 20));

    assertEquals(0, world.moves);
    assertTrue(world.attackedIds.isEmpty());
  }

  @Test
  public void kingOnlyDefendsAfterBetrayalAndUsesEnemyKingFootprint() throws Exception {
    for (boolean cooperating : new boolean[] {true, false}) {
      RobotInfo enemyKing = king(30, Team.B, at(13, 10), 5);
      World world = new World(enemyKing);
      world.type = UnitType.RAT_KING;
      world.health = 600;
      world.cargo = 50;
      world.cooperation = cooperating;
      assertFalse(world.canAttack(enemyKing.getLocation()));

      assertEquals(!cooperating, PvPTactics.engage(world.rc, world.observations()));

      assertEquals(cooperating ? 0 : 1, world.attackedIds.size());
      assertEquals(0, world.moves);
      if (!cooperating) {
        assertTrue(Arrays.asList(UnitType.RAT_KING.getAllTypeLocations(enemyKing.getLocation()))
            .contains(world.attackedTiles.get(0)));
      }
    }
  }

  @Test
  public void sharedKingFocusUsesLegalFootprintWithoutMovingOurKing() throws Exception {
    RobotInfo enemyKing = king(40, Team.B, at(13, 10), 300);
    World world = new World(enemyKing, worker(20, Team.B, at(11, 10), 50, 0));
    world.type = UnitType.RAT_KING;
    world.health = 600;
    world.cooperation = false;

    assertTrue(PvPTactics.engage(world.rc, world.observations(), 40));

    assertEquals(Arrays.asList(40), world.attackedIds);
    assertTrue(Arrays.asList(UnitType.RAT_KING.getAllTypeLocations(enemyKing.getLocation()))
        .contains(world.attackedTiles.get(0)));
    assertEquals(0, world.moves);
  }

  @Test
  public void emptyOrAlliedObservationsLeaveOrdinaryWorkAlone() throws Exception {
    World[] worlds = {new World(), new World(worker(2, Team.A, at(11, 10), 100, 0))};
    for (World world : worlds) {
      assertFalse(PvPTactics.engage(world.rc, world.observations()));
      assertEquals(0, world.moves);
      assertTrue(world.attackedIds.isEmpty());
    }
  }

  @Test
  public void equalHealthTargetRankingIsIndependentOfObservationOrder() throws Exception {
    RobotInfo[] targets = {
      worker(18, Team.B, at(11, 10), 50, 0),
      worker(2, Team.B, at(10, 11), 50, 0),
      worker(34, Team.B, at(11, 11), 50, 0)
    };
    int[][] orders = {{0, 1, 2}, {2, 1, 0}, {1, 2, 0}};
    for (int[] order : orders) {
      World world = new World(targets[order[0]], targets[order[1]], targets[order[2]]);
      world.cooperation = false;

      assertTrue(PvPTactics.engage(world.rc, world.observations()));

      assertEquals(Arrays.asList(2), world.attackedIds);
      assertEquals(Direction.NORTH, world.direction);
      assertEquals(1, world.turns);
      assertEquals(at(9, 9), world.location);
    }
  }

  @Test
  public void equallyDistantRetreatsKeepTheEngineDirectionOrder() throws Exception {
    World world = new World(worker(20, Team.B, at(11, 10), 100, 0));
    world.cooperation = false;
    world.actionReady = false;
    world.allowedMoves = EnumSet.of(Direction.NORTHWEST, Direction.SOUTHWEST);

    assertTrue(PvPTactics.engage(world.rc, world.observations()));

    // Both tiles have distance squared five. SOUTHWEST precedes NORTHWEST in Direction.values().
    assertEquals(at(9, 9), world.location);
    assertEquals(1, world.moves);
  }

  @Test
  public void equallyCloseApproachesKeepTheEngineDirectionOrder() throws Exception {
    World world = approachWorld();
    world.actionReady = false;
    world.allowedMoves = EnumSet.of(Direction.NORTHEAST, Direction.SOUTHEAST);

    assertTrue(PvPTactics.engage(world.rc, world.observations()));

    assertEquals(at(11, 11), world.location);
    assertEquals(1, world.moves);
    assertTrue(world.attackedIds.isEmpty());
  }

  @Test
  public void enemyKingFootprintRetainsTheExactLocalBoundaryAndRetreatTie() throws Exception {
    MapLocation[] centers = {at(13, 13), at(14, 13)};
    for (MapLocation center : centers) {
      World world = new World(king(30, Team.B, center, 600));
      world.cooperation = false;
      world.actionReady = false;
      world.allowedMoves = EnumSet.of(Direction.SOUTH, Direction.WEST);
      boolean withinLocalRange = center.equals(at(13, 13));

      assertEquals(withinLocalRange, PvPTactics.engage(world.rc, world.observations()));

      assertEquals(withinLocalRange ? at(10, 9) : at(10, 10), world.location);
      assertEquals(withinLocalRange ? 1 : 0, world.moves);
    }
  }

  @Test
  public void collidingSightingsKeepLatestSupportWhenProbingWrapsAround() throws Exception {
    for (int latestAllyHealth : new int[] {74, 100}) {
      // Five observations give sixteen slots. These IDs all start at slot fifteen.
      World world = new World(
          worker(15, Team.B, at(11, 10), 100, 0),
          worker(31, Team.A, at(11, 11), 100, 0),
          worker(47, Team.A, at(12, 10), 100, 0),
          worker(31, Team.A, at(11, 11), latestAllyHealth, 0),
          worker(47, Team.A, at(12, 10), 100, 1));
      boolean enoughSupport = latestAllyHealth == 100;

      assertEquals(enoughSupport, PvPTactics.engage(world.rc, world.observations()));

      assertEquals(enoughSupport ? Arrays.asList(15) : Arrays.asList(), world.attackedIds);
      assertEquals(Direction.EAST, world.direction);
      assertEquals(0, world.moves);
    }
  }

  @Test
  public void collidingEnemyUpdatesPreserveTargetHealthAndTurning() throws Exception {
    World world = new World(
        worker(15, Team.B, at(11, 10), 50, 0),
        worker(31, Team.B, at(10, 11), 90, 0),
        worker(47, Team.B, at(11, 11), 30, 0),
        worker(31, Team.B, at(10, 11), 5, 0));
    world.cooperation = false;

    assertTrue(PvPTactics.engage(world.rc, world.observations()));

    assertEquals(Arrays.asList(31), world.attackedIds);
    assertEquals(Direction.NORTH, world.direction);
    assertEquals(1, world.turns);
    assertEquals(at(9, 9), world.location);
  }

  private static World opening(int enemyHealth, int allyHealth) {
    return new World(
        worker(20, Team.B, at(11, 10), enemyHealth, 0),
        worker(2, Team.A, at(11, 11), allyHealth, 0));
  }

  private static World approachWorld() {
    World world = new World(
        worker(20, Team.B, at(12, 10), 100, 0),
        worker(2, Team.A, at(12, 11), 100, 0));
    world.cooperation = false;
    return world;
  }

  private static World focusApproachWorld() {
    World world = new World(
        worker(40, Team.B, at(10, 12), 80, 0),
        worker(20, Team.B, at(12, 10), 50, 0),
        worker(2, Team.A, at(11, 12), 100, 0),
        worker(3, Team.A, at(12, 11), 100, 0));
    world.cooperation = false;
    return world;
  }

  private static MapLocation at(int x, int y) {
    return new MapLocation(x, y);
  }

  private static RobotInfo worker(int id, Team team, MapLocation location, int health, int cargo) {
    return new RobotInfo(
        id, team, UnitType.BABY_RAT, health, location, Direction.EAST, 0, cargo, null);
  }

  private static RobotInfo king(int id, Team team, MapLocation location, int health) {
    return new RobotInfo(
        id, team, UnitType.RAT_KING, health, location, Direction.EAST, 0, 0, null);
  }

  private static RobotInfo cat(MapLocation location) {
    return new RobotInfo(
        1000, Team.NEUTRAL, UnitType.CAT, 4000, location, Direction.EAST, 0, 0, null);
  }

  /** Open terrain with real facing, legal bites, independent cooldowns and strict action checks. */
  private static final class World implements InvocationHandler {
    int id = 1;
    final RobotController rc;
    final List<RobotInfo> robots = new ArrayList<>();
    final List<Integer> attackedIds = new ArrayList<>();
    final List<MapLocation> attackedTiles = new ArrayList<>();
    final Set<MapLocation> walls = new HashSet<>();
    Set<Direction> allowedMoves = EnumSet.allOf(Direction.class);
    MapLocation location = at(10, 10);
    Direction direction = Direction.EAST;
    UnitType type = UnitType.BABY_RAT;
    int health = 100;
    int globalCheese = 1000;
    int cargo;
    int round = 100;
    boolean cooperation = true;
    boolean actionReady = true;
    boolean movementReady = true;
    boolean turningReady = true;
    int moves;
    int turns;

    World(RobotInfo... robots) {
      this.robots.addAll(Arrays.asList(robots));
      rc = (RobotController) Proxy.newProxyInstance(
          RobotController.class.getClassLoader(), new Class<?>[] {RobotController.class}, this);
    }

    RobotInfo[] observations() {
      return robots.toArray(new RobotInfo[0]);
    }

    void replace(RobotInfo robot) {
      robots.removeIf(previous -> previous.getID() == robot.getID());
      robots.add(robot);
    }

    private boolean visible(MapLocation target) {
      return onMap(target) && location.isWithinDistanceSquared(
          target, type.visionConeRadiusSquared, direction, type.visionConeAngle,
          type.usesBottomLeftLocationForDistance());
    }

    private boolean onMap(MapLocation target) {
      return target.x >= 0 && target.y >= 0 && target.x < 30 && target.y < 30;
    }

    private RobotInfo robotAt(MapLocation target) {
      Set<Integer> seen = new HashSet<>();
      for (int index = robots.size() - 1; index >= 0; index--) {
        RobotInfo robot = robots.get(index);
        if (!seen.add(robot.getID())) {
          continue;
        }
        for (MapLocation part : robot.getType().getAllTypeLocations(robot.getLocation())) {
          if (part.equals(target)) {
            return robot;
          }
        }
      }
      return null;
    }

    private boolean canMove(Direction step) {
      MapLocation target = location.add(step);
      return movementReady && step != Direction.CENTER && allowedMoves.contains(step)
          && onMap(target) && !walls.contains(target) && robotAt(target) == null;
    }

    boolean canAttack(MapLocation target) {
      RobotInfo victim = robotAt(target);
      int range = type == UnitType.RAT_KING
          ? GameConstants.RAT_KING_ATTACK_DISTANCE_SQUARED : GameConstants.ATTACK_DISTANCE_SQUARED;
      return actionReady && visible(target) && !location.equals(target)
          && location.distanceSquaredTo(target) <= range && !walls.contains(target)
          && victim != null && victim.getTeam() == Team.B;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      switch (method.getName()) {
        case "getID":
          return id;
        case "getTeam":
          return Team.A;
        case "getType":
          return type;
        case "getHealth":
          return health;
        case "getRawCheese":
          return cargo;
        case "getGlobalCheese":
          return globalCheese;
        case "getRoundNum":
          return round;
        case "getLocation":
          return location;
        case "isCooperation":
          return cooperation;
        case "isActionReady":
          return actionReady;
        case "canSenseLocation":
          return visible((MapLocation) args[0]);
        case "canAttack":
          assertEquals("Only the free bite overload is permitted", 1, method.getParameterCount());
          return canAttack((MapLocation) args[0]);
        case "attack":
          assertEquals("Only the free bite overload is permitted", 1, method.getParameterCount());
          MapLocation target = (MapLocation) args[0];
          assertTrue("Attack must satisfy facing, range and cooldown", canAttack(target));
          attackedIds.add(robotAt(target).getID());
          attackedTiles.add(target);
          actionReady = false;
          cooperation = false;
          return null;
        case "canTurn":
          return turningReady && (args == null || args.length == 0 || args[0] != direction);
        case "turn":
          assertTrue(turningReady);
          assertTrue(args[0] != direction && args[0] != Direction.CENTER);
          direction = (Direction) args[0];
          turningReady = false;
          turns++;
          return null;
        case "canMove":
          return canMove((Direction) args[0]);
        case "move":
          Direction step = (Direction) args[0];
          assertEquals("This helper must never move kings", UnitType.BABY_RAT, type);
          assertTrue("Combat movement must be legal and off cooldown", canMove(step));
          location = location.add(step);
          movementReady = false;
          moves++;
          return null;
        case "setIndicatorString":
          return null;
        default:
          throw new AssertionError("Unsupported controller operation: " + method.getName());
      }
    }
  }
}
