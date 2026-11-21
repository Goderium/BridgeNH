package com.Goderium.BridgeNH.client.survival;

public final class FoodEvidenceSelfTest {
    private static int checks;
    private static void expect(String expected, String actual) {
        checks++;
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Food receipt: " + actual);
    }
    public static int run() {
        FoodEvidence food = new FoodEvidence(12, 3);
        expect(null, food.observed(true, false, 16, true, 2)); // Client prediction only.
        food.serverFood = 16;
        expect(null, food.observed(true, false, 16, true, 2)); // Other causes can raise hunger.
        food.serverFinished = true;
        expect(null, food.observed(true, true, 16, true, 2));
        expect(null, food.observed(true, false, 16, true, 3));
        expect(null, food.observed(true, false, 16, true, 1));
        expect(null, food.observed(true, false, 16, false, 0));
        expect(null, food.observed(true, false, 12, true, 2));
        expect("eaten", food.observed(true, false, 16, true, 2));
        FoodEvidence last = new FoodEvidence(18, 1);
        last.serverFinished = true; last.serverFood = 20;
        expect("eaten", last.observed(true, false, 20, false, 0));
        expect("eaten", last.observed(true, false, 20, false, 1)); // Native bowl returned.
        FoodEvidence axe = new FoodEvidence(18, 1);
        expect("owned", food.owns(food.taskId) ? "owned" : null);
        expect(null, axe.owns(food.taskId) ? "stale" : null);
        expect(null, axe.owns(null) ? "missing" : null);
        expect(null, axe.observed(false, false, 19, true, 1));
        axe.serverFood = 19;
        expect("hand_changed", axe.observed(false, false, 19, false, 1));
        expect("hand_changed", axe.observed(false, false, 19, true, 0));
        expect("replenished", axe.observed(false, false, 19, true, 1));
        return checks;
    }
}
