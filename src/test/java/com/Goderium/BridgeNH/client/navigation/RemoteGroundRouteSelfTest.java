package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.network.RemoteReadProtocol;
import com.Goderium.BridgeNH.util.BridgeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;

/** Exercise the production walker and spatial adapter on a bounded, flat native-block fixture. */
public final class RemoteGroundRouteSelfTest {
    private static int checks;

    public static int run() throws ReflectiveOperationException {
        checks = 0;
        for (String op : new String[] {"world.spatial", "world.walk_plan", "world.walk_probe", "world.walk_arrival"}) {
            require(RemoteReadProtocol.OBSERVATIONS.contains(op), "remote observation is whitelisted: " + op);
            expect("permission_denied", () -> RemoteReadProtocol.permit(op, false));
            RemoteReadProtocol.permit(op, true);
        }
        Terrain world = allocate(Terrain.class);
        setField(World.class, world, "provider", new WorldProviderSurface());
        world.loadedEntityList = Collections.emptyList();
        world.chunks = allocate(Chunks.class);
        world.chunks.chunk = new Chunk(world, 0, 0);
        world.chunks.chunk.isChunkLoaded = true;
        ServerPlayer actor = allocate(ServerPlayer.class);
        actor.capabilities = new PlayerCapabilities();
        actor.inventory = new InventoryPlayer(actor);
        place(actor, world, 6.5, 8.5, false);
        WalkingPathPlanner.Sweep start = new WalkingPathPlanner.Sweep(RemoteGroundRoute.feet(actor),
                RemoteGroundRoute.feet(actor), actor.width, actor.height);
        world.chunks.available = false;
        require(RemoteGroundRoute.geometry(actor, start, Long.MAX_VALUE) == WalkingPathPlanner.Check.UNLOADED
                && world.blockReads == 0, "Unknown chunk is never sampled or treated as clear.");
        world.chunks.available = true;
        require(RemoteGroundRoute.geometry(actor, start, 0) == WalkingPathPlanner.Check.PARTIAL,
                "An exhausted geometry budget does not certify a route.");
        require(RemoteGroundRoute.geometry(actor, start, Long.MAX_VALUE) == WalkingPathPlanner.Check.CLEAR,
                "Standing on a full native stone floor has a complete proof.");
        world.gap = true;
        require(RemoteGroundRoute.geometry(actor, start, Long.MAX_VALUE) == WalkingPathPlanner.Check.BLOCKED,
                "An unsupported footprint is rejected.");
        world.gap = false;

        WalkingPathPlanner.Point goal = new WalkingPathPlanner.Point(10.5, 64, 8.5);
        JsonObject request = new JsonObject();
        request.add("destination", RemoteGroundRoute.json(goal));
        world.wall = true;
        require(RemoteGroundRoute.geometry(actor, new WalkingPathPlanner.Sweep(start.from, goal,
                        actor.width, actor.height), Long.MAX_VALUE) == WalkingPathPlanner.Check.BLOCKED,
                "A native collision box blocks the direct route.");
        ClientTransitRoute transit=new ClientTransitRoute(actor,request,4.5);
        require(transit.advance(0)==null, "Expired client slice cannot finish a route.");
        JsonArray grid=null;
        for (int i=0;i<1024 && grid==null;i++) grid=transit.advance(System.nanoTime()+2000000L);
        require(grid!=null && grid.size()>2, "Client transit search resumes across slices and routes around a native collision.");
        require(RemoteGroundRoute.point(grid.get(grid.size()-1).getAsJsonObject()).distance(goal)<.001, "Client route retains the exact destination.");
        JsonArray turns = plan(actor, request, 4.5).getAsJsonArray("waypoints");
        require(turns.size() >= 2, "The obstructed route requires a turn.");
        WalkingPathPlanner.Point previous = start.from;
        boolean detour = false;
        for (int i = 0; i < turns.size(); i++) {
            WalkingPathPlanner.Point next = RemoteGroundRoute.point(turns.get(i).getAsJsonObject());
            detour |= Math.abs(next.z - start.from.z) > .5;
            // Match the planner's bounded sub-sweeps, including intermediate collision checks.
            int segments = Math.max(1, (int) Math.ceil(previous.distance(next) / .15));
            WalkingPathPlanner.Point from = previous;
            for (int j = 1; j <= segments; j++) {
                double f = (double) j / segments;
                WalkingPathPlanner.Point to = new WalkingPathPlanner.Point(previous.x + (next.x - previous.x) * f,
                        previous.y, previous.z + (next.z - previous.z) * f);
                require(RemoteGroundRoute.geometry(actor, new WalkingPathPlanner.Sweep(from, to,
                                actor.width, actor.height), Long.MAX_VALUE) == WalkingPathPlanner.Check.CLEAR,
                        "Every segment of the returned detour clears the wall.");
                from = to;
            }
            previous = next;
        }
        require(detour && previous.distance(goal) < .001, "Detour returns to the precise requested endpoint.");
        place(actor, world, goal.x, goal.z, false);
        JsonObject probe = new JsonObject();
        probe.add("waypoint", RemoteGroundRoute.json(goal));
        require(RemoteGroundRoute.probe(actor, probe, 4.5, Long.MAX_VALUE).get("reached").getAsBoolean(),
                "A fresh endpoint probe confirms actual feet at destination.");
        int beforeArrival=world.blockReads;
        require(RemoteGroundRoute.arrival(actor,probe,4.5).get("reached").getAsBoolean() && world.blockReads==beforeArrival,
                "Authoritative coordinate confirmation reads the actual feet without a terrain scan.");
        actor.onGround=false;
        require(!RemoteGroundRoute.arrival(actor,probe,4.5).get("reached").getAsBoolean(), "A passing airborne player has not arrived.");
        actor.onGround=true;
        world.wall=false;
        place(actor,world,6.5,8.5,false);
        world.transitFixture=1;
        JsonArray doorPlan=transitPlan(actor,goal);
        require(hasTransit(doorPlan,"door"),"A closed wooden door on a one-wide floor is a native use step.");
        world.transitFixture=2;
        JsonArray ladderPlan=transitPlan(actor,new WalkingPathPlanner.Point(9.5,67,8.5));
        require(hasTransit(ladderPlan,"ladder_up") && hasTransit(ladderPlan,"ladder_exit"),"Ladder travel includes the upper landing.");
        world.transitFixture=0;

        JsonObject block = new JsonObject();
        block.addProperty("x", 10); block.addProperty("y", 65); block.addProperty("z", 8);
        block.addProperty("registryName", "minecraft:stone"); block.addProperty("metadata", 0);
        world.target = true;
        place(actor, world, 8.5, 8.5, false);
        EntityClientPlayerMP client = allocate(EntityClientPlayerMP.class);
        place(client, world, 8.5, 8.5, true);
        JsonObject defaultRay = new JsonObject();
        defaultRay.addProperty("mode","raycast"); defaultRay.addProperty("distance",3);
        for (EntityPlayer player : new EntityPlayer[] {actor, client}) {
            player.rotationYawHead = player.rotationYaw = -90;
            JsonObject observed = com.Goderium.BridgeNH.common.world.WorldObservationsAdapter.spatial(world,player,defaultRay);
            require(Math.abs(observed.getAsJsonObject("from").get("y").getAsDouble()-65.62)<.01,
                    "The native view ray starts at the current eye on both owners.");
            WalkingPathPlanner.Point at = RemoteGroundRoute.feet(player);
            // Warm native ray classes before asserting a result subject to a 4ms observation budget.
            RemoteGroundRoute.visibleSide(player, at, block, 2.01);
            require(RemoteGroundRoute.visibleSide(player, at, block, 2.01) >= 0,
                    "The two-block target is visible from the correct eye on each side.");
            require(RemoteGroundRoute.visibleSide(player, at, block, 1.9) < 0, "Out-of-reach target cannot certify arrival.");
        }
        place(actor, world, 6.5, 8.5, false);
        request = new JsonObject(); request.add("block", block);
        JsonArray approach = plan(actor, request, 2.5).getAsJsonArray("waypoints");
        WalkingPathPlanner.Point end = RemoteGroundRoute.point(approach.get(approach.size() - 1).getAsJsonObject());
        place(actor, world, end.x, end.z, false);
        probe.add("waypoint", RemoteGroundRoute.json(end)); probe.add("block", block);
        JsonObject arrival = RemoteGroundRoute.probe(actor, probe, 2.5, Long.MAX_VALUE);
        require(arrival.get("reached").getAsBoolean() && arrival.get("blockVisible").getAsBoolean(),
                "The block approach ends with both occupied feet and the exact visible target.");
        world.target = false;
        expect("target_unverified", () -> RemoteGroundRoute.probe(actor, probe, 2.5, Long.MAX_VALUE));
        return checks;
    }

    private static JsonArray transitPlan(EntityPlayer player,WalkingPathPlanner.Point end) {
        JsonObject goal=new JsonObject(); goal.add("destination",RemoteGroundRoute.json(end));
        ClientTransitRoute search=new ClientTransitRoute(player,goal,4.5);
        for (int i=0;i<2048;i++) {
            JsonArray path=search.advance(System.nanoTime()+2_000_000);
            if (path!=null) return path;
        }
        throw new AssertionError("Transit fixture did not finish within its slice limit.");
    }
    private static boolean hasTransit(JsonArray path,String kind) {
        for (com.google.gson.JsonElement point:path) {
            JsonObject action=point.getAsJsonObject().getAsJsonObject("transit");
            if (action!=null && action.get("kind").getAsString().equals(kind)) return true;
        }
        return false;
    }

    private static JsonObject plan(EntityPlayer player, JsonObject args, double reach) {
        RemoteGroundRoute planner = new RemoteGroundRoute(player, args, reach);
        JsonObject plan;
        do { plan = planner.advance(player, Long.MAX_VALUE); } while (plan == null);
        return plan;
    }

    private static void place(EntityPlayer player, Terrain world, double x, double z, boolean client)
            throws ReflectiveOperationException {
        player.worldObj = world;
        player.width = .6F; player.height = 1.8F; player.eyeHeight = player.getDefaultEyeHeight();
        player.posX = x; player.posZ = z; player.posY = client ? 65.62 : 64;
        player.onGround = true;
        setField(Entity.class, player, "boundingBox", AxisAlignedBB.getBoundingBox(x-.3, 64, z-.3, x+.3, 65.8, z+.3));
    }

    private static final class ServerPlayer extends EntityPlayerMP {
        private ServerPlayer() { super(null, null, null, null); }
        @Override public boolean isSprinting() { return false; }
        @Override public boolean isOnLadder() { return false; }
    }

    private static final class Terrain extends World {
        Chunks chunks;
        boolean gap, wall, target;
        int blockReads,transitFixture;
        private Terrain() { super(null, "walk_fixture", (WorldSettings) null, null, null); }
        @Override protected IChunkProvider createChunkProvider() { throw new AssertionError("No chunk generation"); }
        @Override protected int getRenderDistanceChunks() { throw new AssertionError("No rendering"); }
        @Override public Entity getEntityByID(int id) { throw new AssertionError("No entity lookup"); }
        @Override public IChunkProvider getChunkProvider() { return chunks; }
        @Override public Block getBlock(int x, int y, int z) {
            blockReads++;
            if (transitFixture!=0) {
                if (transitFixture==1 && x==8 && z==8 && (y==64 || y==65)) return Blocks.wooden_door;
                if (transitFixture==2 && x==8 && y>=64 && y<=67) {
                    if (z==8) return Blocks.ladder;
                    if (z==9) return Blocks.stone;
                }
                if (transitFixture==2 && x==9 && z==8 && y==66) return Blocks.stone;
                return y==63 && z==8 && x>=5 && x<=10 ? Blocks.stone : Blocks.air;
            }
            if (y == 63 && !gap || wall && x == 8 && y == 64 && z == 8
                    || target && x == 10 && y == 65 && z == 8) return Blocks.stone;
            return Blocks.air;
        }
        @Override public int getBlockMetadata(int x,int y,int z) {
            if (transitFixture==1 && x==8 && z==8 && y==65) return 8;
            return transitFixture==2 && x==8 && z==8 && y>=64 && y<=67 ? 2 : 0;
        }
        @Override public int getBlockLightValue(int x, int y, int z) { return 15; }
        @Override public long getTotalWorldTime() { return 1; }
        @Override public List getEntitiesWithinAABBExcludingEntity(Entity actor, AxisAlignedBB box) { return Collections.emptyList(); }
    }

    private static final class Chunks extends ChunkProviderServer {
        Chunk chunk;
        boolean available;
        private Chunks() { super(null, null, null); }
        @Override public boolean chunkExists(int x, int z) { return available && x == 0 && z == 0; }
        @Override public Chunk provideChunk(int x, int z) {
            if (!chunkExists(x, z)) throw new AssertionError("Read outside known terrain");
            return chunk;
        }
    }

    private static void setField(Class<?> declaring, Object self, String key, Object value) throws ReflectiveOperationException {
        Field field = declaring.getDeclaredField(key);
        field.setAccessible(true);
        field.set(self, value);
    }

    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field instance = unsafe.getDeclaredField("theUnsafe"); instance.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(instance.get(null), type));
    }

    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException failure) { require(code.equals(failure.code), "Expected " + code + ", got " + failure.code); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
