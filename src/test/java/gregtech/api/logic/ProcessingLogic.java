package gregtech.api.logic;

import gregtech.api.util.GTRecipe;

public class ProcessingLogic {
    protected GTRecipe lastRecipe;
    public ProcessingLogic(GTRecipe recipe) { lastRecipe = recipe; }
    public long getCalculatedEut() { return 400; }
    public int getDuration() { return 40; }
    public int getCurrentParallels() { return 4; }
    public void process() { throw new AssertionError("Machine processing must not run during inspection"); }
}
