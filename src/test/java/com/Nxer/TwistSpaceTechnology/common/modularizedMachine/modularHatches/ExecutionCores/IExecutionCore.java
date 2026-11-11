package com.Nxer.TwistSpaceTechnology.common.modularizedMachine.modularHatches.ExecutionCores;

import com.Nxer.TwistSpaceTechnology.common.modularizedMachine.ModularizedMachineLogic.IModularizedMachine;

/** Only the documented read-only core methods are needed by these fixtures. */
public interface IExecutionCore {
    boolean isIdle();
    boolean isWorking();
    boolean useMainMachinePower();
    long getEut();
    int getNeedProgressingTime();
    IModularizedMachine.ISupportExecutionCore getMainMachine();
}
