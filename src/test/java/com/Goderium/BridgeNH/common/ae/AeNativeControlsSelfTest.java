package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.util.BridgeException;

import appeng.client.gui.implementations.GuiCraftingStatus;
import appeng.client.gui.implementations.GuiInterfaceTerminal;
import appeng.container.implementations.ContainerCPUTable;
import appeng.core.sync.packets.PacketCraftingItemInterface;
import appeng.core.sync.packets.PacketInventoryAction;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** Native packet identity and synchronization boundaries; no machine/GUI rendering is simulated as acceptance. */
public class AeNativeControlsSelfTest {
    private static int checks;
    public static int run() {
        checks = 0; AeNativeControls.invalidate();
        net.minecraft.inventory.InventoryBasic nativeInventory = new net.minecraft.inventory.InventoryBasic("native pattern slots", true, 36);
        check(AeDataAdapter.inventorySize(nativeInventory) == 36, "Vanilla IInventory uses compiled methods, which retain MCP/SRG remapping");
        check(AeDataAdapter.inventoryItem(nativeInventory, 35) == null, "Native remote inventory reads its final empty slot without reflective Minecraft method names");
        GuiInterfaceTerminal screen = new GuiInterfaceTerminal();
        GuiInterfaceTerminal.InterfaceTerminalEntry first = new GuiInterfaceTerminal.InterfaceTerminalEntry(51, 36);
        GuiInterfaceTerminal.InterfaceTerminalEntry second = new GuiInterfaceTerminal.InterfaceTerminalEntry(52, 9);
        screen.masterList.list.put(51L, first); screen.masterList.list.put(52L, second);
        List<Object> controls = AeNativeControls.discover(screen);
        check(controls.size() == 47, "Provider inventories preserve distinct 36/9-slot capacities, plus individual highlighters");
        check(controls.get(36) == AeNativeControls.discover(screen).get(36), "Unchanged native provider keeps a stable slot object");
        List<Object> packets = new ArrayList<Object>();
        AeNativeControls.activate(controls.get(36), packets::add);
        PacketInventoryAction packet = (PacketInventoryAction) packets.get(0);
        check(packet.id == 51 && packet.slot == 35 && packet.action.name().equals("PICKUP_OR_SET_DOWN"), "Last CRIB slot uses remote provider ID and native slot35, never vanilla window numbering");
        check(!AeNativeControls.describe(controls.get(36)).has("serverConfirmed"), "Empty synchronized slot is not an authoritative mutation receipt");
        screen.masterList.list.remove(51L);
        check(!AeNativeControls.discover(screen).contains(controls.get(36)), "Removed provider's stale control cannot be rediscovered for widget dispatch");
        AeNativeControls.discover(new Object());
        expect("stale_screen", () -> AeNativeControls.activate(controls.get(36), packets::add));
        check(packets.size() == 1, "Stale screen submits no packet");

        GuiCraftingStatus cpu = new GuiCraftingStatus(); ContainerCPUTable table = new ContainerCPUTable();
        cpu.table = new AeGuiFixtures.CpuTableHolder(table);
        AeGuiFixtures.Stack stack = new AeGuiFixtures.Stack(new AeGuiFixtures.Type("item"), new ItemStack(new Item(), 1, 0), "tagged-A", 9007199254740993L);
        cpu.visualState.entries.put("A", new AeGuiFixtures.JobEntry(stack));
        Object locate = AeNativeControls.discover(cpu).get(0);
        check(!AeNativeControls.describe(locate).get("enabled").getAsBoolean(), "A wireless host without native IGridHost is explicitly disabled.");
        expect("unsupported", () -> AeNativeControls.activate(locate, packets::add));
        check(packets.size() == 1 && !AeNativeControls.locator(cpu).get("available").getAsBoolean(), "Unsupported native host sends no packet and reserves no pending lookup.");
        cpu.container.owner = new appeng.api.networking.IGridHost() {};
        cpu.container.openContext = null;
        check(AeNativeControls.describe(locate).get("enabled").getAsBoolean(), "Client openContext is not synchronized; a null client value must not disable a valid native grid-host lookup.");
        cpu.postInterfaceLocationsUpdate(Collections.emptyList());
        AeNativeControls.activate(locate, packets::add);
        PacketCraftingItemInterface query = (PacketCraftingItemInterface) packets.get(1);
        check(query.stack != stack && ((AeGuiFixtures.Stack) query.stack).nbtIdentity.equals("tagged-A") && query.stack.getStackSize() == stack.amount, "Provider query retains actual copied AEStack identity and long amount");
        check(cpu.hoveredInterfaceLocations == null && AeNativeControls.locator(cpu).get("pending").getAsBoolean(), "Old native hover response is cleared through its normal public update method");
        expect("busy", () -> AeNativeControls.activate(locate, packets::add));
        check(packets.size() == 2, "Pending query cannot be sent again");
        table.selectedCpuSerial++;
        cpu.postInterfaceLocationsUpdate(Collections.emptyList()); AeNativeControls.observe(cpu);
        JsonObject result = AeNativeControls.locator(cpu);
        check(result.get("invalidated").getAsBoolean() && !result.get("pending").getAsBoolean(), "CPU change invalidates an outstanding uncorrelated native reply");
        AeNativeControls.invalidate(); check(!AeNativeControls.locator(cpu).get("available").getAsBoolean(), "Closing GUI releases pending locator ownership");
        Object fresh = AeNativeControls.discover(cpu).get(0);
        AeNativeControls.activate(fresh, packets::add);
        cpu.postInterfaceLocationsUpdate(Collections.singletonList(new appeng.api.util.NamedDimensionalCoord(3, 71, -8, 0, "native provider")));
        JsonObject reply = AeNativeControls.locator(cpu);
        check(!reply.get("pending").getAsBoolean() && !reply.get("invalidated").getAsBoolean()
                && reply.getAsJsonArray("locations").get(0).getAsJsonObject().get("x").getAsInt() == 3,
            "Actual native coordinate reads inherited WorldCoord fields and preserves provider dimension/name");
        AeNativeControls.invalidate();
        return checks;
    }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException failure) { check(code.equals(failure.code), code); }
    }
}
