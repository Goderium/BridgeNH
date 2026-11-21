package com.Goderium.BridgeNH.client.survival;

/** Completion rules shared by single-serving use and passive feeding. */
final class FoodEvidence {
    final String taskId = java.util.UUID.randomUUID().toString();
    final int foodBefore, countBefore;
    volatile int serverFood = -1;
    volatile boolean serverFinished;

    FoodEvidence(int food, int count) { foodBefore = food; countBefore = count; }
    boolean owns(String expectedId) { return taskId.equals(expectedId); }

    String observed(boolean eating, boolean using, int localFood, boolean sameKind, int countNow) {
        boolean gain = serverFood > foodBefore && localFood > foodBefore;
        if (!eating) {
            if (!sameKind || countNow != countBefore) return "hand_changed";
            return gain ? "replenished" : null;
        }
        // A final serving can leave an empty slot or an actual container item.
        boolean consumedOne = sameKind ? countNow == countBefore - 1 : countBefore == 1;
        return gain && serverFinished && !using && consumedOne ? "eaten" : null;
    }
}
