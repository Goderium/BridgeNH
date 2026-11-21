package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.loader.rfb.GuiMouseScope;
import com.Goderium.BridgeNH.loader.rfb.GuiMouseTransformer;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;
import com.google.gson.JsonObject;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;

/** A GUI-scoped device. Only the Minecraft thread owns its state and dispatches native GUI events. */
public final class VirtualGuiMouse implements GuiMouseScope.Device {
    public static final String OPERATION = "gui.virtual_mouse";
    public static final VirtualGuiMouse INSTANCE = new VirtualGuiMouse();
    private static final long REQUEST_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long HOLD_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final int NONE = -1;
    private final boolean installed;
    private final GuiMouseOverlay overlay = new GuiMouseOverlay();
    private BooleanSupplier agentBusy;
    private boolean focusedTextThisFrame;
    private long textFeedbackUntil;
    private long thinkingUntil;
    // Multiple conversations may explicitly share a client. Finishing one must preserve the others.
    private final java.util.Map<UUID, Long> compactions = new java.util.HashMap<>();
    private long compactionUntil;
    private Minecraft client;
    private GuiScreen screen;
    private GuiScreen directPress;
    private Object world;
    private BooleanSupplier protection, holdGuard;
    private boolean boundTakeover;
    private int width, height, displayWidth, displayHeight, px, py;
    private int held = NONE;
    private String owner;
    private long holdDeadline, highlightUntil;
    private Request pending;
    private boolean rendered, dispatching;
    private String interrupted;

    private VirtualGuiMouse() {
        boolean present;
        try { Mouse.class.getMethod(GuiMouseTransformer.MARKER); present=true; }
        catch (ReflectiveOperationException | LinkageError absent) { present=false; }
        installed=present;
    }
    public boolean installed() { return installed; }

    public void bind(Minecraft mc, BooleanSupplier takeover, BooleanSupplier busy) {
        if (!installed) return;
        boolean taking = takeover.getAsBoolean();
        if (client == mc && boundTakeover == taking && (!taking || active()
                || screen == null && mc.currentScreen == null && world == mc.theWorld)) return;
        if (screen instanceof GuiMouseReset.ContainerDrag) ((GuiMouseReset.ContainerDrag)screen).mcb$forgetVirtualClicks();
        cancel("Screen, size, world or input ownership changed.");
        screen=null; client=mc; protection=takeover; agentBusy=busy;
        boundTakeover=taking; world=mc.theWorld; highlightUntil=0;
        focusedTextThisFrame=false; textFeedbackUntil=0; overlay.reset();
        if (!taking) { GuiMouseScope.detach(); return; }
        GuiMouseScope.attach(this);
        if (mc.currentScreen==null || mc.currentScreen.width<=0 || mc.currentScreen.height<=0) return;
        screen=mc.currentScreen;
        width=screen.width; height=screen.height;
        displayWidth=mc.displayWidth; displayHeight=mc.displayHeight;
        px=width/2; py=height/2; rendered=false; highlightUntil=0;
    }
    @Override public boolean protectingGui() {
        return client!=null && client.currentScreen!=null && protection.getAsBoolean();
    }
    public boolean active() {
        return screen!=null && client.currentScreen==screen && client.theWorld==world && screen.width==width && screen.height==height
                && client.displayWidth==displayWidth && client.displayHeight==displayHeight && protection.getAsBoolean();
    }
    // Use the first raw pixel of the requested scaled cell (vanilla rounds down on the inverse conversion).
    private static int pixel(int scaled,int rawSize,int guiSize) {
        return (int)(((long)scaled*rawSize+guiSize-1)/guiSize);
    }
    private int rawX(int x) { return pixel(x,displayWidth,width); }
    private int rawY(int y) { return pixel(height-y-1,displayHeight,height); }
    @Override public int x() { if (!active()) return client.displayWidth/2; Point p=LogicalGuiPointer.devicePoint(screen); return rawX(p==null?px:p.x); }
    @Override public int y() { if (!active()) return client.displayHeight/2; Point p=LogicalGuiPointer.devicePoint(screen); return rawY(p==null?py:p.y); }
    @Override public boolean down(int button) { return button>=0 && held==button && active(); }

    public JsonObject inspect() {
        JsonObject result=new JsonObject();
        boolean enabled=active();
        long now = System.nanoTime();
        boolean worldStatus = worldStatusVisible();
        result.addProperty("active",enabled);
        result.addProperty("displayMode", enabled ? (showPointer(now) ? "pointer" : "status") : worldStatus ? "status" : "hidden");
        if (enabled || worldStatus) {
            result.addProperty("appearance", appearance(now).name().toLowerCase(java.util.Locale.ROOT));
            result.addProperty("compactionActive", compactionUntil != 0 && now < compactionUntil);
            result.addProperty("thinkingRemainingMillis", Math.max(0, TimeUnit.NANOSECONDS.toMillis(thinkingUntil - now)));
        }
        if (enabled) {
            Point point=LogicalGuiPointer.devicePoint(screen);
            result.addProperty("x",point==null?px:point.x);
            result.addProperty("y",point==null?py:point.y);
            result.addProperty("heldButton",held);
            result.addProperty("gesturePending",pending!=null);
            if (owner!=null) result.addProperty("holdRemainingMillis",Math.max(0,TimeUnit.NANOSECONDS.toMillis(holdDeadline-System.nanoTime())));
        }
        return result;
    }

    public void requireIdle() {
        if (pending!=null || owner!=null) throw new BridgeException("busy","Another virtual mouse gesture owns GUI input.");
    }
    public void legacyClick(GuiScreen gui,int x,int y) {
        requireIdle();
        if (gui==screen && active()) {
            LogicalGuiPointer.clear("coordinate_click");
            px=x; py=y;
            highlightUntil=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(120);
        }
    }

    void callback(GuiScreen gui,int x,int y,int button,boolean press,Runnable callback) {
        GuiScreen previous = directPress;
        if (press) directPress = gui;
        try {
            if (gui!=screen || !active()) { callback.run(); return; }
            px = x; py = y;
            highlightUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(120);
            GuiMouseScope.dispatch(new GuiMouseScope.Event(rawX(x),rawY(y),0,0,0,button,press,press?button:NONE),callback);
        } finally { directPress = previous; }
    }

    public boolean agentPress(GuiScreen gui) {
        return gui == directPress || gui == screen && GuiMouseScope.dispatching();
    }

    public void beginFrame() { focusedTextThisFrame = false; }

    /** Called by visible, focused native text fields during their own draw, without walking a widget tree. */
    public void observeTextFocus(net.minecraft.client.gui.GuiTextField field) {
        if (active() && LogicalGuiPointer.drewScreen(screen) && GuiControlAdapter.vanillaEnabled(field))
            focusedTextThisFrame = true;
    }

    /** Instant text edits can complete between renders. Give their visual feedback a bounded lifetime. */
    public void textInput(GuiScreen gui, boolean editing) {
        if (gui == screen && active()) {
            textFeedbackUntil = editing ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(180) : 0;
            if (!editing) focusedTextThisFrame = false;
        }
    }

    /** A host notification changes only the artwork of an already active virtual device. */
    public JsonObject compactionCursor(JsonObject args) {
        ExpandedGuiApi.keys(args, "expectedSessionId", "key", "active");
        UUID key;
        try { key = UUID.fromString(JsonData.string(args, "key")); }
        catch (IllegalArgumentException failure) { throw new BridgeException("invalid_args", "Invalid compaction key."); }
        boolean start = GuiDataAdapter.booleanArg(args, "active", false);
        long now = System.nanoTime();
        compactions.values().removeIf(until -> until <= now);
        if (!start) compactions.remove(key);
        else if ((active() || worldControlled()) && (compactions.size() < 16 || compactions.containsKey(key)))
            compactions.put(key, now + TimeUnit.MINUTES.toNanos(10));
        compactionUntil = compactions.isEmpty() ? 0 : java.util.Collections.max(compactions.values());
        JsonObject receipt = new JsonObject();
        receipt.addProperty("indicated", compactions.containsKey(key));
        return receipt;
    }

    private GuiMouseOverlay.Appearance appearance(long now) {
        if ((compactionUntil != 0 && now < compactionUntil) || (thinkingUntil != 0 && now < thinkingUntil) || agentBusy.getAsBoolean()) return GuiMouseOverlay.Appearance.BUSY;
        if (focusedTextThisFrame || now < textFeedbackUntil) return GuiMouseOverlay.Appearance.TEXT;
        if (held != NONE || now < highlightUntil) return GuiMouseOverlay.Appearance.PRESSED;
        return GuiMouseOverlay.Appearance.NORMAL;
    }

    private boolean showPointer(long now) {
        return pending != null || held != NONE || owner != null || now < highlightUntil || LogicalGuiPointer.devicePoint(screen) != null;
    }

    private boolean worldControlled() {
        return installed && client != null && boundTakeover && client.currentScreen == null
                && client.theWorld != null && client.theWorld == world && client.thePlayer != null && protection.getAsBoolean();
    }

    private boolean worldStatusVisible() {
        return worldControlled() && !client.gameSettings.hideGUI;
    }

    public void afterWorldOverlay(int width, int height) {
        if (worldStatusVisible()) {
            long now = System.nanoTime();
            overlay.drawStatus(client, width, height, appearance(now), now);
        }
    }

    public void afterDraw(GuiScreen gui,int x,int y) {
        if (gui!=screen || !active()) return;
        if (x==px && y==py) rendered=true;
        long now = System.nanoTime();
        if (showPointer(now)) overlay.drawPointer(client, x, y, appearance(now), now);
        else overlay.drawStatus(client, gui.width, gui.height, appearance(now), now);
    }

    public CompletableFuture<JsonObject> submit(JsonObject args, BooleanSupplier guard, BooleanSupplier cancelled) {
        if (!installed) throw new BridgeException("unsupported","The loaded mouse backend lacks the virtual GUI device.");
        if (!active()) throw new BridgeException("background_required","The current GUI requires full background takeover.");
        String action=JsonData.string(args,"action");
        if ("indicate_thinking".equals(action)) {
            ExpandedGuiApi.keys(args, "action", "screenId", "expectedSessionId", "durationMillis");
            int duration = JsonData.integer(args, "durationMillis", 0, 120000);
            if (!guard.getAsBoolean() || cancelled.getAsBoolean())
                throw new BridgeException("stale_screen", "Mouse request context changed.");
            thinkingUntil = duration == 0 ? 0 : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(duration);
            JsonObject response = inspect();
            response.addProperty("action", action);
            response.addProperty("eventsDispatched", 0);
            return CompletableFuture.completedFuture(response);
        }
        if (pending!=null) throw new BridgeException("busy","A virtual mouse action is in progress.");
        boolean continuing=owner!=null;
        if (continuing && (!args.has("gestureId") || !owner.equals(JsonData.string(args,"gestureId"))))
            throw new BridgeException("busy","Use the current gestureId to move or release this held button.");
        if (!continuing && args.has("gestureId")) throw new BridgeException("stale_task","The held mouse gesture expired.");
        if (continuing && !action.equals("move") && !action.equals("release"))
            throw new BridgeException("busy","Release the held button before starting another action.");
        if (action.equals("release") && !continuing) throw new BridgeException("invalid_state","No virtual button is held.");
        Point previous=LogicalGuiPointer.devicePoint(screen);
        int fromX=previous==null?px:previous.x, fromY=previous==null?py:previous.y;
        List<Step> steps=new ArrayList<>();
        int x=coordinate(args,"x",width), y=coordinate(args,"y",height);
        switch (action) {
            case "move":
                ExpandedGuiApi.keys(args,"action","screenId","expectedSessionId","gestureId","x","y","steps");
                addMoves(steps,fromX,fromY,x,y,args.has("steps")?JsonData.integer(args,"steps",1,32):1);
                break;
            case "release":
                ExpandedGuiApi.keys(args,"action","screenId","expectedSessionId","gestureId","x","y");
                steps.add(new Step(x,y,held,false,0));
                break;
            case "scroll":
                ExpandedGuiApi.keys(args,"action","screenId","expectedSessionId","x","y","notches");
                int notches=JsonData.integer(args,"notches",-16,16);
                if (notches==0) throw new BridgeException("invalid_args","Choose a nonzero scroll distance.");
                for (int i=0;i<Math.abs(notches);i++) steps.add(new Step(x,y,NONE,false,120*Integer.signum(notches)));
                break;
            case "drag":
            case "click":
            case "press":
                if (action.equals("drag")) ExpandedGuiApi.keys(args,"action","screenId","expectedSessionId","x","y","button","toX","toY","steps");
                else ExpandedGuiApi.keys(args,"action","screenId","expectedSessionId","x","y","button");
                int button=args.has("button")?JsonData.integer(args,"button",0,2):0;
                steps.add(new Step(x,y,button,true,0));
                if (action.equals("drag")) {
                    int toX=coordinate(args,"toX",width), toY=coordinate(args,"toY",height);
                    int count=args.has("steps")?JsonData.integer(args,"steps",1,32):8;
                    addMoves(steps,x,y,toX,toY,count);
                    x=toX; y=toY;
                }
                if (!action.equals("press")) steps.add(new Step(x,y,button,false,0));
                break;
            default: throw new BridgeException("invalid_args","Unknown virtual mouse action.");
        }
        if (!guard.getAsBoolean() || cancelled.getAsBoolean()) throw new BridgeException("stale_screen","Mouse request context changed.");
        LogicalGuiPointer.clear("virtual_mouse_action");
        px=fromX; py=fromY;
        pending=new Request(action,steps,guard,cancelled);
        stage(pending);
        return pending.result;
    }
    private static void addMoves(List<Step> steps,int fromX,int fromY,int toX,int toY,int count) {
        for (int i=1;i<=count;i++) {
            int x=fromX+(int)Math.round((toX-fromX)*(double)i/count);
            int y=fromY+(int)Math.round((toY-fromY)*(double)i/count);
            steps.add(new Step(x,y,NONE,false,0));
        }
    }
    private static int coordinate(JsonObject args,String key,int size) { return JsonData.integer(args,key,0,size-1); }

    private void stage(Request request) {
        Step step=request.steps.get(request.index);
        request.dx=rawX(step.x)-rawX(px); request.dy=rawY(step.y)-rawY(py);
        px=step.x; py=step.y;
        rendered=false; // Cached hover in AE2/MUI2 must reflect the new point before the next input.
    }

    public boolean awaitingListWheel(int x, int y) {
        Request request = pending;
        return request != null && !dispatching && request.eventsDispatched == request.index
                && request.steps.get(request.index).wheel != 0 && x == px && y == py
                && active() && LogicalGuiPointer.drewScreen(screen) && System.nanoTime() < request.deadline
                && !request.result.isCancelled() && !request.cancelled.getAsBoolean() && request.guard.getAsBoolean();
    }

    /** A list outside the pointer bounds will not poll. Its normal draw runs once either way. */
    public void drawList(Runnable draw) {
        Request request = pending;
        pumpStep(request, request.steps.get(request.index), draw);
    }

    private boolean pumpStep(Request request, Step step, Runnable nativePass) {
        GuiMouseScope.Event event = new GuiMouseScope.Event(rawX(px), rawY(py), request.dx, request.dy,
                step.wheel, step.button, step.press, held);
        dispatching = true;
        try {
            return GuiMouseScope.pump(event, nativePass);
        } finally {
            dispatching = false;
            if (event.wasPolled()) request.eventsDispatched++;
        }
    }

    public void tick() {
        Request request=pending;
        if (request==null && owner==null) return;
        try {
            if (!active() || owner!=null && (System.nanoTime()>=holdDeadline || !holdGuard.getAsBoolean())
                    || request!=null && (request.result.isCancelled() || request.cancelled.getAsBoolean() || !request.guard.getAsBoolean())) {
                cancel("GUI, gesture lease or authority changed."); return;
            }
            if (request != null && System.nanoTime() >= request.deadline) {
                cancel(rendered ? "Gesture deadline expired." : "No completed GUI frame at the staged mouse coordinates before the deadline.");
                return;
            }
            if (request==null || !rendered) return;
            Step step=request.steps.get(request.index);
            GuiScreen recipient=screen;
            if (request.index == request.eventsDispatched) {
                if (step.button!=NONE) {
                    request.touchedButton=true;
                    held=step.press?step.button:NONE;
                }
                if (!pumpStep(request, step, recipient::handleInput)) {
                    cancel("The screen did not poll the virtual mouse event.");
                    return;
                }
            }
            request.index++;
            if (interrupted!=null || !active() || !request.guard.getAsBoolean()) {
                if (request.action.equals("click") && step.press && client.currentScreen!=recipient)
                    complete(request,false); // Press opened another GUI. Never send the trailing release to that GUI.
                else if (request.index==request.steps.size() && !request.action.equals("press"))
                    complete(request,false);
                else cancel("GUI context changed during the gesture.");
                interrupted=null;
                return;
            }
            if (request.index<request.steps.size()) { stage(request); return; }
            if (held!=NONE) {
                if (owner==null) owner=UUID.randomUUID().toString();
                holdDeadline=System.nanoTime()+HOLD_NANOS;
                holdGuard=request.guard;
            } else clearOwner();
            complete(request,true);
        } catch (RuntimeException | LinkageError failure) {
            cancel("Native GUI mouse handling stopped ("+failure.getClass().getSimpleName()+").");
        }
    }
    private void complete(Request request,boolean sameContext) {
        if (!sameContext) abandonButton();
        JsonObject out=new JsonObject();
        out.addProperty("action",request.action);
        out.addProperty("status",request.index<request.steps.size()?"screen_changed_after_press":"dispatched");
        out.addProperty("eventsDispatched",request.eventsDispatched); out.addProperty("screenStillCurrent",sameContext);
        out.addProperty("x",px); out.addProperty("y",py); out.addProperty("osCursorModified",false);
        if (owner!=null) { out.addProperty("gestureId",owner); out.addProperty("leaseMillis",15000); }
        pending=null; request.result.complete(out);
    }
    private void clearOwner() { held=NONE; owner=null; holdGuard=null; }
    private void abandonButton() {
        if ((held!=NONE || owner!=null || pending!=null && pending.touchedButton) && screen instanceof GuiMouseReset) {
            ((GuiMouseReset)screen).mcb$cancelGuiMouse();
            if (screen instanceof GuiMouseReset.ContainerDrag) ((GuiMouseReset.ContainerDrag)screen).mcb$cancelContainerDrag();
        }
        clearOwner();
    }
    public void cancel(String reason) {
        highlightUntil = 0;
        thinkingUntil = 0;
        compactions.clear();
        compactionUntil = 0;
        if (dispatching) { interrupted=reason; return; }
        Request request=pending;
        abandonButton(); pending=null; interrupted=null;
        if (request!=null) request.result.completeExceptionally(new BridgeException("mouse_interrupted",
                reason+" Dispatched native events: "+request.eventsDispatched+". Inspect the GUI before continuing."));
    }
    private static final class Step {
        final int x,y,button,wheel;
        final boolean press;
        Step(int x,int y,int button,boolean press,int wheel) {
            this.x=x; this.y=y; this.button=button; this.press=press; this.wheel=wheel;
        }
    }
    private static final class Request {
        final String action;
        final List<Step> steps;
        final BooleanSupplier guard,cancelled;
        boolean touchedButton;
        final CompletableFuture<JsonObject> result=new CompletableFuture<>();
        final long deadline=System.nanoTime()+REQUEST_NANOS;
        int index, eventsDispatched, dx, dy;
        Request(String action,List<Step> steps,BooleanSupplier guard,BooleanSupplier cancelled) {
            this.action=action; this.steps=steps; this.guard=guard; this.cancelled=cancelled;
        }
    }
}
