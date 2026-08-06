package com.denizenscript.denizencore.utilities;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.DurationTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.ScriptEntryData;
import com.denizenscript.denizencore.scripts.containers.ScriptContainer;
import com.denizenscript.denizencore.scripts.queues.ContextSource;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.scripts.queues.core.AsyncQueue;
import com.denizenscript.denizencore.scripts.queues.core.InstantQueue;
import com.denizenscript.denizencore.scripts.queues.core.TimedQueue;
import com.denizenscript.denizencore.tags.ParseableTag;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.tags.TagManager;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.utilities.debugging.Debuggable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * General central API class for simple Denizen calls from external sources.
 * Generally, external addons should register events/tags/commands/etc. instead of using this, but it serves as a 'good enough' entry point for lazy/basic usages.
 * <p>
 * All 'createAndStartQueue' methods here may be called from any thread.
 * A normal (main thread) queue requested from another thread is built immediately but starts running at the beginning of the next tick;
 * an async queue (see <@link language Async Queues>) starts running on its own thread right away.
 */
public class ScriptUtilities {

    /**
     * Runs any script.
     * @param container the script to run.
     * @param path the path within the container to run (or null for default).
     * @param data the player/npc/other data to attach (or null for empty).
     * @return the new queue, or null if not possible.
     */
    public static ScriptQueue createAndStartQueue(ScriptContainer container, String path, ScriptEntryData data) {
        return createAndStartQueue(container, path, data, null, null, null, null, null, null);
    }

    /**
     * Runs any script.
     * @param container the script to run.
     * @param path the path within the container to run (or null for default).
     * @param data the player/npc/other data to attach (or null for empty).
     * @param context the provider for &lt;context&gt; data (or null for none).
     * @param configure a function to configure the queue (eg add definitions) before starting (or null for none).
     * @param speed a specific speed to apply (or null for default).
     * @param id the script ID to force (or null for default).
     * @param definitions definitions to add (or null for none).
     * @param debugDefinitions the object to debug added definitions with (or null for no debug).
     * @return the new queue, or null if not possible.
     */
    public static ScriptQueue createAndStartQueue(ScriptContainer container, String path, ScriptEntryData data,
                                                  ContextSource context, Consumer<ScriptQueue> configure,
                                                  DurationTag speed, String id, ListTag definitions, Debuggable debugDefinitions) {
        return createAndStartQueue(container, path, data, context, configure, speed, id, definitions, debugDefinitions, false);
    }

    /**
     * Runs any script on a separate thread, as an <@link language Async Queues>.
     * Safe to call from any thread.
     * @param container the script to run.
     * @param path the path within the container to run (or null for default).
     * @param data the player/npc/other data to attach (or null for empty).
     * @param definitions definitions to add (or null for none).
     * @return the new queue, or null if not possible.
     */
    public static ScriptQueue createAndStartQueueAsync(ScriptContainer container, String path, ScriptEntryData data, ListTag definitions) {
        return createAndStartQueue(container, path, data, null, null, null, null, definitions, null, true);
    }

    /**
     * Runs any script.
     * @param container the script to run.
     * @param path the path within the container to run (or null for default).
     * @param data the player/npc/other data to attach (or null for empty).
     * @param context the provider for &lt;context&gt; data (or null for none).
     * @param configure a function to configure the queue (eg add definitions) before starting (or null for none).
     * @param speed a specific speed to apply (or null for default).
     * @param id the script ID to force (or null for default).
     * @param definitions definitions to add (or null for none).
     * @param debugDefinitions the object to debug added definitions with (or null for no debug).
     * @param async if true, the script runs on a separate thread instead of the main thread. See <@link language Async Queues>.
     * @return the new queue, or null if not possible.
     */
    public static ScriptQueue createAndStartQueue(ScriptContainer container, String path, ScriptEntryData data,
                                                  ContextSource context, Consumer<ScriptQueue> configure,
                                                  DurationTag speed, String id, ListTag definitions, Debuggable debugDefinitions, boolean async) {
        if (!container.canRunScripts) {
            Debug.echoError("The script container '" + container.getName() + "' is of type '" + container.getContainerType() + "' which cannot run scripts. Consider using a task script instead.");
            return null;
        }
        if (id == null) {
            id = container.getName();
        }
        if (data == null) {
            data = DenizenCore.implementation.getEmptyScriptEntryData();
        }
        List<ScriptEntry> entries;
        if (path == null) {
            entries = container.getBaseEntries(data.clone());
        }
        else {
            entries = container.getEntries(data.clone(), path);
        }
        if (entries == null) {
            return null;
        }
        ScriptQueue queue;
        if (speed == null) {
            if (container.contains("SPEED", String.class)) {
                speed = DurationTag.valueOf(container.getString("SPEED", "0"), DenizenCore.implementation.getTagContext(container));
            }
            if (speed == null) {
                speed = new DurationTag(CoreConfiguration.scriptQueueSpeed);
            }
        }
        if (async && CoreConfiguration.allowAsyncScripts) {
            queue = new AsyncQueue(id, speed.getTicks());
        }
        else if (speed.getTicks() > 0) {
            queue = new TimedQueue(id).setSpeed(speed.getTicks());
        }
        else {
            queue = new InstantQueue(id);
        }
        queue.addEntries(entries);
        queue.contextSource = context;
        if (definitions != null) {
            List<String> definition_names = null;
            if (container.contains("definitions", String.class)) {
                String str = container.getString("definitions");
                definition_names = CoreUtilities.split(str, '|');
            }
            int x = 1;
            for (ObjectTag definition : definitions.objectForms) {
                String name = definition_names != null && definition_names.size() >= x ? definition_names.get(x - 1).trim() : String.valueOf(x);
                int squareBracket = name.indexOf('[');
                if (squareBracket != -1) {
                    name = name.substring(0, squareBracket).trim();
                }
                queue.addDefinition(name, definition);
                if (debugDefinitions != null && debugDefinitions.shouldDebug()) {
                    Debug.echoDebug(debugDefinitions, "Adding definition '" + name + "' as " + definition);
                }
                x++;
            }
            queue.addDefinition("raw_context", definitions);
        }
        if (configure != null) {
            configure.accept(queue);
        }
        queue.start(true);
        return queue;
    }

    /**
     * Creates and starts an arbitrary queue based on just a set of entries, useful for example with running a sub-script in a new queue.
     */
    public static ScriptQueue createAndStartQueueArbitrary(String id, List<ScriptEntry> entries, ScriptEntryData data, ContextSource context, Consumer<ScriptQueue> configure) {
        return createAndStartQueueArbitrary(id, entries, data, context, configure, false);
    }

    /**
     * Creates and starts an arbitrary queue based on just a set of entries, optionally on a separate thread.
     * With async set true this is safe to call from any thread. See <@link language Async Queues>.
     */
    public static ScriptQueue createAndStartQueueArbitrary(String id, List<ScriptEntry> entries, ScriptEntryData data, ContextSource context, Consumer<ScriptQueue> configure, boolean async) {
        if (data == null) {
            data = DenizenCore.implementation.getEmptyScriptEntryData();
        }
        List<ScriptEntry> cleanedEntries = new ArrayList<>();
        ScriptQueue queue = async && CoreConfiguration.allowAsyncScripts ? new AsyncQueue(id) : new InstantQueue(id);
        for (ScriptEntry entry : entries) {
            ScriptEntry newEntry = entry.clone();
            newEntry.queue = queue;
            newEntry.entryData = data.clone();
            newEntry.entryData.scriptEntry = newEntry;
            newEntry.updateContext();
            cleanedEntries.add(newEntry);
        }
        queue.addEntries(cleanedEntries);
        queue.contextSource = context;
        if (configure != null) {
            configure.accept(queue);
        }
        queue.start(true);
        return queue;
    }

    /**
     * Takes a string with tags in it, and the context to use, and returns a string with any/all tags within parsed.
     * If no context is available, use `CoreUtilities.basicContext` or  `CoreUtilities.noDebugContext`.
     */
    public static ObjectTag tagObject(String taggedText, TagContext context) {
        return TagManager.tagObject(taggedText, context);
    }

    /**
     * Takes a string with tags in it, and the context to use, and returns an object representing a pre-parsed reusable tag-holding object that can be used to quickly read tags.
     * Do not store instances past a script reload event.
     * If no context is available, use `CoreUtilities.basicContext` or  `CoreUtilities.noDebugContext`.
     */
    public static ParseableTag textToTag(String taggedText, TagContext context) {
        return TagManager.parseTextToTag(taggedText, context);
    }
}
