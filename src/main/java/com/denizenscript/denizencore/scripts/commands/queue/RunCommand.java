package com.denizenscript.denizencore.scripts.commands.queue;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsRuntimeException;
import com.denizenscript.denizencore.objects.*;
import com.denizenscript.denizencore.objects.core.*;
import com.denizenscript.denizencore.scripts.containers.core.TaskScriptContainer;
import com.denizenscript.denizencore.utilities.CoreConfiguration;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.Deprecations;
import com.denizenscript.denizencore.utilities.ScriptUtilities;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.scripts.commands.Holdable;
import com.denizenscript.denizencore.scripts.commands.generator.*;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.utilities.text.StringHolder;

import java.util.ArrayList;
import java.util.Map;
import java.util.function.Consumer;

public class RunCommand extends AbstractCommand implements Holdable {

    public RunCommand() {
        setName("run");
        setSyntax("run [<script>] (path:<name>) (def:<element>|.../defmap:<map>/def.<name>:<value>) (id:<name>) (speed:<value>/instantly) (delay:<value>) (async)");
        setRequiredArguments(1, -1);
        isProcedural = true;
        allowedDynamicPrefixes = true;
        addRemappedPrefixes("id", "i");
        addRemappedPrefixes("def", "d", "define", "c", "context");
        generateDebug = false;
        generatorInfiniteArgs = true;
        autoCompile();
    }

    // <--[command]
    // @Name Run
    // @Syntax run [<script>] (path:<name>) (def:<element>|.../defmap:<map>/def.<name>:<value>) (id:<name>) (speed:<value>/instantly) (delay:<value>) (async)
    // @Required 1
    // @Maximum -1
    // @Short Runs a script in a new queue.
    // @Guide https://guide.denizenscript.com/guides/basics/run-options.html
    // @Group queue
    //
    // @Description
    // Runs a script in a new queue.
    //
    // You must specify a script object to run.
    //
    // Optionally, use the "path:" argument to choose a specific sub-path within a script.
    //
    // Optionally, use the "def:" argument to specify definition values to pass to the script,
    // the definitions will be named via the "definitions:" script key on the script being ran,
    // or numerically in order if that isn't specified (starting with <[1]>).
    //
    // Alternately, use "defmap:<map>" to specify definitions to pass as a MapTag, where the keys will be definition names and the values will of course be definition values.
    //
    // Alternately, use "def.<name>:<value>" to define one or more  named definitions individually.
    //
    // Optionally, use the "speed:" argument to specify the queue command-speed to run the target script at,
    // or use the "instantly" argument to use an instant speed (no command delay applied).
    // If neither argument is specified, the default queue speed applies (normally instant, refer to the config file).
    // Generally, prefer to set the "speed:" script key on the script to be ran, rather than using this argument.
    //
    // Optionally, use the "delay:" argument to specify a delay time before the script starts running.
    //
    // Optionally, specify the "id:" argument to choose a custom queue ID to be used.
    // If none is specified, a randomly generated one will be used. Generally, don't use this argument.
    //
    // Optionally, specify 'async' to run the script on a separate thread instead of the server's main thread.
    // The new queue's own logic (tags, math, text processing, ...) then costs no main thread time at all,
    // and any command in it that isn't safe to run off-thread is automatically handed back to the main thread.
    // Refer to <@link language Async Queues> before using this - it is not a free speed boost, and is only correct for scripts that mostly process data.
    // Running with 'async' from a script that is itself async costs no main thread time at all: the new thread is started directly by the script that asked for it.
    // Without 'async', a run from an async script is handed to the main thread, and the new queue runs there - a script that didn't ask to be async never becomes async by being run from one.
    //
    // The run command is ~waitable. Refer to <@link language ~waitable>.
    // Note that '~run ... async' is valid and useful: the current queue waits for the result, while the server keeps running.
    //
    // @Tags
    // <entry[saveName].created_queue> returns the queue that was started by the run command.
    //
    // @Usage
    // Use to run a task script named 'MyTask'.
    // - run MyTask
    //
    // @Usage
    // Use to run a task script named 'MyTask' that isn't normally instant, instantly.
    // - run MyTask instantly
    //
    // @Usage
    // Use to run a heavy data-processing task off the main thread, and wait for its result.
    // - ~run MyHeavyTask async save:heavy
    // - narrate "Done: <entry[heavy].created_queue.definition[result]>"
    //
    // @Usage
    // Use to run a local subscript named 'alt_path'.
    // - run <script> path:alt_path
    //
    // @Usage
    // Use to run 'MyTask' and pass 3 definitions to it.
    // - run MyTask def:A|Second_Def|Taco
    //
    // @Usage
    // Use to run 'MyTask' and pass 3 named definitions to it.
    // - run MyTask def.count:5 def.type:Taco def.smell:Tasty
    //
    // @Usage
    // Use to run 'MyTask' and pass a list as a single definition.
    // - run MyTask def:<list_single[<list[a|big|list|here]>]>
    // # MyTask can then get the list back by doing:
    // - define mylist <[1]>
    //
    // -->

    @Override
    public void addCustomTabCompletions(TabCompletionsBuilder tab) {
        tab.addScriptsOfType(TaskScriptContainer.class);
    }

    @Override
    public boolean isAsyncSafe(ScriptEntry scriptEntry) {
        // 'run ... async' gives the new script its own thread, so there is nothing here for the main thread to do - crossing over just to start a thread would cost a tick for nothing.
        // A plain 'run' deliberately keeps crossing: a new queue runs on whatever thread started it, and a script that never asked to be async shouldn't become async merely by being called from one.
        // This reads raw input, so a tag that resolves to "async" isn't seen - the crossing then still happens, which is the harmless direction.
        return CoreConfiguration.allowAsyncScripts && scriptEntry.hasRawArgument("async");
    }

    public static class RunWork {
        public ScriptTag script;
        public ElementTag path;
        public ElementTag id;
        public ListTag definitions;
        public DurationTag speed;
        public DurationTag delay;
        public boolean instant;
        public boolean async;
        public MapTag defMap;
        public ArrayList<Argument> lateDefArgs;
        public ArrayList<MapTag> lateDefs;

        public void addLateDefs(Argument arg, MapTag defs) {
            if (lateDefArgs == null) {
                lateDefArgs = new ArrayList<>();
                lateDefs = new ArrayList<>();
            }
            lateDefArgs.add(arg);
            lateDefs.add(defs);
        }
    }

    public static void handleArgument(ScriptEntry scriptEntry, Argument arg, RunWork work) {
        if (arg.matchesPrefix("i", "id")) {
            work.id = arg.asElement();
        }
        else if (arg.matchesPrefix("d", "def", "define", "c", "context")) {
            work.definitions = arg.asType(ListTag.class);
        }
        else if (arg.matchesPrefix("defmap")
                && arg.matchesArgumentType(MapTag.class)) {
            work.addLateDefs(arg, arg.asType(MapTag.class));
        }
        // The prefix checks matter here: 'matches' compares only the value, so without them "def.x:async" or "def.x:instant"
        // would be read as these flags and the definition would be lost with no error at all.
        else if (!arg.hasPrefix() && arg.matches("instant", "instantly")) {
            work.instant = true;
        }
        else if (!arg.hasPrefix() && arg.matches("async")) {
            work.async = true;
        }
        else if (arg.matchesPrefix("delay")
                && arg.matchesArgumentType(DurationTag.class)) {
            work.delay = arg.asType(DurationTag.class);
        }
        else if (arg.hasPrefix()
                && arg.getPrefix().getRawValue().startsWith("def.")) {
            MapTag single = new MapTag();
            single.putObject(arg.getPrefix().getRawValue().substring("def.".length()), arg.object);
            work.addLateDefs(arg, single);
        }
        else if (!arg.hasPrefix() && arg.getRawValue().startsWith("def.") && arg.getRawValue().contains(":")) {
            int colon = arg.getRawValue().indexOf(':');
            MapTag single = new MapTag();
            single.putObject(arg.getRawValue().substring("def.".length(), colon), new ElementTag(arg.getRawValue().substring(colon + 1)));
            work.addLateDefs(arg, single);
        }
        else if (arg.matches("local", "locally")) {
            Deprecations.locallyArgument.warn(scriptEntry);
            work.script = scriptEntry.getScript();
        }
        else if (work.speed == null
                && arg.matchesPrefix("speed")
                && arg.matchesArgumentType(DurationTag.class)) {
            work.speed = arg.asType(DurationTag.class);
        }
        else if (work.script == null
                && arg.scriptTagValue() != null
                && arg.limitToOnlyPrefix("script")) {
            work.script = arg.scriptTagValue();
        }
        else if (work.script == null && work.path == null
                && !arg.hasPrefix() && arg.asElement().asString().contains(".")) {
            String path = arg.asElement().asString();
            int dotIndex = path.indexOf('.');
            ScriptTag script = ScriptTag.valueOf(path.substring(0, dotIndex), CoreUtilities.noDebugContext);
            if (script == null) {
                arg.reportUnhandled();
            }
            else {
                work.script = script;
                work.path = new ElementTag(path.substring(dotIndex + 1));
            }
        }
        else if (work.path == null
                && arg.limitToOnlyPrefix("path")) {
            if (!arg.hasPrefix() && work.script != null) { // TODO: Temporarily allow missing prefix due to common mistake
                Debug.echoError("Run command path is missing required 'path:' prefix.");
            }
            work.path = arg.asElement();
        }
        else {
            arg.reportUnhandled();
        }
    }

    public static MapTag orderedDefinitions(ScriptEntry scriptEntry, RunWork work) {
        if (work.lateDefArgs == null) {
            return work.defMap;
        }
        MapTag result = new MapTag();
        int[] dynamicIndices = scriptEntry.internal.dynamicPrefixArgs == null ? null : scriptEntry.internal.dynamicPrefixArgs[0];
        ScriptEntry.InternalArgument[] all = scriptEntry.internal.all_arguments;
        for (int i = 0; i < all.length; i++) {
            Argument arg = all[i].aHArg;
            if (dynamicIndices != null) {
                int dynamicPos = -1;
                for (int j = 0; j < dynamicIndices.length; j++) {
                    if (dynamicIndices[j] == i) {
                        dynamicPos = j;
                        break;
                    }
                }
                if (dynamicPos != -1) {
                    CommandExecutionGenerator.putDynamicPrefixed(result, arg, scriptEntry.internal.dynamicPrefixKeys[0][dynamicPos], "def.");
                    continue;
                }
            }
            for (int j = 0; j < work.lateDefArgs.size(); j++) {
                if (work.lateDefArgs.get(j) == arg) {
                    result.putAll(work.lateDefs.get(j));
                }
            }
        }
        return result;
    }

    public static ObjectTag withPrefix(ObjectTag object, String prefix) {
        if (object != null) {
            object.setPrefix(prefix);
        }
        return object;
    }

    public static void autoExecute(ScriptEntry scriptEntry,
                                   @ArgName("id") @ArgPrefixed @ArgDefaultNull ElementTag id,
                                   @ArgName("def") @ArgPrefixed @ArgDefaultNull ListTag definitions,
                                   @ArgName("def_map") @ArgDynamicPrefix("def.") @ArgDefaultNull MapTag defMap,
                                   @ArgName("instant") boolean instant,
                                   @ArgName("instantly") boolean instantly,
                                   @ArgName("async") boolean async) {
        RunWork work = new RunWork();
        work.id = id;
        work.definitions = definitions;
        work.defMap = defMap;
        work.instant = instant || instantly;
        work.async = async;
        for (Argument arg : scriptEntry) {
            handleArgument(scriptEntry, arg, work);
        }
        if (work.script == null) {
            throw new InvalidArgumentsRuntimeException("Must define a SCRIPT to be run.");
        }
        MapTag defs = orderedDefinitions(scriptEntry, work);
        if (defs != null && defs.isEmpty()) {
            defs = null;
        }
        ScriptTag script = work.script;
        String path = work.path != null ? work.path.asString() : null;
        if (path != null && !script.getContainer().containsScriptSection(path)) {
            Debug.echoError(scriptEntry, "Script run failed (invalid path)!");
            return;
        }
        DurationTag speed = work.instant ? new DurationTag(0) : work.speed;
        DurationTag delay = work.delay;
        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, "RUN", withPrefix(script, "script"), withPrefix(work.path, "path"), work.instant ? db("instant", true) : null, work.async ? db("async", true) : null,
                    work.instant ? speed : withPrefix(speed, "speed"), withPrefix(delay, "delay"), withPrefix(work.id, "id"), withPrefix(defs, "def_map"), withPrefix(work.definitions, "definitions"));
        }
        MapTag finalDefs = defs;
        Consumer<ScriptQueue> configure = (queue) -> {
            // Set any delay
            if (delay != null) {
                queue.delayUntil(DenizenCore.serverTimeMillis + delay.getMillis());
            }
            // Setup a callback if the queue is being waited on
            if (scriptEntry.shouldWaitFor()) {
                queue.callBack(() -> scriptEntry.setFinished(true));
            }
            if (finalDefs != null) {
                for (Map.Entry<StringHolder, ObjectTag> val : finalDefs.entrySet()) {
                    queue.addDefinition(val.getKey().str, val.getValue());
                }
            }
            // Save the queue for script referencing
            if (scriptEntry.saveName != null) {
                scriptEntry.saveObject("created_queue", new QueueTag(queue));
            }
            // Preserve procedural status
            queue.procedural = scriptEntry.getResidingQueue().procedural;
        };
        String idString = work.id != null ? "FORCE:" + work.id.asString() : null;
        ScriptQueue result = ScriptUtilities.createAndStartQueue(script.getContainer(), path, scriptEntry.entryData, null, configure, speed, idString, work.definitions, scriptEntry, work.async);
        if (result == null) {
            Debug.echoError(scriptEntry, "Script run failed (are you sure it's a task script, and the path exists?)!");
        }
    }
}
