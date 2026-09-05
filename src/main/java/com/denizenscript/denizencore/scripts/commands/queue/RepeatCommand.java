package com.denizenscript.denizencore.scripts.commands.queue;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsRuntimeException;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.scripts.commands.generator.ArgDefaultText;
import com.denizenscript.denizencore.scripts.commands.generator.ArgLinear;
import com.denizenscript.denizencore.scripts.commands.generator.ArgName;
import com.denizenscript.denizencore.scripts.commands.generator.ArgPrefixed;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.utilities.EnumHelper;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.utilities.text.StringHolder;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.BracedCommand;

import java.util.List;

public class RepeatCommand extends BracedCommand {

    public RepeatCommand() {
        setName("repeat");
        setSyntax("repeat [stop/next/<amount>] (from:<#>) (as:<name>) [<commands>]");
        setRequiredArguments(1, 3);
        isProcedural = true;
        generateDebug = false;
        autoCompile();
        asyncSafe = true; // Only touches its own queue and thread-safe data.
    }

    // <--[command]
    // @Name Repeat
    // @Syntax repeat [stop/next/<amount>] (from:<#>) (as:<name>) [<commands>]
    // @Required 1
    // @Maximum 3
    // @Short Runs a series of braced commands several times.
    // @Synonyms For
    // @Group queue
    // @Guide https://guide.denizenscript.com/guides/basics/loops.html
    //
    // @Description
    // Loops through a series of braced commands a specified number of times.
    // To get the number of loops so far, you can use <[value]>.
    //
    // Optionally, specify "as:<name>" to change the definition name to something other than "value".
    //
    // Optionally, to specify a starting index, use "from:<#>". Note that the "amount" input is how many loops will happen, not an end index.
    // The default "from" index is "1". Note that the value you give to "from" will be the value of the first loop.
    //
    // To stop a repeat loop, do - repeat stop
    //
    // To jump immediately to the next number in the loop, do - repeat next
    //
    // @Tags
    // <[value]> to get the number of loops so far
    //
    // @Usage
    // Use to loop through a command five times.
    // - repeat 5:
    //     - announce "Announce Number <[value]>"
    //
    // @Usage
    // Use to announce the numbers: 1, 2, 3, 4, 5.
    // - repeat 5 as:number:
    //     - announce "I can count! <[number]>"
    //
    // @Usage
    // Use to announce the numbers: 21, 22, 23, 24, 25.
    // - repeat 5 from:21:
    //     - announce "Announce Number <[value]>"
    // -->

    private static class RepeatData {
        public int index;
        public int target;
        public String valueName;

        public StringHolder valueHolder;
        public ObjectTag originalValue;

        public void reapplyAtEnd(ScriptQueue queue) {
            queue.addDefinition(valueName, originalValue);
        }
    }

    public enum Action { RUN, STOP, NEXT, CALLBACK }

    static {
        EnumHelper<Action> enumHack = EnumHelper.get(Action.class);
        enumHack.valuesMapLower.remove("callback");
        enumHack.valuesMapLower.put("\0callback", Action.CALLBACK);
    }

    public static void autoExecute(ScriptEntry scriptEntry, ScriptQueue queue,
                                   @ArgLinear @ArgName("quantity") @ArgDefaultText("-1") int quantity,
                                   @ArgName("action") @ArgDefaultText("run") Action action,
                                   @ArgPrefixed @ArgName("from") @ArgDefaultText("1") int from,
                                   @ArgPrefixed @ArgName("as") @ArgDefaultText("value") String asName) {
        if (action == Action.STOP) {
            if (scriptEntry.dbCallShouldDebug()) {
                Debug.report(scriptEntry, "repeat", db("instruction", "stop"));
            }
            ScriptQueue.LoopFrame frame = queue.findLoopFrame("REPEAT");
            if (frame != null) {
                ((RepeatData) frame.owner.getData()).reapplyAtEnd(queue);
                queue.endLoopFrame(frame);
            }
            else {
                Debug.echoError("Cannot stop repeat: not in one!");
            }
            return;
        }
        else if (action == Action.NEXT) {
            if (scriptEntry.dbCallShouldDebug()) {
                Debug.report(scriptEntry, "repeat", db("instruction", "next"));
            }
            ScriptQueue.LoopFrame frame = queue.findLoopFrame("REPEAT");
            if (frame != null) {
                queue.skipToLoopFrameEnd(frame);
            }
            else {
                Debug.echoError("Cannot 'repeat next': not in one!");
            }
            return;
        }
        else if (action == Action.CALLBACK) {
            Debug.echoError(scriptEntry, "Repeat CALLBACK invalid: loops no longer run through callback entries.");
        }
        else {
            if (quantity == -1) {
                throw new InvalidArgumentsRuntimeException("Must specify a quantity or 'stop' or 'next'!");
            }
            if (scriptEntry.dbCallShouldDebug()) {
                Debug.report(scriptEntry, "repeat", db("from", from), db("times", quantity), db("as_name", asName));
            }
            if (quantity <= 0) {
                if (scriptEntry.dbCallShouldDebug()) {
                    Debug.echoDebug(scriptEntry, "Zero count, not looping...");
                }
                return;
            }
            RepeatData datum = new RepeatData();
            datum.index = from;
            datum.target = datum.index + quantity - 1;
            datum.valueName = asName;
            datum.valueHolder = new StringHolder(asName);
            scriptEntry.setData(datum);
            List<ScriptEntry> bracedCommandsList = scriptEntry.inlinedBody;
            if (bracedCommandsList == null) {
                bracedCommandsList = getBracedCommandsDirect(scriptEntry, scriptEntry);
                if (bracedCommandsList == null || bracedCommandsList.isEmpty()) {
                    Debug.echoError(scriptEntry, "Empty subsection - did you forget a ':'?");
                    return;
                }
                for (ScriptEntry cmd : bracedCommandsList) {
                    cmd.setInstant(true);
                }
                scriptEntry.inlinedBody = bracedCommandsList;
            }
            else {
                ScriptEntry.resetBodyForReuse(bracedCommandsList);
            }
            datum.originalValue = queue.getDefinitionObject(datum.valueName);
            scriptEntry.setInstant(true);
            queue.pushLoopFrame(scriptEntry, bracedCommandsList, ITERATION);
            queue.addDefinition(datum.valueHolder, new ElementTag(String.valueOf(datum.index)));
        }
    }

    public static final ScriptQueue.LoopIteration ITERATION = (queue, owner) -> {
        RepeatData data = (RepeatData) owner.getData();
        data.index++;
        if (data.index > data.target) {
            data.reapplyAtEnd(queue);
            if (owner.dbCallShouldDebug()) {
                Debug.echoDebug(owner, Debug.DebugElement.Header, "Repeat loop complete");
            }
            return false;
        }
        if (owner.dbCallShouldDebug()) {
            Debug.echoDebug(owner, Debug.DebugElement.Header, "Repeat loop " + data.index);
        }
        queue.addDefinition(data.valueHolder, new ElementTag(String.valueOf(data.index)));
        return true;
    };
}
