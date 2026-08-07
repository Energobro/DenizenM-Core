package com.denizenscript.denizencore.scripts.commands.queue;

import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.objects.core.QueueTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.BracedCommand;
import com.denizenscript.denizencore.scripts.commands.generator.ArgDefaultNull;
import com.denizenscript.denizencore.scripts.commands.generator.ArgName;
import com.denizenscript.denizencore.scripts.commands.generator.ArgPrefixed;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.scripts.queues.core.AsyncQueue;
import com.denizenscript.denizencore.scripts.queues.core.TimedQueue;
import com.denizenscript.denizencore.utilities.CoreConfiguration;
import com.denizenscript.denizencore.utilities.debugging.Debug;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class AsyncCommand extends BracedCommand {

    public AsyncCommand() {
        setName("async");
        setSyntax("async (detached) (copy_defs:<name>|...) [<commands>]");
        setRequiredArguments(0, 2);
        // Only the marker is registered by hand - 'detached' is registered by autoCompile, from the autoExecute signature.
        // Registering it in both places would hand out two indices for one name and overrun the entry's boolean array.
        setBooleansHandled("\0callback");
        autoCompile();
        asyncSafe = true; // Only touches its own queue and thread-safe data.
        forceHold = true; // A block command has nowhere to write a '~', so it holds its queue by default instead.
    }

    // <--[command]
    // @Name Async
    // @Syntax async (detached) [<commands>]
    // @Required 0
    // @Maximum 1
    // @Short Runs a block of commands on a separate thread.
    // @Group queue
    //
    // @Description
    // Runs everything inside the block on a separate thread, then continues the script normally.
    // Refer to <@link language Async Queues>.
    //
    // The queue waits for the block to finish, so anything the block defines is ready to use on the line after it:
    //
    // <code>
    // - async:
    //     - define a <[input].parse_tag[<[parse_value].to_uppercase>]>
    //     - define b <[a].filter_tag[<[filter_value].contains[x]>]>
    //     - define c <[b].sort_by_value[length]>
    // - narrate "Result: <[c]>"
    // </code>
    //
    // This is the block form of <@link language ~waitable> - "- ~define x <...>" moves one command off-thread, this moves a whole section.
    // Prefer the block whenever you have several slow lines in a row: the "~" prefix costs a thread hand-off and up to a tick of waiting *per command*,
    // while a block costs that once no matter how many commands are inside it.
    //
    // Only the block's own logic moves off-thread. Any command inside that isn't safe to run off the main thread
    // (anything touching the live world - narrate, teleport, flag on an entity, ...) is automatically handed back to the main thread,
    // and the block waits for it, which costs up to a tick each time. So a block full of world-touching commands is slower, not faster -
    // keep those outside the block and put only data processing (tags, math, text, lists, maps) inside it.
    //
    // Definitions are shared with the outer queue: definitions that exist before the block are readable inside it,
    // and definitions the block creates are applied to the outer queue when the block ends.
    // Note that they are applied at the *end* of the block - another script reading this queue's definitions while the block is running sees the old values.
    //
    // A "stop" command inside the block stops the whole queue, not just the block.
    //
    // Optionally specify 'detached' to let the script continue immediately instead of waiting for the block.
    // A detached block always gets its own thread, including inside a queue that is already async - that is how it runs in parallel with the script that started it.
    //
    // A detached block starts from a copy of the current definitions, because it and the script that started it run at the same moment and so cannot share one set.
    // That copy is deep, so a queue holding large definitions pays for all of them on every detached block, however few it reads.
    // Specify 'copy_defs:' with the names it actually needs to copy only those - this is what makes a detached block cheap enough to use inside a loop:
    // <code>
    // - async detached copy_defs:path|index:
    //     - define next_point <[path].get[<[index]>].parsed>
    // </code>
    // Definitions the block writes are applied back to the queue that started it when it finishes, whether or not they were copied in.
    // Only the names the block actually wrote are applied, so anything the script defined in the meantime is kept.
    // If both wrote the same name, the block's value wins, as it lands later.
    //
    // Because the block finishes at a time the script has no way to predict, a detached definition is NOT ready on the next line.
    // Never guess with a 'wait' - use the save argument to check, or don't detach:
    // <code>
    // - async detached save:bg:
    //     - define result <[input].parse_tag[<[parse_value].to_uppercase>]>
    // # ... other work happens here, in parallel ...
    // - waituntil rate:1t max:30s <entry[bg].created_queue.state.equals[unknown]>
    // - narrate "<[result]>"
    // </code>
    //
    // If you have no other work to do in the meantime, plain 'async' (without 'detached') is the right choice - it hands definitions back with no guesswork.
    //
    // A waiting (non-detached) block inside a queue that is already async simply runs inline, as a second thread would gain it nothing.
    // If async scripts are disabled in the Denizen config, any block runs inline. Either way it is never an error to use.
    //
    // Blocks time themselves, and one that has never cost enough main thread time to be worth a thread hand-off simply runs on the main thread,
    // which skips the tick of waiting entirely. Nothing else about it changes. So a block that turns out to be trivial costs nothing for having been written,
    // and there is no need to guess in advance whether a section is heavy enough to be worth marking async.
    //
    // @Tags
    // <entry[saveName].created_queue> returns the queue the block runs in. Its 'state' tag reads 'running' until the block is done.
    // <queue.is_async> returns whether the current queue runs off-thread (true inside a non-detached block).
    // <util.is_main_thread> returns whether the tag itself is being read on the main thread.
    //
    // @Usage
    // Use to do a batch of slow data processing without freezing the server.
    // - async:
    //     - define scores <server.flag[player_scores].sort_by_value>
    //     - define top <[scores].keys.last[10].reverse>
    // - narrate "Top 10: <[top].comma_separated>"
    //
    // @Usage
    // Use to run a slow section in the background while the script carries on, then collect the result.
    // - async detached save:bg:
    //     - define report <server.flag[stats].parse_tag[<[parse_value].sort_by_value>]>
    // - narrate "Generating your report..."
    // - waituntil rate:1t max:30s <entry[bg].created_queue.state.equals[unknown]>
    // - narrate "Done: <[report]>"
    // -->

    /** Shared between an async block's main entry and the marker entry appended to the end of its sub-queue. */
    public static class AsyncData {

        /**
         * Set true by the marker entry at the very end of the block.
         * Still false when the block is over means something cut it short - a 'stop' command, or an error that killed the sub-queue.
         */
        public volatile boolean reachedEnd = false;

        /** When the block's contents started running, for the self-measurement described on {@link CoreConfiguration#asyncBlockInlineThresholdNanos}. */
        public long startNanos;
    }

    public static void autoExecute(ScriptEntry scriptEntry, @ArgName("detached") boolean detached,
                                   @ArgPrefixed @ArgName("copy_defs") @ArgDefaultNull ListTag copyDefs) {
        if (scriptEntry.argAsBoolean("\0callback")) {
            ScriptEntry owner = scriptEntry.getOwner();
            if (owner != null && owner.getData() instanceof AsyncData) {
                AsyncData data = (AsyncData) owner.getData();
                data.reachedEnd = true;
                // Reaching the marker means the block ran to its end, so this is the honest cost of its contents,
                // measured the same way whether it ran on a worker or inline.
                long elapsed = System.nanoTime() - data.startNanos;
                if (elapsed > owner.internal.asyncBlockMaxNanos) {
                    owner.internal.asyncBlockMaxNanos = elapsed;
                }
            }
            else {
                Debug.echoError(scriptEntry, "Async CALLBACK invalid: not a real callback!");
            }
            // 'forceHold' applies to this marker entry too, so it has to release its queue itself.
            scriptEntry.setFinished(true);
            return;
        }
        ScriptQueue queue = scriptEntry.getResidingQueue();
        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, "Async", db("detached", detached), new QueueTag(queue));
        }
        List<ScriptEntry> entries = getBracedCommandsDirect(scriptEntry, scriptEntry);
        if (entries == null || entries.isEmpty()) {
            Debug.echoError(scriptEntry, "Empty subsection - did you forget a ':'?");
            scriptEntry.setFinished(true);
            return;
        }
        AsyncData data = new AsyncData();
        data.startNanos = System.nanoTime();
        scriptEntry.setData(data);
        ScriptEntry markerEntry = new ScriptEntry("ASYNC", new String[]{"\0CALLBACK"}, scriptEntry.getScriptContainer());
        markerEntry.copyFrom(scriptEntry);
        markerEntry.setOwner(scriptEntry);
        entries.add(markerEntry);
        if ((queue.isAsync() && !detached) || !CoreConfiguration.allowAsyncScripts || isKnownCheap(scriptEntry, detached)) {
            // Run the block right here, in this queue, on this thread. Identical in every observable way to the worker path -
            // same definitions, same ordering, same 'stop' behaviour - it just skips the thread hand-off and the tick of waiting it costs.
            // Reasons to end up here: the queue is already off-thread so a second thread would gain nothing; async is switched off entirely;
            // or the block has measured itself as too cheap for a hand-off to be worth it.
            for (ScriptEntry entry : entries) {
                entry.setInstant(true);
            }
            scriptEntry.setFinished(true);
            queue.injectEntriesAtStart(entries);
            return;
        }
        if (!detached && !(queue instanceof TimedQueue)) {
            // Waiting for the block will force this queue to become a timed queue - do that now, before the worker thread starts.
            // If it happened later (from ScriptEngine.shouldHold), the main thread would be copying the queue's definitions
            // at the same moment the worker is writing to them.
            queue.forceToTimed(null);
            queue = scriptEntry.getResidingQueue();
        }
        AsyncQueue subQueue = new AsyncQueue("ASYNC");
        subQueue.debugOutput = queue.debugOutput;
        subQueue.procedural = queue.procedural;
        subQueue.setContextSource(queue.contextSource);
        subQueue.determinationTarget = queue.determinationTarget;
        if (detached) {
            // A detached block runs alongside the outer queue, so the two really do need separate maps - there is no moment when only one of them is writing.
            // That copy is deep, so it costs in proportion to everything the queue has defined, however little of it the block actually reads.
            // 'copy_defs' lets a script say which definitions it needs and skip the rest, which is what makes a detached block affordable in a loop.
            if (copyDefs != null) {
                MapTag subset = new MapTag();
                for (String name : copyDefs) {
                    ObjectTag value = queue.getDefinitionObject(name);
                    if (value != null) {
                        subset.putDeepObject(name, value.duplicate());
                    }
                }
                subQueue.definitions = subset;
            }
            else {
                subQueue.definitions = queue.definitions.duplicate();
            }
        }
        else {
            // A waiting block is different: the outer queue is held for the whole of it and executes nothing, so at any instant exactly one of
            // the two queues is touching this map. Handing it over as-is is therefore safe, and skips a deep copy of every definition the script holds.
            // The one thing this gives up is another script reading '<queue[id].definition[x]>' on the held queue mid-block, which would now
            // be reading a map the worker is writing. That was already an odd thing to do, and it is worth what the copy was costing.
            subQueue.definitions = queue.definitions;
        }
        for (ScriptEntry entry : entries) {
            entry.setInstant(true);
            entry.setSendingQueue(subQueue);
            entry.updateContext();
        }
        subQueue.addEntries(entries);
        scriptEntry.saveObject("created_queue", new QueueTag(subQueue));
        ScriptQueue outerQueue = queue;
        if (detached) {
            // The outer queue keeps running alongside the block, so its definitions can't simply be replaced at the end -
            // it may have defined things of its own in the meantime. Track what the block writes and merge back only that.
            subQueue.trackedDefinitionWrites = ConcurrentHashMap.newKeySet();
            subQueue.callBack(() -> outerQueue.runOnQueueThread(() -> mergeDetachedDefinitions(outerQueue, subQueue)));
            scriptEntry.setFinished(true);
            subQueue.start();
            return;
        }
        subQueue.callBack(() -> outerQueue.runOnQueueThread(() -> finishBlock(scriptEntry, outerQueue, subQueue, data)));
        subQueue.start();
    }

    /**
     * Returns true if this block has run before and never cost enough main thread time to be worth handing to another thread.
     * <p>
     * A block only pays off when the work it moves off the main thread is worth more than the tick of latency the hand-off costs the script.
     * Rather than making script writers judge that, each block times itself (see {@link AsyncData#startNanos}) and answers it from its own history.
     * The first run always goes to a worker, since nothing is known about it yet, and one run that is expensive settles the question for good -
     * the maximum is what's compared, so a block that is usually fast but sometimes slow keeps its thread.
     */
    public static boolean isKnownCheap(ScriptEntry scriptEntry, boolean detached) {
        if (detached) {
            // 'detached' is a promise not to wait, which running inline would break no matter how cheap the contents are.
            return false;
        }
        long threshold = CoreConfiguration.asyncBlockInlineThresholdNanos;
        if (threshold <= 0) {
            return false;
        }
        long maxSeen = scriptEntry.internal.asyncBlockMaxNanos;
        return maxSeen >= 0 && maxSeen < threshold;
    }

    /**
     * Merges a finished detached block's definitions into the queue that started it.
     * <p>
     * Always runs on the outer queue's own thread (between its commands, never during one), which is what makes this safe
     * despite the two queues having run at the same time - see {@link ScriptQueue#runOnQueueThread}.
     */
    public static void mergeDetachedDefinitions(ScriptQueue outerQueue, AsyncQueue subQueue) {
        for (String key : subQueue.trackedDefinitionWrites) {
            // A definition the block removed reads back as null here, which 'putObject' turns into a removal on the outer queue too.
            outerQueue.definitions.putObject(key, subQueue.definitions.getObject(key));
        }
    }

    /** Applies a finished block's results to the outer queue and releases it. Always runs on the outer queue's own thread. */
    public static void finishBlock(ScriptEntry scriptEntry, ScriptQueue outerQueue, AsyncQueue subQueue, AsyncData data) {
        outerQueue.definitions = subQueue.definitions;
        if (subQueue.determinations != null) {
            outerQueue.determinations = subQueue.determinations;
        }
        if (!data.reachedEnd) {
            // The block didn't run to its end - a 'stop' inside it (or a fatal error) is meant to stop the script, not just the block.
            Debug.echoDebug(scriptEntry, "Async block ended early, stopping the queue.");
            outerQueue.holdingOn = null;
            outerQueue.clear();
            outerQueue.stop();
            return;
        }
        scriptEntry.setFinished(true);
    }
}
