package com.denizenscript.denizencore.scripts.commands;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.exceptions.InvalidArgumentsRuntimeException;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.utilities.CoreConfiguration;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.scripts.queues.core.TimedQueue;
import com.denizenscript.denizencore.tags.TagManager;

import java.util.function.Consumer;

public class CommandExecutor {

    /** The queue currently executing a command on the main thread. Use {@link #getCurrentQueue()} to read this in code that might run off-thread. */
    public static ScriptQueue currentQueue;

    /** The queue currently executing a command on any given non-main thread. */
    private static final ThreadLocal<ScriptQueue> asyncCurrentQueue = new ThreadLocal<>();

    /** Gets the queue currently executing a command on the calling thread, or null if none. */
    public static ScriptQueue getCurrentQueue() {
        return DenizenCore.isMainThread() ? currentQueue : asyncCurrentQueue.get();
    }

    /** Sets the queue currently executing a command on the calling thread. */
    public static void setCurrentQueue(ScriptQueue queue) {
        if (DenizenCore.isMainThread()) {
            currentQueue = queue;
        }
        else {
            asyncCurrentQueue.set(queue);
        }
    }

    public static void debugSingleExecution(ScriptEntry scriptEntry) {
        if (scriptEntry.getOriginalArguments().size() == 1 && scriptEntry.getOriginalArguments().get(0).equals("\0CALLBACK")) {
            return;
        }
        Consumer<String> altDebug = scriptEntry.getResidingQueue().debugOutput;
        scriptEntry.getResidingQueue().debugOutput = null;
        Debug.echoDebug(scriptEntry, Debug.DebugElement.Header, "<LP>Queue '" + scriptEntry.getResidingQueue().debugId
                + "<LP>' Executing: <G>(line " + scriptEntry.internal.lineNumber + ")<W> " + scriptEntry.internal.originalLine);
        scriptEntry.getResidingQueue().debugOutput = altDebug;
    }

    // <--[language]
    // @name The Save Argument
    // @group Script Command System
    // @description
    // The "save:<name>" argument is a special meta-argument that is available for all commands, but is only useful for some.
    // It is written like:
    // - run MyScript save:mysave
    //
    // When the save argument is used, the results of the command will be saved on the queue, for later usage by the "entry" tag.
    //
    // The useful entry keys available for any command are listed in the "Tags" documentation section for any command.
    // For example, the "run" command lists "<entry[saveName].created_queue>".
    // The "saveName" part should be replaced with whatever name you gave to the "save" argument,
    // and the "created_queue" part changes between commands.
    // Some commands have multiple save entry keys, some have just one, most don't have any.
    //
    // Many users make the mistake of using dynamic save names like "save:<[something]>" - this is almost always wrong. Use a constant name, just like you do for definitions.
    // -->

    // <--[language]
    // @name The Global If Argument
    // @group Script Command System
    // @description
    // The "if:<boolean>" argument is a special meta-argument that is available for all commands, but is more useful for some than others.
    // It is written like:
    // <code>
    // - stop if:<player.has_flag[forbidden]>
    // # Equivalent to
    // - if <player.has_flag[forbidden]>:
    //   - stop
    // </code>
    //
    // When the if argument is used, the command will only run if the value of the argument is 'true'.
    //
    // The most useful place to have this is a 'stop' command, to quickly stop a script if a condition is true (a player has a flag, lacks a permission, is outside a region, or whatever else).
    //
    // If you need more complex matching, especially using '&&', '||', '==', etc. you should probably just do an 'if' command rather than using the argument.
    // Though if you really want to, you can use tags here like <@link tag objecttag.is.to> or <@link tag elementtag.and> or <@link tag elementtag.or>.
    // -->

    /**
     * Executes a script entry, picking the correct thread for it first.
     * <p>
     * There are three paths:
     * - A '~' waited command that runs async (eg "~define") is handed to a worker thread, and the queue holds until it's done.
     * - A command that isn't async-safe, reached from an async queue, is handed back to the main thread, and the worker waits for it.
     * - Everything else runs right here on the calling thread.
     */
    public static boolean execute(ScriptEntry scriptEntry) {
        AbstractCommand command = scriptEntry.internal.actualCommand;
        boolean onMainThread = DenizenCore.isMainThread();
        if (scriptEntry.internal.waitfor && command.runAsyncWhenWaited && scriptEntry.getResidingQueue().holdingOn == scriptEntry) {
            if (onMainThread && CoreConfiguration.allowAsyncScripts) {
                ScriptQueue queue = scriptEntry.getResidingQueue();
                if (!(queue instanceof TimedQueue)) {
                    // Waiting for this command will force the queue to become a timed queue - do that now, before the worker thread starts.
                    // If it happened later (from ScriptEngine.shouldHold), the main thread would be copying the queue's definitions
                    // at the same moment the worker is writing to them.
                    queue.forceToTimed(null);
                }
                scriptEntry.makeAsyncSafe();
                DenizenCore.runAsync(() -> {
                    try {
                        executeInternal(scriptEntry);
                    }
                    finally {
                        // Hand the "I'm done" back to the queue's own thread, so the queue never resumes in the middle of this.
                        // Read the queue now rather than at dispatch time, as holding can move an entry to a replacement queue.
                        scriptEntry.getResidingQueue().runOnQueueThread(() -> scriptEntry.setFinished(true));
                    }
                });
                return true;
            }
            // Either already off-thread (an async queue running its own '~' command), or async is disabled by config.
            // Either way, run it right here - the queue still has to be released, since these commands never call setFinished themselves.
            try {
                return executeInternal(scriptEntry);
            }
            finally {
                scriptEntry.setFinished(true);
            }
        }
        if (!onMainThread && !command.asyncSafe && canDefer(scriptEntry, command)) {
            return executeDeferred(scriptEntry);
        }
        if (!onMainThread && !command.asyncSafe) {
            // This command can't safely run off-thread, so the async queue waits while the main thread runs it.
            Debug.verboseLog("Command '" + command.getName() + "' isn't async-safe, handing it to the main thread from thread '" + Thread.currentThread().getName() + "'.");
            boolean[] result = new boolean[1];
            long waitStart = System.nanoTime();
            try {
                DenizenCore.runOnMainThreadAndWait(() -> result[0] = executeInternal(scriptEntry));
            }
            catch (Throwable ex) {
                Debug.echoError(scriptEntry, "Failed to hand command '" + command.getName() + "' to the main thread:");
                Debug.echoError(scriptEntry, ex);
                scriptEntry.setFinished(true);
                return false;
            }
            finally {
                // The queue is read from the entry rather than the thread, as the thread's 'current queue' is only set once execution actually starts.
                ScriptQueue.recordMainThreadWait(scriptEntry.getResidingQueue(), System.nanoTime() - waitStart);
            }
            return result[0];
        }
        return executeInternal(scriptEntry);
    }

    /**
     * Returns true if this entry may be handed to the main thread without the async script waiting for it.
     * See {@link AbstractCommand#asyncDeferrable} for what a command must be for this to be allowed at all;
     * the checks here are the per-entry ones, which the command itself cannot know about.
     */
    public static boolean canDefer(ScriptEntry scriptEntry, AbstractCommand command) {
        if (!command.asyncDeferrable || command.generatedExecutor != null) {
            return false;
        }
        if (scriptEntry.internal.waitfor) {
            // The script is explicitly waiting for this one, which is the opposite of firing it off.
            return false;
        }
        // 'if:' decides whether the command runs at all and 'save:' is read by a later line - both must be resolved now, not later,
        // and both are handled inside the execution step. Rare on the commands this applies to, so simply don't defer when either is present.
        return scriptEntry.internal.preprocArgs == null || scriptEntry.internal.preprocArgs.isEmpty();
    }

    /**
     * Runs a command whose result its script never reads, without making the script wait for the main thread.
     * <p>
     * Arguments are parsed here, on the script's own thread, so the command is handed over with the values the script had at this moment.
     * Only the execution itself goes to the main thread, and it keeps its place in line behind anything deferred before it.
     */
    public static boolean executeDeferred(ScriptEntry scriptEntry) {
        AbstractCommand command = scriptEntry.internal.actualCommand;
        if (scriptEntry.dbCallShouldDebug()) {
            debugSingleExecution(scriptEntry);
        }
        ScriptQueue queue = scriptEntry.getResidingQueue();
        TagContext lastContext = Debug.getCurrentContext();
        try {
            setCurrentQueue(queue);
            Debug.setCurrentContext(scriptEntry.getContext());
            command.parseArgs(scriptEntry);
        }
        catch (Throwable ex) {
            Debug.echoError(scriptEntry, "Woah! An exception has been called while reading this command's arguments!");
            Debug.echoError(scriptEntry, ex);
            return false;
        }
        finally {
            setCurrentQueue(null);
            Debug.setCurrentContext(lastContext);
        }
        Debug.verboseLog("Command '" + command.getName() + "' handed to the main thread without waiting, from thread '" + Thread.currentThread().getName() + "'.");
        DenizenCore.runOnMainThread(() -> {
            TagContext priorContext = Debug.getCurrentContext();
            try {
                setCurrentQueue(queue);
                Debug.setCurrentContext(scriptEntry.getContext());
                command.execute(scriptEntry);
            }
            catch (Throwable ex) {
                // Note this surfaces after the script has moved on, so the error is reported against the entry rather than the current queue state.
                Debug.echoError(scriptEntry, "Woah! An exception has been called with this command (handed over by an async script)!");
                Debug.echoError(scriptEntry, ex);
            }
            finally {
                setCurrentQueue(null);
                Debug.setCurrentContext(priorContext);
            }
        });
        return true;
    }

    /** Executes a script entry on the current thread. Prefer {@link #execute(ScriptEntry)}, which handles thread selection. */
    public static boolean executeInternal(ScriptEntry scriptEntry) {
        if (scriptEntry.dbCallShouldDebug()) {
            debugSingleExecution(scriptEntry);
        }
        TagManager.recentTagError = false;
        AbstractCommand command = scriptEntry.internal.actualCommand;
        ScriptQueue queue = scriptEntry.getResidingQueue();
        setCurrentQueue(queue);
        if (queue.procedural && !command.isProcedural) {
            Debug.echoError("Command " + command.name + " is not accepted within a procedure. Procedures may not produce a change in the world, they may only process logic.");
            return false;
        }
        TagContext lastContext = Debug.getCurrentContext();
        try {
            TagContext context = scriptEntry.getContext();
            Debug.setCurrentContext(context);
            for (Argument arg : scriptEntry.internal.preprocArgs) {
                if (DenizenCore.implementation.handleCustomArgs(scriptEntry, arg)) {
                    // Do nothing
                }
                else if (arg.matchesPrefix("if")) {
                    String tagged = CoreUtilities.toLowerCase(TagManager.tag(arg.getValue(), context));
                    boolean shouldRun = tagged.equals("true") || tagged.equals("!false");
                    if (scriptEntry.dbCallShouldDebug()) {
                        Debug.echoDebug(scriptEntry, shouldRun ? "'if:' arg passed, command will run." : "'if:' arg returned false, command won't run.");
                    }
                    if (!shouldRun) {
                        scriptEntry.setFinished(true);
                        setCurrentQueue(null);
                        return true;
                    }
                }
                else if (arg.matchesPrefix("save")) {
                    scriptEntry.saveName = TagManager.tag(arg.getValue(), context);
                    if (scriptEntry.dbCallShouldDebug()) {
                        Debug.echoDebug(scriptEntry, "...remembering this script entry as '" + scriptEntry.saveName + "'!");
                    }
                }
            }
            if (command.generatedExecutor != null) {
                command.generatedExecutor.execute(scriptEntry, scriptEntry.getResidingQueue());
            }
            else {
                command.parseArgs(scriptEntry);
                command.execute(scriptEntry);
            }
            setCurrentQueue(null);
            return true;
        }
        catch (InvalidArgumentsException | InvalidArgumentsRuntimeException e) {
            // Give usage hint if InvalidArgumentsException was called.
            if (e.getMessage() != null && e.getMessage().length() > 0) {
                Debug.echoError(scriptEntry, "Woah! Invalid arguments were specified!\n<FORCE_ALIGN>" + e.getMessage());
            }
            else {
                Debug.echoError(scriptEntry, "Woah! Invalid arguments were specified!");
            }
            Debug.log("Usage: " + command.getUsageHint());
            Debug.log("(Attempted: " + scriptEntry + ")");
            Debug.echoDebug(scriptEntry, Debug.DebugElement.Footer);
            scriptEntry.setFinished(true);
            setCurrentQueue(null);
            return false;
        }
        catch (Throwable e) {
            Debug.echoError(scriptEntry, "Woah! An exception has been called with this command!");
            Debug.echoError(scriptEntry, e);
            Debug.log("(Attempted: " + scriptEntry + ")");
            Debug.echoDebug(scriptEntry, Debug.DebugElement.Footer);
            scriptEntry.setFinished(true);
            setCurrentQueue(null);
            return false;
        }
        finally {
            Debug.setCurrentContext(lastContext);
        }
    }
}
