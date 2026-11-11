package codechicken.nei.recipe.widget;
import codechicken.nei.PositionedStack;
import java.util.List;
public class RecipeGroup {
    public List<PositionedStack> inputs, outputs;
    public RecipeGroup(List<PositionedStack> inputs, List<PositionedStack> outputs) { this.inputs=inputs; this.outputs=outputs; }
    public List<PositionedStack> getInputs(int member) { return inputs; }
    public List<PositionedStack> getOutputs(int member) { return outputs; }
}
