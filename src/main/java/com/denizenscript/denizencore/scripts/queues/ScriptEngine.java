package com.denizenscript.denizencore.scripts.queues;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.scripts.commands.CommandExecutor;
import com.denizenscript.denizencore.scripts.queues.core.TimedQueue;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.scripts.ScriptEntry;

public class ScriptEngine {

    /**
     * Kept small on purpose: revolve calls this for every line, and that call site is not counted as hot, so anything
     * over MaxInlineSize (35 bytes) becomes a real call per line. The waiting case lives in {@link #holdForWaitingEntry}.
     */
    static boolean shouldHold(ScriptQueue scriptQueue) {
        if (scriptQueue instanceof TimedQueue timed && timed.isPaused()) {
            return true;
        }
        ScriptEntry last = scriptQueue.getLastEntryExecuted();
        return last != null && last.shouldWaitFor() && holdForWaitingEntry(scriptQueue);
    }

    private static boolean holdForWaitingEntry(ScriptQueue scriptQueue) {
        if (!(scriptQueue instanceof TimedQueue)) {
            if (scriptQueue.replacementQueue != null) {
                // Already converted (eg by an async '~' command converting it up-front) - converting again would strand a duplicate queue.
                return true;
            }
            scriptQueue.forceToTimed(null);
        }
        return true;
    }

    /** Prepares an entry for execution within its queue, including giving it isolated internals if it's about to run off-thread. */
    static void prepareEntry(ScriptQueue scriptQueue, ScriptEntry scriptEntry) {
        // The thread check matters on top of the queue check: a plain (non-async) queue still runs on whichever thread started it,
        // which for a sub-queue built by an async script (a 'proc' tag, an 'inject', ...) is that script's worker thread.
        if (scriptQueue.isAsync() || !DenizenCore.isMainThread()) {
            scriptEntry.makeAsyncSafe(scriptQueue);
        }
        scriptEntry.setSendingQueue(scriptQueue);
        scriptEntry.updateContext();
    }

    public static ScriptQueue enterQueue(ScriptQueue scriptQueue) {
        CommandExecutor.QueueState state = CommandExecutor.currentQueueState();
        ScriptQueue prior = state.queue;
        state.queue = scriptQueue;
        return prior;
    }

    public static void leaveQueue(ScriptQueue prior) {
        CommandExecutor.currentQueueState().queue = prior;
    }

    public static void revolveOnceForce(ScriptQueue scriptQueue) {
        ScriptEntry scriptEntry = scriptQueue.getNext();
        if (scriptEntry == null) {
            return;
        }
        prepareEntry(scriptQueue, scriptEntry);
        scriptQueue.setLastEntryExecuted(scriptEntry);
        if (scriptEntry.internal.waitfor) {
            scriptQueue.holdingOn = scriptEntry;
        }
        try {
            CommandExecutor.execute(scriptEntry);
        }
        catch (Throwable e) {
            Debug.echoError(scriptEntry, "An exception has been called with this command (while revolving the queue forcefully)!");
            Debug.echoError(scriptEntry, e);
        }
    }

    public static void revolve(ScriptQueue scriptQueue) {
        if (shouldHold(scriptQueue)) {
            return;
        }
        ScriptEntry scriptEntry = scriptQueue.getNext();
        TimedQueue delayedQueue = scriptQueue instanceof TimedQueue timed ? timed : null;
        while (scriptEntry != null) {
            prepareEntry(scriptQueue, scriptEntry);
            scriptQueue.setLastEntryExecuted(scriptEntry);
            if (scriptEntry.internal.waitfor) {
                scriptQueue.holdingOn = scriptEntry;
            }
            CommandExecutor.execute(scriptEntry);
            if (delayedQueue != null) {
                if (delayedQueue.isDelayed() || delayedQueue.isPaused()) {
                    break;
                }
                if (delayedQueue.isInstantSpeed() || scriptEntry.isInstant()) {
                    if (shouldHold(scriptQueue)) {
                        return;
                    }
                    scriptEntry = scriptQueue.getNext();
                }
                else {
                    break;
                }
            }
            else if (scriptEntry.isInstant()) {
                if (shouldHold(scriptQueue)) {
                    return;
                }
                scriptEntry = scriptQueue.getNext();
            }
            else {
                break;
            }
        }
    }
}
