package com.Goderium.BridgeNH.client.control;

import com.Goderium.BridgeNH.util.BridgeException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Exact-instance/owner/context fixtures, without a transformed KeyBinding or live movement. */
public final class OwnedKeyStateSelfTest {
    private static int checks;
    public static void main(String[] args) throws Exception { System.out.println("OwnedKeyStateSelfTest: " + run() + " checks passed"); }
    public static int run() throws Exception {
        checks = 0; Object sneak = new Object(), sibling = new Object(); AtomicLong clock = new AtomicLong(); AtomicBoolean context = new AtomicBoolean(true);
        OwnedKeyState.begin("owner", 100, context::get, clock::get); OwnedKeyState.hold("owner", sneak, 42);
        try { OwnedKeyState.begin("other",100,context::get,clock::get); throw new AssertionError("Expected exclusive lease"); }
        catch(BridgeException error) { check("busy".equals(error.code) && OwnedKeyState.active("owner"),"A competing task cannot steal the existing native input lease."); }
        check(OwnedKeyState.active("owner") && !OwnedKeyState.active("other"), "only the live task owner passes reassertion checks");
        check(Boolean.TRUE.equals(OwnedKeyState.override(sneak, 42)), "logical hold does not require a physical modifier");
        check(((Number) OwnedKeyState.trace(sneak).get("getterAccepted")).longValue() == 1 && "owned_hold".equals(OwnedKeyState.trace(sneak).get("lastGetterDecision")), "getter evidence belongs to the exact held object");
        check(OwnedKeyState.override(sibling, 42) == null, "same-code sibling bindings retain native behavior");
        check(Boolean.FALSE.equals(OwnedKeyState.trace(sibling).get("available")), "same-code sibling does not gain target hook evidence");
        OwnedKeyState.release("other", sneak); check(Boolean.TRUE.equals(OwnedKeyState.override(sneak, 42)), "another owner cannot release the binding");
        OwnedKeyState.hold("other", sibling, 42); check(OwnedKeyState.override(sibling, 42) == null, "another owner cannot add a binding");
        check(!OwnedKeyState.nativeRead(() -> Boolean.TRUE.equals(OwnedKeyState.override(sneak, 42))), "native diagnostic read suspends the logical override");
        check(((Number) OwnedKeyState.trace(sneak).get("getterSuspended")).longValue() == 1, "native diagnostic suspension is distinguished from a rejected effective read");
        check(Boolean.TRUE.equals(OwnedKeyState.override(sneak, 42)), "native read restores the active owner afterward");
        try { OwnedKeyState.nativeRead(() -> { throw new IllegalStateException("getter fixture"); }); throw new AssertionError("Expected failed getter"); }
        catch (IllegalStateException expected) { check(Boolean.TRUE.equals(OwnedKeyState.override(sneak, 42)), "failed native getter still restores the logical owner scope"); }
        try (OwnedKeyState.Scope first = OwnedKeyState.suspend(); OwnedKeyState.Scope second = OwnedKeyState.suspend()) {
            check(OwnedKeyState.override(sneak, 42) == null, "nested native reads remain suspended");
            second.close(); check(OwnedKeyState.override(sneak, 42) == null, "closing an inner scope keeps the outer suspension");
        }
        check(Boolean.TRUE.equals(OwnedKeyState.override(sneak, 42)), "nested scopes restore the original logical hold");
        AtomicBoolean foreign = new AtomicBoolean(); Thread thread = new Thread(() -> foreign.set(OwnedKeyState.override(sneak, 42) == null)); thread.start(); thread.join(1000);
        check(!thread.isAlive() && foreign.get(), "server or other threads never receive a client logical key");
        OwnedKeyState.release("owner", sneak); check(OwnedKeyState.override(sneak, 42) == null && OwnedKeyState.heldCount() == 0, "normal release immediately resumes native reads");
        OwnedKeyState.hold("owner", sneak, 42); OwnedKeyState.releaseAll("other"); check(Boolean.TRUE.equals(OwnedKeyState.override(sneak, 42)), "another owner cannot transiently release navigation keys");
        OwnedKeyState.releaseAll("owner"); check(OwnedKeyState.override(sneak, 42) == null && OwnedKeyState.heldCount() == 0, "geometry wait releases every exact object without discarding the owner's lease");
        OwnedKeyState.hold("owner", sneak, 42); check(Boolean.TRUE.equals(OwnedKeyState.override(sneak, 42)), "navigation may resume a declared hold after a transient geometry wait");
        OwnedKeyState.hold("owner", sneak, 42); check(OwnedKeyState.override(sneak, 54) == null && OwnedKeyState.heldCount() == 0, "rebinding invalidates exact owned code without moving the hold to another key");
        check("binding_code_changed".equals(OwnedKeyState.trace(sneak).get("lastGetterDecision")), "binding-code mismatch has an explicit small rejection reason");
        OwnedKeyState.hold("owner", sneak, 42); context.set(false); check(OwnedKeyState.override(sneak, 42) == null && OwnedKeyState.heldCount() == 0, "GUI/world/profile/agent context failure drops ownership");
        OwnedKeyState.begin("owner", 100, context::get, clock::get); OwnedKeyState.hold("owner", sneak, 42);
        check(!OwnedKeyState.active("owner") && OwnedKeyState.heldCount() == 0, "tick reassertion fails closed before writing a native key in changed context");
        context.set(true); OwnedKeyState.begin("owner", 100, context::get, clock::get); OwnedKeyState.hold("owner", sneak, 42); clock.set(100);
        check(OwnedKeyState.override(sneak, 42) == null && OwnedKeyState.heldCount() == 0, "timeout invalidates even before another game tick");
        check("context_or_deadline_rejected".equals(OwnedKeyState.trace(sneak).get("lastGetterDecision")), "timeout rejection remains available as target evidence after releasing ownership");
        clock.set(0); OwnedKeyState.begin("owner", 100, () -> { throw new IllegalStateException("fixture"); }, clock::get); OwnedKeyState.hold("owner", sneak, 42);
        check(OwnedKeyState.override(sneak, 42) == null, "failed context observation preserves native behavior");
        OwnedKeyState.begin("owner", 100, context::get, clock::get); for (int index = 0; index < 9; index++) OwnedKeyState.hold("owner", new Object(), 17);
        try { OwnedKeyState.hold("owner", new Object(), 17); throw new AssertionError("Expected finite cap"); } catch (BridgeException error) { check("owned_input_limit".equals(error.code) && OwnedKeyState.heldCount() == 9, "at most nine exact binding objects are owned"); }
        OwnedKeyState.clear("other"); check(OwnedKeyState.heldCount() == 9, "another owner cannot clear active input");
        OwnedKeyState.clear("owner"); check(OwnedKeyState.heldCount() == 0, "agent-off/owner completion removes all scoped keys");
        OwnedKeyState.begin("owner", 100, context::get, clock::get); OwnedKeyState.hold("owner", sneak, 0); check(OwnedKeyState.override(sneak, 0) == null, "unbound keys never gain a logical hold"); OwnedKeyState.clear("owner");
        OwnedKeyState.begin("owner", 100, context::get, clock::get); OwnedKeyState.hold("owner", sneak, 42); OwnedKeyState.clear("owner");
        check(Boolean.TRUE.equals(OwnedKeyState.trace(sneak).get("available")), "released target evidence is retained briefly for immediate diagnosis");
        clock.set(10000000001L); check(Boolean.FALSE.equals(OwnedKeyState.trace(sneak).get("available")), "historical target trace expires within ten seconds");
        clock.set(0);
        for (String captured : new String[] {"player", "developer"}) {
            AtomicReference<String> profile = new AtomicReference<String>(captured);
            OwnedKeyState.begin("owner", 100, () -> captured.equals(profile.get()), clock::get); OwnedKeyState.hold("owner", sneak, 42);
            check(Boolean.TRUE.equals(OwnedKeyState.override(sneak, 42)), "Existing low-level player input and developer navigation both retain their captured profile.");
            profile.set("player".equals(captured) ? "developer" : "player");
            check(OwnedKeyState.override(sneak, 42) == null && OwnedKeyState.heldCount() == 0, "A changed profile invalidates the exact input lease without moving ownership.");
        }
        Object attack = new Object(); context.set(true); clock.set(0);
        check(OwnedKeyState.attackInput(true, attack, -100), "Native manual mining is unchanged without an owner.");
        check(!OwnedKeyState.attackInput(false, attack, -100), "Unfocused manual input is not enabled.");
        OwnedKeyState.begin("player.navigate", 100, context::get, clock::get); OwnedKeyState.hold("player.navigate", attack, -100);
        check(!OwnedKeyState.attackInput(false, attack, -100), "Navigation cannot acquire background mining through another owner.");
        OwnedKeyState.clear("player.navigate");
        OwnedKeyState.begin("player.input", 100, context::get, clock::get); OwnedKeyState.hold("player.input", attack, -100);
        check(OwnedKeyState.attackInput(false, attack, -100), "The exact live attack lease enables ordinary native mining.");
        check(!OwnedKeyState.attackInput(false, sibling, -100), "Same-code sibling is not the leased attack.");
        try (OwnedKeyState.Scope ignored = OwnedKeyState.suspend()) {
            check(!OwnedKeyState.attackInput(false, attack, -100), "Native diagnostics cannot activate mining.");
        }
        AtomicBoolean foreignAttack = new AtomicBoolean();
        Thread attackThread = new Thread(() -> foreignAttack.set(OwnedKeyState.attackInput(false, attack, -100)));
        attackThread.start(); attackThread.join(1000);
        check(!attackThread.isAlive() && !foreignAttack.get(), "Only the owner client thread can mine.");
        context.set(false);
        check(!OwnedKeyState.attackInput(false, attack, -100), "GUI, world, actor or background change stops mining.");
        context.set(true); OwnedKeyState.begin("player.input", 100, context::get, clock::get); OwnedKeyState.hold("player.input", attack, -100);
        clock.set(100); check(!OwnedKeyState.attackInput(false, attack, -100), "The input deadline still stops mining.");
        clock.set(0); OwnedKeyState.begin("player.input", 100, context::get, clock::get); OwnedKeyState.hold("player.input", attack, -100);
        check(!OwnedKeyState.attackInput(false, attack, 42), "Changed attack binding code refuses mining.");
        OwnedKeyState.hold("player.input", attack, -100); OwnedKeyState.release("player.input", attack);
        check(!OwnedKeyState.attackInput(false, attack, -100), "Released attack immediately returns native false/reset behavior.");
        OwnedKeyState.clear("player.input");
        return checks;
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}
