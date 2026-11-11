package com.github.dcysteine.neicustomdiagram.api.diagram.component;
import java.util.Optional;
import net.minecraftforge.fluids.Fluid;
public abstract class FluidComponent implements Component {
    public abstract Fluid fluid();
    public abstract Optional<ImmutableNbtWrapper> nbtWrapper();
    @Override public String description() { return "fixture fluid description: " + fluid().getName(); }
    public static FluidComponent generated(Fluid fluid, ImmutableNbtWrapper nbt) { return new AutoValue_FluidComponent(fluid, Optional.ofNullable(nbt)); }
}
