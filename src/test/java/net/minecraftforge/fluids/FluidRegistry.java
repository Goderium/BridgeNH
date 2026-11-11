package net.minecraftforge.fluids;

import cpw.mods.fml.common.registry.RegistryDelegate;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Mock public fluid registry for plain-JVM tests; avoids initializing the actual Forge Loader. */
public final class FluidRegistry {
    private static final Map<String, Fluid> fluids = new HashMap<String, Fluid>();
    private static final Map<Fluid, RegistryDelegate<Fluid>> delegates = new IdentityHashMap<Fluid, RegistryDelegate<Fluid>>();
    public static final Fluid WATER = registered("water"), LAVA = registered("lava");
    private static Fluid registered(String name) { Fluid fluid = new Fluid(name); registerFluid(fluid); return fluid; }
    public static boolean registerFluid(Fluid fluid) {
        if (fluids.containsKey(fluid.getName())) return false;
        fluids.put(fluid.getName(), fluid);
        delegates.put(fluid, new RegistryDelegate.Delegate<Fluid>(fluid, Fluid.class));
        return true;
    }
    public static void forgetForTest(String name) { fluids.remove(name); }
    public static Fluid getFluid(String name) { return fluids.get(name); }
    public static Fluid getFluid(int id) { return null; }
    public static boolean isFluidRegistered(Fluid fluid) { return fluid != null && fluids.get(fluid.getName()) == fluid; }
    public static String getFluidName(Fluid fluid) { return fluid == null ? null : fluid.getName(); }
    public static String getFluidName(FluidStack fluid) { return getFluidName(fluid.getFluid()); }
    public static int getFluidID(Fluid fluid) { return isFluidRegistered(fluid) ? 1 : -1; }
    public static int getFluidID(String name) { return getFluidID(getFluid(name)); }
    static RegistryDelegate<Fluid> makeDelegate(Fluid fluid) { return delegates.get(fluid); }
}
