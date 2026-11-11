package com.github.dcysteine.neicustomdiagram.api.diagram.component;
import java.util.Optional;
import net.minecraftforge.fluids.Fluid;
final class AutoValue_FluidComponent extends FluidComponent {
    private final Fluid fluid;
    private final Optional<ImmutableNbtWrapper> nbt;
    AutoValue_FluidComponent(Fluid fluid, Optional<ImmutableNbtWrapper> nbt) { this.fluid = fluid; this.nbt = nbt; }
    @Override public Fluid fluid() { return fluid; }
    @Override public Optional<ImmutableNbtWrapper> nbtWrapper() { return nbt; }
}
