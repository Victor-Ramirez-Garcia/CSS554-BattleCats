package battlecats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure bank-ledger cases; amounts are test controls rather than simulated map income. */
public class FoodBudgetTest {
  @Test
  public void requestsDeliveryAtTheLowFoodBoundary() {
    for (int bank : new int[] {-1, 0, 199, 200, 201, Integer.MAX_VALUE}) {
      assertEquals("Bank=" + bank, bank <= 200, FoodBudget.needsDelivery(bank));
    }
  }

  @Test
  public void optionalSpendingPreservesTheGlobalReserveWithoutIntegerOverflow() {
    int[][] cases = {
      {299, 0, 0}, {300, 0, 1}, {349, 50, 0}, {350, 50, 1},
      {Integer.MAX_VALUE, Integer.MAX_VALUE, 0},
      {Integer.MAX_VALUE, Integer.MAX_VALUE - 300, 1},
      {Integer.MIN_VALUE, Integer.MAX_VALUE, 0}, {1000, -1, 0}
    };
    for (int[] scenario : cases) {
      assertEquals(
          "Bank=" + scenario[0] + ", cost=" + scenario[1],
          scenario[2] == 1,
          FoodBudget.canSpend(scenario[0], scenario[1]));
    }
  }

  @Test
  public void initialBankFundsBootstrapButIsNotEarnedGrowthCredit() {
    FoodBudget budget = new FoodBudget();
    budget.observeStart(2500);
    budget.finishTurn(2500);
    budget.observeStart(2500);

    assertTrue(budget.canSpawn(2500, 10));
    assertTrue(budget.canSpawn(2500, 20));
    assertTrue(budget.canSpawn(2500, 30));
    assertTrue(budget.canSpawn(2500, 40));
    assertFalse("The next engine band represents at least sixteen living babies",
        budget.canSpawn(2500, 50));
    assertFalse(budget.canSpawn(2500, 0));
    assertFalse(budget.canSpawn(2500, -1));
  }

  @Test
  public void lowerCostBandAllowsReplacementWithoutInventingIncome() {
    FoodBudget budget = new FoodBudget();
    budget.observeStart(1000);
    budget.finishTurn(980);
    budget.observeStart(978);

    assertTrue(budget.canSpawn(978, 20));
    budget.recordBirth(20);
    assertFalse(budget.canSpawn(958, 50));
    assertTrue("Another replacement remains possible while the reserve permits it",
        budget.canSpawn(958, 10));
  }

  @Test
  public void growthAccumulatesNetIncomeAcrossTurnsAtTheExactInvestmentBoundary() {
    FoodBudget budget = new FoodBudget();
    budget.observeStart(1000);
    budget.finishTurn(1000);
    budget.observeStart(1099);
    assertFalse(budget.canSpawn(1099, 50));
    budget.finishTurn(1099);

    budget.observeStart(1100);

    assertTrue(budget.canSpawn(1100, 50));
    budget.recordBirth(50);
    assertFalse("A successful growth purchase consumes its earned credit",
        budget.canSpawn(1050, 50));
  }

  @Test
  public void endOfTurnSnapshotExcludesOurOwnPurchaseFromTheNextIncomeDelta() {
    FoodBudget budget = new FoodBudget();
    budget.observeStart(1000);
    budget.finishTurn(1000);
    budget.observeStart(1200);
    budget.recordBirth(50);
    budget.finishTurn(1150);

    budget.observeStart(1150);

    assertTrue("The remaining 100 credit should still fund another 50-cheese birth",
        budget.canSpawn(1150, 50));
  }

  @Test
  public void signedBankLossConsumesCreditAndFloorsAtZero() {
    FoodBudget budget = new FoodBudget();
    budget.observeStart(1000);
    budget.finishTurn(1000);
    budget.observeStart(1140);
    budget.finishTurn(1140);
    budget.observeStart(1100);
    assertTrue(budget.canSpawn(1100, 50));
    budget.finishTurn(1100);
    budget.observeStart(1099);
    assertFalse(budget.canSpawn(1099, 50));
    budget.finishTurn(1099);
    budget.observeStart(800);
    budget.finishTurn(800);
    budget.observeStart(899);
    assertFalse(budget.canSpawn(899, 50));
    budget.finishTurn(899);

    budget.observeStart(900);

    assertTrue("Old losses must not become a permanent debt", budget.canSpawn(900, 50));
  }

  @Test
  public void bootstrapPurchasesAlsoConsumePreviouslyEarnedCredit() {
    FoodBudget budget = new FoodBudget();
    budget.observeStart(1000);
    budget.finishTurn(1000);
    budget.observeStart(1140);
    budget.recordBirth(20);
    budget.finishTurn(1120);
    budget.observeStart(1120);
    assertTrue(budget.canSpawn(1120, 50));
    budget.recordBirth(10);
    budget.finishTurn(1110);

    budget.observeStart(1110);

    assertFalse(budget.canSpawn(1110, 50));
  }

  @Test
  public void eligibilityChecksDoNotChargeForUnsuccessfulBirths() {
    FoodBudget budget = new FoodBudget();
    budget.observeStart(1000);
    budget.finishTurn(1000);
    budget.observeStart(1100);

    for (int attempt = 0; attempt < 3; attempt++) {
      assertTrue(budget.canSpawn(1100, 50));
    }
  }

  @Test
  public void interruptedTurnCannotCountTheSameIncomeTwice() {
    FoodBudget budget = new FoodBudget();
    budget.observeStart(1000);
    budget.finishTurn(1000);
    budget.observeStart(1100);
    // The previous turn ended before finishTurn, for example after an engine action exception.
    budget.observeStart(1100);
    budget.recordBirth(50);

    assertFalse(budget.canSpawn(1050, 50));
  }

  @Test
  public void creditSaturatesAtTheLargestPossibleInvestmentWithoutOverflow() {
    FoodBudget budget = new FoodBudget();
    budget.observeStart(0);
    for (int turn = 0; turn < 3; turn++) {
      budget.finishTurn(0);
      budget.observeStart(Integer.MAX_VALUE);
    }
    int hugeCost = Integer.MAX_VALUE - 300;
    assertTrue(budget.canSpawn(Integer.MAX_VALUE, hugeCost));
    budget.recordBirth(hugeCost);
    budget.finishTurn(300);
    budget.observeStart(300);
    assertTrue(budget.canSpawn(1000, 300));
    budget.recordBirth(300);

    assertFalse("Saturation leaves exactly 600 credit after the huge purchase",
        budget.canSpawn(1000, 50));
  }

  @Test
  public void ledgerRejectsInvalidBankSnapshotsAndPurchaseCosts() {
    FoodBudget budget = new FoodBudget();

    assertThrows(IllegalArgumentException.class, () -> budget.observeStart(-1));
    assertThrows(IllegalArgumentException.class, () -> budget.finishTurn(-1));
    assertThrows(IllegalArgumentException.class, () -> budget.recordBirth(0));
    assertThrows(IllegalArgumentException.class, () -> budget.recordBirth(-1));

    assertTrue(
        "Invalid inputs must not corrupt the usable default budget", budget.canSpawn(310, 10));
  }
}
