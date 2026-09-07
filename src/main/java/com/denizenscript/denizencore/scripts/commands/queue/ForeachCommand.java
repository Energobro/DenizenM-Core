package com.denizenscript.denizencore.scripts.commands.queue;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsRuntimeException;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.scripts.commands.generator.ArgDefaultNull;
import com.denizenscript.denizencore.scripts.commands.generator.ArgDefaultText;
import com.denizenscript.denizencore.scripts.commands.generator.ArgLinear;
import com.denizenscript.denizencore.scripts.commands.generator.ArgName;
import com.denizenscript.denizencore.scripts.commands.generator.ArgPrefixed;
import com.denizenscript.denizencore.scripts.commands.generator.ArgRaw;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.utilities.DefinitionSlots;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.BracedCommand;
import com.denizenscript.denizencore.utilities.text.StringHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ForeachCommand extends BracedCommand {

    public ForeachCommand() {
        setName("foreach");
        setSyntax("foreach [stop/next/<object>|...] (as:<name>) (key:<name>) [<commands>]");
        setRequiredArguments(1, 3);
        isProcedural = true;
        setBooleansHandled("stop", "next", "\0callback");
        generateDebug = false;
        autoCompile();
        asyncSafe = true; // Only touches its own queue and thread-safe data.
    }

    // <--[command]
    // @Name Foreach
    // @Syntax foreach [stop/next/<object>|...] (as:<name>) (key:<name>) [<commands>]
    // @Required 1
    // @Maximum 3
    // @Short Loops through a ListTag, running a set of commands for each item.
    // @Group queue
    // @Guide https://guide.denizenscript.com/guides/basics/loops.html
    //
    // @Description
    // Loops through a ListTag of any type. For each item in the ListTag, the specified commands will be ran for that list entry.
    //
    // Alternately, specify a map tag to loop over the set of key/value pairs in the map, where the key will be <[key]> and the value will be <[value]>.
    // Specify "key:<name>" to set the key definition name (if unset, will be "key").
    //
    // Specify "as:<name>" to set the value definition name (if unset, will be "value").
    // Use "as:__player" to change the queue's player link, or "as:__npc" to change the queue's NPC link.
    // Note that a changed player/NPC link persists after the end of the loop.
    //
    // To end a foreach loop, do - foreach stop
    //
    // To jump immediately to the next entry in the loop, do - foreach next
    //
    // Note that many commands and tags in Denizen support inputting a list directly, making foreach redundant for many simpler cases.
    //
    // Note that if you delay the queue (such as with <@link command wait> or <@link language ~waitable>) inside a foreach loop,
    // the loop can't process the next entry until the delay is over.
    // This can lead to very long waits if you have a long list and a wait directly in the loop, as the total delay is effectively multiplied by the number of iterations.
    // Use <@link command run> if you want to run logic simultaneously for many entries in a list in a way that allows them to separately wait without delaying each other.
    //
    // @Tags
    // <[value]> to get the current item in the loop
    // <[loop_index]> to get the current loop iteration number
    //
    // @Usage
    // Use to run commands 'for each entry' in a manually created list of objects/elements.
    // - foreach <[some_entity]>|<[some_npc]>|<[player]> as:entity:
    //     - announce "There's something at <[entity].location>!"
    //
    // @Usage
    // Use to iterate through entries in any tag that returns a list.
    // - foreach <player.location.find_entities[zombie].within[50]> as:zombie:
    //     - narrate "There's a zombie <[zombie].location.distance[<player.location>].round> blocks away"
    //
    // @Usage
    // Use to iterate through a list of players and run commands automatically linked to each player in that list.
    // - foreach <server.online_players> as:__player:
    //     - narrate "Thanks for coming to our server, <player.name>! Here's a bonus $50.00!"
    //     - money give quantity:50
    //
    // -->

    private static class ForeachData {
        public int index;
        public ListTag list;
        public List<String> keys;
        public String valueName, keyName;

        public StringHolder valueHolder, keyHolder;
        public DefinitionSlots slotTable;
        public int valueSlot = DefinitionSlots.NO_SLOT, keySlot = DefinitionSlots.NO_SLOT, indexSlot = DefinitionSlots.NO_SLOT;
        public ObjectTag originalValue, originalKeyValue, originalIndexValue;

        public void reapplyAtEnd(ScriptQueue queue) {
            queue.addDefinition(valueHolder, originalValue);
            if (keys != null) {
                queue.addDefinition(keyHolder, originalKeyValue);
            }
            queue.addDefinition(ScriptQueue.LOOP_INDEX_KEY, originalIndexValue);
        }
    }

    public static void autoExecute(ScriptEntry scriptEntry, ScriptQueue queue,
                                   @ArgRaw @ArgLinear @ArgName("object") @ArgDefaultNull ObjectTag object,
                                   @ArgPrefixed @ArgName("as") @ArgDefaultText("value") String asName,
                                   @ArgPrefixed @ArgName("key") @ArgDefaultText("key") String keyName) {
        boolean stop = scriptEntry.argAsBoolean("stop");
        boolean next = scriptEntry.argAsBoolean("next");
        if (stop) {
            if (scriptEntry.dbCallShouldDebug()) {
                Debug.report(scriptEntry, "FOREACH", db("instruction", "stop"));
            }
            ScriptQueue.LoopFrame frame = queue.findLoopFrame("FOREACH");
            if (frame != null) {
                ((ForeachData) frame.owner.getData()).reapplyAtEnd(queue);
                queue.endLoopFrame(frame);
            }
            else {
                Debug.echoError(scriptEntry, "Cannot stop foreach: not in one!");
            }
            return;
        }
        else if (next) {
            if (scriptEntry.dbCallShouldDebug()) {
                Debug.report(scriptEntry, "FOREACH", db("instruction", "next"));
            }
            ScriptQueue.LoopFrame frame = queue.findLoopFrame("FOREACH");
            if (frame != null) {
                queue.skipToLoopFrameEnd(frame);
            }
            else {
                Debug.echoError(scriptEntry, "Cannot 'foreach next': not in one!");
            }
            return;
        }
        else {
            if (object == null) {
                throw new InvalidArgumentsRuntimeException("Must specify a quantity or 'stop' or 'next'!");
            }
            ListTag list = null;
            MapTag map = null;
            if (object instanceof MapTag || (!(object instanceof ListTag) && object.toString().startsWith("map@"))) {
                map = MapTag.getMapFor(object, scriptEntry.context);
                if (map == null) {
                    throw new InvalidArgumentsRuntimeException("Invalid MapTag specified!");
                }
            }
            else {
                list = object instanceof ListTag ? (ListTag) object : ListTag.valueOf(object.toString(), scriptEntry.getContext());
            }
            if (scriptEntry.dbCallShouldDebug()) {
                if (map != null) {
                    map.setPrefix("map");
                }
                if (list != null) {
                    list.setPrefix("list");
                }
                Debug.report(scriptEntry, "FOREACH", map, map == null ? null : db("key", keyName), list, db("as", asName));
            }
            int target = list == null ? map.size() : list.size();
            if (target <= 0) {
                if (scriptEntry.dbCallShouldDebug()) {
                    Debug.echoDebug(scriptEntry, "Empty list, not looping...");
                }
                return;
            }
            ForeachData datum = new ForeachData();
            if (list == null) {
                datum.keys = new ArrayList<>(map.size());
                datum.list = new ListTag(map.size());
                for (Map.Entry<StringHolder, ObjectTag> entry : map.entrySet()) {
                    datum.keys.add(entry.getKey().str);
                    datum.list.addObject(entry.getValue());
                }
            }
            else {
                datum.keys = null;
                datum.list = list;
            }
            datum.index = 1;
            scriptEntry.setData(datum);
            List<ScriptEntry> bracedCommandsList = scriptEntry.inlinedBody;
            boolean freshBody = bracedCommandsList == null;
            if (freshBody) {
                bracedCommandsList = getBracedCommandsDirect(scriptEntry, scriptEntry);
                if (bracedCommandsList == null || bracedCommandsList.isEmpty()) {
                    Debug.echoError(scriptEntry, "Empty subsection - did you forget a ':'?");
                    return;
                }
            }
            else {
                ScriptEntry.resetBodyForReuse(bracedCommandsList);
            }
            if (datum.keys != null) {
                datum.keyName = keyName;
                datum.keyHolder = new StringHolder(datum.keyName);
                datum.originalKeyValue = queue.getDefinitionObject(datum.keyName);
            }
            datum.valueName = asName;
            datum.valueHolder = new StringHolder(datum.valueName);
            datum.originalValue = queue.getDefinitionObject(datum.valueName);
            datum.originalIndexValue = queue.getDefinitionObject("loop_index");
            datum.slotTable = scriptEntry.internal.slotTable;
            datum.valueSlot = ScriptQueue.slotFor(scriptEntry, datum.valueHolder);
            datum.keySlot = datum.keyHolder == null ? DefinitionSlots.NO_SLOT : ScriptQueue.slotFor(scriptEntry, datum.keyHolder);
            datum.indexSlot = ScriptQueue.slotFor(scriptEntry, ScriptQueue.LOOP_INDEX_KEY);
            if (freshBody) {
                for (ScriptEntry cmd : bracedCommandsList) {
                    cmd.setInstant(true);
                }
                scriptEntry.inlinedBody = bracedCommandsList;
            }
            scriptEntry.setInstant(true);
            queue.pushLoopFrame(scriptEntry, bracedCommandsList, ITERATION);
            if (datum.keys != null) {
                queue.addDefinitionSlot(datum.slotTable, datum.keySlot, datum.keyHolder, new ElementTag(datum.keys.get(0)));
            }
            queue.addDefinitionSlot(datum.slotTable, datum.valueSlot, datum.valueHolder, datum.list.getObject(0));
            queue.addDefinitionSlot(datum.slotTable, datum.indexSlot, ScriptQueue.LOOP_INDEX_KEY, new ElementTag(1));
        }
    }

    public static final ScriptQueue.LoopIteration ITERATION = (queue, owner) -> {
        ForeachData data = (ForeachData) owner.getData();
        data.index++;
        if (data.index > data.list.size()) {
            data.reapplyAtEnd(queue);
            if (owner.dbCallShouldDebug()) {
                Debug.echoDebug(owner, Debug.DebugElement.Header, "Foreach loop complete");
            }
            return false;
        }
        if (owner.dbCallShouldDebug()) {
            Debug.echoDebug(owner, Debug.DebugElement.Header, "Foreach loop " + data.index);
        }
        queue.addDefinitionSlot(data.slotTable, data.indexSlot, ScriptQueue.LOOP_INDEX_KEY, new ElementTag(data.index));
        if (data.keys != null) {
            queue.addDefinitionSlot(data.slotTable, data.keySlot, data.keyHolder, new ElementTag(data.keys.get(data.index - 1)));
        }
        queue.addDefinitionSlot(data.slotTable, data.valueSlot, data.valueHolder, data.list.getObject(data.index - 1));
        return true;
    };
}
