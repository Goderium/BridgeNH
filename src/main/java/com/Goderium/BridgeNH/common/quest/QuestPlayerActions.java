package com.Goderium.BridgeNH.common.quest;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cpw.mods.fml.common.Loader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

/** Fixed optional BetterQuesting 3 player APIs. The server owns the actor and the membership check. */
public final class QuestPlayerActions {
    public static final String READ = "quest.progress", STEP = "quest.step";
    private QuestPlayerActions() {}
    public static boolean handles(String op) { return READ.equals(op) || STEP.equals(op); }
    private static final String ROOT = "betterquesting.";
    private static final Class<?>[] EMPTY = {};
    private static Object singleton(String cls, String field) throws ReflectiveOperationException {
        return Class.forName(cls).getField(field).get(null);
    }
    private static Object call(Object owner,String name,Class<?>[] types,Object... args) throws ReflectiveOperationException {
        return owner.getClass().getMethod(name,types).invoke(owner,args);
    }
    private static boolean value(Object obj,String method,Class<?> type,Object arg) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(call(obj,method,new Class<?>[]{type},arg));
    }
    private static boolean global(Object quest) throws ReflectiveOperationException {
        Class<?> prop = Class.forName(ROOT+"api.properties.IPropertyType");
        Object value = call(quest,"getProperty",new Class<?>[]{prop},singleton(ROOT+"api.properties.NativeProps","GLOBAL"));
        if (!(value instanceof Boolean)) throw new BridgeException("unsupported","Quest sharing settings are unavailable.");
        return (Boolean)value;
    }
    public static UUID questId(JsonObject args) {
        String id=JsonData.string(args,"questId");
        if(id.matches("0|[1-9a-f][0-9a-f]{0,11}")) id="00000000-0000-0000-0000-"+("000000000000"+id).substring(id.length());
        try { UUID result=UUID.fromString(id); if(result.toString().equals(id)) return result; }
        catch(IllegalArgumentException ignored) {}
        throw new BridgeException("invalid_args","questId must be a canonical BetterQuesting quest UUID.");
    }
    /** Fingerprint the whole current membership without exposing other player identities. */
    private static String membership(List<?> ids,UUID actor) {
        if(ids.isEmpty() || !ids.contains(actor) || ids.size()>1024) throw new BridgeException("unsupported","Could not establish the quest participants.");
        List<String> members=new ArrayList<>();
        for(Object id:ids) {
            if(!(id instanceof UUID)) throw new BridgeException("unsupported","Invalid quest participant.");
            members.add(id.toString());
        }
        Collections.sort(members);
        return UUID.nameUUIDFromBytes(String.join(",",members).getBytes(StandardCharsets.US_ASCII)).toString();
    }
    private static JsonObject snapshot(Object quest,UUID actor,EntityPlayer player,UUID id) throws ReflectiveOperationException {
        JsonObject r=new JsonObject(); r.addProperty("questId",id.toString());
        r.addProperty("state",((Enum<?>)call(quest,"getState",new Class<?>[]{EntityPlayer.class},player)).name());
        r.addProperty("unlocked",value(quest,"isUnlocked",UUID.class,actor));
        r.addProperty("complete",value(quest,"isComplete",UUID.class,actor));
        r.addProperty("claimed",value(quest,"hasClaimed",UUID.class,actor));
        r.addProperty("canSubmit",value(quest,"canSubmit",EntityPlayer.class,player));
        r.addProperty("canClaim",value(quest,"canClaim",EntityPlayer.class,player));
        r.addProperty("global",global(quest));
        List<?> rows=(List<?>)call(call(quest,"getTasks",EMPTY),"getEntries",EMPTY);
        JsonArray tasks=new JsonArray();
        for(Object row:rows) {
            if (tasks.size()>=64) break;
            Object task=call(row,"getValue",EMPTY); JsonObject t=new JsonObject();
            t.addProperty("taskId",(Integer)call(row,"getID",EMPTY)); t.addProperty("type",call(task,"getFactoryID",EMPTY).toString());
            t.addProperty("complete",value(task,"isComplete",UUID.class,actor)); tasks.add(t);
        }
        r.add("tasks",tasks);r.addProperty("taskCount",rows.size());r.addProperty("truncated",tasks.size()<rows.size());
        return r;
    }
    public static JsonObject run(String op,JsonObject args,EntityPlayer player) {
        if(!Loader.isModLoaded("betterquesting")) throw new BridgeException("unsupported","BetterQuesting is unavailable.");
        UUID id=questId(args); boolean write=STEP.equals(op),sent=false;
        try {
            Object db=singleton(ROOT+"questing.QuestDatabase","INSTANCE");
            Object quest=call(db,"get",new Class<?>[]{Object.class},id);
            if(quest==null) throw new BridgeException("not_found","Unknown BetterQuesting quest UUID.");
            UUID actor=(UUID)Class.forName(ROOT+"api.api.QuestingAPI").getMethod("getQuestingUUID",EntityPlayer.class).invoke(null,player);
            JsonObject before=snapshot(quest,actor,player,id);
            if(player.worldObj.isRemote || !(player instanceof EntityPlayerMP)) {
                if(write) throw new BridgeException("unsupported","A current authoritative quest participant check is required.");
                before.addProperty("source","client_synced_quest_state"); before.addProperty("partyVerified",false); return before;
            }
            Class<?> pi=Class.forName(ROOT+"api2.utils.ParticipantInfo");
            List<?> members; String token;
            try {
                members=(List<?>)pi.getField("ALL_UUIDS").get(pi.getConstructor(EntityPlayer.class).newInstance(player));
                token=membership(members,actor);
            } catch(ReflectiveOperationException | RuntimeException unavailable) {
                if(write) throw new BridgeException("unsupported","Unable to verify current quest participants.");
                before.addProperty("source","server_quest_state");before.addProperty("partyVerified",false);return before;
            }
            before.addProperty("source","server_quest_state");before.addProperty("partyVerified",true);
            before.addProperty("participantCount",members.size()); before.addProperty("partyConfirmation",token);
            String folder=net.minecraft.server.MinecraftServer.getServer().getFolderName();
            before.addProperty("worldFolder",folder); before.addProperty("dimension",player.dimension);
            if(!write) return before;
            if(!folder.equals(JsonData.string(args,"worldFolder")) || player.dimension!=JsonData.integer(args,"dimension",Integer.MIN_VALUE,Integer.MAX_VALUE))
                throw new BridgeException("stale_world","The quest owner world or dimension changed.");
            if(before.get("global").getAsBoolean()) throw new BridgeException("unsupported","Global quest writes can affect players outside the party.");
            if((members.size()>1 || args.has("partyConfirmation")) && (!args.has("partyConfirmation") || !token.equals(JsonData.string(args,"partyConfirmation"))))
                throw new BridgeException("party_confirmation_required","Read quest progress and confirm the current party fingerprint to allow BetterQuesting's shared effects.");
            String act=JsonData.string(args,"action");
            if(!before.get("unlocked").getAsBoolean()) throw new BridgeException("quest_locked","The quest is locked for this player.");
            if("checkbox".equals(act)) {
                int taskId=JsonData.integer(args,"taskId",0,Integer.MAX_VALUE);
                Object task=call(call(quest,"getTasks",EMPTY),"getValue",new Class<?>[]{int.class},taskId);
                if(!Class.forName("bq_standard.tasks.TaskCheckbox").isInstance(task)) throw new BridgeException("invalid_args","The selected task is not a checkbox.");
                if(!before.get("canSubmit").getAsBoolean() || value(task,"isComplete",UUID.class,actor)) throw new BridgeException("quest_unavailable","This checkbox cannot be submitted now.");
                Object registry=singleton(ROOT+"network.PacketTypeRegistry","INSTANCE");
                Object handler=call(registry,"getServerHandler",new Class<?>[]{ResourceLocation.class},new ResourceLocation("bq_standard:task_checkbox"));
                if(!(handler instanceof Consumer)) throw new BridgeException("unsupported","The native BetterQuesting checkbox handler is unavailable.");
                Object codec=singleton(ROOT+"api.utils.NBTConverter$UuidValueType","QUEST");
                NBTTagCompound tag=(NBTTagCompound)call(codec,"writeId",new Class<?>[]{UUID.class},id);tag.setInteger("taskID",taskId);
                Object message=Class.forName(ROOT+"api2.utils.Tuple2").getConstructor(Object.class,Object.class).newInstance(tag,player);
                sent=true;
                @SuppressWarnings("unchecked") Consumer<Object> nativeHandler=(Consumer<Object>)handler;
                nativeHandler.accept(message);
            } else {
                if(args.has("taskId") || !("claim".equals(act) || "detect".equals(act))) throw new BridgeException("invalid_args","Expected detect, checkbox or claim and taskId only for checkbox.");
                if(!before.get("claim".equals(act)?"canClaim":"canSubmit").getAsBoolean()) throw new BridgeException("quest_unavailable","The quest cannot perform this action now. A choice reward must be selected in its UI.");
                java.lang.reflect.Method method=Class.forName(ROOT+"network.handlers.NetQuestAction").getMethod("claim".equals(act)?"claimQuest":"detectQuest",java.util.Collection.class,EntityPlayerMP.class);
                sent=true;method.invoke(null,Collections.singleton(id),player);
            }
            JsonObject after=snapshot(quest,actor,player,id);JsonObject result=new JsonObject();
            result.addProperty("source","server_quest_state");result.addProperty("action",act);result.addProperty("nativeActionReturned",true);
            result.add("before",before);result.add("after",after); result.addProperty("stateChanged", !after.get("state").equals(before.get("state")) || !after.get("tasks").equals(before.get("tasks")) || !after.get("claimed").equals(before.get("claimed")));
            return result;
        } catch(BridgeException failure) { throw failure; }
        catch(ReflectiveOperationException | LinkageError | RuntimeException failure) {
            Throwable cause=failure instanceof InvocationTargetException && failure.getCause()!=null?failure.getCause():failure;
            throw new BridgeException(sent?"quest_outcome_unknown":"unsupported","BetterQuesting player API: "+cause.getClass().getSimpleName()+". Read current state before another action.");
        }
    }
}
