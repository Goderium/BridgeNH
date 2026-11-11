package cpw.mods.fml.common.registry;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.RegistryNamespaced;

/** Headless fixture using the normal in-memory namespaced registry, with no Forge loader or world remapping. */
public class FMLControlledNamespacedRegistry<I> extends RegistryNamespaced {
    FMLControlledNamespacedRegistry(String defaultName, int maximum, int minimum, Class<I> type, char discriminator) { }

    @Override public void addObject(int id, String name, Object value) {
        super.addObject(id, name, value);
        if (value instanceof Item) ((RegistryDelegate.Delegate<Item>) ((Item) value).delegate).setName(ensureNamespaced(name));
        else if (value instanceof Block) ((RegistryDelegate.Delegate<Block>) ((Block) value).delegate).setName(ensureNamespaced(name));
    }

    public RegistryDelegate<I> getDelegate(I value, Class<I> type) { return new RegistryDelegate.Delegate<I>(value, type); }
    public int getId(I value) { return getIDForObject(value); }
    public int getId(String name) { return getIDForObject(getObject(name)); }
}
