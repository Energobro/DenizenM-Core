package com.denizenscript.denizencore.scripts.commands.queue;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.objects.core.QueueTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.BracedCommand;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.scripts.queues.core.AsyncQueue;
import com.denizenscript.denizencore.scripts.queues.core.TimedQueue;
import com.denizenscript.denizencore.utilities.CoreConfiguration;
import com.denizenscript.denizencore.utilities.debugging.Debug;

import java.util.List;

public class AsyncCommand extends BracedCommand {

    public AsyncCommand() {
        setName("async");
        setSyntax("async (detached) [<commands>]");
        setRequiredArguments(0, 1);
        setBooleansHandled("detached", "\0callback");
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
    // A detached block is fire-and-forget: it gets a copy of the current definitions, and nothing it defines comes back.
    // A detached block always gets its own thread, including inside a queue that is already async - that is how it runs in parallel with the script that started it.
    //
    // A waiting (non-detached) block inside a queue that is already async simply runs inline, as a second thread would gain it nothing.
    // If async scripts are disabled in the Denizen config, any block runs inline. Either way it is never an error to use.
    //
    // @Tags
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
    // Use to run a slow section in the background while the script carries on.
    // - async detached:
    //     - ~run heavy_report_generator
    // - narrate "Report is being generated, check back later!"
    // -->

    /** Shared between an async block's main entry and the marker entry appended to the end of its sub-queue. */
    public static class AsyncData {

        /**
         * Set true by the marker entry at the very end of the block.
         * Still false when the block is over means something cut it short - a 'stop' command, or an error that killed the sub-queue.
         */
        public volatile boolean reachedEnd = false;
    }

    @Override
    public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {
        for (Argument arg : scriptEntry) {
            if (arg.matches("{")) {
                break;
            }
            arg.reportUnhandled();
        }
    }

    @Override
    public void execute(ScriptEntry scriptEntry) {
        if (scriptEntry.argAsBoolean("\0callback")) {
            if (scriptEntry.getOwner() != null && scriptEntry.getOwner().getData() instanceof AsyncData) {
                ((AsyncData) scriptEntry.getOwner().getData()).reachedEnd = true;
            }
            else {
                Debug.echoError(scriptEntry, "Async CALLBACK invalid: not a real callback!");
            }
            // 'forceHold' applies to this marker entry too, so it has to release the sub-queue itself.
            scriptEntry.setFinished(true);
            return;
        }
        boolean detached = scriptEntry.argAsBoolean("detached");
        ScriptQueue queue = scriptEntry.getResidingQueue();
        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, getName(), db("detached", detached), new QueueTag(queue));
        }
        List<ScriptEntry> entries = getBracedCommandsDirect(scriptEntry, scriptEntry);
        if (entries == null || entries.isEmpty()) {
            Debug.echoError(scriptEntry, "Empty subsection - did you forget a ':'?");
            scriptEntry.setFinished(true);
            return;
        }
        if ((queue.isAsync() && !detached) || !CoreConfiguration.allowAsyncScripts) {
            // A waiting block on a queue that's already off-thread gains nothing from a second thread, so it just runs inline.
            // A detached block is different: it means "don't wait for this", which inlining would silently break, so that still gets its own queue below.
            // Async being switched off entirely overrides both - there is no thread to run on.
            for (ScriptEntry entry : entries) {
                entry.setInstant(true);
            }
            scriptEntry.setFinished(true);
            queue.injectEntriesAtStart(entries);
            return;
        }
        MapTag blockDefinitions = null;
        if (!detached && !(queue instanceof TimedQueue)) {
            // Waiting for the block will force this queue to become a timed queue - do that now, before the worker thread starts.
            // If it happened later (from ScriptEngine.shouldHold), the main thread would be copying the queue's definitions
            // at the same moment the worker is writing to them.
            ScriptQueue deadQueue = queue;
            queue.forceToTimed(null);
            queue = scriptEntry.getResidingQueue();
            // That conversion already handed the outer queue a fresh deep copy and left the old queue dead (stopped, cleared, and skipped
            // by 'QueueTag.ensure'), so its map has no readers left and the block can simply take it.
            // Duplicating a second time here would cost the main thread exactly the kind of work the block exists to move off it.
            blockDefinitions = deadQueue.definitions;
        }
        AsyncQueue subQueue = new AsyncQueue("ASYNC");
        subQueue.debugOutput = queue.debugOutput;
        subQueue.procedural = queue.procedural;
        subQueue.setContextSource(queue.contextSource);
        subQueue.determinationTarget = queue.determinationTarget;
        // The block works on its own copy of the definitions, which replaces the outer queue's set once the block is done.
        // Sharing one map instead would have the worker thread and the main thread writing to it at the same time.
        subQueue.definitions = blockDefinitions != null ? blockDefinitions : queue.definitions.duplicate();
        AsyncData data = new AsyncData();
        scriptEntry.setData(data);
        ScriptEntry callbackEntry = new ScriptEntry("ASYNC", new String[]{"\0CALLBACK"}, scriptEntry.getScriptContainer());
        callbackEntry.copyFrom(scriptEntry);
        callbackEntry.setOwner(scriptEntry);
        entries.add(callbackEntry);
        for (ScriptEntry entry : entries) {
            entry.setInstant(true);
            entry.setSendingQueue(subQueue);
            entry.updateContext();
        }
        subQueue.addEntries(entries);
        if (detached) {
            // Nothing to hand back, so release the outer queue right away and let it carry on.
            scriptEntry.setFinished(true);
            subQueue.start();
            return;
        }
        ScriptQueue outerQueue = queue;
        subQueue.callBack(() -> outerQueue.runOnQueueThread(() -> finishBlock(scriptEntry, outerQueue, subQueue, data)));
        subQueue.start();
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
