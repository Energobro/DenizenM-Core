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
        public ObjectTag originalValue, originalKeyValue, originalIndexValue;

        public void reapplyAtEnd(ScriptQueue queue) {
            queue.addDefinition(valueName, originalValue);
            if (keys != null) {
                queue.addDefinition(keyName, originalKeyValue);
            }
            queue.addDefinition("loop_index", originalIndexValue);
        }
    }

    public static void autoExecute(ScriptEntry scriptEntry, ScriptQueue queue,
                                   @ArgRaw @ArgLinear @ArgName("object") @ArgDefaultNull ObjectTag object,
                                   @ArgPrefixed @ArgName("as") @ArgDefaultText("value") String asName,
                                   @ArgPrefixed @ArgName("key") @ArgDefaultText("key") String keyName) {
        boolean stop = scriptEntry.argAsBoolean("stop");
        boolean next = scriptEntry.argAsBoolean("next");
        boolean callback = scriptEntry.argAsBoolean("\0callback");
        if (stop) {
            if (scriptEntry.dbCallShouldDebug()) {
                Debug.report(scriptEntry, "FOREACH", db("instruction", "stop"));
            }
            boolean hasnext = false;
            for (int i = 0; i < queue.getQueueSize(); i++) {
                ScriptEntry entry = queue.getEntry(i);
                List<String> args = entry.getOriginalArguments();
                if (entry.getCommandName().equals("FOREACH") && args.size() == 1 && args.get(0).equals("\0CALLBACK")) {
                    hasnext = true;
                    break;
                }
            }
            if (hasnext) {
                while (queue.getQueueSize() > 0) {
                    ScriptEntry entry = queue.getEntry(0);
                    List<String> args = entry.getOriginalArguments();
                    if (entry.getCommandName().equals("FOREACH") && args.size() == 1 && args.get(0).equals("\0CALLBACK")) {
                        ((ForeachData) entry.getOwner().getData()).reapplyAtEnd(queue);
                        queue.removeFirst();
                        break;
                    }
                    queue.removeFirst();
                }
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
            boolean hasnext = false;
            for (int i = 0; i < queue.getQueueSize(); i++) {
                ScriptEntry entry = queue.getEntry(i);
                List<String> args = entry.getOriginalArguments();
                if (entry.getCommandName().equals("FOREACH") && args.size() == 1 && args.get(0).equals("\0CALLBACK")) {
                    hasnext = true;
                    break;
                }
            }
            if (hasnext) {
                while (queue.getQueueSize() > 0) {
                    ScriptEntry entry = queue.getEntry(0);
                    List<String> args = entry.getOriginalArguments();
                    if (entry.getCommandName().equals("FOREACH") && args.size() == 1 && args.get(0).equals("\0CALLBACK")) {
                        break;
                    }
                    queue.removeFirst();
                }
            }
            else {
                Debug.echoError(scriptEntry, "Cannot 'foreach next': not in one!");
            }
            return;
        }
        else if (callback) {
            if (scriptEntry.getOwner() != null && (scriptEntry.getOwner().getCommandName().equals("FOREACH") ||
                    scriptEntry.getOwner().getBracedSet() == null || scriptEntry.getOwner().getBracedSet().isEmpty() ||
                    scriptEntry.getBracedSet().get(0).value.get(scriptEntry.getBracedSet().get(0).value.size() - 1) != scriptEntry)) {
                ForeachData data = (ForeachData) scriptEntry.getOwner().getData();
                data.index++;
                if (data.index <= data.list.size()) {
                    if (scriptEntry.dbCallShouldDebug()) {
                        Debug.echoDebug(scriptEntry, Debug.DebugElement.Header, "Foreach loop " + data.index);
                    }
                    queue.addDefinition(ScriptQueue.LOOP_INDEX_KEY, new ElementTag(data.index));
                    if (data.keys != null) {
                        queue.addDefinition(data.keyHolder, new ElementTag(data.keys.get(data.index - 1)));
                    }
                    queue.addDefinition(data.valueHolder, data.list.getObject(data.index - 1));
                    List<ScriptEntry> bracedCommands = BracedCommand.getBracedCommandsDirect(scriptEntry.getOwner(), scriptEntry);
                    ScriptEntry callbackEntry = scriptEntry.clone();
                    callbackEntry.setOwner(scriptEntry.getOwner());
                    bracedCommands.add(callbackEntry);
                    for (ScriptEntry cmd : bracedCommands) {
                        cmd.setInstant(true);
                    }
                    queue.injectEntriesAtStart(bracedCommands);
                }
                else {
                    data.reapplyAtEnd(queue);
                    if (scriptEntry.dbCallShouldDebug()) {
                        Debug.echoDebug(scriptEntry, Debug.DebugElement.Header, "Foreach loop complete");
                    }
                }
            }
            else {
                Debug.echoError(scriptEntry, "Foreach CALLBACK invalid: not a real callback!");
            }
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
            ScriptEntry callbackEntry = getCallback(scriptEntry);
            List<ScriptEntry> bracedCommandsList = getBracedCommandsDirect(scriptEntry, scriptEntry);
            if (bracedCommandsList == null || bracedCommandsList.isEmpty()) {
                Debug.echoError(scriptEntry, "Empty subsection - did you forget a ':'?");
                return;
            }
            if (datum.keys != null) {
                datum.keyName = keyName;
                datum.keyHolder = new StringHolder(datum.keyName);
                datum.originalKeyValue = queue.getDefinitionObject(datum.keyName);
                queue.addDefinition(datum.keyName, datum.keys.get(0));
            }
            datum.valueName = asName;
            datum.valueHolder = new StringHolder(datum.valueName);
            datum.originalValue = queue.getDefinitionObject(datum.valueName);
            datum.originalIndexValue = queue.getDefinitionObject("loop_index");
            queue.addDefinition(datum.valueName, datum.list.getObject(0));
            queue.addDefinition("loop_index", new ElementTag("1"));
            callbackEntry.copyFrom(scriptEntry);
            callbackEntry.setOwner(scriptEntry);
            bracedCommandsList.add(callbackEntry);
            for (ScriptEntry cmd : bracedCommandsList) {
                cmd.setInstant(true);
            }
            scriptEntry.setInstant(true);
            queue.injectEntriesAtStart(bracedCommandsList);
        }
    }

    /**
     * The end-of-loop marker entry, built once per parsed foreach line and cloned per loop.
     * <p>
     * Building it was a full {@link ScriptEntry} construction - argument crunching, tag parsing, the braced set - repeated
     * on every entry into the loop. {@link RepeatCommand#getCallback} already does it this way.
     */
    public static ScriptEntry getCallback(ScriptEntry forEntry) {
        if (forEntry.internal.specialProcessedData == null) {
            forEntry.internal.specialProcessedData = new ScriptEntry("FOREACH", new String[]{"\0CALLBACK"},
                    forEntry.getScript() != null ? forEntry.getScript().getContainer() : null);
        }
        return ((ScriptEntry) forEntry.internal.specialProcessedData).clone();
    }
}
