package com.Nxer.TwistSpaceTechnology.common.modularizedMachine.ModularizedMachineLogic;

import java.util.Collection;
import com.Nxer.TwistSpaceTechnology.common.modularizedMachine.modularHatches.IModularHatch;

/** Read-only public interface fixture; no per-machine implementation or client bootstrapping. */
public interface IModularizedMachine {
    Collection<IModularHatch> getAllModularHatches();
    interface ISupportExecutionCore extends IModularizedMachine { }
}
