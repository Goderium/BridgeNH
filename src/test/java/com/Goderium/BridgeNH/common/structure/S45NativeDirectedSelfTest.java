package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.client.navigation.ApproachPlannerSelfTest;

/** Directed existing native-input/geometry fixtures; client/server operations remain mocked. */
public final class S45NativeDirectedSelfTest {
    public static void main(String[] args) throws Exception {
        System.out.println("Bogo native-input fixtures passed: " + BogoCreativeInputSelfTest.run() + " assertions.");
        System.out.println("Native shape-placement fixtures passed: " + NativePlacementSelfTest.run() + " assertions.");
        System.out.println("Approach planning fixtures passed: " + ApproachPlannerSelfTest.run() + " assertions.");
    }
}
