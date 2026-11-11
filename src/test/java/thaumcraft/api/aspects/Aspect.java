package thaumcraft.api.aspects;
public class Aspect {
    private final String tag, name, description;
    public Aspect(String tag, String name, String description) { this.tag = tag; this.name = name; this.description = description; }
    public String getTag() { return tag; }
    public String getName() { return name; }
    public String getLocalizedDescription() { return description; }
    public int getColor() { return 0x123456; }
}
