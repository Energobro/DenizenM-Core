package com.denizenscript.denizencore.scripts.commands.queue;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.scripts.containers.core.TaskScriptContainer;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.Deprecations;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.ScriptTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.ScriptRegistry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;

import java.util.List;

public class InjectCommand extends AbstractCommand {

    public InjectCommand() {
        setName("inject");
        setSyntax("inject [<script>] (path:<name>) (instantly)");
        setRequiredArguments(1, 3);
        isProcedural = true;
        asyncSafe = true; // Only reads script data and touches its own queue.
    }

    // <--[command]
    // @Name Inject
    // @Syntax inject [<script>] (path:<name>) (instantly)
    // @Required 1
    // @Maximum 3
    // @Short Runs a script in the current queue.
    // @Guide https://guide.denizenscript.com/guides/basics/run-options.html
    // @Group queue
    //
    // @Description
    // Injects a script into the current queue.
    // This means this task will run with all of the original queue's definitions and tags.
    // It will also now be part of the queue, so any delays or definitions used in the injected script will be accessible in the original queue.
    //
    // @Tags
    // None
    //
    // @Usage
    // Injects the InjectedTask task into the current queue
    // - inject InjectedTask
    // -->

    @Override
    public void addCustomTabCompletions(TabCompletionsBuilder tab) {
        tab.addScriptsOfType(TaskScriptContainer.class);
    }

    public static class ParsedInject {
        public ScriptTag script;
        public ElementTag path;
        public ElementTag instant;
    }

    public static boolean isFixedLine(ScriptEntry scriptEntry) {
        ScriptEntry.InternalArgument[] args = scriptEntry.internal.all_arguments;
        if (args == null) {
            return false;
        }
        for (ScriptEntry.InternalArgument arg : args) {
            if ((arg.value != null && arg.value.hasTag) || (arg.prefix != null && arg.prefix.value != null && arg.prefix.value.hasTag)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {
        if (scriptEntry.internal.specialProcessedData instanceof ParsedInject parsed && ScriptRegistry.getScriptContainerLowered(parsed.script.getContainer().getLoweredName()) == parsed.script.getContainer()) {
            scriptEntry.addObject("script", parsed.script);
            scriptEntry.addObject("path", parsed.path);
            scriptEntry.addObject("instant", parsed.instant);
            return;
        }
        boolean cacheable = isFixedLine(scriptEntry);
        for (Argument arg : scriptEntry) {
            if (arg.matches("instant", "instantly")) {
                scriptEntry.addObject("instant", new ElementTag(true));
            }
            else if (arg.matches("local", "locally")) {
                cacheable = false;
                Deprecations.locallyArgument.warn(scriptEntry);
                scriptEntry.addObject("script", scriptEntry.getScript());
            }
            else if (!scriptEntry.hasObject("script")
                    && arg.matchesArgumentType(ScriptTag.class)
                    && arg.limitToOnlyPrefix("script")) {
                scriptEntry.addObject("script", arg.asType(ScriptTag.class));
            }
            else if (!scriptEntry.hasObject("script") && !scriptEntry.hasObject("path")
                    && !arg.hasPrefix() && arg.asElement().asString().contains(".")) {
                String path = arg.asElement().asString();
            int dotIndex = path.indexOf('.');
                ScriptTag script = ScriptTag.valueOf(path.substring(0, dotIndex), CoreUtilities.noDebugContext);
                if (script == null) {
                    cacheable = false;
                    arg.reportUnhandled();
                }
                else {
                    scriptEntry.addObject("script", script);
                    scriptEntry.addObject("path", new ElementTag(path.substring(dotIndex + 1)));
                }
            }
            else if (!scriptEntry.hasObject("path")
                    && arg.limitToOnlyPrefix("path")) {
                if (!arg.hasPrefix() && scriptEntry.hasObject("script")) { // TODO: Temporarily allow missing prefix due to common mistake
                    cacheable = false;
                    Debug.echoError("Inject command path is missing required 'path:' prefix.");
                }
                scriptEntry.addObject("path", arg.asElement());
            }
            else {
                cacheable = false;
                arg.reportUnhandled();
            }
        }
        if (!scriptEntry.hasObject("script")) {
            throw new InvalidArgumentsException("Must define a SCRIPT to be injected.");
        }
        if (cacheable) {
            ParsedInject parsed = new ParsedInject();
            parsed.script = scriptEntry.getObjectTag("script");
            parsed.path = scriptEntry.getElement("path");
            parsed.instant = scriptEntry.getElement("instant");
            scriptEntry.internal.specialProcessedData = parsed;
            scriptEntry.sharedInternal().specialProcessedData = parsed;
        }
    }

    @Override
    public void execute(ScriptEntry scriptEntry) {
        ScriptTag script = scriptEntry.getObjectTag("script");
        if (script == null) {
            script = scriptEntry.getScript();
        }
        ElementTag instant = scriptEntry.getElement("instant");
        ElementTag path = scriptEntry.getElement("path");
        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, getName(), script, instant, path);
        }
        ScriptQueue residingQueue = scriptEntry.getResidingQueue();
        List<ScriptEntry> entries;
        if (path != null) {
            entries = script.getContainer().getEntries(scriptEntry.entryData, path.asString(), residingQueue);
        }
        else {
            entries = script.getContainer().getBaseEntries(scriptEntry.entryData, residingQueue);
        }
        if (entries == null) {
            Debug.echoError(scriptEntry, "Script inject failed (invalid script path '" + path + "')!");
            return;
        }
        if (instant != null && instant.asBoolean()) {
            scriptEntry.getResidingQueue().runNow(entries);
        }
        else {
            scriptEntry.getResidingQueue().injectEntriesAtStart(entries);
        }
    }
}
