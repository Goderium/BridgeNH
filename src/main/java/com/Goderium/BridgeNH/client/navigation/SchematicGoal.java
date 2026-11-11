package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.config.NavigationCosts;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.CatalogData;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generic immutable schematic predicates. Comparison/ordering is native; no setters or machine-specific rules. */
public final class SchematicGoal {
    public static final String OPERATION = "structure.goal";
    public static final int MAX_CELLS = 8192;
    interface Source { List<Cell> cells(); boolean completeRules(); }
    static final class Cell {
        final int x,y,z;
        final String kind;
        final JsonArray alternatives;
        Cell(int x,int y,int z,String kind,JsonArray alternatives) { this.x=x; this.y=y; this.z=z; this.kind=kind; this.alternatives=alternatives; }
        JsonObject xyz() { JsonObject row=new JsonObject(); row.addProperty("x",x); row.addProperty("y",y); row.addProperty("z",z); return row; }
    }
    static final class ExplicitSource implements Source {
        final List<Cell> values = new ArrayList<Cell>();
        ExplicitSource(JsonObject args) {
            ExpandedGuiApi.keys(args,"anchor","cells","palette","boxes","rotation","mirrorX","limit","includePlacementOrder","expectedWorld","expectedSessionId","dimension","expectedConnectionId");
            if (!args.has("anchor") || !args.get("anchor").isJsonObject() || !args.has("cells") || !args.get("cells").isJsonArray()) throw new BridgeException("invalid_args","Schematic needs anchor XYZ and cells.");
            JsonObject anchor=args.getAsJsonObject("anchor"); ExpandedGuiApi.keys(anchor,"x","y","z");
            int ax=JsonData.integer(anchor,"x",-29999999,29999999), ay=JsonData.integer(anchor,"y",0,255), az=JsonData.integer(anchor,"z",-29999999,29999999);
            int rotation=args.has("rotation") ? JsonData.integer(args,"rotation",0,3) : 0;
            boolean mirror=GuiDataAdapter.booleanArg(args,"mirrorX",false);
            JsonArray declared=args.getAsJsonArray("cells");
            if (declared.size()<1 || declared.size()>(args.has("palette") ? MAX_CELLS : 512)) throw new BridgeException("invalid_args","Schematic exceeds its expanded cell budget.");
            Set<String> positions=new HashSet<String>();
            for (JsonElement value:declared) {
                if (!value.isJsonObject()) throw new BridgeException("invalid_args","Every schematic cell is an object.");
                JsonObject row=value.getAsJsonObject(); ExpandedGuiApi.keys(row,"x","y","z","kind","alternatives");
                int x=JsonData.integer(row,"x",-2048,2048), y=JsonData.integer(row,"y",-2048,2048), z=JsonData.integer(row,"z",-2048,2048);
                int[] transformed=transform(x,y,z,rotation,mirror); x=ax+transformed[0]; y=ay+transformed[1]; z=az+transformed[2];
                if (x < -29999999 || x>29999999 || y<0 || y>255 || z < -29999999 || z>29999999) throw new BridgeException("invalid_args","Transformed schematic cell is outside world bounds.");
                if (!positions.add(x+":"+y+":"+z)) throw new BridgeException("invalid_args","Duplicate transformed schematic coordinates are ambiguous.");
                String kind=ExpandedGuiApi.option(row,"kind","block","block","air","ignore"); JsonArray alternatives=new JsonArray();
                if ("block".equals(kind)) {
                    if (!row.has("alternatives") || !row.get("alternatives").isJsonArray() || row.getAsJsonArray("alternatives").size()<1 || row.getAsJsonArray("alternatives").size()>16) throw new BridgeException("invalid_args","Block cells need 1..16 exact alternatives.");
                    for (JsonElement option:row.getAsJsonArray("alternatives")) {
                        if (!option.isJsonObject()) throw new BridgeException("invalid_args","Block alternatives must be objects.");
                        JsonObject identity=option.getAsJsonObject(); ExpandedGuiApi.keys(identity,"registryName","metadata","metaTileId","metaClass");
                        String registry=CatalogData.text(identity,"registryName",256,"");
                        if (registry.indexOf(':')<1 || "minecraft:air".equals(registry)) throw new BridgeException("invalid_args","Block alternatives require registered non-air identities.");
                        JsonData.integer(identity,"metadata",0,32767);
                        if (identity.has("metaTileId")) JsonData.integer(identity,"metaTileId",0,32767);
                        if (identity.has("metaClass")) CatalogData.text(identity,"metaClass",256,"");
                        alternatives.add(new com.google.gson.JsonParser().parse(identity.toString()));
                    }
                } else if (row.has("alternatives")) throw new BridgeException("invalid_args","Air/ignore cells cannot declare block alternatives.");
                values.add(new Cell(x,y,z,kind,alternatives));
            }
        }
        public List<Cell> cells() { return java.util.Collections.unmodifiableList(values); }
        public boolean completeRules() { return true; }
    }
    /** Compact regions implement the same predicates; no metadata or machine acceptance is inferred. */
    static Source source(JsonObject args) {
        if(!args.has("palette") && !args.has("boxes")) return new ExplicitSource(args);
        if(args.has("cells") || !args.has("palette") || !args.get("palette").isJsonArray() || !args.has("boxes") || !args.get("boxes").isJsonArray())
            throw new BridgeException("invalid_args","Use explicit cells or palette+boxes, exclusively.");
        JsonArray palette=args.getAsJsonArray("palette"), boxes=args.getAsJsonArray("boxes"), cells=new JsonArray();
        if(palette.size()<1 || palette.size()>64 || boxes.size()<1 || boxes.size()>256) throw new BridgeException("invalid_args","Palette/region count exceeds 64/256.");
        for(JsonElement entry:palette) {
            if(!entry.isJsonObject()) throw new BridgeException("invalid_args","Palette entries must be block/air/ignore objects.");
            ExpandedGuiApi.keys(entry.getAsJsonObject(),"kind","alternatives");
        }
        for(JsonElement value:boxes) {
            if(!value.isJsonObject()) throw new BridgeException("invalid_args","Every region must be an object.");
            JsonObject box=value.getAsJsonObject(); ExpandedGuiApi.keys(box,"min","max","palette");
            if(!box.has("min") || !box.get("min").isJsonObject() || !box.has("max") || !box.get("max").isJsonObject()) throw new BridgeException("invalid_args","Regions require min/max relative XYZ.");
            JsonObject min=box.getAsJsonObject("min"), max=box.getAsJsonObject("max"); ExpandedGuiApi.keys(min,"x","y","z"); ExpandedGuiApi.keys(max,"x","y","z");
            int[] lo=new int[3],hi=new int[3]; long count=1; String[] axes={"x","y","z"};
            for(int i=0;i<3;i++) { lo[i]=JsonData.integer(min,axes[i],-2048,2048); hi[i]=JsonData.integer(max,axes[i],lo[i],2048); count*=hi[i]-lo[i]+1L; }
            if(count+cells.size()>MAX_CELLS) throw new BridgeException("invalid_args","Expanded schematic exceeds 8192 cells.");
            JsonObject predicate=palette.get(JsonData.integer(box,"palette",0,palette.size()-1)).getAsJsonObject();
            for(int y=lo[1];y<=hi[1];y++) for(int z=lo[2];z<=hi[2];z++) for(int x=lo[0];x<=hi[0];x++) {
                JsonObject cell=new com.google.gson.JsonParser().parse(predicate.toString()).getAsJsonObject(); cell.addProperty("x",x); cell.addProperty("y",y); cell.addProperty("z",z); cells.add(cell);
            }
        }
        JsonObject expanded=new com.google.gson.JsonParser().parse(args.toString()).getAsJsonObject(); expanded.remove("boxes"); expanded.add("cells",cells);
        return new ExplicitSource(expanded);
    }
    static int[] transform(int x,int y,int z,int rotation,boolean mirror) {
        if (mirror) x=-x;
        for(int i=0;i<rotation;i++) { int previous=x; x=-z; z=previous; }
        return new int[]{x,y,z};
    }
    static boolean matches(JsonObject expected,JsonObject actual) {
        if (!JsonData.string(expected,"registryName").equals(JsonData.string(actual,"registryName")) || expected.get("metadata").getAsInt()!=actual.get("metadata").getAsInt()) return false;
        if (expected.has("metaTileId") || expected.has("metaClass")) {
            if (!actual.has("gregtech") || !actual.get("gregtech").isJsonObject()) return false;
            JsonObject gt=actual.getAsJsonObject("gregtech");
            if (expected.has("metaTileId") && (!gt.has("metaTileId") || expected.get("metaTileId").getAsInt()!=gt.get("metaTileId").getAsInt())) return false;
            if (expected.has("metaClass") && (!gt.has("metaClass") || !expected.get("metaClass").equals(gt.get("metaClass")))) return false;
        }
        return true;
    }
    /** Provider suggestion evidence cannot be promoted to a complete StructureLib predicate. */
    public static JsonObject publicPosition(JsonObject position) {
        JsonObject result=new JsonObject(); result.addProperty("provider","StructureLib_public_suggestions"); result.addProperty("completeRules",false);
        result.addProperty("formationProved",false);
        if (position.has("observed") && position.has("candidates")) {
            boolean suggested=false;
            for(JsonElement value:position.getAsJsonArray("candidates")) if(value.isJsonObject() && matches(value.getAsJsonObject(),position.getAsJsonObject("observed"))) suggested=true;
            result.addProperty("status",suggested ? "matches_public_suggestion" : "acceptance_unknown");
        } else result.addProperty("status","unobserved");
        return result;
    }
    public static final class Comparison {
        final Source source; final int limit;
        final NavigationCosts.Model costs=NavigationCosts.snapshot();
        final boolean includePlacementOrder;
        final JsonArray differences=new JsonArray(), order=new JsonArray();
        final Map<String,Integer> counts=new LinkedHashMap<String,Integer>(), materials=new LinkedHashMap<String,Integer>();
        final List<Cell> missing=new ArrayList<Cell>(); final Set<String> supports=new HashSet<String>();
        int next, supportIndex, planned, orderSearch;
        Cell orderSelected;
        double orderBest=Double.POSITIVE_INFINITY;
        boolean orderBounded;
        List<Cell> pending;
        PlayerNavigation.Point cursor;
        public Comparison(JsonObject args) { source=source(args); limit=args.has("limit") ? JsonData.integer(args,"limit",1,128):32; includePlacementOrder=GuiDataAdapter.booleanArg(args,"includePlacementOrder",true); }
        public interface Observations { JsonObject block(int x,int y,int z); }
        public JsonArray observationPositions() {
            Map<String,JsonObject> positions=new LinkedHashMap<String,JsonObject>();
            for(Cell cell:source.cells()) if(!"ignore".equals(cell.kind)) {
                positions.put(key(cell),cell.xyz());
            }
            JsonArray result=new JsonArray(); for(JsonObject row:positions.values()) result.add(row); return result;
        }
        public JsonArray supportPositions() {
            Map<String,JsonObject> positions=new LinkedHashMap<String,JsonObject>();
            if(includePlacementOrder) for(Cell cell:missing) for(int[] d:DIRECTIONS) {
                    int x=cell.x+d[0],y=cell.y+d[1],z=cell.z+d[2];
                    if(x>=-29999999 && x<=29999999 && z>=-29999999 && z<=29999999 && y>=0 && y<=255) {
                        JsonObject row=new JsonObject(); row.addProperty("x",x); row.addProperty("y",y); row.addProperty("z",z);
                        positions.put(x+":"+y+":"+z,row);
                    }
            }
            JsonArray result=new JsonArray(); for(JsonObject row:positions.values()) result.add(row); return result;
        }
        public boolean classify(Observations access,boolean creative,long deadline) {
            for(int sampled=0; next<source.cells().size() && sampled<64 && System.nanoTime()<deadline; sampled++,next++) {
                Cell cell=source.cells().get(next); String status; JsonObject observed=null;
                if ("ignore".equals(cell.kind)) status="ignored";
                else if ((observed=access.block(cell.x,cell.y,cell.z))==null || !observed.get("loaded").getAsBoolean()) { observed=null; status="unknown"; }
                else {
                    boolean air=observed.get("air").getAsBoolean(),replaceable=observed.get("replaceable").getAsBoolean();
                    if ("air".equals(cell.kind)) status=air ? "matched" : "conflict";
                    else {
                        boolean match=false; for(JsonElement alternative:cell.alternatives) if(matches(alternative.getAsJsonObject(),observed)) match=true;
                        status=match ? "matched" : air ? "missing" : "conflict";
                        if ("missing".equals(status)) {
                            missing.add(cell); JsonObject first=cell.alternatives.get(0).getAsJsonObject(); String key=first.toString();
                            materials.put(key,materials.getOrDefault(key,0)+1);
                        }
                    }
                    if (!air && !replaceable) supports.add(key(cell));
                }
                counts.put(status,counts.getOrDefault(status,0)+1);
                if (!"matched".equals(status) && !"ignored".equals(status) && differences.size()<limit) {
                    JsonObject row=cell.xyz(); row.addProperty("kind",cell.kind); row.addProperty("status",status); row.add("alternatives",cell.alternatives);
                    if(observed!=null) row.add("observed",observed);
                    if("missing".equals(status)) row.add("cost",costs.placing(1).json());
                    else if("conflict".equals(status) && observed!=null && observed.has("tilePresent") && !observed.get("tilePresent").getAsBoolean() && observed.has("relativeHardness")) {
                        row.add("breakCost",costs.breaking(observed.get("relativeHardness").getAsDouble(),creative).json());
                        row.addProperty("requiresExplicitRemovalOwnership",true);
                    }
                    row.addProperty("mutationAuthorized",false); differences.add(row);
                }
            }
            return next==source.cells().size();
        }
        public boolean advance(Observations access,double playerX,double playerY,double playerZ,boolean creative,long deadline) {
            if(!classify(access,creative,deadline)) return false;
            if(pending==null) { pending=new ArrayList<Cell>(missing); cursor=new PlayerNavigation.Point(playerX,playerY,playerZ); }
            if(!includePlacementOrder) return true;
            // Seed dependency ordering from real six-neighbour supports. Unknown neighbours never seed a plan.
            for(int sampled=0; supportIndex<missing.size() && sampled<32 && System.nanoTime()<deadline; sampled++,supportIndex++) {
                Cell cell=missing.get(supportIndex);
                for(int[] d:DIRECTIONS) {
                    int x=cell.x+d[0],y=cell.y+d[1],z=cell.z+d[2];
                    if(y>=0 && y<=255) {
                        JsonObject state=access.block(x,y,z);
                        if(state!=null && state.get("loaded").getAsBoolean() && !state.get("air").getAsBoolean() && !state.get("replaceable").getAsBoolean()) supports.add(x+":"+y+":"+z);
                    }
                }
            }
            if(supportIndex<missing.size()) return false;
            for(int actions=0; !pending.isEmpty() && actions<32 && System.nanoTime()<deadline; actions++) {
                if(planned>=512) { orderBounded=true; return true; }
                for(;orderSearch<pending.size() && System.nanoTime()<deadline;orderSearch++) { Cell cell=pending.get(orderSearch); if(attached(cell,supports)) {
                    double score=costs.walking(Math.hypot(cell.x+.5-cursor.x,cell.z+.5-cursor.z),0).score()+costs.placing(1).score();
                    if(score<orderBest) { orderBest=score; orderSelected=cell; }
                } }
                if(orderSearch<pending.size()) return false;
                Cell selected=orderSelected; orderSearch=0; orderSelected=null; orderBest=Double.POSITIVE_INFINITY;
                if(selected==null) return true;
                if(order.size()<limit) { JsonObject row=selected.xyz(); row.addProperty("operation","player.place_block"); row.add("alternatives",selected.alternatives); row.addProperty("requiresFreshNativeApproach",true); order.add(row); }
                supports.add(key(selected)); pending.remove(selected); planned++; cursor=new PlayerNavigation.Point(selected.x+.5,cursor.y,selected.z+.5);
            }
            return pending.isEmpty();
        }
        public JsonObject finish() {
            JsonObject result=new JsonObject(); result.addProperty("operation",OPERATION); result.addProperty("provider","explicit_generic_schematic"); result.addProperty("computationSide","local_client");
            result.addProperty("complete",next==source.cells().size()); result.addProperty("processedCount",next); result.addProperty("requestedCount",source.cells().size());
            result.addProperty("goalSatisfied",source.completeRules() && next==source.cells().size() && counts.getOrDefault("unknown",0)==0 && counts.getOrDefault("missing",0)==0 && counts.getOrDefault("conflict",0)==0);
            result.addProperty("formationProved",false); result.addProperty("nonAtomic",true); result.addProperty("worldMutated",false);
            JsonObject totals=new JsonObject(); for(String status:new String[]{"matched","missing","conflict","unknown","ignored"}) totals.addProperty(status,counts.getOrDefault(status,0)); result.add("counts",totals);
            result.add("differences",differences); result.add("placementOrder",order); result.addProperty("plannedPlacementCount",planned);
            if(includePlacementOrder && !orderBounded) result.addProperty("unattachedMissingCount",pending.size()); else result.add("unattachedMissingCount",com.google.gson.JsonNull.INSTANCE);
            result.addProperty("unplannedMissingCount",pending.size());
            result.addProperty("placementOrderIncluded",includePlacementOrder); result.addProperty("maximumExpandedCells",MAX_CELLS);
            result.addProperty("placementOrderBounded",orderBounded); result.addProperty("maximumPredictedPlacements",512);
            result.addProperty("detailsLimited",orderBounded || differences.size()<counts.getOrDefault("missing",0)+counts.getOrDefault("conflict",0)+counts.getOrDefault("unknown",0) || order.size()<planned);
            JsonArray required=new JsonArray(); for(Map.Entry<String,Integer> entry:materials.entrySet()) { JsonObject row=new com.google.gson.JsonParser().parse(entry.getKey()).getAsJsonObject(); row.addProperty("count",entry.getValue()); required.add(row); }
            result.add("materials",required); result.add("costModel",costs.json());
            result.addProperty("materialsSelection", "first_declared_block_alternative"); result.addProperty("materialItemStacksResolved", false);
            result.addProperty("materialsScope", "Missing cells only, suggested block-predicate counts. No inventory availability or ItemBlock damage mapping is inferred.");
            result.addProperty("scope","Exact declared block/air/ignore predicates only. Geometry rotation never rotates block metadata. Order predicts attachments; every actual action still requires fresh native material/ray/reach/body and server confirmation. No formation inference or removal permission."); return result;
        }
    }
    static final int[][] DIRECTIONS={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    static String key(Cell cell) { return cell.x+":"+cell.y+":"+cell.z; }
    static boolean attached(Cell cell,Set<String> supports) { for(int[] d:DIRECTIONS) if(supports.contains((cell.x+d[0])+":"+(cell.y+d[1])+":"+(cell.z+d[2]))) return true; return false; }
}
