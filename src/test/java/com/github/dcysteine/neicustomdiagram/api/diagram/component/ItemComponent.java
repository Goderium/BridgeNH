package com.github.dcysteine.neicustomdiagram.api.diagram.component;
import java.util.Optional;
import net.minecraft.item.Item;
public class ItemComponent implements Component {
    private final Item item;
    public ItemComponent(Item item) { this.item = item; }
    public Item item() { return item; }
    public int damage() { return 9; }
    public Optional<Object> nbtWrapper() { return Optional.empty(); }
    public String description() { return "fixture component description"; }
}
