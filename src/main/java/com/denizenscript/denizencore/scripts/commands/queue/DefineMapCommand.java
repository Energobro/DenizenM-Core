package com.denizenscript.denizencore.scripts.commands.queue;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.objects.core.QueueTag;
import com.denizenscript.denizencore.scripts.ScriptBuilder;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.scripts.commands.Holdable;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.tags.ParseableTag;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.tags.TagManager;
import com.denizenscript.denizencore.utilities.DefinitionSlots;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.utilities.text.StringHolder;

import java.util.List;
import java.util.Map;

public class DefineMapCommand extends AbstractCommand implements Holdable {

    public DefineMapCommand() {
        setName("definemap");
        setSyntax("definemap [<name>] [<key>:<value> ...]");
        setRequiredArguments(1, -1);
        isProcedural = true;
        allowedDynamicPrefixes = true;
        anyPrefixSymbolAllowed = true;
        setAsyncWaitable(true);
    }

    // <--[command]
    // @Name DefineMap
    // @Syntax definemap [<name>] [<key>:<value> ...]
    // @Required 1
    // @Maximum -1
    // @Short Creates a MapTag definition with key/value pairs constructed from the input arguments.
    // @Group queue
    // @Guide https://guide.denizenscript.com/guides/basics/definitions.html
    //
    // @Description
    // Creates a MapTag definition with key/value pairs constructed from the input arguments.
    //
    // Like <@link command define>, this command is ~waitable, which runs the command (including parsing the tags in its values) on a separate thread.
    // Refer to <@link language ~waitable>, and to the notes in the 'define' command's description about when that's worth doing.
    //
    // @Tags
    // <[<id>]> to get the value assigned to an ID
    //
    // @Usage
    // Use to make a MapTag definition with three inputs.
    // - definemap my_map count:5 type:Taco smell:Tasty
    //
    // @Usage
    // Use to make a MapTag definition with complex input.
    // - definemap my_map:
    //     count: 5
    //     some_list:
    //     - a
    //     - b
    //     some_submap:
    //         some_subkey: taco
    //
    // -->

    public static boolean isFixedText(Object yamlSubcontent) {
        if (yamlSubcontent instanceof Map) {
            for (Object subObj : ((Map<?, ?>) yamlSubcontent).values()) {
                if (!isFixedText(subObj)) {
                    return false;
                }
            }
            return true;
        }
        if (yamlSubcontent instanceof Iterable) {
            for (Object subObj : (Iterable<?>) yamlSubcontent) {
                if (!isFixedText(subObj)) {
                    return false;
                }
            }
            return true;
        }
        return yamlSubcontent == null || !CoreUtilities.contains(yamlSubcontent.toString(), '<');
    }

    public static final Object REBUILT_EVERY_RUN = new Object();

    public static class CachedMap {
        public StringHolder key;
        public ElementTag definition;
        public MapTag template;
        public int slot = DefinitionSlots.NO_SLOT;
        public int resolvedAtSize = -1;
        public CompiledValue compiled;
    }

    public static final int COMPILED_MAP = 0, COMPILED_LIST = 1, COMPILED_TAG = 2, COMPILED_NULL = 3;

    public static class CompiledValue {
        public int kind;
        public StringHolder[] keys;
        public CompiledValue[] children;
        public ParseableTag tag;

        public ObjectTag build(TagContext context) {
            switch (kind) {
                case COMPILED_MAP -> {
                    MapTag result = new MapTag(keys.length);
                    for (int i = 0; i < keys.length; i++) {
                        result.putObject(keys[i], children[i].build(context));
                    }
                    return result;
                }
                case COMPILED_LIST -> {
                    ListTag result = new ListTag(children.length);
                    for (CompiledValue child : children) {
                        result.addObject(child.build(context));
                    }
                    return result;
                }
                case COMPILED_TAG -> {
                    return tag.parse(context).duplicate();
                }
                default -> {
                    return new ElementTag("null");
                }
            }
        }
    }

    public static CompiledValue compileValue(Object obj, TagContext context) {
        CompiledValue result = new CompiledValue();
        if (obj == null) {
            result.kind = COMPILED_NULL;
            return result;
        }
        if (obj instanceof Map<?, ?> map) {
            result.kind = COMPILED_MAP;
            result.keys = new StringHolder[map.size()];
            result.children = new CompiledValue[map.size()];
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.keys[i] = new StringHolder(ScriptBuilder.stripLinePrefix(String.valueOf(entry.getKey())));
                result.children[i] = compileValue(entry.getValue(), context);
                if (result.children[i] == null) {
                    return null;
                }
                i++;
            }
            return result;
        }
        if (obj instanceof List<?> list) {
            result.kind = COMPILED_LIST;
            result.children = new CompiledValue[list.size()];
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) == obj) {
                    return null;
                }
                result.children[i] = compileValue(list.get(i), context);
                if (result.children[i] == null) {
                    return null;
                }
            }
            return result;
        }
        if (!(obj instanceof String)) {
            return null;
        }
        result.kind = COMPILED_TAG;
        result.tag = TagManager.parseTextToTag(ScriptBuilder.stripLinePrefix((String) obj), context);
        return result;
    }

    public static CompiledValue compileLine(ScriptEntry scriptEntry) {
        ScriptEntry.InternalArgument[] args = scriptEntry.internal.arguments_to_use;
        if (args.length != 1 || args[0].prefix != null || isTagged(args[0]) || !(scriptEntry.internal.yamlSubcontent instanceof Map)) {
            return null;
        }
        return compileValue(scriptEntry.internal.yamlSubcontent, scriptEntry.getContext());
    }

    @Override
    public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {
        if (scriptEntry.internal.specialProcessedData instanceof CachedMap) {
            return;
        }
        MapTag value = new MapTag();
        boolean anyUnhandled = false;
        for (Argument arg : scriptEntry) {
            if (!scriptEntry.hasObject("definition")
                    && !arg.hasPrefix()) {
                // Argument already lowercased this when it was filled - see Argument.fillStrNoColon and requireValue.
                arg.requireValue();
                scriptEntry.addObject("definition", new ElementTag(arg.lower_value));
            }
            else if (arg.hasPrefix()) {
                value.putObject(arg.getPrefix().getRawValue(), arg.object);
            }
            else if (!arg.hasPrefix() && arg.getRawValue().contains(":")) {
                int colon = arg.getRawValue().indexOf(':');
                value.putObject(arg.getRawValue().substring(0, colon), new ElementTag(arg.getRawValue().substring(colon + 1)));
            }
            else {
                anyUnhandled = true;
                arg.reportUnhandled();
            }
        }
        if (scriptEntry.internal.yamlSubcontent instanceof Map) {
            MapTag map = (MapTag) CoreUtilities.objectToTagForm(scriptEntry.internal.yamlSubcontent, scriptEntry.getContext(), true, true);
            value.putAll(map);
        }
        scriptEntry.addObject("map", value);
        if (!scriptEntry.hasObject("definition")) {
            throw new InvalidArgumentsException("Must specify a definition and value!");
        }
        if (scriptEntry.internal.specialProcessedData == REBUILT_EVERY_RUN) {
            return;
        }
        CompiledValue compiled = null;
        if (anyUnhandled || !isStaticLine(scriptEntry)) {
            compiled = anyUnhandled ? null : compileLine(scriptEntry);
            if (compiled == null) {
                scriptEntry.internal.specialProcessedData = REBUILT_EVERY_RUN;
                scriptEntry.sharedInternal().specialProcessedData = REBUILT_EVERY_RUN;
                return;
            }
        }
        CachedMap cached = new CachedMap();
        cached.definition = scriptEntry.getElement("definition");
        cached.key = StringHolder.ofLowered(CoreUtilities.toLowerCase(cached.definition.asString()));
        cached.template = value;
        cached.compiled = compiled;
        scriptEntry.internal.specialProcessedData = cached;
        scriptEntry.sharedInternal().specialProcessedData = cached;
    }

    public static boolean isStaticLine(ScriptEntry scriptEntry) {
        for (ScriptEntry.InternalArgument internalArg : scriptEntry.internal.arguments_to_use) {
            if (isTagged(internalArg) || (internalArg.prefix != null && isTagged(internalArg.prefix))) {
                return false;
            }
        }
        return !(scriptEntry.internal.yamlSubcontent instanceof Map) || isFixedText(scriptEntry.internal.yamlSubcontent);
    }

    public static boolean isTagged(ScriptEntry.InternalArgument internalArg) {
        return internalArg.value == null || internalArg.value.hasTag;
    }

    @Override
    public void execute(ScriptEntry scriptEntry) {
        CachedMap cached = scriptEntry.internal.specialProcessedData instanceof CachedMap ? (CachedMap) scriptEntry.internal.specialProcessedData : null;
        ElementTag definition = cached != null ? cached.definition : scriptEntry.getElement("definition");
        MapTag value = cached != null ? cached.template : scriptEntry.getObjectTag("map");
        MapTag built = null;
        if (cached != null && cached.compiled != null) {
            MapTag parsedNow = scriptEntry.getObjectTag("map");
            built = parsedNow != null ? parsedNow.duplicate() : (MapTag) cached.compiled.build(scriptEntry.getContext());
            if (scriptEntry.debugThisLine) {
                built.setPrefix("map");
            }
            value = built;
        }
        if (scriptEntry.debugThisLine) {
            Debug.report(scriptEntry, getName(), new QueueTag(scriptEntry.getResidingQueue()), definition, value);
        }
        if (cached == null) {
            scriptEntry.getResidingQueue().addDefinition(StringHolder.ofLowered(CoreUtilities.toLowerCase(definition.asString())), value.duplicate());
            return;
        }
        if (cached.slot == DefinitionSlots.NO_SLOT && cached.resolvedAtSize != ScriptQueue.tableSizeFor(scriptEntry)) {
            cached.resolvedAtSize = ScriptQueue.tableSizeFor(scriptEntry);
            cached.slot = ScriptQueue.slotFor(scriptEntry, cached.key);
        }
        scriptEntry.getResidingQueue().addDefinitionSlot(scriptEntry.internal.slotTable, cached.slot, cached.key, built != null ? built : value.duplicate());
    }
}
