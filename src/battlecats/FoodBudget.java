package battlecats;

import battlecode.common.GameConstants;

/** Global food reserve and a king-owned ledger for income-funded population growth. */
public final class FoodBudget {

  //PUBLIC VARS


  //PRIVATE VARS
  // Experiments for one king: request delivery at 100 food rounds and protect 150 food rounds.
  // The engine consumes two global cheese per king per round; more kings shorten this runway.
  private static final int DELIVERY_THRESHOLD = 200;    //what's this and why are we doing it
  private static final int SPENDING_RESERVE = 300;
  // Experiment: bootstrap or replace sixteen living babies; their initial purchases total 400.
  private static final int BOOTSTRAP_BABIES = 16;


  // Engine rule: base 10 + 10 * floor(living babies / 4). At sixteen babies the next cost is 50.

  private static final int GROWTH_COST_THRESHOLD =
      GameConstants.BUILD_ROBOT_BASE_COST
          + GameConstants.BUILD_ROBOT_COST_INCREASE
              * (BOOTSTRAP_BABIES / GameConstants.NUM_ROBOTS_FOR_COST_INCREASE);


  // Experiment: each birth invests its purchase price again in future food.
  private static final int GROWTH_INVESTMENT_MULTIPLIER = 2;
  // Accounting bound: no int-valued purchase can require more credit than this.
  private static final long MAX_GROWTH_CREDIT = (long) GROWTH_INVESTMENT_MULTIPLIER * Integer.MAX_VALUE;

  // Experiment: judge whether the map can feed a second king over the last 100 king turns.
  // Shorter windows react to a single large delivery; longer ones delay promotion past the
  // early game where an extra king's spawns pay off most.
  private static final int INCOME_WINDOW_TURNS = 100;
  // Engine rule: each king eats RAT_KING_CHEESE_CONSUMPTION cheese a round (2 in 1.2.6).
  // Experiment: require net bank growth over the window (after this king's upkeep and rat
  // construction, before its own spawning) to cover another king's upkeep plus 100 margin.
  // Untuned. In the 7-map comparison against team main e82c077, gross deliveries by round 250
  // were 1440-2660 on maps where a promoted king survived and at most about 500 on minimaze,
  // safelycontained and pipes, where it starved. Promotion also absorbs six collectors, which
  // this pre-promotion measure does not account for.
  private static final int PROMOTION_SURPLUS_PER_WINDOW =
      INCOME_WINDOW_TURNS * GameConstants.RAT_KING_CHEESE_CONSUMPTION + 100;

  private boolean hasBankSnapshot;
  private final int[] windowChanges = new int[INCOME_WINDOW_TURNS];
  private int nextWindowSlot;
  private boolean windowFull;
  private long windowNetIncome;
  private int bankAtPreviousBoundary;
  private long growthCredit;


  //PUBLIC METHODS
  public static boolean needsDelivery(int globalCheese) {
    return globalCheese <= DELIVERY_THRESHOLD;
  }

  /** Optional spending must preserve global food; carried cheese is not king food. */
  public static boolean canSpend(int globalCheese, int cost) {
    return cost >= 0 && (long) globalCheese - cost >= SPENDING_RESERVE;
  }

  /**
   * Observes net income since the previous end of turn. Deliveries can be hidden by upkeep,
   * construction or another king's spending; this conservative ledger never guesses gross income.
   * The initial bank is a baseline, not earned credit.
   */
  public void observeStart(int globalCheese) {
    if (globalCheese < 0) {
      throw new IllegalArgumentException("Global cheese cannot be negative");
    }
    if (hasBankSnapshot) {
      long change = (long) globalCheese - bankAtPreviousBoundary;
      growthCredit = Math.max(0, Math.min(MAX_GROWTH_CREDIT, growthCredit + change));
      recordWindowChange(change);
    }
    // Advance the baseline now too, so an interrupted turn cannot count the same income twice.
    bankAtPreviousBoundary = globalCheese;
    hasBankSnapshot = true;
  }

  /**
   * Returns whether net income over the last full window could feed another king. The window
   * starts with the first observed change, so the opening bank never counts as income.
   */
  public boolean canFeedAnotherKing() {
    return windowFull && windowNetIncome >= PROMOTION_SURPLUS_PER_WINDOW;
  }

  /** The engine's current cost bands also permit replacement when opening workers have died. */
  public boolean canSpawn(int globalCheese, int ratCost) {
    return ratCost > 0
        && canSpend(globalCheese, ratCost)
        && (ratCost < GROWTH_COST_THRESHOLD || growthCredit >= investment(ratCost));
  }

  /** Call only after a successful birth, using its cost before the population increased. */
  public void recordBirth(int ratCost) {
    if (ratCost <= 0) {
      throw new IllegalArgumentException("Rat cost must be positive");
    }
    growthCredit = Math.max(0, growthCredit - investment(ratCost));
  }

  /** Captures our own spending, so the next observation measures only changes between turns. */
  public void finishTurn(int globalCheese) {
    if (globalCheese < 0) {
      throw new IllegalArgumentException("Global cheese cannot be negative");
    }
    bankAtPreviousBoundary = globalCheese;
    hasBankSnapshot = true;
  }


  //PRIVATE METHODS
  private void recordWindowChange(long change) {
    // Bank values are non-negative ints, so a change between two of them fits in an int.
    int bounded = (int) change;
    windowNetIncome += bounded - windowChanges[nextWindowSlot];
    windowChanges[nextWindowSlot] = bounded;
    nextWindowSlot++;
    if (nextWindowSlot == INCOME_WINDOW_TURNS) {
      nextWindowSlot = 0;
      windowFull = true;
    }
  }

  private static long investment(int ratCost) {
    return (long) GROWTH_INVESTMENT_MULTIPLIER * ratCost;
  }
}
