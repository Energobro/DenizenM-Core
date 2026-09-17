package com.denizenscript.denizencore.scripts.commands.queue;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.utilities.DefinitionSlots;
import com.denizenscript.denizencore.utilities.LoopValue;
import com.denizenscript.denizencore.utilities.CoreConfiguration;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.BracedCommand;

import java.util.ArrayList;
import java.util.List;

public class WhileCommand extends BracedCommand {

    public WhileCommand() {
        setName("while");
        setSyntax("while [stop/next/[<value>] (!)(<operator> <value>) (&&/|| ...)] [<commands>]");
        setRequiredArguments(1, -1);
        setParseArgs(false);
        isProcedural = true;
        asyncSafe = true; // Only touches its own queue and thread-safe data.
    }

    // <--[command]
    // @Name While
    // @Syntax while [stop/next/[<value>] (!)(<operator> <value>) (&&/|| ...)] [<commands>]
    // @Required 1
    // @Maximum -1
    // @Short Runs a series of braced commands until the tag returns false.
    // @Group queue
    // @Guide https://guide.denizenscript.com/guides/basics/loops.html
    //
    // @Description
    // Runs a series of braced commands until the if comparisons returns false. Refer to <@link command if> for if command syntax information.
    // To end a while loop, use the 'stop' argument.
    // To jump to the next entry in the loop, use the 'next' argument.
    //
    // @Tags
    // <[loop_index]> to get the number of loops so far.
    //
    // @Usage
    // Use to loop until a player sneaks, or the player goes offline. (Note: generally use 'waituntil' for this instead)
    // - while !<player.is_sneaking> && <player.is_online>:
    //     - narrate "Waiting for you to sneak..."
    //     - wait 1s
    //
    // -->

    private static class WhileData {
        public int index;
        public List<String> value;
        public long LastChecked;
        public int instaTicks;
        public ObjectTag originalIndexValue;
        public DefinitionSlots slotTable;
        public LoopValue.Counter indexCounter;
        public int indexSlot = DefinitionSlots.NO_SLOT;

        public void reapplyAtEnd(ScriptQueue queue) {
            queue.addDefinition(ScriptQueue.LOOP_INDEX_KEY, originalIndexValue);
        }
    }

    /**
     * The parts of a while line that are the same on every run: which form it is, its comparison arguments, and the compiled condition.
     * <p>
     * parseArgs runs before every execution, so all of this was being rebuilt for every turn of every loop, back when a turn was a
     * callback entry going through the command dispatcher, even though it is a pure function of the line as written.
     */
    public static class ParsedWhile {

        public boolean stop, next, callback;

        public List<String> comparisons;

        /** Held here rather than through IfCommand.conditionFor: that stores a bare Condition in the same slot this holder occupies. */
        public IfCommand.Condition condition;

    }

    public static final ScriptQueue.LoopIteration ITERATION = (queue, owner) -> {
        WhileData data = (WhileData) owner.getData();
        data.index++;
        if (CoreUtilities.monotonicMillis() - data.LastChecked < 50) {
            data.instaTicks++;
            if (data.instaTicks > CoreConfiguration.whileMaxLoops && CoreConfiguration.whileMaxLoops != 0) {
                return false;
            }
        }
        else {
            data.instaTicks = 0;
        }
        data.LastChecked = CoreUtilities.monotonicMillis();
        if (!parsedFor(owner).condition.evaluate(owner)) {
            data.reapplyAtEnd(queue);
            if (owner.dbCallShouldDebug()) {
                Debug.echoDebug(owner, Debug.DebugElement.Header, "While loop complete");
            }
            return false;
        }
        if (owner.dbCallShouldDebug()) {
            Debug.echoDebug(owner, Debug.DebugElement.Header, "While loop " + data.index);
        }
        data.indexCounter.value = data.index;
        if (!data.indexCounter.installed) {
            queue.addDefinitionSlot(data.slotTable, data.indexSlot, ScriptQueue.LOOP_INDEX_KEY, data.indexCounter);
            data.indexCounter.installed = true;
        }
        return true;
    };

    public static ParsedWhile parsedFor(ScriptEntry entry) {
        if (entry.internal.specialProcessedData instanceof ParsedWhile) {
            return (ParsedWhile) entry.internal.specialProcessedData;
        }
        ParsedWhile parsed = new ParsedWhile();
        List<String> original = entry.getOriginalArguments();
        if (original.size() == 1) {
            String arg = original.get(0);
            parsed.stop = CoreUtilities.equalsIgnoreCase(arg, "stop");
            parsed.next = CoreUtilities.equalsIgnoreCase(arg, "next");
            parsed.callback = arg.equals("\0CALLBACK");
        }
        parsed.comparisons = new ArrayList<>();
        for (String arg : original) {
            if (arg.equals("{")) {
                break;
            }
            parsed.comparisons.add(arg);
        }
        parsed.condition = parsed.comparisons.isEmpty() ? null : IfCommand.ArgComparer.compile(parsed.comparisons);
        entry.internal.specialProcessedData = parsed;
        return parsed;
    }

    @Override
    public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {
        ParsedWhile parsed = parsedFor(scriptEntry);
        if (parsed.comparisons.isEmpty() && !parsed.stop && !parsed.next && !parsed.callback) {
            throw new InvalidArgumentsException("Must specify a comparison value or 'stop' or 'next'!");
        }
    }

    @Override
    public void execute(ScriptEntry scriptEntry) {
        ParsedWhile parsed = parsedFor(scriptEntry);
        ScriptQueue queue = scriptEntry.getResidingQueue();
        if (parsed.stop) {
            if (scriptEntry.dbCallShouldDebug()) {
                Debug.report(scriptEntry, getName(), db("stop", true));
            }
            ScriptQueue.LoopFrame frame = queue.findLoopFrame("WHILE");
            if (frame != null) {
                ((WhileData) frame.owner.getData()).reapplyAtEnd(queue);
                queue.endLoopFrame(frame);
            }
            else {
                Debug.echoError(scriptEntry, "Cannot stop while: not in one!");
            }
            return;
        }
        else if (parsed.next) {
            if (scriptEntry.dbCallShouldDebug()) {
                Debug.report(scriptEntry, getName(), db("next", true));
            }
            ScriptQueue.LoopFrame frame = queue.findLoopFrame("WHILE");
            if (frame != null) {
                queue.skipToLoopFrameEnd(frame);
            }
            else {
                Debug.echoError(scriptEntry, "Cannot 'while next': not in one!");
            }
            return;
        }
        else if (parsed.callback) {
            Debug.echoError(scriptEntry, "While CALLBACK invalid: loops no longer run through callback entries.");
        }
        else {
            List<String> comparisons = parsed.comparisons;
            boolean run = parsed.condition.evaluate(scriptEntry);
            if (scriptEntry.dbCallShouldDebug()) {
                Debug.report(scriptEntry, getName(), db("run_first_loop", run));
            }
            if (!run) {
                return;
            }
            WhileData datum = new WhileData();
            datum.index = 1;
            datum.value = comparisons;
            datum.LastChecked = CoreUtilities.monotonicMillis();
            datum.instaTicks = 1;
            scriptEntry.setData(datum);
            List<ScriptEntry> bracedCommandsList = scriptEntry.inlinedBody;
            if (bracedCommandsList == null) {
                bracedCommandsList = getBracedCommandsDirect(scriptEntry, scriptEntry);
                if (bracedCommandsList == null || bracedCommandsList.isEmpty()) {
                    Debug.echoError(scriptEntry, "Empty subsection - did you forget a ':'?");
                    return;
                }
                for (int i = 0; i < bracedCommandsList.size(); i++) {
                    bracedCommandsList.get(i).setInstant(true);
                }
                scriptEntry.inlinedBody = bracedCommandsList;
            }
            else {
                ScriptEntry.resetBodyForReuse(bracedCommandsList);
            }
            datum.originalIndexValue = queue.getDefinitionObject("loop_index");
            datum.slotTable = scriptEntry.internal.slotTable;
            datum.indexSlot = ScriptQueue.slotFor(scriptEntry, ScriptQueue.LOOP_INDEX_KEY);
            scriptEntry.setInstant(true);
            queue.pushLoopFrame(scriptEntry, bracedCommandsList, ITERATION);
            datum.indexCounter = new LoopValue.Counter(1);
            datum.indexCounter.installed = queue.installLoopHolder(datum.slotTable, datum.indexSlot, ScriptQueue.LOOP_INDEX_KEY, datum.indexCounter);
        }
    }
}
