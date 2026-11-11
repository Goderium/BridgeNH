package gregtech.api.metatileentity.implementations;

import java.util.ArrayList;
import java.util.List;
import gregtech.api.logic.ProcessingLogic;
import gregtech.api.metatileentity.BaseMetaTileEntity.Message;
import gregtech.api.util.GTRecipe;

public class MTEMultiBlockBase {
    public boolean mMachine = true, mStructureChanged;
    public int mEfficiency = 9000, mEfficiencyIncrease = 100, lastParallel = 4, mProgresstime = 12, mMaxProgresstime = 40;
    public long recipesDone = 1234;
    public List<Object> mInputHatches = new ArrayList<>(), mOutputHatches = new ArrayList<>(), mInputBusses = new ArrayList<>(),
            mOutputBusses = new ArrayList<>(), mDualInputHatches = new ArrayList<>(), mDynamoHatches = new ArrayList<>(),
            mMufflerHatches = new ArrayList<>(), mEnergyHatches = new ArrayList<>(), mMaintenanceHatches = new ArrayList<>();
    protected final ProcessingLogic processingLogic = new ProcessingLogic(new GTRecipe());
    public Message getCheckRecipeResult() { return new Message(); }
    public int getMachineMode() { return 2; }
    public String getMachineModeName() { return "fixture mode"; }
    public boolean isInputSeparationEnabled() { return true; }
    public boolean isBatchModeEnabled() { return true; }
    public boolean isRecipeLockingEnabled() { return false; }
    public String getVoidingMode() { return "VOID_NONE"; }
    public int getMaxParallelRecipes() { return 64; }
    public int getTrueParallel() { return 16; }
    public int getPowerPanelMaxParallel() { return 8; }
    public long getMaxInputVoltage() { return 0; }
    public boolean isAlwaysMaxParallel() { return false; }
    public long getEUtForDamageCalc() { return -400; }
    protected long getActualEnergyUsage() { return 444; }
    public int getIdealStatus() { return 6; }
    public int getRepairStatus() { return 5; }
    public int getErrorDisplayID() { return 1; }
    public long getTotalRunTime() { return 999; }
    public long getLastWorkingTick() { return 777; }
    public String[] getInfoData() { return new String[] {"fixture machine info"}; }
    public List<Object> getExoticEnergyHatches() { return new ArrayList<>(); }
    public List<Object> getExoticDynamoHatches() { return new ArrayList<>(); }
    public List<Object> getCryotheumHatches() { return new ArrayList<>(); }
    public List<Object> getPyrotheumHatches() { return new ArrayList<>(); }
    public void checkStructure() { throw new AssertionError("Inspection must not recheck structures"); }
}
