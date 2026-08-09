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

    /** Every async queue that currently has a live worker thread. */
    public static final Set<AsyncQueue> runningQueues = ConcurrentHashMap.newKeySet();

    /** The thread currently running this queue, or null if the worker isn't live (not started yet, or already finished). */
    public volatile Thread ownerThread = null;

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

    @Override
    public boolean isAsync() {
        return true;
    }

    @Override
    public Thread getOwnerThread() {
        return ownerThread;
    }

    @Override
    public boolean isOnOwnerThread() {
        Thread owner = ownerThread;
        // A null owner means no worker is live yet (or it already finished), so whichever thread is asking is free to act on the queue directly.
        return owner == null || Thread.currentThread() == owner;
    }

    @Override
    public void runOnQueueThread(Runnable run) {
        if (isOnOwnerThread()) {
            run.run();
            return;
        }
        pendingTasks.add(run);
        wake();
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
            Debug.echoDebug(this, "Async scripts are disabled in config - running queue '" + debugId + "' on the main thread instead.");
            super.onStart();
            return;
        }
        DenizenCore.runAsync(this::runLoop);
    }

    /** Whether the count warning has already been given, so that a busy server gets it once rather than on every queue it starts. */
    private static volatile boolean warnedOnCount = false;

    /** The worker loop. Runs on the async thread for the entire life of the queue. */
    public void runLoop() {
        ownerThread = Thread.currentThread();
        runningQueues.add(this);
        checkQueueCount();
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
            ownerThread = null;
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
