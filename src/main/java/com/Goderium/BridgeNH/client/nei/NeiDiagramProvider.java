package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.fluids.Fluid;

/** Existing NEICustomDiagram 1.8.35 model, with a fixed, typed and bounded continuation graph. */
final class NeiDiagramProvider {
    private static final String API = "com.github.dcysteine.neicustomdiagram.api.";
    private static final String GROUP = API + "diagram.DiagramGroup";
    private static final String DIAGRAM = API + "diagram.Diagram";
    private static final String COMPOSITE = API + "diagram.CompositeDiagram";
    private static final String STATE = API + "diagram.DiagramState";
    private static final String CUSTOM_STATE = API + "diagram.CustomDiagramState";
    private static final String LAYOUT = API + "diagram.layout.Layout";
    private static final String TEXT = API + "diagram.layout.Text";
    private static final String LINES = API + "diagram.layout.Lines";
    private static final String SEGMENT = LINES + "$Segment";
    private static final String SLOT = API + "diagram.layout.Slot";
    private static final String SLOT_GROUP = API + "diagram.layout.SlotGroup";
    private static final String COMPONENT_LABEL = API + "diagram.layout.ComponentLabel";
    private static final String COMPONENT_GROUP = API + "diagram.interactable.InteractiveComponentGroup";
    private static final String CUSTOM_INTERACTABLE = API + "diagram.interactable.CustomInteractable";
    private static final String DISPLAY = API + "diagram.component.DisplayComponent";
    private static final String ITEM = API + "diagram.component.ItemComponent";
    private static final String FLUID = API + "diagram.component.FluidComponent";
    private static final String NBT = API + "diagram.component.ImmutableNbtWrapper";
    private static final String TOOLTIP = API + "diagram.tooltip.Tooltip";
    private static final String TOOLTIP_LINE = API + "diagram.tooltip.TooltipLine";
    private static final String TOOLTIP_ELEMENT = API + "diagram.tooltip.TooltipElement";
    private static final String TOOLTIP_ONE_OF = API + "diagram.tooltip.AutoOneOf_TooltipElement";
    private static final String FORMATTING = API + "diagram.tooltip.TextFormatting";
    private static final String POINT = API + "draw.Point";
    private static final String DIMENSION = API + "draw.Dimension";
    private static final String COLOR = "com.gtnewhorizon.gtnhlib.color.ColorResource";
    private static final String SOURCE = "NEICustomDiagram 1.8.35 existing DiagramGroup/Diagram/Layout and typed immutable models; GTNHLib 0.11.51 cached colour";
    private static final String[] KEYS = {"diagramState", "labels", "lines", "slots", "slotGroups", "interactables", "coverage"};
    private static final int BYTE_LIMIT = 112 * 1024;
    private static final int PREVIEW = 512;
    private static final int MAX_PATH = 8;
    private static final long SOFT_NANOS = 4000000L;
    private static final Map<Class<?>, Map<String, Field>> FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, Method>> METHODS = new ConcurrentHashMap<>();

    static JsonObject read(Object handler, int recipeIndex, int offset, int limit, long started) {
        validate(offset, limit, 64);
        JsonObject result = base(handler, recipeIndex, offset, limit);
        try {
            Model model = model(handler, recipeIndex);
            if (model == null) return unavailable(result, "not_present", "Current handler has no existing NCD DiagramGroup model.");
            describe(result, model);
            List<JsonObject> entries = descriptors(model);
            return page(result, entries.size(), offset, limit, started, i -> entries.get(i));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { return failed(result, error); }
    }

    static List<JsonObject> metadataEntries(Object handler, int recipeIndex) {
        try {
            Model model = model(handler, recipeIndex);
            return model == null ? Collections.emptyList() : descriptors(model);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { return Collections.emptyList(); }
    }

    static JsonObject metadataValue(Object handler, int recipeIndex, int metadataIndex, int[] valuePath,
            int offset, int limit, long started) {
        validate(offset, limit, 8192);
        if (metadataIndex < 0 || metadataIndex >= KEYS.length) throw new BridgeException("not_found", "NCD metadataIndex is absent.");
        int[] path = valuePath == null ? new int[0] : valuePath;
        if (path.length > MAX_PATH) throw new BridgeException("invalid_args", "NCD valuePath has more than eight indices.");
        for (int i : path) if (i < 0) throw new BridgeException("invalid_args", "NCD valuePath indices must be nonnegative.");
        JsonObject result = base(handler, recipeIndex, offset, limit);
        result.addProperty("diagramMetadataIndex", metadataIndex);
        try {
            Model model = model(handler, recipeIndex);
            if (model == null) return unavailable(result, "not_present", "Current handler has no existing NCD DiagramGroup model.");
            describe(result, model);
            Node node = root(model, metadataIndex);
            for (int selector : path) node = descend(model, node, selector);
            if ("missing".equals(node.kind)) return unavailable(result, "not_present", "The selected optional model value is absent.");
            if ("text".equals(node.kind) || "description".equals(node.kind) || "nbt".equals(node.kind)) {
                return text(result, textValue(model, node), offset, limit, started);
            }
            if ("list".equals(node.kind)) {
                Node selected = node;
                return page(result, selected.items.size(), offset, limit, started, i ->
                        summary(model, item(model, selected, i), metadataIndex, append(path, i)));
            }
            JsonObject data = result.getAsJsonObject("data");
            if (offset == 0) data.add("value", summary(model, node, metadataIndex, path));
            result.addProperty("total", 1);
            result.addProperty("nextOffset", 1);
            result.addProperty("complete", !"unknown".equals(node.kind));
            result.addProperty("softBudgetExceeded", expired(started));
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { return failed(result, error); }
    }

    /** Bounded stable definitions, not the normally advancing animation tick or current cycle. */
    static String context(Object handler, int recipeIndex) {
        try {
            Model model = model(handler, recipeIndex);
            if (model == null) return "ncd:not_present";
            Fingerprint fingerprint = new Fingerprint();
            fingerprint.mix(model.diagram.getClass().getName());
            fingerprint.mix(System.identityHashCode(model.diagram));
            fingerprint.mix(System.identityHashCode(model.layout));
            fingerprint.mix(recipeIndex);
            fingerprint.mix(model.state.getClass().getName());
            fingerprint.mix(model.standardCycle);
            fingerprint.mix(model.paddingBottom);
            fingerprint.mix(model.composite);
            fingerprint.mix(model.baseDrawConfirmed);
            fingerprint.mix(model.customStateSize);
            fingerprint.mix(model.existingStateClass);
            fingerprint.mix(model.globalStateClass);
            for (int key = 1; key <= 5; key++) fingerprint(model, root(model, key), fingerprint, 0);
            return "ncd:" + Long.toHexString(fingerprint.hash);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return "ncd:unsupported:" + error.getClass().getSimpleName();
        }
    }

    private static Model model(Object handler, int recipeIndex) throws ReflectiveOperationException {
        if (handler == null) return null;
        Model model = new Model();
        model.loader = handler.getClass().getClassLoader();
        if (!is(model, handler, GROUP)) return null;
        List<?> diagrams = list(field(model, GROUP, handler, "diagrams", true));
        if (recipeIndex < 0 || recipeIndex >= diagrams.size()) throw new BridgeException("not_found", "Existing diagram recipeIndex is absent.");
        model.diagram = diagrams.get(recipeIndex);
        if (!is(model, model.diagram, DIAGRAM)) throw new BridgeException("unsupported", "Existing diagram is outside the known Diagram model.");
        model.state = field(model, GROUP, handler, "diagramState", true);
        if (!is(model, model.state, STATE)) throw new BridgeException("unsupported", "Existing diagram state is unavailable.");
        model.layout = field(model, DIAGRAM, model.diagram, "layout", false);
        if (!known(model, model.layout, LAYOUT, "labels", "lines", "slots", "slotGroups", "paddingBottom")) {
            throw new BridgeException("unsupported", "Existing base Layout has no verified stored getter schema.");
        }
        model.paddingBottom = number(call(model, LAYOUT, model.layout, "paddingBottom")).intValue();
        model.interactables = list(field(model, DIAGRAM, model.diagram, "interactables", false));
        model.ticks = number(field(model, STATE, model.state, "ticks", false)).intValue();
        model.standardCycle = declared(model.state, STATE, "cycleIndex", int.class)
                && declared(model.state, STATE, "cycle") && declared(model.state, STATE, "ticks");
        model.composite = is(model, model.diagram, COMPOSITE);
        Class<?> stateType = load(model, STATE);
        model.baseDrawConfirmed = (declared(model.diagram, DIAGRAM, "drawBackground", stateType)
                || declared(model.diagram, COMPOSITE, "drawBackground", stateType))
                && (declared(model.diagram, DIAGRAM, "drawForeground", stateType)
                || declared(model.diagram, COMPOSITE, "drawForeground", stateType));
        if (is(model, model.state, CUSTOM_STATE)) {
            Object stored = field(model, CUSTOM_STATE, model.state, "stateMap", false);
            if (stored instanceof Map<?, ?>) {
                Map<?, ?> map = (Map<?, ?>) stored;
                model.customStateSize = map.size();
                Object existing = map.get(model.diagram);
                model.existingStateClass = existing == null ? null : existing.getClass().getName();
            }
            Object global = field(model, CUSTOM_STATE, model.state, "globalState", false);
            model.globalStateClass = global == null ? null : global.getClass().getName();
        }
        return model;
    }

    private static Node root(Model model, int key) throws ReflectiveOperationException {
        switch (key) {
            case 0: return new Node("state", model);
            case 1: return sequence(call(model, LAYOUT, model.layout, "labels"), "drawable");
            case 2: return sequence(call(model, LAYOUT, model.layout, "lines"), "lines");
            case 3: return mapping(call(model, LAYOUT, model.layout, "slots"), "slot", LAYOUT + "$SlotKey");
            case 4: return mapping(call(model, LAYOUT, model.layout, "slotGroups"), "slotGroup", LAYOUT + "$SlotGroupKey");
            case 5: return sequence(model.interactables, "interactable");
            case 6: return new Node("coverage", model);
            default: throw new BridgeException("not_found", "NCD metadataIndex is absent.");
        }
    }

    private static List<JsonObject> descriptors(Model model) throws ReflectiveOperationException {
        List<JsonObject> result = new ArrayList<>(KEYS.length);
        for (int index = 0; index < KEYS.length; index++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("provider", "NEICustomDiagram");
            entry.addProperty("providerClass", NeiDiagramProvider.class.getName());
            entry.addProperty("diagramMetadataIndex", index);
            entry.addProperty("key", KEYS[index]);
            entry.addProperty("source", SOURCE);
            entry.addProperty("availability", "stored_model");
            entry.add("value", summary(model, root(model, index), index, new int[0]));
            result.add(entry);
        }
        return result;
    }

    private static Node sequence(Object values, String schema) {
        Node node = new Node("list", values);
        if (values instanceof List<?>) node.items = (List<?>) values;
        else if (values instanceof Collection<?>) node.items = collectionView((Collection<?>) values);
        else throw new BridgeException("unsupported", "Known NCD collection is unavailable.");
        node.schema = schema;
        return node;
    }

    private static Node mapping(Object values, String schema, String keyOwner) {
        if (!(values instanceof Map<?, ?>)) throw new BridgeException("unsupported", "Known NCD map is unavailable.");
        Node node = sequence(collectionView(((Map<?, ?>) values).entrySet()), schema);
        node.keyOwner = keyOwner;
        return node;
    }

    private static Node item(Model model, Node list, int index) throws ReflectiveOperationException {
        if (index < 0 || index >= list.items.size()) throw new BridgeException("not_found", "NCD collection index is absent.");
        Object value = list.items.get(index);
        Object key = null;
        if (list.keyOwner != null) {
            if (!(value instanceof Map.Entry<?, ?>)) throw new BridgeException("unsupported", "Known NCD map entry is unavailable.");
            key = ((Map.Entry<?, ?>) value).getKey();
            value = ((Map.Entry<?, ?>) value).getValue();
        }
        Node result;
        if ("element".equals(list.schema)) result = classify(model, value);
        else if ("segment".equals(list.schema)) result = new Node(known(model, value, SEGMENT, "a", "b") ? "segment" : "unknown", value);
        else if ("tooltipLine".equals(list.schema)) {
            if (!known(model, value, TOOLTIP_LINE, "elements", "additionalSpacing")) result = new Node("unknown", value);
            else {
                result = sequence(call(model, TOOLTIP_LINE, value, "elements"), "element");
                result.lineSpacing = number(call(model, TOOLTIP_LINE, value, "additionalSpacing")).intValue();
            }
        } else result = classify(model, value);
        if (key != null) {
            if (known(model, key, list.keyOwner, "key")) result.key = string(call(model, list.keyOwner, key, "key"));
            else result.keyClass = key.getClass().getName();
        }
        return result;
    }

    private static Node classify(Model model, Object value) throws ReflectiveOperationException {
        if (value == null) return new Node("missing", null);
        if (known(model, value, TEXT, "text", "position", "dimension", "colour", "small", "shadow")) return new Node("labelText", value);
        if (known(model, value, LINES, "colour", "segments", "arrows")) return new Node("lines", value);
        if (known(model, value, SLOT, "position", "slotWidth", "tooltip", "drawFunction")) return new Node("slot", value);
        if (known(model, value, SLOT_GROUP, "width", "height", "slots")) return new Node("slotGroup", value);
        if (is(model, value, COMPONENT_GROUP)) return new Node("componentGroup", value);
        if (is(model, value, CUSTOM_INTERACTABLE)) return new Node("customInteractable", value);
        if (known(model, value, DISPLAY, "component", "stackSize", "additionalInfo", "additionalTooltip")) return new Node("display", value);
        if (known(model, value, ITEM, "item", "damage", "nbtWrapper")) return new Node("item", value);
        if (known(model, value, FLUID, "fluid", "nbtWrapper")) return new Node("fluid", value);
        if (known(model, value, COMPONENT_LABEL, "component", "position")) return new Node("componentLabel", value);
        if (known(model, value, TOOLTIP, "lines")) return sequence(call(model, TOOLTIP, value, "lines"), "tooltipLine");
        if (is(model, value, TOOLTIP_ELEMENT) && (knownTooltipOneOf(value)
                || known(model, value, TOOLTIP_ELEMENT, "type", "spacing", "text", "displayComponentIcon", "componentDescription", "formatting"))) {
            return new Node("element", value);
        }
        if (known(model, value, FORMATTING, "small", "formatting")) return new Node("formatting", value);
        return new Node("unknown", value);
    }

    private static Node descend(Model model, Node node, int selector) throws ReflectiveOperationException {
        if ("list".equals(node.kind)) return item(model, node, selector);
        List<Child> children = children(model, node);
        if (selector >= children.size()) throw new BridgeException("not_found", "Known NCD child selector is absent.");
        return children.get(selector).node;
    }

    private static List<Child> children(Model model, Node node) throws ReflectiveOperationException {
        List<Child> result = new ArrayList<>(4);
        Object value = node.value;
        switch (node.kind) {
            case "labelText":
                result.add(new Child("text", new Node("text", string(call(model, TEXT, value, "text"))))); break;
            case "lines":
                result.add(new Child("segments", sequence(call(model, LINES, value, "segments"), "segment")));
                result.add(new Child("arrows", sequence(call(model, LINES, value, "arrows"), "segment"))); break;
            case "slot":
                result.add(new Child("tooltip", classify(model, call(model, SLOT, value, "tooltip")))); break;
            case "slotGroup":
                result.add(new Child("slots", sequence(call(model, SLOT_GROUP, value, "slots"), "slot"))); break;
            case "componentGroup":
                result.add(new Child("components", sequence(field(model, COMPONENT_GROUP, value, "components", true), "display")));
                result.add(new Child("slotTooltip", classify(model, field(model, COMPONENT_GROUP, value, "slotTooltip", true)))); break;
            case "customInteractable":
                result.add(new Child("drawable", classify(model, field(model, CUSTOM_INTERACTABLE, value, "drawable", true))));
                result.add(new Child("tooltip", classify(model, field(model, CUSTOM_INTERACTABLE, value, "tooltip", true)))); break;
            case "componentLabel":
                result.add(new Child("component", classify(model, call(model, COMPONENT_LABEL, value, "component")))); break;
            case "display":
            case "displayIcon":
                Object component = call(model, DISPLAY, value, "component");
                componentChildren(model, component, result);
                result.add(new Child("additionalInfo", optionalText(call(model, DISPLAY, value, "additionalInfo"))));
                if ("display".equals(node.kind)) result.add(new Child("additionalTooltip",
                        classify(model, call(model, DISPLAY, value, "additionalTooltip"))));
                break;
            case "item":
            case "fluid": componentChildren(model, value, result); break;
            case "element":
                String element = enumName(call(model, TOOLTIP_ELEMENT, value, "type"));
                if ("TEXT".equals(element)) result.add(new Child("text", new Node("text", string(call(model, TOOLTIP_ELEMENT, value, "text")))));
                else if ("DISPLAY_COMPONENT_ICON".equals(element)) {
                    Object display = call(model, TOOLTIP_ELEMENT, value, "displayComponentIcon");
                    result.add(new Child("displayedIcon", new Node(known(model, display, DISPLAY, "component", "stackSize", "additionalInfo") ? "displayIcon" : "unknown", display)));
                } else if ("COMPONENT_DESCRIPTION".equals(element)) {
                    Object described = call(model, TOOLTIP_ELEMENT, value, "componentDescription");
                    result.add(new Child("description", new Node(descriptionReadable(model, described) ? "description" : "unknown", described)));
                } else if ("FORMATTING".equals(element)) result.add(new Child("formatting",
                        classify(model, call(model, TOOLTIP_ELEMENT, value, "formatting"))));
                break;
            default: break;
        }
        if (node.key != null) result.add(new Child("layoutKey", new Node("text", node.key)));
        return result;
    }

    private static void componentChildren(Model model, Object component, List<Child> result) throws ReflectiveOperationException {
        String owner = componentOwner(model, component);
        if (owner == null) {
            result.add(new Child("nbt", new Node("unknown", component)));
            result.add(new Child("description", new Node("unknown", component)));
            return;
        }
        Object wrapper = optional(call(model, owner, component, "nbtWrapper"));
        result.add(new Child("nbt", wrapper == null ? new Node("missing", null) :
                new Node(is(model, wrapper, NBT) && knownValueClass(wrapper, NBT) && declared(wrapper, NBT, "get") ? "nbt" : "unknown", wrapper)));
        result.add(new Child("description", new Node(descriptionReadable(model, component) ? "description" : "unknown", component)));
    }

    private static JsonObject summary(Model model, Node node, int metadataIndex, int[] path) throws ReflectiveOperationException {
        JsonObject result = new JsonObject();
        result.addProperty("kind", node.kind);
        result.addProperty("availability", "unknown".equals(node.kind) ? "unknown" : "missing".equals(node.kind) ? "not_present" : "stored_model");
        if (node.value != null && !(node.value instanceof Model)) result.addProperty("class", node.value.getClass().getName());
        if (node.key != null) preview(result, "layoutKey", node.key);
        if (node.keyClass != null) {
            result.addProperty("layoutKeyClass", node.keyClass);
            result.addProperty("layoutKeyAvailability", "unknown_custom_key_getter");
        }
        if ("list".equals(node.kind)) {
            result.addProperty("total", node.items.size());
            result.addProperty("order", node.keyOwner == null ? "Existing immutable/list encounter order" : "Existing layout map encounter order");
            if (node.lineSpacing != null) result.addProperty("additionalSpacing", node.lineSpacing);
            if (path.length <= MAX_PATH) result.add("continuation", continuation(metadataIndex, path));
            else result.addProperty("continuationStatus", "unknown_beyond_eight_selector_contract");
            return result;
        }
        switch (node.kind) {
            case "state": state(result, model); break;
            case "coverage": result.add("coverage", coverage(model)); break;
            case "labelText":
                preview(result, "text", string(call(model, TEXT, node.value, "text")));
                result.add("position", point(model, call(model, TEXT, node.value, "position")));
                result.add("dimension", dimension(model, call(model, TEXT, node.value, "dimension")));
                result.addProperty("colour", number(call(model, TEXT, node.value, "colour")));
                result.addProperty("small", bool(call(model, TEXT, node.value, "small")));
                result.addProperty("shadow", bool(call(model, TEXT, node.value, "shadow"))); break;
            case "lines":
                Object colour = call(model, LINES, node.value, "colour");
                result.addProperty("colourClass", colour == null ? null : colour.getClass().getName());
                if (colour != null && declared(colour, COLOR, "getColor")) result.addProperty("colour", number(call(model, COLOR, colour, "getColor")));
                else result.addProperty("colourStatus", "unknown_unverified_color_getter"); break;
            case "segment":
                result.add("a", point(model, call(model, SEGMENT, node.value, "a")));
                result.add("b", point(model, call(model, SEGMENT, node.value, "b"))); break;
            case "slot":
                result.add("position", point(model, call(model, SLOT, node.value, "position")));
                result.addProperty("slotWidth", number(call(model, SLOT, node.value, "slotWidth")));
                Object draw = call(model, SLOT, node.value, "drawFunction");
                result.addProperty("drawFunctionClass", draw == null ? null : draw.getClass().getName());
                result.addProperty("drawCoverage", "Stored position/width/tooltip; drawFunction is not executed and its custom pixel semantics are unknown."); break;
            case "slotGroup":
                result.addProperty("width", number(call(model, SLOT_GROUP, node.value, "width")));
                result.addProperty("height", number(call(model, SLOT_GROUP, node.value, "height"))); break;
            case "componentGroup": componentGroup(result, model, node.value); break;
            case "customInteractable":
                result.addProperty("callbackCoverage", "Existing drawable and tooltip are readable; interaction/background/foreground/overlay Consumers are unknown and never invoked."); break;
            case "componentLabel":
                result.add("position", point(model, call(model, COMPONENT_LABEL, node.value, "position"))); break;
            case "display":
            case "displayIcon":
                Object displayed = call(model, DISPLAY, node.value, "component");
                component(result, model, displayed);
                Object quantity = optional(call(model, DISPLAY, node.value, "stackSize"));
                result.add("stackSize", quantity == null ? JsonNull.INSTANCE : primitive(number(quantity)));
                String quantityOwner = componentOwner(model, displayed);
                if (quantityOwner != null && knownValueClass(displayed, quantityOwner)) {
                    result.addProperty("effectiveStackSize", quantity == null ? ITEM.equals(quantityOwner) ? 1 : 1000 : number(quantity));
                    result.addProperty("effectiveStackSizeSource", quantity == null ? "Known ItemComponent/FluidComponent default stack size" : "Stored DisplayComponent.stackSize override");
                } else result.addProperty("effectiveStackSizeAvailability", "unknown_custom_component_stack_semantics");
                Object info = optional(call(model, DISPLAY, node.value, "additionalInfo"));
                if (info == null) result.add("additionalInfo", JsonNull.INSTANCE); else preview(result, "additionalInfo", string(info));
                if ("displayIcon".equals(node.kind)) result.addProperty("additionalTooltipStatus", "Not drawn for an icon embedded inside another tooltip; no recursive tooltip callback."); break;
            case "item":
            case "fluid": component(result, model, node.value); break;
            case "element": element(result, model, node.value); break;
            case "formatting": formatting(result, model, node.value); break;
            case "text": preview(result, "text", string(node.value)); break;
            case "description": result.addProperty("source", "Known ItemComponent/FluidComponent description getter; normal item/fluid display-name and current SHOW_IDS option."); break;
            case "nbt": result.addProperty("source", "Explicit ImmutableNbtWrapper.get() copy and SNBT text; no recipe/model/tile serialization."); break;
            case "unknown":
                result.addProperty("reason", "Object has no verified concrete NCD getter schema; no object graph or renderer callback is scanned."); break;
            default: break;
        }
        JsonArray children = new JsonArray();
        List<Child> values = children(model, node);
        for (int i = 0; i < values.size(); i++) {
            Child child = values.get(i);
            JsonObject entry = new JsonObject();
            entry.addProperty("childIndex", i);
            entry.addProperty("name", child.name);
            entry.addProperty("kind", child.node.kind);
            entry.addProperty("availability", "unknown".equals(child.node.kind) ? "unknown" : "missing".equals(child.node.kind) ? "not_present" : "stored_model");
            if ("list".equals(child.node.kind)) entry.addProperty("total", child.node.items.size());
            if ("text".equals(child.node.kind)) entry.addProperty("characters", string(child.node.value).codePointCount(0, string(child.node.value).length()));
            if (path.length < MAX_PATH && !"unknown".equals(child.node.kind) && !"missing".equals(child.node.kind)) {
                entry.add("continuation", continuation(metadataIndex, append(path, i)));
            } else if (path.length >= MAX_PATH) entry.addProperty("continuationStatus", "unknown_beyond_eight_selector_contract");
            children.add(entry);
        }
        if (children.size() > 0) result.add("children", children);
        return result;
    }

    private static void state(JsonObject result, Model model) {
        result.addProperty("stateClass", model.state.getClass().getName());
        result.addProperty("layoutPaddingBottom", model.paddingBottom);
        result.addProperty("storedTicks", model.ticks);
        result.addProperty("cycleFromStoredTicks", model.ticks / 20);
        result.addProperty("standardCycleSemanticsConfirmed", model.standardCycle);
        result.addProperty("cycleSource", "Stored DiagramState.ticks, TICKS_PER_CYCLE=20, Java integer division and floorMod; unknown state overrides are not invoked.");
        result.addProperty("animationNonAtomic", true);
        result.addProperty("animationInContext", false);
        result.addProperty("existingCustomStateCount", model.customStateSize);
        result.addProperty("existingPerDiagramStateClass", model.existingStateClass);
        result.addProperty("globalStateClass", model.globalStateClass);
        result.addProperty("customStateCoverage", "Existing fixed map/global references only; opaque values are not scanned and state()/computeIfAbsent is never called.");
    }

    private static void componentGroup(JsonObject result, Model model, Object group) throws ReflectiveOperationException {
        result.add("position", point(model, field(model, COMPONENT_GROUP, group, "position", false)));
        List<?> components = list(field(model, COMPONENT_GROUP, group, "components", true));
        result.addProperty("componentCount", components.size());
        result.addProperty("storedTicks", model.ticks);
        result.addProperty("currentCycle", model.ticks / 20);
        boolean known = model.standardCycle && declared(group, COMPONENT_GROUP, "currentIndex", load(model, STATE))
                && declared(group, COMPONENT_GROUP, "currentComponent", load(model, STATE));
        if (known && !components.isEmpty()) {
            result.addProperty("selectedIndex", Math.floorMod(model.ticks / 20, components.size()));
            result.addProperty("selectedIndexAvailability", "derived_from_verified_current_model");
        } else result.addProperty("selectedIndexAvailability", "unknown_custom_cycle_override_or_empty_candidates");
        result.addProperty("selectedIndexSource", "Known currentIndex/currentComponent semantics from this response's stored tick; no callbacks or tick updates. Normal cycles can change between reads.");
        result.addProperty("animationNonAtomic", true);
    }

    private static void component(JsonObject result, Model model, Object value) throws ReflectiveOperationException {
        String owner = componentOwner(model, value);
        if (owner == null) {
            result.addProperty("componentAvailability", "unknown_custom_component");
            result.addProperty("componentClass", value == null ? null : value.getClass().getName());
            return;
        }
        result.addProperty("componentClass", value.getClass().getName());
        result.addProperty("type", ITEM.equals(owner) ? "ITEM" : "FLUID");
        if (ITEM.equals(owner)) {
            Object item = call(model, ITEM, value, "item");
            if (!(item instanceof Item)) throw new BridgeException("unsupported", "Known item component has no Item.");
            Object registry = Item.itemRegistry.getNameForObject(item);
            result.addProperty("registryName", registry instanceof String ? (String) registry : null);
            result.addProperty("metadata", number(call(model, ITEM, value, "damage")));
        } else {
            Object fluid = call(model, FLUID, value, "fluid");
            if (!(fluid instanceof Fluid)) throw new BridgeException("unsupported", "Known fluid component has no Fluid.");
            result.addProperty("fluidName", ((Fluid) fluid).getName());
            result.addProperty("fluidId", ((Fluid) fluid).getID());
        }
        result.addProperty("nbtPresent", optional(call(model, owner, value, "nbtWrapper")) != null);
        if (descriptionReadable(model, value)) preview(result, "description", string(call(model, owner, value, "description")));
        else result.addProperty("descriptionStatus", "unknown_unverified_description_override");
        result.addProperty("identitySource", "Existing typed component registry identity/damage/NBT wrapper; no matcher, recipe search or new diagram.");
    }

    private static void element(JsonObject result, Model model, Object value) throws ReflectiveOperationException {
        String type = enumName(call(model, TOOLTIP_ELEMENT, value, "type"));
        result.addProperty("elementType", type);
        if ("SPACING".equals(type)) result.addProperty("spacing", number(call(model, TOOLTIP_ELEMENT, value, "spacing")));
        else if ("TEXT".equals(type)) preview(result, "text", string(call(model, TOOLTIP_ELEMENT, value, "text")));
        else if (!"DISPLAY_COMPONENT_ICON".equals(type) && !"COMPONENT_DESCRIPTION".equals(type) && !"FORMATTING".equals(type)) {
            result.addProperty("availability", "unknown");
            result.addProperty("reason", "Unknown TooltipElement variant is not serialized.");
        }
    }

    private static void formatting(JsonObject result, Model model, Object value) throws ReflectiveOperationException {
        result.addProperty("small", bool(call(model, FORMATTING, value, "small")));
        Object values = call(model, FORMATTING, value, "formatting");
        if (!(values instanceof Collection<?>)) throw new BridgeException("unsupported", "Known TextFormatting codes are unavailable.");
        if (((Collection<?>) values).size() > EnumChatFormatting.values().length) {
            throw new BridgeException("unsupported", "Known immutable formatting set exceeded its finite enum domain.");
        }
        JsonArray codes = new JsonArray();
        for (Object code : (Collection<?>) values) {
            if (!(code instanceof EnumChatFormatting)) throw new BridgeException("unsupported", "Unknown formatting code is not invoked.");
            JsonObject item = new JsonObject();
            item.addProperty("name", ((EnumChatFormatting) code).name());
            item.addProperty("code", code.toString());
            codes.add(item);
        }
        result.add("codes", codes); // Vanilla EnumChatFormatting has a fixed finite set.
    }

    private static String textValue(Model model, Node node) throws ReflectiveOperationException {
        if ("text".equals(node.kind)) return string(node.value);
        if ("description".equals(node.kind)) {
            String owner = componentOwner(model, node.value);
            if (owner == null || !descriptionReadable(model, node.value)) throw new BridgeException("unsupported", "Unknown description callback is not invoked.");
            return string(call(model, owner, node.value, "description"));
        }
        if (!is(model, node.value, NBT) || !knownValueClass(node.value, NBT) || !declared(node.value, NBT, "get")) {
            throw new BridgeException("unsupported", "Unknown NBT wrapper callback is not invoked.");
        }
        Object value = call(model, NBT, node.value, "get");
        if (!(value instanceof NBTTagCompound)) throw new BridgeException("unsupported", "Known NBT wrapper did not return a compound.");
        return value.toString();
    }

    private static Node optionalText(Object value) {
        Object text = optional(value);
        return text == null ? new Node("missing", null) : new Node("text", string(text));
    }

    private static String componentOwner(Model model, Object value) throws ReflectiveOperationException {
        return known(model, value, ITEM, "item", "damage", "nbtWrapper") ? ITEM
                : known(model, value, FLUID, "fluid", "nbtWrapper") ? FLUID : null;
    }

    private static boolean descriptionReadable(Model model, Object value) throws ReflectiveOperationException {
        String owner = componentOwner(model, value);
        return owner != null && knownValueClass(value, owner) && declared(value, owner, "description");
    }

    private static JsonObject point(Model model, Object point) throws ReflectiveOperationException {
        JsonObject result = new JsonObject();
        if (!known(model, point, POINT, "x", "y")) return unknownGeometry(result, point);
        result.addProperty("x", number(call(model, POINT, point, "x")));
        result.addProperty("y", number(call(model, POINT, point, "y")));
        return result;
    }

    private static JsonObject dimension(Model model, Object dimension) throws ReflectiveOperationException {
        JsonObject result = new JsonObject();
        if (!known(model, dimension, DIMENSION, "width", "height")) return unknownGeometry(result, dimension);
        result.addProperty("width", number(call(model, DIMENSION, dimension, "width")));
        result.addProperty("height", number(call(model, DIMENSION, dimension, "height")));
        return result;
    }

    private static JsonObject unknownGeometry(JsonObject result, Object value) {
        result.addProperty("availability", "unknown");
        result.addProperty("class", value == null ? null : value.getClass().getName());
        result.addProperty("reason", "Unknown geometry getter is not invoked.");
        return result;
    }

    private static JsonArray coverage(Model model) {
        JsonArray result = new JsonArray();
        cover(result, "layout", "stored_model", "Known base Layout labels/lines/slots/slotGroups; existing collections only.");
        cover(result, "components", "stored_model", "Existing component groups, all alternatives, quantity, additionalInfo and typed item/fluid/NBT; unknown component implementations remain explicit.");
        cover(result, "tooltips", "stored_model", "Existing slot/additional/custom Tooltip lines/elements/text/icon/description/formatting. No tooltip drawing or virtual mouse interaction.");
        cover(result, "currentCycle", model.standardCycle ? "readable_non_atomic" : "unknown", "Stored ticks/currentIndex semantics are returned per response; normal tick progression is excluded from context.");
        cover(result, "dynamicCompositeChildren", model.composite ? "unknown" : "not_present", "CompositeDiagram.activeDiagrams can execute custom behavior and is never called. Only its base model is inspected.");
        cover(result, "customDiagramDrawing", model.baseDrawConfirmed ? "known_base_path" : "unknown", "Unknown drawBackground/drawForeground overrides may add, omit or transform data; existing fields do not prove final pixels.");
        cover(result, "customState", model.customStateSize >= 0 ? "unknown_semantics" : "not_present", "Existing CustomDiagramState stateMap/global references are summarized by class/count; no state() or arbitrary object graph scan.");
        cover(result, "customCallbacks", "unknown", "Slot drawFunction and CustomInteractable interaction/draw/overlay Consumers are not executed; known stored drawable/tooltip data still has selectors.");
        cover(result, "completeItemTooltip", "unknown", "The standard component description is readable. ItemStack tooltip callbacks, modifier-dependent generated cycle tooltips and graphics are not reconstructed here.");
        cover(result, "geometry", "model_coordinates", "Point/dimension values are recipe-relative stored values. Font/scissor/GL transforms, texture content, custom glyphs and occlusion require actual draw observation.");
        cover(result, "continuationDepth", "bounded", "Explicit typed selectors only, maximum eight. Deeper unknown custom nesting has no fabricated continuation.");
        return result;
    }

    private static void cover(JsonArray result, String feature, String availability, String reason) {
        JsonObject entry = new JsonObject();
        entry.addProperty("feature", feature);
        entry.addProperty("availability", availability);
        entry.addProperty("source", SOURCE);
        entry.addProperty("reason", reason);
        result.add(entry);
    }

    private static JsonObject base(Object handler, int recipeIndex, int offset, int limit) {
        JsonObject result = new JsonObject();
        result.addProperty("status", "ok");
        result.addProperty("provider", "NEICustomDiagram");
        result.addProperty("providerClass", NeiDiagramProvider.class.getName());
        result.addProperty("source", SOURCE);
        result.addProperty("availability", "stored_model");
        result.addProperty("recipeIndex", recipeIndex);
        if (handler != null) result.addProperty("handlerClass", handler.getClass().getName());
        result.addProperty("offset", offset); result.addProperty("limit", limit);
        result.addProperty("total", 0); result.addProperty("nextOffset", 0); result.addProperty("hasMore", false);
        result.addProperty("complete", false); result.addProperty("byteLimited", false); result.addProperty("softBudgetPartial", false);
        result.add("data", new JsonObject());
        return result;
    }

    private static void describe(JsonObject result, Model model) {
        result.addProperty("diagramClass", model.diagram.getClass().getName());
        result.addProperty("stateClass", model.state.getClass().getName());
        JsonObject visibility = new JsonObject();
        visibility.addProperty("existingHandlerDiagram", true);
        visibility.addProperty("baseDrawSemanticsConfirmed", model.baseDrawConfirmed);
        visibility.addProperty("composite", model.composite);
        visibility.addProperty("dynamicChildrenConfirmed", false);
        visibility.addProperty("source", "Requested current handler's existing diagrams[original recipeIndex]; no global matcher, loadDiagrams or visibility config materialization.");
        result.add("knownVisibility", visibility);
        result.add("coverage", coverage(model));
        result.addProperty("nonAtomic", true);
        result.addProperty("coordinateFrame", "NEI recipe-relative stored model coordinates, not final screen-space pixel bounds.");
        result.addProperty("contextCoverage", "Existing diagram/layout identities and stable typed definitions in at most 128 nodes, first 64 entries per collection, eight levels and first 4096 chars per text. No animation tick/cycle, opaque state contents, full NBT or dynamic callbacks in the fingerprint.");
        result.addProperty("budgetSource", "4 ms soft deadline; first entry, selected-path traversal and synchronous verified getter may finish. No callback/getter is preempted.");
    }

    private static JsonObject page(JsonObject result, int total, int offset, int limit, long started, Reader reader)
            throws ReflectiveOperationException {
        limit = Math.min(limit, 64);
        result.addProperty("limit", limit);
        JsonArray entries = new JsonArray();
        int next = Math.min(offset, total);
        boolean partial = false;
        while (next < total && entries.size() < limit) {
            if (entries.size() > 0 && expired(started)) break;
            JsonObject entry = reader.read(next);
            if (bytes(result) + bytes(entries) + bytes(entry) > BYTE_LIMIT && entries.size() > 0) {
                result.addProperty("byteLimited", true); break;
            }
            entries.add(entry);
            partial |= "unknown".equals(entry.has("availability") ? entry.get("availability").getAsString() : "") || truncated(entry);
            next++;
        }
        result.getAsJsonObject("data").add("entries", entries);
        result.addProperty("total", total); result.addProperty("nextOffset", next); result.addProperty("hasMore", next < total);
        result.addProperty("complete", next >= total && !partial);
        result.addProperty("softBudgetPartial", next < total && expired(started));
        result.addProperty("softBudgetExceeded", expired(started));
        return result;
    }

    private static JsonObject text(JsonObject result, String value, int offset, int limit, long started) {
        int total = value.codePointCount(0, value.length());
        int from = Math.min(offset, total);
        int next = (int) Math.min((long) total, (long) offset + limit);
        result.getAsJsonObject("data").addProperty("text", value.substring(value.offsetByCodePoints(0, from), value.offsetByCodePoints(0, next)));
        result.getAsJsonObject("data").addProperty("offsetUnit", "unicode_codepoints");
        result.addProperty("total", total); result.addProperty("nextOffset", next); result.addProperty("hasMore", next < total);
        result.addProperty("complete", next >= total); result.addProperty("softBudgetExceeded", expired(started));
        return result;
    }

    private static JsonObject continuation(int key, int[] path) {
        JsonObject result = new JsonObject();
        result.addProperty("target", "metadata_value");
        result.addProperty("diagramMetadataIndex", key);
        JsonArray values = new JsonArray();
        for (int index : path) values.add(new JsonPrimitive(index));
        result.add("valuePath", values);
        result.addProperty("offset", 0);
        return result;
    }

    private static void preview(JsonObject result, String key, String text) {
        int total = text.codePointCount(0, text.length());
        result.addProperty(key, text.substring(0, text.offsetByCodePoints(0, Math.min(PREVIEW, total))));
        result.addProperty(key + "Characters", total);
        result.addProperty(key + "Truncated", total > PREVIEW);
    }

    private static boolean truncated(JsonObject value) {
        for (Map.Entry<String, JsonElement> entry : value.entrySet()) {
            if (entry.getKey().endsWith("Truncated") && entry.getValue().getAsBoolean()) return true;
        }
        return false;
    }

    private static JsonObject unavailable(JsonObject result, String status, String reason) {
        result.addProperty("status", status); result.addProperty("availability", status); result.addProperty("reason", reason);
        result.addProperty("total", 0); result.addProperty("nextOffset", 0); result.addProperty("hasMore", false); result.addProperty("complete", false);
        return result;
    }

    private static JsonObject failed(JsonObject result, Throwable error) {
        if (error instanceof BridgeException) {
            BridgeException bridge = (BridgeException) error;
            if ("invalid_args".equals(bridge.code) || "not_found".equals(bridge.code)) throw bridge;
        }
        return unavailable(result, "unsupported", "Known read-only NCD model unavailable: " + error.getClass().getSimpleName());
    }

    private static void fingerprint(Model model, Node node, Fingerprint out, int depth) throws ReflectiveOperationException {
        if (out.remaining-- <= 0 || depth > MAX_PATH) return;
        out.mix(node.kind);
        out.mix(node.key);
        if ("text".equals(node.kind)) { out.mix(node.value); return; }
        if ("nbt".equals(node.kind) || "description".equals(node.kind) || "missing".equals(node.kind)) return;
        if ("list".equals(node.kind)) {
            out.mix(node.items.size());
            out.mix(node.lineSpacing);
            for (int i = 0; i < Math.min(64, node.items.size()) && out.remaining > 0; i++) fingerprint(model, item(model, node, i), out, depth + 1);
            return;
        }
        JsonObject value = summary(model, node, 0, new int[0]);
        for (String key : new String[] {"storedTicks", "currentCycle", "selectedIndex", "cycleFromStoredTicks", "children"}) value.remove(key);
        out.mix(value.toString());
        for (Child child : children(model, node)) {
            if (out.remaining <= 0) break;
            out.mix(child.name);
            fingerprint(model, child.node, out, depth + 1);
        }
    }

    private static Object call(Model model, String owner, Object target, String name) throws ReflectiveOperationException {
        Class<?> api = load(model, owner);
        if (!api.isInstance(target)) throw new BridgeException("unsupported", "Known NCD getter owner is unavailable.");
        if (!knownGetter(target, owner, name)) throw new BridgeException("unsupported", "Unknown NCD getter override is not invoked.");
        Map<String, Method> cache = METHODS.computeIfAbsent(api, ignored -> new ConcurrentHashMap<>());
        Method method = cache.get(name);
        if (method == null) { method = api.getMethod(name); cache.put(name, method); }
        return method.invoke(target);
    }

    private static Object field(Model model, String owner, Object target, String name, boolean publicField)
            throws ReflectiveOperationException {
        Class<?> api = load(model, owner);
        if (!api.isInstance(target)) throw new BridgeException("unsupported", "Known NCD field owner is unavailable.");
        Map<String, Field> cache = FIELDS.computeIfAbsent(api, ignored -> new ConcurrentHashMap<>());
        Field field = cache.get(name);
        if (field == null) {
            field = publicField ? api.getField(name) : api.getDeclaredField(name);
            if (!publicField) field.setAccessible(true);
            cache.put(name, field);
        }
        return field.get(target);
    }

    private static boolean declared(Object object, String owner, String name, Class<?>... args) {
        if (object == null) return false;
        try { return owner.equals(object.getClass().getMethod(name, args).getDeclaringClass().getName()); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) { return false; }
    }

    private static boolean known(Model model, Object value, String owner, String... getters) throws ReflectiveOperationException {
        if (!is(model, value, owner)) return false;
        for (String getter : getters) if (!knownGetter(value, owner, getter)) return false;
        return true;
    }

    private static boolean knownGetter(Object value, String owner, String getter) {
        if (value == null) return false;
        try {
            if (knownGeneratedOwner(value.getClass().getMethod(getter).getDeclaringClass().getName(), owner)) return true;
            return TOOLTIP_ELEMENT.equals(owner) && knownTooltipOneOf(value)
                    && ("type".equals(getter) || getter.equals(tooltipOneOfProperty(value)));
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) { return false; }
    }

    /** Exact 1.8.35 generated sum types; inactive Parent_ accessors are checked, never invoked. */
    private static boolean knownTooltipOneOf(Object value) {
        String active = tooltipOneOfProperty(value);
        if (active == null) return false;
        Class<?> type = value.getClass();
        if (!Modifier.isFinal(type.getModifiers()) || type.getSuperclass() == null
                || !(TOOLTIP_ONE_OF + "$Parent_").equals(type.getSuperclass().getName())) return false;
        try {
            for (String getter : new String[] {"type", "spacing", "text", "displayComponentIcon", "componentDescription", "formatting"}) {
                String expected = "type".equals(getter) || active.equals(getter) ? type.getName() : TOOLTIP_ONE_OF + "$Parent_";
                if (!expected.equals(type.getMethod(getter).getDeclaringClass().getName())) return false;
            }
            return (TOOLTIP_ELEMENT + "$ElementType").equals(type.getMethod("type").getReturnType().getName());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { return false; }
    }

    private static String tooltipOneOfProperty(Object value) {
        if (value == null) return null;
        switch (value.getClass().getName()) {
            case TOOLTIP_ONE_OF + "$Impl_spacing": return "spacing";
            case TOOLTIP_ONE_OF + "$Impl_text": return "text";
            case TOOLTIP_ONE_OF + "$Impl_displayComponentIcon": return "displayComponentIcon";
            case TOOLTIP_ONE_OF + "$Impl_componentDescription": return "componentDescription";
            case TOOLTIP_ONE_OF + "$Impl_formatting": return "formatting";
            default: return null;
        }
    }

    private static boolean knownValueClass(Object value, String owner) {
        return value != null && knownGeneratedOwner(value.getClass().getName(), owner);
    }

    private static boolean knownGeneratedOwner(String declaring, String owner) {
        if (owner.equals(declaring)) return true;
        int last = owner.lastIndexOf('.');
        String generated = owner.substring(0, last + 1) + "AutoValue_" + owner.substring(last + 1).replace('$', '_');
        return generated.equals(declaring) || (owner.substring(0, last + 1) + "$" + generated.substring(last + 1)).equals(declaring);
    }

    private static Class<?> load(Model model, String name) throws ClassNotFoundException { return Class.forName(name, false, model.loader); }
    private static boolean is(Model model, Object value, String owner) throws ReflectiveOperationException {
        if (value == null) return false;
        try { return load(model, owner).isInstance(value); }
        catch (ClassNotFoundException missing) { return false; }
    }

    private static List<?> list(Object value) {
        if (!(value instanceof List<?>)) throw new BridgeException("unsupported", "Known NCD value is not a List.");
        return (List<?>) value;
    }

    private static List<?> collectionView(Collection<?> values) {
        return new AbstractList<Object>() {
            @Override public int size() { return values.size(); }
            @Override public Object get(int index) {
                if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
                int i = 0;
                for (Object value : values) if (i++ == index) return value;
                throw new IndexOutOfBoundsException();
            }
        };
    }

    private static String string(Object value) {
        if (!(value instanceof String)) throw new BridgeException("unsupported", "Known NCD text value is not a String.");
        return (String) value;
    }
    private static String enumName(Object value) {
        if (!(value instanceof Enum<?>)) throw new BridgeException("unsupported", "Known NCD variant is not an enum.");
        return ((Enum<?>) value).name();
    }
    private static Number number(Object value) {
        if (!(value instanceof Number)) throw new BridgeException("unsupported", "Known NCD numeric value is unavailable.");
        Number number = (Number) value;
        if (Double.isInfinite(number.doubleValue()) || Double.isNaN(number.doubleValue())) throw new BridgeException("unsupported", "Known NCD numeric value is not finite.");
        return number;
    }
    private static boolean bool(Object value) {
        if (!(value instanceof Boolean)) throw new BridgeException("unsupported", "Known NCD boolean value is unavailable.");
        return (Boolean) value;
    }
    private static Object optional(Object value) {
        if (!(value instanceof Optional<?>)) throw new BridgeException("unsupported", "Known NCD optional value is unavailable.");
        return ((Optional<?>) value).orElse(null);
    }
    private static JsonElement primitive(Number value) { return value == null ? JsonNull.INSTANCE : new JsonPrimitive(value); }
    private static int[] append(int[] values, int index) {
        int[] result = java.util.Arrays.copyOf(values, values.length + 1); result[values.length] = index; return result;
    }
    private static void validate(int offset, int limit, int maximum) {
        if (offset < 0 || limit < 1 || limit > maximum) throw new BridgeException("invalid_args", "NCD offset must be nonnegative and limit from 1 to " + maximum + ".");
    }
    private static boolean expired(long started) { return System.nanoTime() - started > SOFT_NANOS; }
    private static int bytes(Object value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }

    private interface Reader { JsonObject read(int index) throws ReflectiveOperationException; }
    private static final class Model {
        ClassLoader loader;
        Object diagram, layout, state;
        List<?> interactables;
        int ticks, paddingBottom, customStateSize = -1;
        boolean standardCycle, composite, baseDrawConfirmed;
        String existingStateClass, globalStateClass;
    }
    private static final class Node {
        final String kind;
        final Object value;
        String schema, keyOwner, key, keyClass;
        List<?> items;
        Integer lineSpacing;
        Node(String kind, Object value) { this.kind = kind; this.value = value; }
    }
    private static final class Child {
        final String name;
        final Node node;
        Child(String name, Node node) { this.name = name; this.node = node; }
    }
    private static final class Fingerprint {
        long hash = 0xcbf29ce484222325L;
        int remaining = 128;
        void mix(Object value) {
            String text = value == null ? "<null>" : String.valueOf(value);
            hash = (hash ^ text.length()) * 0x100000001b3L;
            for (int i = 0; i < Math.min(4096, text.length()); i++) hash = (hash ^ text.charAt(i)) * 0x100000001b3L;
        }
    }
}
