package com.denizenscript.denizencore.scripts.queues;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.events.ScriptEvent;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.*;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.CommandExecutor;
import com.denizenscript.denizencore.scripts.queues.core.TimedQueue;
import com.denizenscript.denizencore.utilities.*;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.utilities.debugging.Debuggable;
import com.denizenscript.denizencore.utilities.scheduling.OneTimeSchedulable;
import com.denizenscript.denizencore.utilities.scheduling.Schedulable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public abstract class ScriptQueue implements Debuggable, DefinitionProvider {

    protected static final AtomicLong total_queues = new AtomicLong();

    public static String getStats() {
        String c1 = DenizenCore.implementation.applyDebugColors("<W>"), c2 = DenizenCore.implementation.applyDebugColors("<A>");
        StringBuilder stats = new StringBuilder();
        TreeSet<Map.Entry<Long, String>> statsSet = new TreeSet<>(Comparator.comparingLong(Map.Entry::getKey));
        for (ScriptEvent event : ScriptEvent.events) {
            if (event.eventData.stats_fires > 0) {
                stats.setLength(0);
                stats.append(c1).append("Event '").append(event.getName()).append(c1).append("' ran ").append(c2).append(event.eventData.stats_fires)
                        .append(c1).append(" times (").append(c2).append(event.eventData.stats_scriptFires).append(c1).append(" script fires)")
                        .append(c1).append(", totalling ").append(c2).append((float) event.eventData.stats_nanoTimes / 1000000f)
                        .append(c1).append("ms, averaging ").append(c2).append((float) event.eventData.stats_nanoTimes / 1000000f / (float) event.eventData.stats_fires)
                        .append(c1).append("ms per event or ").append(c2).append(((float) event.eventData.stats_nanoTimes / 1000000f / (float) event.eventData.stats_scriptFires)).append(c1).append("ms per script.\n");
                statsSet.add(new HashMap.SimpleEntry<>(event.eventData.stats_nanoTimes, stats.toString()));
            }
        }
        return "Total number of queues created: "
                + total_queues.get()
                + ", currently active queues: "
                + allQueues.size() + ",\n" + String.join("", statsSet.stream().map(Map.Entry::getValue).collect(Collectors.joining()));
    }

    public static ListTag getStatsRawData() {
        return new ListTag(ScriptEvent.events, event -> event.eventData.stats_fires > 0, event -> {
            MapTag map = new MapTag();
            map.putObject("name", new ElementTag(event.getName(), true));
            map.putObject("total_fires", new ElementTag(event.eventData.stats_fires));
            map.putObject("script_fires", new ElementTag(event.eventData.stats_scriptFires));
            map.putObject("total_time", new DurationTag(event.eventData.stats_nanoTimes / 1000000.0));
            return map;
        });
    }

    public static ScriptQueue getExistingQueue(String id) {
        return allQueues.get(id);
    }

    /** All currently running queues. Concurrent, as queues may be started/stopped from async threads. */
    protected static Map<String, ScriptQueue> allQueues = new ConcurrentHashMap<>();

    public static Collection<ScriptQueue> getQueues() {
        return allQueues.values();
    }

    public static boolean queueExists(String id) {
        return allQueues.containsKey(id);
    }

    public String id;

    public String debugId;

    /**
     * Whether this queue is locked to procedural commands only.
     */
    public boolean procedural = false;

    /**
     * Optional secondary debug output method.
     */
    public Consumer<String> debugOutput = null;

    public final ListQueue script_entries = new ListQueue(4);

    private ScriptEntry lastEntryExecuted = null;

    /**
     If this number is larger than DenizenCore.serverTimeMillis, the queue will delay execution of the next ScriptEntry.
     */
    private long delay_time = 0;

    public MapTag definitions = new MapTag();

    /**
     * If set, every definition written on this queue records its top-level name here.
     * Used by a detached <@link command async> block to merge just the definitions it actually wrote back into the queue that started it,
     * rather than overwriting that queue's whole set (which would discard anything it defined while the block was running).
     * Null (the default) means no tracking, at zero cost.
     */
    public Set<String> trackedDefinitionWrites = null;

    public ListTag determinations = null;

    public ScriptTag script;

    public ContextSource contextSource = null;

    public DeterminationTarget determinationTarget = null;

    public ScriptQueue replacementQueue = null;

    public volatile boolean is_stopping = false;

    public volatile boolean isStopped = false;

    public volatile ScriptEntry holdingOn = null;

    /**
     * If set true, the queue will simply freeze and wait when it's empty.
     * Otherwise (set false), it will fully stop and remove itself when empty.
     */
    public boolean waitWhenEmpty = false;

    public volatile boolean is_started;

    public long startTime = 0;

    public long startTimeMilli = 0;

    private Runnable callback = null;

    public long numericId;

    protected ScriptQueue(String id) {
        numericId = total_queues.getAndIncrement();
        this.id = id;
        generateId(id, numericId, 0);
    }

    // <--[language]
    // @name Async Queues
    // @group Script Command System
    // @description
    // Normally, all script queues run on the server's main thread: one command at a time, in lock-step with the rest of the server.
    // An "async queue" instead runs its commands on a separate thread, meaning the script's own logic (tags, math, text handling, ...)
    // does not consume main thread time, and a slow script cannot lag the server.
    //
    // Async queues are created by <@link command async>, by the "async" argument of <@link command run>,
    // or by the DenizenCore API (see 'ScriptUtilities.createAndStartQueueAsync').
    //
    // Commands are marked internally as async-safe or not. Any command that is not async-safe is automatically handed to the main thread
    // and the async queue simply waits for it to complete - so scripts remain correct, they just don't gain any speed from those commands.
    // Most queue/logic commands (define, if, foreach, while, repeat, choose, wait, ...) are async-safe.
    //
    // Note that most tags are safe to read from an async queue, but tags that read live server/world state may return slightly outdated data,
    // or in rare cases may error, as the main thread can be modifying that data at the same moment.
    //
    // See also <@link language ~waitable> for the simpler option of just moving a single command off-thread with the "~" prefix.
    // -->

    /**
     * How many times this queue has had to stop and wait for the main thread, and how long that has cost in total (nanoseconds).
     * <p>
     * This is the number that decides whether running a script off-thread was worth doing: a queue that spends most of its life here
     * is not gaining anything from async, it's just paying for the privilege. See <@link tag QueueTag.async_stats>.
     * Only the queue's own thread writes these, so plain volatile is enough.
     */
    public volatile long mainThreadWaitCount, mainThreadWaitNanos;

    /** Records time this queue spent waiting on the main thread. Pass null to attribute it to whatever queue is running on this thread, if any. */
    public static void recordMainThreadWait(ScriptQueue queue, long nanos) {
        if (queue == null) {
            queue = CommandExecutor.getCurrentQueue();
        }
        if (queue != null) {
            queue.mainThreadWaitCount++;
            queue.mainThreadWaitNanos += nanos;
        }
    }

    /** Returns true if this queue runs its script entries on a thread other than the main thread. */
    public boolean isAsync() {
        return false;
    }

    /** The thread that currently executes this queue's script entries, or null if it isn't currently running anywhere. */
    public Thread getOwnerThread() {
        return DenizenCore.MAIN_THREAD;
    }

    /** Returns true if the calling thread is the thread that owns/executes this queue. */
    public boolean isOnOwnerThread() {
        return DenizenCore.isMainThread();
    }

    /**
     * Runs a task on the thread that owns this queue (immediately if already on it, otherwise as soon as that thread is available).
     * Use this whenever an async operation needs to hand a result back to a queue.
     */
    public void runOnQueueThread(Runnable run) {
        DenizenCore.runOnMainThread(run);
    }

    /** Wakes this queue up if it's sleeping between revolutions. No-op for main thread queues (which tick with the server). */
    public void wake() {
    }

    public final void setContextSource(ContextSource source) {
        contextSource = source;
    }

    @Override
    public ObjectTag getDefinitionObject(String definition) {
        if (definition == null) {
            return null;
        }
        if (definition.startsWith("__")) {
            ObjectTag value = DenizenCore.implementation.getSpecialDef(definition, this);
            if (value != null) {
                return value;
            }
        }
        return definitions.getDeepObject(definition);
    }

    @Override
    public void addDefinition(String definition, ObjectTag value) {
        if (definition.startsWith("__")) {
            if (DenizenCore.implementation.setSpecialDef(definition, this, value)) {
                return;
            }
        }
        if (trackedDefinitionWrites != null) {
            // Sub-mapped names ('x.y.z') are recorded by their root, as that's the whole value that gets handed back.
            int dotIndex = definition.indexOf('.');
            trackedDefinitionWrites.add(dotIndex == -1 ? definition : definition.substring(0, dotIndex));
        }
        definitions.putDeepObject(definition, value);
    }

    @Override
    public String getDefinition(String definition) {
        if (definition == null) {
            return null;
        }
        return CoreUtilities.stringifyNullPass(getDefinitionObject(definition));
    }

    @Override
    public boolean hasDefinition(String definition) {
        return getDefinitionObject(definition) != null;
    }

    @Override
    public void addDefinition(String definition, String value) {
        addDefinition(definition, new ElementTag(value));
    }

    @Override
    public void removeDefinition(String definition) {
        addDefinition(definition, (ObjectTag) null);
    }

    @Override
    public MapTag getAllDefinitions() {
        return definitions;
    }

    public final ScriptEntry getLastEntryExecuted() {
        return lastEntryExecuted;
    }

    public final void clear() {
        script_entries.clear();
    }

    public void delayUntil(long delayTime) {
        this.delay_time = delayTime;
    }

    public final void generateId(String prefix, long numericId, int depth) {
        if (prefix.startsWith("FORCE:")) {
            id = prefix.substring("FORCE:".length());
            debugId = id;
            return;
        }
        // DUUIDs v2.5
        int size = QueueWordList.FinalWordList.size();
        Random random = CoreUtilities.getRandom();
        String wordsRaw = "", wordsColor = "";
        if (CoreConfiguration.queueIdWords) {
            String wordOne = QueueWordList.FinalWordList.get(random.nextInt(size));
            String wordTwo = QueueWordList.FinalWordList.get(random.nextInt(size));
            String colorOne = DenizenCore.implementation.getRandomColor();
            String colorTwo = DenizenCore.implementation.getRandomColor();
            wordsRaw = wordOne + wordTwo;
            wordsColor = colorOne + wordOne + colorTwo + wordTwo;
            for (int i = 0; i < depth; i++) {
                String wordThree = QueueWordList.FinalWordList.get(random.nextInt(size));
                String colorThree = DenizenCore.implementation.getRandomColor();
                wordsRaw += wordThree;
                wordsColor += colorThree + wordThree;
            }
        }
        id = (CoreConfiguration.queueIdPrefix ? prefix + "_" : "") + (CoreConfiguration.queueIdNumeric ? numericId + (CoreConfiguration.queueIdWords ? "_" : "") : "") + (CoreConfiguration.queueIdWords ? wordsRaw : "");
        debugId = (CoreConfiguration.queueIdPrefix ? "<LG>" + prefix + "_" : "") + (CoreConfiguration.queueIdNumeric ? "<GR>" + numericId + (CoreConfiguration.queueIdWords ? "<LG>_" : "") : "") + (CoreConfiguration.queueIdWords ? wordsColor : "");
        if (!CoreConfiguration.queueIdNumeric && queueExists(id)) {
            if (!CoreConfiguration.queueIdWords) { // Prevent infinite loop from invalid config
                Debug.echoError("WARNING: Configuration invalid! Trying to generate queue IDs with neither numbers nor words! Resetting to both enabled.");
                CoreConfiguration.queueIdNumeric = true;
                CoreConfiguration.queueIdWords = true;
            }
            generateId(prefix, numericId, depth + 1);
        }
    }

    /**
     * Converts any queue type to a timed queue.
     *
     * @param delay how long to delay initially.
     * @return the newly created queue.
     */
    public final TimedQueue forceToTimed(TimedQueue.DelayTracker delay) {
        queueDebug("Forcing queue '<QUEUE>' into a timed queue...");
        Runnable r = callback;
        callback = null;
        TimedQueue newQueue = new TimedQueue("FORCE:" + id, 0);
        replacementQueue = newQueue;
        stopSilent();
        newQueue.id = id;
        newQueue.debugId = debugId;
        newQueue.debugOutput = this.debugOutput;
        for (ScriptEntry entry : getEntries()) {
            entry = entry.clone();
            entry.entryData.scriptEntry = entry;
            entry.setInstant(true);
            entry.setSendingQueue(newQueue);
            entry.updateContext();
            newQueue.script_entries.add(entry);
        }
        newQueue.determinations = determinations;
        newQueue.definitions = definitions.duplicate();
        newQueue.setContextSource(contextSource);
        newQueue.determinationTarget = determinationTarget;
        newQueue.setLastEntryExecuted(getLastEntryExecuted());
        clear();
        newQueue.delay = delay;
        newQueue.startTime = startTime;
        newQueue.startTimeMilli = startTimeMilli;
        newQueue.script = script;
        newQueue.holdingOn = holdingOn;
        newQueue.callBack(r);
        if (newQueue.script_entries.isEmpty() && newQueue.holdingOn == null) {
            newQueue.stop();
        }
        else {
            newQueue.start(false);
        }
        return newQueue;
    }

    public abstract void onStart();

    public String getName() {
        return "UnidentifiedQueueType";
    }

    public final void queueDebug(String message) {
        Debug.echoDebug(this, "<O>" + message.replace("<QUEUE>", debugId + "<O>"));
    }

    public final void start() {
        start(true);
    }

    public final void start(boolean doBasicConfig) {
        if (is_started) {
            return;
        }
        if (script_entries.isEmpty() && holdingOn == null) {
            return;
        }
        // Note: a queue started from a non-main thread deliberately runs on that thread rather than being deferred to the main thread.
        // Callers rely on a started queue having actually run (the 'proc' tag reads its determination immediately after starting it),
        // and safety is handled per-command and per-tag instead: anything that isn't async-safe is handed to the main thread as it's reached.
        if (CoreConfiguration.verifyThreadMatches && !isOnOwnerThread()) {
            Debug.verboseLog("Queue '" + id + "' is being started from thread '" + Thread.currentThread().getName() + "' and will run there.");
        }
        allQueues.put(id, this);
        is_started = true;
        long delay = delay_time - DenizenCore.serverTimeMillis;
        boolean is_delayed = delay > 0;
        if (doBasicConfig) {
            script = script_entries.get(0).getScript();
            startTime = System.nanoTime();
            startTimeMilli = CoreUtilities.monotonicMillis();
            String name = getName();
            if (queueNeedsToDebug()) {
                if (is_delayed) {
                    queueDebug("Delaying " + name + " '<QUEUE>'" + " for '" + new DurationTag(((double) delay) / 1000f).identify() + "'...");
                }
                else {
                    queueDebug("Starting " + name + " '<QUEUE>'" + DenizenCore.implementation.queueHeaderInfo(script_entries.get(0)) + "...");
                }
            }
        }
        if (is_delayed) {
            Schedulable schedulable = new OneTimeSchedulable(this::onStart, ((float) delay) / 1000);
            DenizenCore.schedule(schedulable);

        }
        else {
            onStart();
        }
    }

    /**
     * Immediately runs a list of entries within the script queue.
     * Primarily used as a simple method of instant command injection.
     *
     * @param entries the entries to be run.
     */
    public final void runNow(List<ScriptEntry> entries) {
        ScriptEntry nextup = getQueueSize() > 0 ? getEntry(0) : null;
        injectEntriesAtStart(entries);
        while (getQueueSize() > 0 && getEntry(0) != nextup) {
            getEntry(0).setInstant(true);
            holdingOn = null;
            ScriptEngine.revolveOnceForce(this);
        }
        return;
    }

    /**
     * Adds a runnable to call back when the queue is completed.
     *
     * @param r the Runnable to call back
     */
    public final void callBack(Runnable r) {
        callback = r;
    }

    private void stopSilent() {
        is_stopping = true;
        allQueues.remove(id);
        is_started = false;
        isStopped = true;
    }

    public final void stop() {
        if (is_stopping) {
            return;
        }
        if (isAsync() && !isOnOwnerThread()) {
            // An async queue must only be stopped by its own worker thread, so it can't be torn down mid-command.
            // Other queue types stop wherever they're told to - deferring that would break callers that expect a stopped queue immediately (eg procedure scripts).
            runOnQueueThread(this::stop);
            return;
        }
        if (queueNeedsToDebug()) {
            long totalMs = (System.nanoTime() - startTime) / 1000000;
            queueDebug("Completing queue '<QUEUE>' in <A>" + totalMs + "<O>ms.");
            if (mainThreadWaitCount > 0) {
                // The single most useful number for anyone using async: how much of the queue's life was spent not running.
                long waitMs = mainThreadWaitNanos / 1000000;
                queueDebug("Queue '<QUEUE>' waited on the main thread <A>" + waitMs + "<O>ms across <A>" + mainThreadWaitCount + "<O> hand-off(s)"
                        + (totalMs > 0 ? " - <A>" + (waitMs * 100 / totalMs) + "%<O> of its runtime" : "")
                        + ". If that share is large, this script gains little from running off-thread.");
            }
        }
        if (callback != null) {
            callback.run();
        }
        stopSilent();
    }

    public final void setLastEntryExecuted(ScriptEntry entry) {
        lastEntryExecuted = entry;
        if (entry != null) {
            entry.queue = this;
        }
    }

    public final ScriptEntry getNext() {
        if (!script_entries.isEmpty()) {
            return script_entries.removeFirst();
        }
        else {
            return null;
        }
    }

    public final void addEntries(List<ScriptEntry> entries) {
        script_entries.addAll(entries);
    }

    public final ListQueue getEntries() {
        return script_entries;
    }

    public final void injectEntriesAtStart(List<ScriptEntry> entries) {
        script_entries.addAllToStart(entries);
    }

    public final boolean removeFirst() {
        if (script_entries.isEmpty()) {
            return false;
        }
        script_entries.removeFirst();
        return true;
    }

    public final ScriptEntry getEntry(int position) {
        if (script_entries.size() < position) {
            return null;
        }
        return script_entries.get(position);
    }

    public final void injectEntryAtStart(ScriptEntry entry) {
        script_entries.injectAtStart(entry);
    }

    public final int getQueueSize() {
        return script_entries.size();
    }

    public final boolean queueNeedsToDebug() {
        return Debug.shouldDebug(this);
    }

    @Override
    public boolean shouldDebug() {
        return (lastEntryExecuted != null ? lastEntryExecuted.shouldDebug() : script_entries.get(0).shouldDebug());
    }

    @Override
    public String toString() {
        return id;
    }
}
