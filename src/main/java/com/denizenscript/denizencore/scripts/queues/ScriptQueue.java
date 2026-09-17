package com.denizenscript.denizencore.scripts.queues;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.events.ScriptEvent;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.*;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.CommandExecutor;
import com.denizenscript.denizencore.tags.Attribute;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.tags.TagManager;
import com.denizenscript.denizencore.scripts.queues.core.TimedQueue;
import com.denizenscript.denizencore.utilities.*;
import com.denizenscript.denizencore.utilities.text.StringHolder;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.utilities.debugging.Debuggable;
import com.denizenscript.denizencore.utilities.scheduling.OneTimeSchedulable;
import com.denizenscript.denizencore.utilities.scheduling.Schedulable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
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

    protected String debugId;

    protected String debugIdPrefix;

    protected long debugIdNumeric;

    protected String[] debugIdWords;

    public String getDebugId() {
        if (debugId == null) {
            StringBuilder wordsColor = new StringBuilder();
            if (debugIdWords != null) {
                for (String word : debugIdWords) {
                    wordsColor.append(DenizenCore.implementation.getRandomColor()).append(word);
                }
            }
            debugId = (CoreConfiguration.queueIdPrefix ? "<LG>" + debugIdPrefix + "_" : "") + (CoreConfiguration.queueIdNumeric ? "<GR>" + debugIdNumeric + (CoreConfiguration.queueIdWords ? "<LG>_" : "") : "") + (CoreConfiguration.queueIdWords ? wordsColor.toString() : "");
        }
        return debugId;
    }

    /**
     * Whether this queue is locked to procedural commands only.
     */
    public boolean procedural = false;

    /**
     * Optional secondary debug output method.
     */
    public Consumer<String> debugOutput = null;

    private final ListQueue script_entries = new ListQueue(4);

    /**
     * Decides whether a loop runs its body once more, and does the per-iteration work (counter, definitions, debug header) when it does.
     * Returning false ends the loop, and the implementation is responsible for restoring whatever it saved before the first iteration.
     */
    public interface LoopIteration {
        boolean next(ScriptQueue queue, ScriptEntry owner);
    }

    /**
     * A loop in progress: the body to run, how far through it the queue has got, and the point in the queue at which the body is finished.
     * <p>
     * The body is never placed in the queue - the frame serves it one entry at a time from {@link #pointer}, and an iteration is a reset
     * of that pointer rather than a fresh copy of the body. {@link #tailSize} records how long the queue was underneath the loop, so
     * anything the body injects while it runs (an if block, an inject, a nested loop) sits above that mark and is consumed first.
     */
    public static class LoopFrame {
        public ScriptEntry owner;
        public List<ScriptEntry> body;
        public LoopIteration handler;
        public int tailSize;
        public int pointer;
    }

    public ArrayList<LoopFrame> loopFrames = null;

    public final void pushLoopFrame(ScriptEntry owner, List<ScriptEntry> body, LoopIteration handler) {
        if (loopFrames == null) {
            loopFrames = new ArrayList<>(2);
        }
        LoopFrame frame = new LoopFrame();
        frame.owner = owner;
        frame.body = body;
        frame.handler = handler;
        frame.tailSize = script_entries.size();
        loopFrames.add(frame);
        adoptSlots(body);
    }

    public final LoopFrame findLoopFrame(String commandName) {
        if (loopFrames == null) {
            return null;
        }
        for (int i = loopFrames.size() - 1; i >= 0; i--) {
            if (loopFrames.get(i).owner.getCommandName().equals(commandName)) {
                return loopFrames.get(i);
            }
        }
        return null;
    }

    /** Drops everything the queue still holds inside the given loop's body, including any loops nested in it, leaving the loop itself ready to decide on another iteration. */
    public final void skipToLoopFrameEnd(LoopFrame frame) {
        while (loopFrames.get(loopFrames.size() - 1) != frame) {
            loopFrames.remove(loopFrames.size() - 1);
        }
        while (script_entries.size() > frame.tailSize) {
            script_entries.removeFirst();
        }
        frame.pointer = frame.body.size();
    }

    public final void endLoopFrame(LoopFrame frame) {
        skipToLoopFrameEnd(frame);
        loopFrames.remove(loopFrames.size() - 1);
    }

    /** True if the queue has anything left to do - pending entries, or a loop that may still want another iteration. */
    public final boolean hasMoreWork() {
        return !script_entries.isEmpty() || (loopFrames != null && !loopFrames.isEmpty());
    }

    private boolean runLoopIteration(LoopFrame frame) {
        if (frame.body.isEmpty()) {
            // Cannot happen from any loop command, all of which refuse an empty body - but an empty body would spin here forever, so it is not left to trust.
            return false;
        }
        if (frame.owner.getResidingQueue() != this) {
            // The loop entry itself is long since executed and so was never moved by whatever replaced its queue (a 'wait' converting to
            // a timed queue, say). Its context still points at the old queue, and a 'while' condition reads its definitions through exactly that.
            frame.owner.setSendingQueue(this);
            frame.owner.updateContext();
        }
        // The queue is already this one: revolve, revolveOnceForce and runNow are the only ways in here, and every one of them
        // runs inside enterQueue(this). Setting it again would cost a thread-state lookup on every single turn of every loop
        // to write back the value already there. Only the debug context still has to be swapped, as errors and the loop's own
        // header line belong to the loop's owner rather than to the last entry that ran.
        Debug.ThreadState debugState = Debug.currentState();
        TagContext priorContext = debugState.context;
        try {
            debugState.context = frame.owner.getContext();
            if (!frame.handler.next(this, frame.owner)) {
                return false;
            }
            frame.pointer = 0;
            return true;
        }
        catch (Throwable ex) {
            Debug.echoError(frame.owner, "Woah! An exception has been called while looping!");
            Debug.echoError(frame.owner, ex);
            return false;
        }
        finally {
            debugState.context = priorContext;
        }
    }

    private ScriptEntry lastEntryExecuted = null;

    /**
     If this number is larger than DenizenCore.serverTimeMillis, the queue will delay execution of the next ScriptEntry.
     */
    private long delay_time = 0;

    public DefinitionStore definitions = new DefinitionStore();

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
    // Most queue/logic commands (define, if, foreach, while, repeat, choose, wait, ...) are async-safe,
    // as are the file and web commands (fileread, filewrite, filecopy, log, webget) - those touch nothing but files and the network,
    // so an async queue runs them itself rather than making the main thread do the waiting.
    //
    // Some commands only send something out and never report anything back - narrate, actionbar, playsound, playeffect, animate, showfake, ...
    // Those are handed to the main thread without the script waiting: their arguments, including all tags, are read on the script's own thread
    // at the moment the script reaches the line, and only the sending itself is left for the main thread to do when it next gets a chance.
    // The script carries on immediately, so a loop of them costs it nothing, and they still happen in the order the script wrote them.
    // A line the script is waiting for anyway ('~', or a 'save:' or 'if:' argument) is never handed over this way,
    // and neither is a narrate or actionbar using 'per_player', which has to parse its text once per target at the exact moment it sends.
    //
    // Note that most tags are safe to read from an async queue, but tags that read live server/world state may return slightly outdated data,
    // or in rare cases may error, as the main thread can be modifying that data at the same moment.
    //
    // The same caution applies to <context.*> inside an async block: the block inherits the event's context source, so those tags do read there.
    // Most events store their context at the moment they fire, which is safe to read from any thread.
    // A minority build it on demand out of a live world object, and reading one of those off-thread races the main thread for that data.
    // If a block needs event context, read it into a definition on the line before and use the definition inside.
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
    public ObjectTag getDefinitionObject(StringHolder definition) {
        if (definition == null) {
            return null;
        }
        if (CoreUtilities.contains(definition.str, '.')) {
            return getDefinitionObject(definition.str);
        }
        if (definition.str.startsWith("__")) {
            ObjectTag value = DenizenCore.implementation.getSpecialDef(definition.str, this);
            if (value != null) {
                return value;
            }
        }
        return definitions.getObject(definition);
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

    /**
     * Sets a definition using a key the caller has already prepared.
     * <p>
     * A name containing '.' falls back to the string path, so this is safe for any name.
     * Loops use it for their counter, which is written once per iteration and so paid for the key every time.
     */
    /** The key every loop writes its index under, prepared once instead of per iteration. */
    public static final StringHolder LOOP_INDEX_KEY = StringHolder.ofLowered("loop_index");

    public void addDefinition(StringHolder definition, ObjectTag value) {
        if (CoreUtilities.contains(definition.str, '.')) {
            // A sub-mapped name has to walk into sub-maps, which a flat key cannot do. Falling back here rather than at every
            // call site means a prepared key is safe to hand over for any name, and cannot silently write to the wrong place.
            addDefinition(definition.str, value);
            return;
        }
        if (definition.str.startsWith("__")) {
            if (DenizenCore.implementation.setSpecialDef(definition.str, this, value)) {
                return;
            }
        }
        if (trackedDefinitionWrites != null) {
            trackedDefinitionWrites.add(definition.str);
        }
        definitions.putObject(definition, value);
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
        return definitions.toMap();
    }

    public ObjectTag getDefinitionDeep(StringHolder[] path) {
        return definitions.getDeepObject(path);
    }

    public void addDefinitionDeep(StringHolder[] path, ObjectTag value) {
        if (trackedDefinitionWrites != null) {
            trackedDefinitionWrites.add(path[0].str);
        }
        definitions.putDeepObject(path, value);
    }

    /**
     * Installs a loop's own mutable holder under a definition name, and reports whether the holder really ended up stored there.
     * <p>
     * A '__'-prefixed name is not a definition at all: the implementation reads the value, applies it as a queue link (the player or
     * NPC a queue runs as), and stores nothing. A loop that then mutates its holder in place would set that link from the first
     * element of the list and never again, so such a name gets the resolved value written instead, and 'false' is returned to say
     * the write has to be repeated on every iteration.
     */
    public boolean installLoopHolder(DefinitionSlots table, int slot, StringHolder definition, LoopValue holder) {
        if (definition.str.startsWith("__")) {
            addDefinition(definition, holder.resolve());
            return false;
        }
        addDefinitionSlot(table, slot, definition, holder);
        return true;
    }

    public static TagManager.SlotBinding bindingFor(DefinitionSlots table, TagManager.SlotBinding existing, StringHolder definition) {
        if (existing != null && existing.table == table) {
            return existing;
        }
        int slot = table.lookup(definition.low);
        if (slot == DefinitionSlots.NO_SLOT) {
            slot = table.assign(definition.low);
        }
        return new TagManager.SlotBinding(table, slot);
    }

    public ObjectTag getDefinitionSlot(TagManager.ParseableTagPiece tag, StringHolder definition) {
        DefinitionSlots table = definitions.boundTable();
        if (table == null) {
            return getDefinitionObject(definition);
        }
        TagManager.SlotBinding binding = bindingFor(table, tag.binding, definition);
        tag.binding = binding;
        if (binding.slot == DefinitionSlots.NO_SLOT) {
            return getDefinitionObject(definition);
        }
        return definitions.getSlot(table, binding.slot, definition);
    }

    public ObjectTag getDefinitionSlot(Attribute.AttributeComponent component, StringHolder definition) {
        DefinitionSlots table = definitions.boundTable();
        if (table == null) {
            return getDefinitionObject(definition);
        }
        TagManager.SlotBinding binding = bindingFor(table, component.definitionBinding, definition);
        component.definitionBinding = binding;
        if (binding.slot == DefinitionSlots.NO_SLOT) {
            return getDefinitionObject(definition);
        }
        return definitions.getSlot(table, binding.slot, definition);
    }

    public void addDefinitionPlain(DefinitionSlots table, int slot, StringHolder definition, ObjectTag value) {
        if (trackedDefinitionWrites != null) {
            trackedDefinitionWrites.add(definition.str);
        }
        if (table == null || slot == DefinitionSlots.NO_SLOT) {
            definitions.putObject(definition, value);
            return;
        }
        definitions.putSlot(table, slot, definition, value);
    }

    public void addDefinitionSlot(DefinitionSlots table, int slot, StringHolder definition, ObjectTag value) {
        if (table == null || slot == DefinitionSlots.NO_SLOT) {
            addDefinition(definition, value);
            return;
        }
        if (trackedDefinitionWrites != null) {
            trackedDefinitionWrites.add(definition.str);
        }
        definitions.putSlot(table, slot, definition, value);
    }

    public static int slotFor(ScriptEntry owner, StringHolder definition) {
        DefinitionSlots table = owner.internal.slotTable;
        return table == null ? DefinitionSlots.NO_SLOT : table.lookup(definition.low);
    }

    public static int tableSizeFor(ScriptEntry owner) {
        DefinitionSlots table = owner.internal.slotTable;
        return table == null ? 0 : table.size();
    }

    public final ScriptEntry getLastEntryExecuted() {
        return lastEntryExecuted;
    }

    public final void clear() {
        script_entries.clear();
        if (loopFrames != null) {
            loopFrames.clear();
        }
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
        debugIdWords = null;
        StringBuilder idBuilder = new StringBuilder(prefix.length() + 32);
        if (CoreConfiguration.queueIdPrefix) {
            idBuilder.append(prefix).append('_');
        }
        if (CoreConfiguration.queueIdNumeric) {
            idBuilder.append(numericId);
            if (CoreConfiguration.queueIdWords) {
                idBuilder.append('_');
            }
        }
        if (CoreConfiguration.queueIdWords) {
            int size = QueueWordList.FinalWordList.size();
            Random random = CoreUtilities.getRandom();
            String[] words = new String[2 + depth];
            for (int i = 0; i < words.length; i++) {
                words[i] = QueueWordList.FinalWordList.get(random.nextInt(size));
                idBuilder.append(words[i]);
            }
            debugIdWords = words;
        }
        id = idBuilder.toString();
        debugIdPrefix = prefix;
        debugIdNumeric = numericId;
        debugId = null;
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
        newQueue.debugIdPrefix = debugIdPrefix;
        newQueue.debugIdNumeric = debugIdNumeric;
        newQueue.debugIdWords = debugIdWords;
        newQueue.debugOutput = this.debugOutput;
        for (ScriptEntry entry : getEntries()) {
            entry = entry.clone();
            entry.entryData.scriptEntry = entry;
            entry.setInstant(true);
            entry.setSendingQueue(newQueue);
            entry.updateContext();
            newQueue.appendPending(entry);
        }
        if (loopFrames != null && !loopFrames.isEmpty()) {
            newQueue.loopFrames = new ArrayList<>(loopFrames);
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
        if (!newQueue.hasMoreWork() && newQueue.holdingOn == null) {
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
        Debug.echoDebug(this, "<O>" + message.replace("<QUEUE>", getDebugId() + "<O>"));
    }

    public final void start() {
        start(true);
    }

    public final void start(boolean doBasicConfig) {
        if (is_started) {
            return;
        }
        if (!hasMoreWork() && holdingOn == null) {
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
        int baseSize = getQueueSize(), baseFrames = loopFrames == null ? 0 : loopFrames.size();
        injectEntriesAtStart(entries);
        ScriptQueue prior = ScriptEngine.enterQueue(this);
        try {
            while (getQueueSize() > baseSize || (loopFrames != null && loopFrames.size() > baseFrames)) {
                ScriptEntry next = peekPending();
                if (next != null) {
                    next.setInstant(true);
                }
                holdingOn = null;
                ScriptEngine.revolveOnceForce(this);
            }
        }
        finally {
            ScriptEngine.leaveQueue(prior);
        }
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
            // Repeated crossings are what's worth reporting. A single one is just the cost of reading one live value, which nearly every script does,
            // so saying anything about it would put two extra lines under every '- async:' one-liner for no reason.
            // Exact numbers are available whenever they're wanted, via <@link tag QueueTag.async_stats>.
            if (mainThreadWaitCount > 1) {
                long waitMs = mainThreadWaitNanos / 1000000;
                long waitPercent = totalMs > 0 ? waitMs * 100 / totalMs : 0;
                queueDebug("Queue '<QUEUE>' waited on the main thread <A>" + waitMs + "<O>ms across <A>" + mainThreadWaitCount + "<O> hand-off(s)"
                        + (totalMs > 0 ? " - <A>" + waitPercent + "%<O> of its runtime" : "")
                        + (waitPercent >= 50 ? ". Most of this queue's life was spent waiting, so it gains little from running off-thread." : "."));
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
        ArrayList<LoopFrame> frames = loopFrames;
        if (frames != null && !frames.isEmpty()) {
            LoopFrame frame = frames.get(frames.size() - 1);
            if (script_entries.size() == frame.tailSize) {
                if (frame.pointer < frame.body.size()) {
                    ScriptEntry entry = frame.body.get(frame.pointer++);
                    entry.resetForReuse();
                    return entry;
                }
                // The body is spent, so decide on another turn right here. This used to fall through to getNextAcrossFrames,
                // which re-read the frame, the queue length and the pointer from scratch - and then again after the iteration.
                // A body of one line pays that on EVERY turn, because the fast path above serves exactly one entry per turn.
                if (runLoopIteration(frame)) {
                    if (script_entries.size() == frame.tailSize && frame.pointer < frame.body.size()) {
                        ScriptEntry entry = frame.body.get(frame.pointer++);
                        entry.resetForReuse();
                        return entry;
                    }
                }
                else {
                    // The loop is over. Dropped here rather than below so that the shared walk never runs the iteration twice.
                    frames.remove(frames.size() - 1);
                }
            }
            return getNextAcrossFrames();
        }
        return script_entries.isEmpty() ? null : script_entries.removeFirst();
    }

    private ScriptEntry getNextAcrossFrames() {
        while (loopFrames != null && !loopFrames.isEmpty()) {
            LoopFrame frame = loopFrames.get(loopFrames.size() - 1);
            int size = script_entries.size();
            if (size > frame.tailSize) {
                break;
            }
            if (size == frame.tailSize && frame.pointer < frame.body.size()) {
                ScriptEntry entry = frame.body.get(frame.pointer++);
                entry.resetForReuse();
                return entry;
            }
            // Below the mark means something cut the queue back past this loop, eg a goto out of it - the loop is over either way, but it doesn't get another turn.
            if (size < frame.tailSize || !runLoopIteration(frame)) {
                loopFrames.remove(loopFrames.size() - 1);
            }
        }
        if (!script_entries.isEmpty()) {
            return script_entries.removeFirst();
        }
        else {
            return null;
        }
    }

    public final void addEntries(List<ScriptEntry> entries) {
        adoptSlots(entries);
        script_entries.addAll(entries);
    }

    private void adoptSlots(List<ScriptEntry> entries) {
        if (!entries.isEmpty()) {
            DefinitionSlots table = entries.get(0).internal.slotTable;
            if (table != null) {
                definitions.bindIfUnbound(table);
            }
        }
    }

    /** Adds one already-prepared entry to the end of what is queued. */
    public final void appendPending(ScriptEntry entry) {
        script_entries.add(entry);
    }

    /**
     * The entry this queue will run next, without taking it, or null when nothing is queued.
     * <p>
     * A loop body is served from its frame rather than from the queue, so inside one this is the body's next entry, and a reader that has
     * reached the end of the body sees nothing rather than the entries waiting past the loop.
     */
    public final ScriptEntry peekPending() {
        LoopFrame frame = servingFrame();
        if (frame != null) {
            return frame.pointer < frame.body.size() ? frame.body.get(frame.pointer) : null;
        }
        return script_entries.isEmpty() ? null : script_entries.get(0);
    }

    /** Takes the next queued entry off the queue and returns it, or null when nothing is queued. */
    public final ScriptEntry consumePending() {
        LoopFrame frame = servingFrame();
        if (frame != null) {
            return frame.pointer < frame.body.size() ? frame.body.get(frame.pointer++) : null;
        }
        return script_entries.isEmpty() ? null : script_entries.removeFirst();
    }

    /** The frame the next entry will be served from, or null when it comes from the queue itself. */
    private LoopFrame servingFrame() {
        if (loopFrames == null || loopFrames.isEmpty()) {
            return null;
        }
        LoopFrame frame = loopFrames.get(loopFrames.size() - 1);
        return script_entries.size() == frame.tailSize ? frame : null;
    }

    /**
     * Visits the entries queued to run, in the order they will run.
     * <p>
     * Narrower than {@link #forEachPendingEntry}, which also reaches a loop body that is being held between iterations and the
     * loop owners themselves. This one is what a reader of the queue sees as "waiting to run".
     */
    public final void forEachQueuedEntry(Consumer<ScriptEntry> action) {
        findUpcoming(entry -> {
            action.accept(entry);
            return false;
        });
    }

    /**
     * Walks what the queue will run, in the order it will run it, and returns the first entry the test accepts.
     * <p>
     * The stream is not the pending list: a loop's unserved body sits between the entries injected above the loop and the entries
     * waiting below its mark, and every frame on the stack contributes the part of its body it has not reached yet.
     */
    public final ScriptEntry findUpcoming(Predicate<ScriptEntry> test) {
        int index = 0, size = script_entries.size();
        if (loopFrames != null) {
            for (int f = loopFrames.size() - 1; f >= 0; f--) {
                LoopFrame frame = loopFrames.get(f);
                int above = size - frame.tailSize;
                while (index < above) {
                    ScriptEntry entry = script_entries.get(index++);
                    if (test.test(entry)) {
                        return entry;
                    }
                }
                for (int i = frame.pointer; i < frame.body.size(); i++) {
                    ScriptEntry entry = frame.body.get(i);
                    if (test.test(entry)) {
                        return entry;
                    }
                }
            }
        }
        while (index < size) {
            ScriptEntry entry = script_entries.get(index++);
            if (test.test(entry)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Drops whatever the queue would run next until the test accepts the entry in front, which is left in place to run.
     * <p>
     * Skipping past a loop ends it - the frame is dropped rather than asked for another iteration, which is what jumping out of a loop means.
     */
    public final boolean skipUpcomingUntil(Predicate<ScriptEntry> test) {
        while (true) {
            LoopFrame frame = loopFrames == null || loopFrames.isEmpty() ? null : loopFrames.get(loopFrames.size() - 1);
            if (frame != null && script_entries.size() <= frame.tailSize) {
                if (script_entries.size() < frame.tailSize || frame.pointer >= frame.body.size()) {
                    loopFrames.remove(loopFrames.size() - 1);
                }
                else if (test.test(frame.body.get(frame.pointer))) {
                    return true;
                }
                else {
                    frame.pointer++;
                }
            }
            else if (!script_entries.isEmpty()) {
                if (test.test(script_entries.get(0))) {
                    return true;
                }
                script_entries.removeFirst();
            }
            else {
                return false;
            }
        }
    }

    /** How many entries the queue will still run, counting the parts of loop bodies that have not been served yet. */
    public final int pendingEntryCount() {
        int count = script_entries.size();
        if (loopFrames != null) {
            for (int i = 0; i < loopFrames.size(); i++) {
                LoopFrame frame = loopFrames.get(i);
                count += frame.body.size() - frame.pointer;
            }
        }
        return count;
    }

    /**
     * The raw list of entries waiting on the queue itself, which is not everything the queue will run: a loop serves its body from its
     * frame, so the part of a body that has not been reached yet is not in here, and neither is a nested loop's.
     * <p>
     * Nothing in the engine uses this - {@link #findUpcoming} walks what will actually run, in order, and {@link #forEachPendingEntry}
     * reaches every entry that may still run. It stays for third-party addons, and it is a trap for them for exactly the reason above.
     */
    public final ListQueue getEntries() {
        return script_entries;
    }

    /**
     * Visits every entry that may still run, which is more than {@link #getEntries()}: a loop's body is only on the queue while that
     * iteration is running, and between iterations it is held by the frame instead. Anything that rewrites pending entries - the
     * player or NPC a queue is linked to, above all - has to reach the bodies too, or a loop keeps running against the old value.
     * <p>
     * A loop's owner is rebuilt here because it is the one entry that keeps using its context without running again - every other entry
     * passes {@link ScriptEngine#prepareEntry} first, which rebuilds a context whose data was changed by this visit.
     */
    public final void forEachPendingEntry(Consumer<ScriptEntry> action) {
        for (ScriptEntry entry : script_entries) {
            visitPending(entry, action);
        }
        if (loopFrames != null) {
            for (int i = 0; i < loopFrames.size(); i++) {
                LoopFrame frame = loopFrames.get(i);
                action.accept(frame.owner);
                frame.owner.updateContext();
                // The owner's own held body is this frame's body, so walking the frame covers it - hence 'accept' above rather than 'visitPending'.
                for (int j = 0; j < frame.body.size(); j++) {
                    visitPending(frame.body.get(j), action);
                }
            }
        }
    }

    /**
     * Applies the action to one entry and to every block body that entry is holding for reuse.
     * <p>
     * A block's body is cloned once and kept on the entry that owns it ({@link ScriptEntry#inlinedBody} and, for an if/else chain,
     * {@link ScriptEntry#inlinedBranches}) rather than rebuilt per turn. On the second and later turns of an enclosing loop those
     * clones are the entries that will actually run, and until the block is entered again they hang off that one entry - reachable
     * from neither the queue nor any loop frame. An action that rewrites what pending entries run as has to reach them there, or a
     * nested block keeps running against the value its first turn was built with.
     */
    private static void visitPending(ScriptEntry entry, Consumer<ScriptEntry> action) {
        action.accept(entry);
        List<ScriptEntry> body = entry.inlinedBody;
        if (body != null) {
            for (int i = 0; i < body.size(); i++) {
                visitPending(body.get(i), action);
            }
        }
        List<List<ScriptEntry>> branches = entry.inlinedBranches;
        if (branches != null) {
            for (int i = 0; i < branches.size(); i++) {
                // The list is indexed by branch number and padded with nulls, so a branch that has not been taken yet is a hole.
                List<ScriptEntry> branch = branches.get(i);
                if (branch == null) {
                    continue;
                }
                for (int j = 0; j < branch.size(); j++) {
                    visitPending(branch.get(j), action);
                }
            }
        }
    }

    public final void injectEntriesAtStart(List<ScriptEntry> entries) {
        adoptSlots(entries);
        script_entries.addAllToStart(entries);
    }

    /** Drops the queue's own first entry. Blind to a loop body being served from its frame - see {@link #getEntries()}, and prefer {@link #consumePending()}. */
    public final boolean removeFirst() {
        if (script_entries.isEmpty()) {
            return false;
        }
        script_entries.removeFirst();
        return true;
    }

    /** Reads by position within the queue's own entries. Blind to a loop body being served from its frame - see {@link #getEntries()}. */
    public final ScriptEntry getEntry(int position) {
        if (script_entries.size() < position) {
            return null;
        }
        return script_entries.get(position);
    }

    public final void injectEntryAtStart(ScriptEntry entry) {
        script_entries.injectAtStart(entry);
    }

    /** How many entries are on the queue itself. Script-visible counts want {@link #pendingEntryCount()}, which also counts unserved loop bodies. */
    public final int getQueueSize() {
        return script_entries.size();
    }

    public final boolean queueNeedsToDebug() {
        return Debug.shouldDebug(this);
    }

    @Override
    public boolean shouldDebug() {
        if (lastEntryExecuted != null) {
            return lastEntryExecuted.shouldDebug();
        }
        ScriptEntry next = peekPending();
        return next == null || next.shouldDebug();
    }

    @Override
    public String toString() {
        return id;
    }
}
