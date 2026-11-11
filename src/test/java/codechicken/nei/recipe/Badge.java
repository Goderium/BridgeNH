package codechicken.nei.recipe;
import java.util.Arrays;
import java.util.List;
public class Badge {
    public String getText() { return "NC"; }
    public List<String> getTooltip() { return Arrays.asList("not consumed", "badge second line"); }
    public int getColor() { return 123; }
    public boolean hasShadow() { return false; }
    public String getAlignment() { return "TopLeft"; }
    public void draw() { throw new AssertionError("No badge drawing"); }
}
