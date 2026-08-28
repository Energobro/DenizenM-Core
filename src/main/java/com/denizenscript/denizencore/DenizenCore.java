package com.denizenscript.denizencore;

import com.denizenscript.denizencore.events.ScriptEvent;
import com.denizenscript.denizencore.events.core.*;
import com.denizenscript.denizencore.flags.SavableMapFlagTracker;
import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.objects.core.SecretTag;
import com.denizenscript.denizencore.objects.notable.NoteManager;
import com.denizenscript.denizencore.scripts.ScriptHelper;
import com.denizenscript.denizencore.scripts.ScriptRegistry;
import com.denizenscript.denizencore.scripts.commands.CommandRegistry;
import com.denizenscript.denizencore.scripts.commands.queue.RunLaterCommand;
import com.denizenscript.denizencore.scripts.containers.ScriptContainer;
import com.denizenscript.denizencore.scripts.queues.core.TimedQueue;
import com.denizenscript.denizencore.tags.Attribute;
import com.denizenscript.denizencore.tags.ReplaceableTagEvent;
import com.denizenscript.denizencore.tags.TagManager;
import com.denizenscript.denizencore.utilities.CoreConfiguration;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.PropertyMatchHelper;
import com.denizenscript.denizencore.utilities.ReflectionHelper;
import com.denizenscript.denizencore.utilities.debugging.*;
import com.denizenscript.denizencore.utilities.scheduling.AsyncSchedulable;
import com.denizenscript.denizencore.utilities.scheduling.OneTimeSchedulable;
import com.denizenscript.denizencore.utilities.scheduling.Schedulable;

import com.denizenscript.denizencore.scripts.queues.core.AsyncQueue;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The entry point of the core Denizen engine.
 */
public class DenizenCore {

    /**
     * (Automatically populated) current core version.
     */
    public final static String VERSION;

    /**
     * All commands known to the system are registered here.
     */
    public static CommandRegistry commandRegistry = new CommandRegistry();

    /**
     * Monotonic time (CoreUtilities.monotonicMillis) that the engine first loaded.
     */
    public final static long startTime = CoreUtilities.monotonicMillis();

    /**
     * Server flags, for the 'flag' command and 'server.flag[...]' tags.
     */
    public static SavableMapFlagTracker serverFlagMap;

    /**
     * Last monotonic time (CoreUtilities.monotonicMillis) that scripts wre reloaded.
     */
    public static long lastReloadTime;

    /**
     * How many times scripts have been reloaded.
     */
    public static int reloads = 0;

    /**
     * Helper to intercept System.out for the redirect_logging mechanism.
     */
    public static LogInterceptor logInterceptor = new LogInterceptor();

    /**
     * Known main thread reference, for async scheduler usage.
     */
    public static Thread MAIN_THREAD;

    /**
     * Current system time (System.currentTimeMillis), updated per-tick.
     * Used to avoid multiple checks in the same tick having different time values.
     * Volatile, as async queues read this from other threads.
     */
    public static volatile long currentTimeMillis = System.currentTimeMillis();

    /**
     * Current monotonic time (System.nanoTime), updated per-tick.
     * Used to avoid multiple checks in the same tick having different time values.
     * Volatile, as async queues read this from other threads.
     */
    public static volatile long currentTimeMonotonicMillis = CoreUtilities.monotonicMillis();

    /**
     * Duration of time, in milliseconds, since the server started.
     * Volatile, as async queues read this from other threads (eg for 'wait' delay tracking).
     */
    public static volatile long serverTimeMillis = 1;

    /**
     * All current scheduled tasks.
     */
    public static final ArrayList<Schedulable> scheduled = new ArrayList<>();

    /**
     * All current delayed queues.
     */
    public static final ArrayList<TimedQueue> timedQueues = new ArrayList<>();

    /**
     * Tasks submitted from other threads that must run on the main thread, processed at the start of every tick.
     * Prefer {@link #runOnMainThread(Runnable)} over touching this directly.
     * <p>
     * Fire-and-forget work only - nothing is standing still waiting for anything in here, which is why it is processed under a
     * time budget (see {@link CoreConfiguration#mainThreadTaskBudgetMillis}) with the remainder left for the next tick.
     * Without that, an async script can produce deferred commands and debug output faster than the main thread can run them,
     * and freeze the server with them - the one thing running off-thread is supposed to prevent.
     * Work that a thread is blocked on goes to {@link #mainThreadWaitingTasks} instead.
     */
    public static final ConcurrentLinkedQueue<Runnable> mainThreadTasks = new ConcurrentLinkedQueue<>();

    /**
     * Tasks from {@link #runOnMainThreadAndWait(Runnable)}, where a thread is standing still until they are done.
     * <p>
     * Always processed in full, and ahead of {@link #mainThreadTasks}: there can never be more of these at once than there are
     * live async threads, and leaving them behind a backlog of logging and deferred commands would mean async scripts waiting
     * on work that nobody is waiting on.
     */
    public static final ConcurrentLinkedQueue<Runnable> mainThreadWaitingTasks = new ConcurrentLinkedQueue<>();

    /**
     * Timed queues that were started from a different thread, and so need to be added to {@link #timedQueues} by the main thread.
     */
    public static final ConcurrentLinkedQueue<TimedQueue> pendingTimedQueues = new ConcurrentLinkedQueue<>();

    /**
     * Implementation helper class, must be implemented for Denizen to function.
     */
    public static DenizenImplementation implementation;

    static {
        String version = "UNKNOWN";
        try {
            InputStream is = DenizenCore.class.getClassLoader().getResourceAsStream("denizencore.properties");
            if (is == null) {
                throw new FileNotFoundException("denizencore.properties not found in jar file");
            }
            else {
                Properties properties = new Properties();
                properties.load(is);
                version = properties.getProperty("version") + " (Build " + properties.getProperty("build") + ")";
                is.close();
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        VERSION = version;
    }

    /**
     * Must be called first: prepares the engine!
     *
     * @param implementation your Denizen implementation.
     */
    public static void init(DenizenImplementation implementation) {
        currentTimeMillis = System.currentTimeMillis();
        currentTimeMonotonicMillis = CoreUtilities.monotonicMillis();
        DenizenCore.implementation = implementation;
        MAIN_THREAD = Thread.currentThread();
        Debug.log("Initializing Denizen Core v" + VERSION + ", impl for " + implementation.getImplementationName() + " v" + implementation.getImplementationVersion());
        ScriptRegistry._registerCoreTypes();
        ScriptEvent.registerCoreEvents();
        ObjectFetcher.registerCoreObjects();
        TagManager.registerCoreTags();
        commandRegistry.registerCoreCommands();
        DebugSubmitter.init();
        ReflectionHelper.hasInitialized = true;
    }

    /**
     * Call to reload anything that was saved, especially after init.
     */
    public static void reloadSaves() {
        serverFlagMap = SavableMapFlagTracker.loadFlagFile(new File(implementation.getDataFolder(), "server_flags").getPath(), true);
        SecretTag.load();
    }

    /**
     * Call to save anything that needs to be saved, especially before shutdown.
     */
    public static void saveAll(boolean lockUntilDone) {
        NoteManager.save(lockUntilDone);
        serverFlagMap.saveToFile(new File(implementation.getDataFolder(), "server_flags").getPath(), lockUntilDone);
    }

    /**
     * Must be called last: performs final shutdowns / saves / etc.
     */
    public static void shutdown() {
        ShutdownScriptEvent.instance.fire();
        AsyncQueue.stopAll();
        // No budget here - there is no next tick to leave the remainder for, and dropping it would lose the last of the deferred work and debug output.
        runMainThreadTasks(0);
        saveAll(true);
        logInterceptor.standardOutput();
        commandRegistry.disableCoreMembers();
    }

    /**
     * Call postLoadScripts after.
     */
    public static void preloadScripts(boolean delayable, Runnable onFinished) {
        try {
            reloads++;
            final long start = CoreUtilities.monotonicMillis();
            ScriptHelper.resetError();
            ScriptHelper.reloadScripts(delayable, () -> {
                PreScriptReloadScriptEvent.instance.fire();
                ScriptEvent.worldContainers.clear();
                PropertyMatchHelper.matchHelperCache.clear();
                implementation.preScriptReload();
            }, (midpoint) -> {
                long completion = CoreUtilities.monotonicMillis();
                Debug.log("Scripts loaded! File load took <A>" + (midpoint - start) + "<W>ms, processing <A>" + (completion - midpoint) + "<W>ms.");
                if (onFinished != null) {
                    onFinished.run();
                }
            });
        }
        catch (Exception ex) {
            Debug.echoError("Error loading scripts:");
            Debug.echoError(ex);
        }
    }

    /**
     * Called last in the init sequence, loads all scripts and starts the Denizen engine.
     * Call preloadScripts first.
     */
    public static void postLoadScripts() {
        try {
            TagManager.preCalced.clear();
            Attribute.attribsLookup.clear();
            ReplaceableTagEvent.refs.clear();
            ScriptRegistry.postLoadScripts();
            for (ScriptContainer container : ScriptRegistry.scriptContainers.values()) {
                container.postCheck();
            }
            ScriptEvent.reload();
            implementation.onScriptReload();
            lastReloadTime = CoreUtilities.monotonicMillis();
            ScriptsLoadedScriptEvent.instance.hadError = ScriptHelper.hadError();
            ScriptsLoadedScriptEvent.instance.fire();
        }
        catch (Exception ex) {
            Debug.echoError("Error loading scripts:");
            Debug.echoError(ex);
        }
    }

    /**
     * Call when a script reload is required (EG, requested by user command).
     */
    public static void reloadScripts(boolean delayable, Runnable onFinished) {
        preloadScripts(delayable, () -> {
            postLoadScripts();
            ReloadScriptsScriptEvent.instance.hadError = ScriptHelper.hadError();
            ReloadScriptsScriptEvent.instance.fire();
            if (onFinished != null) {
                onFinished.run();
            }
        });
    }

    /**
     * Schedule an item to be run automatically after a given period of time, optionally repeating.
     */
    public static void schedule(Schedulable sched) {
        synchronized (scheduled) {
            scheduled.add(sched);
        }
    }

    /** Returns true if called from the thread that DenizenCore understands to be the main thread, or false if on a different thread. */
    public static boolean isMainThread() {
        Thread curThread = Thread.currentThread();
        return curThread.equals(MAIN_THREAD) || curThread.equals(TagManager.tagThread);
    }

    /** Returns true if called from the literal main thread (unlike {@link #isMainThread()}, this ignores the tag-timeout helper thread). */
    public static boolean isStrictlyMainThread() {
        return Thread.currentThread().equals(MAIN_THREAD);
    }

    /** Runs the task immediately if called on main thread, or at the start of the next tick if called off-thread. */
    public static void runOnMainThread(Runnable run) {
        if (isMainThread()) {
            run.run();
        }
        else {
            mainThreadTasks.add(run);
        }
    }

    /**
     * Runs the task on the main thread and blocks the calling thread until it has completed.
     * Runs immediately (without blocking) if already on the main thread.
     * Any exception thrown by the task is rethrown on the calling thread.
     * Never call this from the main thread's own scheduled tasks, and never call it while holding a lock the main thread might need.
     */
    public static void runOnMainThreadAndWait(Runnable run) {
        if (isMainThread()) {
            run.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Throwable[] error = new Throwable[1];
        // Exactly one of the two threads gets to own this task: the main thread runs it, or this one gives up on it, never both.
        // Without the claim, a timed-out wait would leave the task sitting in the queue to run later - while this thread has already
        // moved on to the next command, leaving the main thread and this one working the same script entry at once.
        AtomicBoolean claimed = new AtomicBoolean(false);
        mainThreadWaitingTasks.add(() -> {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            try {
                run.run();
            }
            catch (Throwable ex) {
                error[0] = ex;
            }
            finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(CoreConfiguration.mainThreadWaitTimeoutMillis, TimeUnit.MILLISECONDS)) {
                if (claimed.compareAndSet(false, true)) {
                    throw new RuntimeException("Timed out after " + CoreConfiguration.mainThreadWaitTimeoutMillis + "ms waiting for the main thread to process an async request - is the server frozen, or was Denizen shut down?");
                }
                // The main thread had already started it. Walking away now would be the very thing the claim is here to prevent,
                // so wait it out - a task that never finishes means the server is gone anyway, and this at least says so.
                Debug.echoError("Waited " + CoreConfiguration.mainThreadWaitTimeoutMillis + "ms on the main thread, which is still running this request - continuing to wait, as abandoning it now would leave two threads on one script entry.");
                latch.await();
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for the main thread.", ex);
        }
        if (error[0] != null) {
            if (error[0] instanceof RuntimeException runtimeEx) {
                throw runtimeEx;
            }
            if (error[0] instanceof Error errorObj) {
                throw errorObj;
            }
            throw new RuntimeException(error[0]);
        }
    }

    /** Runs the task on a separate thread. */
    public static void runAsync(Runnable run) {
        AsyncSchedulable.executor.execute(run);
    }

    /** How many tasks to run between clock readings, so that measuring the budget doesn't cost more than the small tasks it is measuring. */
    private static final int TASKS_PER_TIME_CHECK = 64;

    private static void runMainThreadTask(Runnable task) {
        try {
            task.run();
        }
        catch (Throwable ex) {
            Debug.echoError("DenizenCore - Main thread task (from an async source) failed");
            Debug.echoError(ex);
        }
    }

    /** Ceiling on one pass of {@link #lingerForFollowUpRequests()}, so a script asking in a tight loop cannot hold the tick open indefinitely. */
    private static final long LINGER_HARD_CAP_NANOS = 5_000_000L;

    /** How many empty polls to spin through before yielding once, so that on a box with few cores the thread being waited for can actually run. */
    private static final int SPINS_PER_YIELD = 64;

    private static final int LINGER_ADAPTIVE_MAX_MULTIPLIER = 4;

    public static volatile long lingerAdaptiveNanos = 0;

    /**
     * Nanoseconds spent in {@link #lingerForFollowUpRequests()} after the last answer, waiting for a follow-up that never came - what
     * {@link CoreConfiguration#mainThreadWaitLingerMicros} costs when it does not pay off. TPS cannot show this: a few ms a tick reads as nothing
     * until the tick has no headroom left. Written only by the main thread, volatile so <@link tag util.linger_stats> can read it off-thread.
     */
    public static volatile long lingerIdleNanos = 0;

    /** How many times the window has been entered, so the idle total above can be read as an average per entry. */
    public static volatile long lingerCount = 0;

    public static long nextLingerNanos() {
        long base = CoreConfiguration.mainThreadWaitLingerMicros * 1000L;
        return Math.max(base, Math.min(lingerAdaptiveNanos, base * LINGER_ADAPTIVE_MAX_MULTIPLIER));
    }

    /**
     * Waits a moment for an async script's next request, answers it, and keeps going until nothing more arrives in time.
     * <p>
     * Requests arrive back to back, so the one that follows an answer is usually microseconds behind it - and without this it would miss the
     * drain it just missed by nothing and wait for the next tick. See {@link CoreConfiguration#mainThreadWaitLingerMicros} for what it costs.
     * Each answer restarts the window, so a script working through a chain keeps its turn, but never past the hard cap above.
     */
    private static void lingerForFollowUpRequests() {
        long base = CoreConfiguration.mainThreadWaitLingerMicros * 1000L;
        if (base <= 0) {
            return;
        }
        long linger = nextLingerNanos();
        long start = System.nanoTime();
        long hardEnd = start + LINGER_HARD_CAP_NANOS;
        long deadline = Math.min(start + linger, hardEnd);
        // What comes after the last answer, up to whichever exit is taken, is the part that bought nothing.
        long lastAnswer = start;
        lingerCount++;
        int spins = 0;
        while (true) {
            Runnable task = mainThreadWaitingTasks.poll();
            if (task != null) {
                runMainThreadTask(task);
                long after = System.nanoTime();
                lastAnswer = after;
                if (after >= hardEnd) {
                    adaptLingerWindow(linger, base, true);
                    return; // Left on the cap right after answering, so nothing was spent in vain.
                }
                deadline = Math.min(after + linger, hardEnd);
                spins = 0;
                continue;
            }
            // The deadline check needed this reading anyway, so measuring the tail costs nothing.
            long now = System.nanoTime();
            if (now >= deadline) {
                lingerIdleNanos += now - lastAnswer;
                adaptLingerWindow(linger, base, lastAnswer != start);
                return;
            }
            if (++spins >= SPINS_PER_YIELD) {
                spins = 0;
                Thread.yield();
            }
            else {
                Thread.onSpinWait();
            }
        }
    }

    private static void adaptLingerWindow(long linger, long base, boolean productive) {
        long next = productive ? linger * 2 : linger / 2;
        lingerAdaptiveNanos = Math.max(base, Math.min(next, base * LINGER_ADAPTIVE_MAX_MULTIPLIER));
    }

    /**
     * Processes the tasks that other threads have requested to be run on the main thread. Called automatically per-tick.
     * <p>
     * Tasks with a thread waiting on them go first and all of them run; fire-and-forget tasks then run until the tick's budget is spent,
     * keeping their order, with whatever is left going to the next tick.
     */
    public static void runMainThreadTasks() {
        runMainThreadTasks(CoreConfiguration.mainThreadTaskBudgetMillis);
    }

    /** As {@link #runMainThreadTasks()}, with an explicit budget in milliseconds - 0 for "everything, however much there is". */
    public static void runMainThreadTasks(long budgetMillis) {
        Runnable task;
        boolean answeredAny = false;
        while ((task = mainThreadWaitingTasks.poll()) != null) {
            runMainThreadTask(task);
            answeredAny = true;
        }
        if (answeredAny) {
            lingerForFollowUpRequests();
        }
        if (budgetMillis <= 0) {
            while ((task = mainThreadTasks.poll()) != null) {
                runMainThreadTask(task);
            }
        }
        else {
            long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
            int sinceTimeCheck = 0;
            while ((task = mainThreadTasks.poll()) != null) {
                runMainThreadTask(task);
                if (++sinceTimeCheck >= TASKS_PER_TIME_CHECK) {
                    sinceTimeCheck = 0;
                    if (System.nanoTime() > deadline) {
                        break;
                    }
                }
            }
        }
        TimedQueue queue;
        while ((queue = pendingTimedQueues.poll()) != null) {
            if (!queue.isStopped) {
                timedQueues.add(queue);
            }
        }
    }

    /**
     * Ran by 'tick' once per second.
     */
    static void oncePerSecond() {
        SystemTimeScriptEvent.instance.checkTime();
        DeltaTimeScriptEvent.instance.checkTime();
    }

    /**
     * Counter for 'oncePerSecond'.
     */
    static int tMS = 0;

    /**
     * Call every 'tick' in the engine. (1/20th of a second on a standard engine.)
     *
     * @param ms_elapsed how many MS have actually elapsed. (50 on a standard engine).
     */
    public static void tick(int ms_elapsed) {
        DebugInternals.onTick();
        serverTimeMillis += ms_elapsed;
        currentTimeMillis = System.currentTimeMillis();
        currentTimeMonotonicMillis = CoreUtilities.monotonicMillis();
        runMainThreadTasks();
        TickScriptEvent.instance.ticks++;
        if (TickScriptEvent.instance.eventData.isEnabled) {
            TickScriptEvent.instance.fire();
        }
        RunLaterCommand.tickFutureRuns();
        tMS += ms_elapsed;
        while (tMS > 1000) {
            tMS -= 1000;
            oncePerSecond();
        }
        synchronized (scheduled) {
            for (int i = 0; i < scheduled.size(); i++) {
                Schedulable current = scheduled.get(i);
                try {
                    if (!current.tick((float) ms_elapsed / 1000)) {
                        scheduled.remove(i--);
                    }
                }
                catch (Throwable ex) {
                    Debug.echoError("DenizenCore - Scheduler item failed");
                    Debug.echoError(ex);
                    if (current instanceof OneTimeSchedulable) {
                        scheduled.remove(i--);
                    }
                }
            }
        }
        for (int i = 0; i < timedQueues.size(); i++) {
            TimedQueue queue = timedQueues.get(i);
            queue.tryRevolveOnce();
            if (queue.isStopped) {
                timedQueues.remove(i--);
            }
        }
        // Second pass, because the loop above is where scripts actually run, and so it's where async work gets dispatched and where async queues
        // ask the main thread for the commands they can't run themselves. Anything that arrived during it would otherwise sit until the next tick.
        // Serving it here instead costs a poll of an empty queue when there's nothing waiting, and saves a full tick of latency when there is.
        runMainThreadTasks();
    }
}
