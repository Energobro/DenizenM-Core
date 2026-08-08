package com.denizenscript.denizencore.scripts.commands;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.*;
import com.denizenscript.denizencore.objects.notable.Notable;
import com.denizenscript.denizencore.objects.notable.NoteManager;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.ScriptRegistry;
import com.denizenscript.denizencore.scripts.commands.generator.CommandExecutionGenerator;
import com.denizenscript.denizencore.scripts.containers.ScriptContainer;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.utilities.CoreConfiguration;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.EnumHelper;
import com.denizenscript.denizencore.utilities.debugging.Debug;

import java.util.*;

public abstract class AbstractCommand {

    /**
     * Special extra command names that should still not error.
     */
    public static HashSet<String> noErrorCommandNames = new HashSet<>(Arrays.asList("case", "default"));

    public String syntax = "No usage defined! See documentation for more information!";

    public List<String> docFlagArgs = new ArrayList<>();

    public List<String> docPrefixes = new ArrayList<>();

    public void setSyntax(String syntax) {
        this.syntax = syntax;
        int firstSpace = syntax.indexOf(' ');
        if (firstSpace < 0) {
            return;
        }
        String cleaned = syntax.substring(firstSpace).replace("/", " ");
        cleaned = cleaned.replace("[", "").replace("]", "").replace("(", "").replace(")", "").replace("{", "").replace("}", "");
        List<String> args = CoreUtilities.split(cleaned, ' ');
        for (String arg : args) {
            if (arg.isEmpty()) {
                continue;
            }
            int colonIndex = arg.indexOf(':');
            if (colonIndex > 0) {
                String prefix = arg.substring(0, colonIndex);
                if (!prefix.contains("<")) {
                    docPrefixes.add(prefix);
                }
            }
            else if (!arg.contains("<") && !arg.contains("|")) {
                docFlagArgs.add(arg);
            }
        }
        if (CoreConfiguration.debugVerbose) {
            Debug.log("Command syntax '" + syntax + "' parsed to flat args: ( " + String.join(", ", docFlagArgs) + " ) and prefixes ( " + String.join(", ", docPrefixes) + " ).");
        }
    }

    public static class TabCompletionsBuilder {

        public String arg;

        public ArrayList<String> completions = new ArrayList<>();

        public TagContext context;

        public final void addWithPrefix(String prefix, Iterable<String> values) {
            if (arg.startsWith(prefix)) {
                for (String val : values) {
                    add(prefix + val);
                }
            }
        }

        public final void addWithPrefix(String prefix, Enum<?>[] values) {
            if (arg.startsWith(prefix)) {
                for (Enum<?> val : values) {
                    add(prefix + val.name());
                }
            }
        }

        public final void add(String text) {
            if (CoreUtilities.toLowerCase(text).startsWith(arg)) {
                completions.add(text);
            }
        }

        public final void add(String a, String... values) {
            add(a);
            for (String val : values) {
                add(val);
            }
        }

        public final void add(String[] values) {
            for (String val : values) {
                add(val);
            }
        }

        public final void add(Iterable<String> values) {
            for (String val : values) {
                add(val);
            }
        }

        public final void add(Enum<?>[] values) {
            for (Enum<?> val : values) {
                add(val.name());
            }
        }

        public final void addNotesOfType(Class<? extends Notable> type) {
            for (Notable note : NoteManager.notesByType.get(type)) {
                add(NoteManager.getSavedId(note));
            }
        }

        public final void addScriptsOfType(Class<? extends ScriptContainer> type) {
            for (ScriptContainer script : ScriptRegistry.scriptContainers.values()) {
                if (type.isAssignableFrom(script.getClass())) {
                    add(script.getName());
                }
            }
        }
    }

    public void addCustomTabCompletions(TabCompletionsBuilder tab) {
    }

    private boolean preparseArgs = true;

    public boolean forceHold = false;

    public int minimumArguments = 0;

    public int maximumArguments = Integer.MAX_VALUE;

    public int prefixesThusFar = 0;

    public HashMap<String, Integer> prefixesHandled = new HashMap<>();

    public HashMap<String, Integer> booleansHandled = new HashMap<>();

    public HashMap<String, String> prefixRemapper = new HashMap<>();

    public HashMap<EnumHelper, Integer> enumsHandled = new HashMap<>();

    public HashMap<String, Integer> enumPrefixes = new HashMap<>();

    public boolean allowedDynamicPrefixes = false;

    public boolean anyPrefixSymbolAllowed = false;

    public int linearHandledCount = 0;

    public boolean generatorInfiniteArgs = false;

    public boolean generateDebug = true;

    public void addRemappedPrefixes(String realName, String... alts) {
        Integer oldIndex = prefixesHandled.get(realName);
        int index = oldIndex != null ? oldIndex : prefixesThusFar++;
        prefixesHandled.put(realName, index);
        for (String str : alts) {
            prefixesHandled.put(str, index);
        }
        for (String alt : alts) {
            prefixRemapper.put(alt, realName);
        }
    }

    public void setPrefixesHandled(String... prefixes) {
        for (String str : prefixes) {
            setPrefixHandled(str);
        }
    }

    public int setPrefixHandled(String prefixes) {
        int index = prefixesThusFar++;
        prefixesHandled.put(prefixes, index);
        return index;
    }

    public void setBooleansHandled(String... boolNames) {
        for (String str : boolNames) {
            setBooleanHandled(str);
        }
    }

    public int setBooleanHandled(String boolName) {
        int index = booleansHandled.size();
        booleansHandled.put(boolName, index);
        return index;
    }

    public int setEnumHandled(String prefix, Class<? extends Enum> enumType) {
        int index = enumsHandled.size();
        enumPrefixes.put(prefix, index);
        enumsHandled.put(EnumHelper.get(enumType), index);
        return index;
    }

    public static String db(String prefix, boolean value) {
        return "<G>" + prefix + "='<Y>" + value + "<G>'  ";
    }

    public static String db(String prefix, Object value) {
        if (value == null) {
            return "";
        }
        return ArgumentHelper.debugObj(prefix, value);
    }

    /**
     * Whether this command is valid for usage in procedural logic.
     */
    public boolean isProcedural = false;

    /**
     * Whether this command is safe to execute on a thread other than the main thread.
     * <p>
     * When an async queue (see <@link language Async Queues>) reaches a command that is not async-safe,
     * the command is automatically executed on the main thread instead, with the async queue waiting for it to finish.
     * <p>
     * Only set this true for commands that exclusively touch their own queue and thread-safe data.
     * Anything that reads or writes live server/world state must stay false.
     */
    public boolean asyncSafe = false;

    /**
     * Whether this command should move its own execution (including its tag parsing) to a separate thread when it's '~' waited for.
     * <p>
     * This is what makes "- ~define x &lt;some.slow.tag&gt;" run off-thread: the queue holds until the command completes,
     * and the rest of the server keeps running in the meantime.
     * <p>
     * Requires {@link #asyncSafe}, and requires the command to be {@link Holdable}.
     * The command executor marks the entry finished automatically once execution returns, so such commands don't need to call setFinished themselves.
     */
    public boolean runAsyncWhenWaited = false;

    /**
     * Whether an async script may hand this command to the main thread and carry on without waiting for it.
     * <p>
     * Normally an async script that reaches a command it can't run itself stops until the main thread has run it, which costs up to a tick each time.
     * A deferrable command skips that wait: its arguments are still parsed immediately, on the script's own thread, so the values are the ones
     * the script had at that moment - only the execution is passed over. Deferred commands keep their order relative to each other.
     * <p>
     * Set this true only for a command that hands nothing back to its script - particles, sounds, messages.
     * It must not be {@link Holdable}, must not save an entry the script reads later, and must not parse tags inside its own execute method,
     * since those would then be read at the deferred moment rather than at the moment the script asked for them.
     * Requires the command to have no generated executor, as those fuse argument parsing and execution into one step that cannot be split.
     */
    public boolean asyncDeferrable = false;

    /**
     * Whether this one entry may be handed off without waiting, for a command that is only deferrable some of the time.
     * <p>
     * Called before the entry's arguments are parsed, so it can only look at raw input - see {@link ScriptEntry#hasRawArgument}.
     * The general rules of {@link #asyncDeferrable} still apply on top of whatever this returns.
     */
    public boolean isAsyncDeferrable(ScriptEntry scriptEntry) {
        return asyncDeferrable;
    }

    /** Marks this command as safe to run off the main thread. See {@link #asyncSafe}. */
    public void setAsyncSafe(boolean safe) {
        asyncSafe = safe;
    }

    /** Marks this command as one an async script may fire off without waiting. See {@link #asyncDeferrable}. */
    public void setAsyncDeferrable(boolean deferrable) {
        asyncDeferrable = deferrable;
    }

    /** Marks this command as one that runs off-thread when '~' waited for. See {@link #runAsyncWhenWaited}. */
    public void setAsyncWaitable(boolean waitable) {
        runAsyncWhenWaited = waitable;
        if (waitable) {
            asyncSafe = true;
        }
    }

    public void setRequiredArguments(int min, int max) {
        minimumArguments = min;
        maximumArguments = max == -1 ? Integer.MAX_VALUE : max;
    }

    public void setParseArgs(boolean parse) {
        preparseArgs = parse;
    }

    public boolean shouldPreParse() {
        return preparseArgs;
    }

    public void setName(String commandName) {
        name = CoreUtilities.toUpperCase(commandName);
    }

    protected String name;

    public String getName() {
        return name;
    }

    public String getUsageHint() {
        return syntax;
    }

    public void onDisable() {
    }

    public CommandExecutionGenerator.CommandExecutor generatedExecutor;

    public void autoCompile() {
        generatedExecutor = CommandExecutionGenerator.generateExecutorFor(getClass(), this);
    }

    public void execute(ScriptEntry scriptEntry) {
        Debug.echoError("Something went wrong! Command '" + name + "' has no executor (or called wrongly)?");
    }

    /**
     * Legacy argument parsing method.
     */
    public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {
        for (Argument arg : scriptEntry) {
            arg.reportUnhandled();
        }
    }

}
