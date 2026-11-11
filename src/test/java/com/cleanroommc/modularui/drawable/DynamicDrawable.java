package com.cleanroommc.modularui.drawable;

import java.util.function.Supplier;

public class DynamicDrawable {
    public final Supplier<Object> supplier;
    public DynamicDrawable(Supplier<Object> supplier) { this.supplier = supplier; }
    public Supplier<Object> getSupplier() { return supplier; }
}
