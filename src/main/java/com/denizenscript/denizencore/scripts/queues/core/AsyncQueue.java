package com.denizenscript.denizencore.scripts.queues.core;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.objects.core.DurationTag;
import com.denizenscript.denizencore.utilities.CoreConfiguration;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.debugging.Debug;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A queue that runs its script entries on a separate thread instead of the main server thread.
 * <p>
 * The queue owns exactly one worker thread at a time (borrowed from the core async executor), which loops:
 * run any tasks handed to it by other threads, revolve the queue, then sleep until the next revolution is due.
 * <p>
 * Commands that aren't marked async-safe (see {@link com.denizenscript.denizencore.scripts.commands.AbstractCommand#asyncSafe})
 * are automatically executed on the main thread by the command executor, with this queue's thread waiting for them.
 */
public class AsyncQueue extends TimedQueue {

    /** Every async queue that has a worker: from the moment one is handed to the executor until that worker has finished. */
    public static final Set<AsyncQueue> runningQueues = ConcurrentHashMap.newKeySet();

    /** The thread currently running this queue, or null if the worker isn't live - not started yet, already finished, or dispatched but not picked up yet (see {@link #workerDispatched}). */
    public volatile Thread ownerThread = null;

    /**
     * True from the moment a worker is handed to the executor until that worker has finished.
     * <p>
     * {@link #ownerThread} alone cannot answer whether a worker is live: it is set by the worker itself, as its first line, so between the
     * dispatch and that line the field is still null while a worker is very much on its way. Without this, {@link #isOnOwnerThread} tells whoever
     * is asking that they own the queue, and another thread can stop it - on itself - while the worker is starting up.
     * <p>
     * Only ever set on the branch of {@link #onStart} that actually dispatches a worker. The branches that fall back to the main thread
     * (async disabled in config, or the queue count limit reached) must leave it false: a queue running on the main thread has no worker to
     * drain {@link #pendingTasks}, so marking it would send handed-over work into a box nobody empties.
     */
    public volatile boolean workerDispatched = false;

    /** Set true to ask the worker loop to end at the earliest opportunity (used by shutdown). */
    public volatile boolean abandoned = false;

    /** Tasks other threads want run on this queue's thread, in order. */
    public final ConcurrentLinkedQueue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();

    /** Lock object used to sleep between revolutions, so that {@link #wake()} can interrupt the sleep immediately. */
    private final Object sleepLock = new Object();

    public AsyncQueue(String id) {
        super(id, 0);
    }

    public AsyncQueue(String id, long ticks) {
        super(id, ticks);
    }

    public AsyncQueue(String id, DurationTag timing) {
        super(id, timing);
    }

    @Override
    public String getName() {
        return "AsyncQueue";
    }

    /**
     * Set when {@link #onStart} folded this queue onto the main thread instead of giving it a worker - async off in config, the count limit
     * reached, or the executor refusing. Kept as the inverse of "owns a thread" so that only folded queues change what {@link #isAsync} answers,
     * and a queue not started yet or already finished keeps answering as it always did.
     */
    public volatile boolean foldedToMainThread = false;

    @Override
    public boolean isAsync() {
        // Not a constant true: the class is still the queue's type after onStart folded it onto the main thread, and claiming "async" there makes
        // <@link tag QueueTag.is_async> report a thread that does not exist, and an '- async:' block inline itself as "already off-thread".
        return !foldedToMainThread;
    }

    @Override
    public Thread getOwnerThread() {
        return ownerThread;
    }

    @Override
    public boolean isOnOwnerThread() {
        Thread owner = ownerThread;
        if (owner != null) {
            return Thread.currentThread() == owner;
        }
        // No owner set means one of two very different things. Either no worker was ever dispatched - a queue not started yet, or one that fell
        // back to the main thread - and then whichever thread is asking is free to act on the queue directly. Or a worker was dispatched and has
        // not reached its first line yet, and then the asking thread must not act, because it would be acting alongside a worker that is about
        // to run this queue. Handing the work over instead costs nothing there: draining pendingTasks is the first thing the worker does.
        return !workerDispatched;
    }

    @Override
    public void runOnQueueThread(Runnable run) {
        if (isOnOwnerThread()) {
            run.run();
            return;
        }
        pendingTasks.add(run);
        wake();
        // Publish, then look again: between the check above and this add, the worker may have drained for the last time and put its marks down,
        // leaving this task with nobody to run it. Looking again catches that - a queue with no worker is one this thread may drain itself.
        // Both threads draining at once is fine, since runPendingTasks polls.
        if (isOnOwnerThread()) {
            runPendingTasks();
        }
    }

    @Override
    public void wake() {
        synchronized (sleepLock) {
            sleepLock.notifyAll();
        }
    }

    @Override
    public void onStart() {
        if (!CoreConfiguration.allowAsyncScripts) {
            // debugId carries its own colour codes, so the surrounding colour has to be restored after it, or the last word of the generated
            // queue name paints the rest of the line. Which code to restore to depends on where the text lands: a debug body is <O> (see
            // ScriptQueue.queueDebug), and an error body is <W> (DebugInternals.ERROR_HEADER_END). <LR> belongs only inside an error header.
            Debug.echoDebug(this, "Async scripts are disabled in config - running queue '" + debugId + "<O>' on the main thread instead.");
            foldedToMainThread = true;
            super.onStart();
            return;
        }
        int limit = CoreConfiguration.asyncQueueCountLimit;
        if (limit > 0 && runningQueues.size() >= limit) {
            // Running it on the main thread rather than refusing it: the script still does what it says, it just stops adding threads.
            // A server that reaches this is already in trouble - this only keeps a runaway loop from making it worse.
            // Two threads starting queues at once can each overshoot by one. Left alone: the case worth stopping is one script's loop, on one thread.
            if (!warnedOnLimit) {
                warnedOnLimit = true;
                Debug.echoError(limit + " async script queues are already running, which is the configured limit, so queue '" + debugId
                        + "<W>' is running on the main thread instead. Something is starting async queues in a loop - consider one queue that processes a list.");
            }
            foldedToMainThread = true;
            super.onStart();
            return;
        }
        // Both marks go up before the dispatch, not inside the worker. isOnOwnerThread must say the queue is taken even before the worker's first
        // line; and the count must include it before the next queue starts, since a script's loop starts them all inside one tick - counting in the
        // worker let all 40 of 40 past a limit of 20.
        workerDispatched = true;
        runningQueues.add(this);
        checkQueueCount();
        try {
            DenizenCore.runAsync(this::runLoop);
        }
        catch (Throwable ex) {
            // Only reachable after a shutdown, or when no thread can be created. Clear both marks: the flag would send handed-over work into
            // pendingTasks with no worker to drain it, and the set entry would hold a slot under the limit forever.
            workerDispatched = false;
            runningQueues.remove(this);
            Debug.echoError("Could not start a thread for async queue '" + debugId + "<W>' - running it on the main thread instead:");
            Debug.echoError(ex);
            foldedToMainThread = true;
            super.onStart();
        }
    }

    /** Whether the count warning has already been given, so that a busy server gets it once rather than on every queue it starts. */
    private static volatile boolean warnedOnCount = false;

    /** The same, for the hard limit. Re-armed by {@link #checkQueueCount()} once the count has fallen well back. */
    private static volatile boolean warnedOnLimit = false;

    /** The worker loop. Runs on the async thread for the entire life of the queue. */
    public void runLoop() {
        ownerThread = Thread.currentThread();
        try {
            while (is_started && !isStopped && !abandoned) {
                runPendingTasks();
                if (!is_started || isStopped || abandoned) {
                    break;
                }
                revolve();
                if (!is_started || isStopped || abandoned) {
                    break;
                }
                sleepUntilNextRevolution();
            }
        }
        catch (Throwable ex) {
            Debug.echoError("Async queue '" + id + "' hit an unhandled error and will stop:");
            Debug.echoError(ex);
        }
        finally {
            runningQueues.remove(this);
            // Any tasks still pending would never run otherwise (eg a stop request that arrived while the loop was ending).
            runPendingTasks();
            // Both cleared together, and only after that last drain: from here the queue has no worker, so another thread asking to act on it
            // is answered yes and does so itself.
            ownerThread = null;
            workerDispatched = false;
            // The other half of the handover in runOnQueueThread: this drain takes anything added before the marks came down, and anything added
            // after is run by the thread that added it. No lock on the queue lifecycle needed.
            runPendingTasks();
            if (!isStopped) {
                try {
                    stop();
                }
                catch (Throwable ex) {
                    Debug.echoError(ex);
                }
            }
        }
    }

    /**
     * Says something once when a lot of async queues are running at the same time.
     * <p>
     * Each one holds a thread for its whole life, so this is a script starting more threads than it probably meant to.
     * Warns on the way up and re-arms once the count has fallen well back, so a server hovering around the threshold isn't spammed.
     */
    public static void checkQueueCount() {
        int threshold = CoreConfiguration.asyncQueueCountWarning;
        if (threshold <= 0) {
            return;
        }
        int count = runningQueues.size();
        if (!warnedOnCount && count >= threshold) {
            warnedOnCount = true;
            Debug.echoError(count + " async script queues are running at once. Each one holds a thread for as long as it lives, including while waiting,"
                    + " so this many is usually a script starting async queues in a loop. Consider one queue that processes a list instead.");
        }
        else if (warnedOnCount && count < threshold / 2) {
            warnedOnCount = false;
        }
        int limit = CoreConfiguration.asyncQueueCountLimit;
        if (warnedOnLimit && limit > 0 && count < limit / 2) {
            warnedOnLimit = false;
        }
    }

    /** Runs everything other threads have queued up for this queue's thread. */
    public void runPendingTasks() {
        Runnable task;
        while ((task = pendingTasks.poll()) != null) {
            try {
                task.run();
            }
            catch (Throwable ex) {
                Debug.echoError("Async queue '" + id + "' - task from another thread failed:");
                Debug.echoError(ex);
            }
        }
    }

    /**
     * Sleeps the worker thread until it's time to revolve again.
     * Wakes early if another thread calls {@link #wake()} (eg a '~waited' command finishing, or a stop request).
     */
    public void sleepUntilNextRevolution() {
        long sleepMs = ticks > 0 ? ticks * 50 : 0;
        if (sleepMs <= 0) {
            // Instant speed: a revolution already ran everything it could, so we're only here because the queue is held/delayed/paused/waiting for entries.
            // A short poll covers the cases nothing can signal (delays running out, entries injected from elsewhere),
            // while anything that finishes a hold calls wake() and resumes us immediately anyway.
            sleepMs = 5;
        }
        synchronized (sleepLock) {
            if (!pendingTasks.isEmpty() || isStopped || abandoned || !is_started) {
                return;
            }
            try {
                sleepLock.wait(sleepMs);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                abandoned = true;
            }
        }
    }

    /** Asks every running async queue to stop, and waits (briefly) for their workers to finish. Used on shutdown. */
    public static void stopAll() {
        Collection<AsyncQueue> queues = new ArrayList<>(runningQueues);
        if (queues.isEmpty()) {
            return;
        }
        Debug.log("Stopping " + queues.size() + " async script queue(s)...");
        for (AsyncQueue queue : queues) {
            queue.abandoned = true;
            queue.wake();
        }
        long end = CoreUtilities.monotonicMillis() + CoreConfiguration.asyncShutdownTimeoutMillis;
        while (!runningQueues.isEmpty() && CoreUtilities.monotonicMillis() < end) {
            // Async queues that are mid-command may be waiting on the main thread, so keep processing main thread work while they wind down.
            DenizenCore.runMainThreadTasks();
            try {
                Thread.sleep(5);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!runningQueues.isEmpty()) {
            Debug.echoError("Gave up waiting on " + runningQueues.size() + " async script queue(s) during shutdown.");
        }
    }
}
