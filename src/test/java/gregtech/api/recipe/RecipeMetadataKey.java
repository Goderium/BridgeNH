package gregtech.api.recipe;

public class RecipeMetadataKey<T> {
    private final Class<T> clazz;
    private final String identifier;
    public RecipeMetadataKey(Class<T> clazz, String identifier) { this.clazz = clazz; this.identifier = identifier; }
    @Override public String toString() { throw new AssertionError("Metadata key arbitrary toString must not be called"); }
}
