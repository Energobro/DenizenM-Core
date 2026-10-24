package com.denizenscript.denizencore.scripts.commands.queue;

import com.denizenscript.denizencore.scripts.commands.generator.ArgRaw;
import com.denizenscript.denizencore.scripts.commands.generator.ArgName;
import com.denizenscript.denizencore.scripts.commands.generator.ArgLinear;
import com.denizenscript.denizencore.scripts.commands.generator.ArgDefaultNull;
import com.denizenscript.denizencore.scripts.commands.generator.CommandExecutionGenerator;
import com.denizenscript.denizencore.exceptions.InvalidArgumentsRuntimeException;
import com.denizenscript.denizencore.objects.core.QueueTag;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.data.ActionableDataProvider;
import com.denizenscript.denizencore.utilities.data.DataAction;
import com.denizenscript.denizencore.utilities.data.DataActionHelper;
import com.denizenscript.denizencore.utilities.data.DataActionType;
import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.utilities.text.StringHolder;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.scripts.commands.Holdable;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.utilities.DefinitionSlots;

import java.util.List;

public class DefineCommand extends AbstractCommand implements Holdable {

    public DefineCommand() {
        setName("define");
        setSyntax("define [<id>](:<action>)[:<value>]");
        setRequiredArguments(1, 2);
        isProcedural = true;
        allowedDynamicPrefixes = true;
        setAsyncWaitable(true);
        generateDebug = false; // Reported by hand below, to keep the queue in the line as the legacy path did.
        autoCompile();
        if (generatedExecutor != null && generatedExecutor.args != null && generatedExecutor.args.length == 2 && "value".equals(generatedExecutor.args[1].name)) {
            generatedExecutor = new FixedNameExecutor(generatedExecutor);
        }
    }

    public static class FixedNameExecutor extends CommandExecutionGenerator.CommandExecutor {

        public final CommandExecutionGenerator.CommandExecutor generated;

        public FixedNameExecutor(CommandExecutionGenerator.CommandExecutor generated) {
            this.generated = generated;
            args = generated.args;
        }

        @Override
        public void execute(ScriptEntry scriptEntry, ScriptQueue queue) {
            if (!scriptEntry.debugThisLine && scriptEntry.internal.specialProcessedData instanceof CachedKey cached && cached.fixed && !cached.hasColon
                    && scriptEntry.internal.arguments_to_use.length == 2) {
                ObjectTag value = CommandExecutionGenerator.helperPrefixEntryArg(scriptEntry, args[1]);
                if (value != null) {
                    value.setPrefix("value");
                    refreshSlot(scriptEntry, cached);
                    store(scriptEntry, queue, cached, value);
                    return;
                }
            }
            generated.execute(scriptEntry, queue);
        }
    }

    public static void refreshSlot(ScriptEntry scriptEntry, CachedKey cached) {
        if (cached.slot == DefinitionSlots.NO_SLOT && cached.resolvedAtSize != ScriptQueue.tableSizeFor(scriptEntry)) {
            cached.resolvedAtSize = ScriptQueue.tableSizeFor(scriptEntry);
            cached.slot = ScriptQueue.slotFor(scriptEntry, cached.key);
        }
    }

    public static void store(ScriptEntry scriptEntry, ScriptQueue queue, CachedKey cached, ObjectTag value) {
        if (cached.path != null) {
            queue.addDefinitionDeep(cached.path, value.duplicate());
            return;
        }
        if (cached.special) {
            queue.addDefinitionSlot(scriptEntry.internal.slotTable, cached.slot, cached.key, value.duplicate());
            return;
        }
        queue.addDefinitionPlain(scriptEntry.internal.slotTable, cached.slot, cached.key, value.duplicate());
    }

    // <--[command]
    // @Name Define
    // @Syntax define [<id>](:<action>)[:<value>]
    // @Required 1
    // @Maximum 2
    // @Short Creates a temporary variable inside a script queue.
    // @Synonyms Definition
    // @Group queue
    // @Guide https://guide.denizenscript.com/guides/basics/definitions.html
    //
    // @Description
    // Definitions are queue-level 'variables' that can be used throughout a script, once defined, by using the <[<id>]> tag.
    // Definitions are only valid on the current queue and are not transferred to any new queues constructed within the script,
    // such as by a 'run' command, without explicitly specifying to do so.
    //
    // Definitions are lighter and faster than creating a temporary flag.
    // Definitions are also automatically removed when the queue is completed, so there is no worry for leaving unused data hanging around.
    //
    // This command supports data actions, see <@link language data actions>.
    //
    // Definitions can be sub-mapped with the '.' character, meaning a def named 'x.y.z' is actually a def 'x' as a MapTag with key 'y' as a MapTag with key 'z' as the final defined value.
    // In other words, "<[a.b.c]>" is equivalent to "<[a].get[b].get[c]>"
    //
    // The define command is ~waitable, and unusually so: when waited for, the entire command - including parsing the tags in its value - runs on a separate thread.
    // Refer to <@link language ~waitable>.
    // This is useful when the value is expensive to calculate (long lists, heavy text or math processing, large flag data, ...):
    // the queue waits for the result as usual, but the server's main thread stays free the whole time instead of freezing until the tags finish.
    //
    // <code>
    // - ~define result <server.flag[big_data].parse_tag[<[parse_value].to_uppercase>].filter_tag[<[filter_value].contains[x]>]>
    // </code>
    //
    // Only use this for tags that are safe to read off-thread. Tags that read live world state may return slightly outdated results,
    // and any tag provided by an implementation that requires the main thread should not be used here.
    // For simple values, plain "define" is faster - the "~" version costs a thread hand-off, which is only worth it for genuinely slow work.
    //
    // @Tags
    // <[<id>]> to get the value assigned to an ID
    // <QueueTag.definition[<definition>]>
    // <QueueTag.definitions>
    //
    // @Usage
    // Use to make complex tags look less complex, and scripts more readable.
    // - narrate "You invoke your power of notice..."
    // - define range <player.flag[range_level].mul[3]>
    // - define blocks <player.flag[noticeable_blocks]>
    // - define count <player.location.find_blocks[<[blocks]>].within[<[range]>].size>
    // - narrate "<&[base]>[NOTICE] You have noticed <[count].custom_color[emphasis]> blocks in the area that may be of interest."
    //
    // @Usage
    // Use to validate a player input to a command script, and then output the found player's name.
    // - define target <server.match_player[<context.args.get[1]>]||null>
    // - if <[target]> == null:
    //   - narrate "<red>Unknown player target."
    //   - stop
    // - narrate "You targeted <[target].name>!"
    //
    // @Usage
    // Use to keep the value of a tag that you might use many times within a single script.
    // - define arg1 <context.args.get[1]>
    // - if <[arg1]> == hello:
    //     - narrate Hello!
    // - else if <[arg1]> == goodbye:
    //     - narrate Goodbye!
    //
    // @Usage
    // Use to calculate a slow value without freezing the server while it calculates.
    // - ~define sorted_data <server.flag[player_scores].sort_by_value.reverse>
    // - narrate "Top scorer: <[sorted_data].keys.first>"
    //
    // @Usage
    // Use to remove a definition.
    // - define myDef:!
    //
    // @Usage
    // Use to make a MapTag definition and set the value of a key inside.
    // - define myroot.mykey MyValue
    // - define myroot.myotherkey MyOtherValue
    // - narrate "The main value is <[myroot.mykey]>, and the map's available key set is <[myroot].keys>"
    //
    // -->

    /**
     * The map key for a definition name, cached on the line when the name is fixed text.
     * <p>
     * Moving to autoCompile cost the shortcut through {@code Argument.lower_value}, so the name was being lowercased on every
     * execution again - and for a non-Latin name that reads the Unicode tables per character. A name written as a tag
     * (<@link tag definition>, eg 'define <[which]> 1') really can differ per run, so only a tagless one is cached.
     * A data action line is cached even with a tag in it, as its fixed 'name:action:' part is checked against the text on every run.
     */
    public static class CachedKey {
        public boolean hasColon;
        public boolean fixed;
        public StringHolder key;
        public int slot = DefinitionSlots.NO_SLOT;
        public int resolvedAtSize = -1;
        public StringHolder[] path;
        public boolean special;
        public CachedAction action;
    }

    public static class CachedAction {
        public final String prefix;
        public final boolean exact;
        public final String key;
        public final int index;
        public final DataActionType type;
        public final StringHolder plainKey;
        public final StringHolder[] path;
        public int slot = DefinitionSlots.NO_SLOT;
        public int resolvedAtSize = -1;

        public CachedAction(String prefix, boolean exact, DataAction parsed) {
            this.prefix = prefix;
            this.exact = exact;
            key = parsed.key;
            index = parsed.index;
            type = parsed.type;
            String lowered = CoreUtilities.toLowerCase(key);
            if (lowered.startsWith("__")) {
                plainKey = null;
                path = null;
            }
            else if (CoreUtilities.contains(lowered, '.')) {
                plainKey = null;
                List<String> segments = CoreUtilities.split(lowered, '.');
                path = new StringHolder[segments.size()];
                for (int i = 0; i < path.length; i++) {
                    path[i] = StringHolder.ofLowered(segments.get(i));
                }
            }
            else {
                plainKey = StringHolder.ofLowered(lowered);
                path = null;
            }
        }
    }

    public static CachedKey keyFor(ScriptEntry scriptEntry, String defName) {
        if (scriptEntry.internal.specialProcessedData instanceof CachedKey cached) {
            if (cached.hasColon) {
                if (cached.fixed || CoreUtilities.contains(defName, ':')) {
                    return cached;
                }
                return newKey(scriptEntry, defName);
            }
            refreshSlot(scriptEntry, cached);
            return cached;
        }
        CachedKey cached = newKey(scriptEntry, defName);
        if (cached.fixed || cached.hasColon) {
            scriptEntry.internal.specialProcessedData = cached;
        }
        return cached;
    }

    private static CachedKey newKey(ScriptEntry scriptEntry, String defName) {
        CachedKey cached = new CachedKey();
        ScriptEntry.InternalArgument[] args = scriptEntry.internal.arguments_to_use;
        cached.fixed = args != null && args.length > 0 && args[0].value != null && !args[0].value.hasTag;
        if (CoreUtilities.contains(defName, ':')) {
            cached.hasColon = true;
            return cached;
        }
        String lowered = CoreUtilities.toLowerCase(defName);
        cached.key = StringHolder.ofLowered(lowered);
        cached.special = lowered.startsWith("__");
        if (!cached.special && CoreUtilities.contains(lowered, '.')) {
            List<String> segments = CoreUtilities.split(lowered, '.');
            cached.path = new StringHolder[segments.size()];
            for (int i = 0; i < cached.path.length; i++) {
                cached.path[i] = StringHolder.ofLowered(segments.get(i));
            }
        }
        cached.resolvedAtSize = ScriptQueue.tableSizeFor(scriptEntry);
        cached.slot = ScriptQueue.slotFor(scriptEntry, cached.key);
        return cached;
    }

    public static DataAction actionFor(ScriptEntry scriptEntry, CachedKey cached, String defName, DefinitionActionProvider provider) {
        CachedAction known = cached.action;
        if (known != null && (known.exact ? defName.equals(known.prefix) : defName.startsWith(known.prefix))) {
            DataAction action = new DataAction();
            action.provider = provider;
            action.key = known.key;
            action.index = known.index;
            action.type = known.type;
            if (!known.exact) {
                String rest = defName.substring(known.prefix.length());
                action.inputValue = known.type == DataActionType.INSERT ? ObjectFetcher.pickObjectFor(rest, scriptEntry.context) : new ElementTag(rest);
            }
            if (known.plainKey != null) {
                int size = ScriptQueue.tableSizeFor(scriptEntry);
                if (known.slot == DefinitionSlots.NO_SLOT && known.resolvedAtSize != size) {
                    known.resolvedAtSize = size;
                    known.slot = ScriptQueue.slotFor(scriptEntry, known.plainKey);
                }
            }
            provider.known = known;
            provider.table = scriptEntry.internal.slotTable;
            return action;
        }
        DataAction action = DataActionHelper.parse(provider, defName, scriptEntry.context);
        int first = defName.indexOf(':');
        int second = defName.indexOf(':', first + 1);
        String actionText = second == -1 ? defName.substring(first + 1) : defName.substring(first + 1, second);
        if (second == -1) {
            if (actionText.equals("++") || actionText.equals("--") || actionText.equals("!") || actionText.equals("<-")) {
                cached.action = new CachedAction(defName, true, action);
            }
        }
        else {
            switch (actionText) {
                case "->", "<-", "|", "!|", "+", "-", "*", "/" -> cached.action = new CachedAction(defName.substring(0, second + 1), false, action);
            }
        }
        return action;
    }

    public static class DefinitionActionProvider extends ActionableDataProvider {

        public ScriptQueue queue;

        public CachedAction known;

        public DefinitionSlots table;

        @Override
        public ObjectTag getValueAt(String keyName) {
            CachedAction known = this.known;
            if (known != null && keyName == known.key) {
                if (known.plainKey != null) {
                    return known.slot == DefinitionSlots.NO_SLOT ? queue.definitions().getObject(known.plainKey) : queue.definitions().getSlot(table, known.slot, known.plainKey);
                }
                if (known.path != null) {
                    return queue.getDefinitionDeep(known.path);
                }
            }
            return queue.getDefinitionObject(keyName);
        }

        @Override
        public void setValueAt(String keyName, ObjectTag value) {
            CachedAction known = this.known;
            if (known != null && keyName == known.key) {
                if (known.plainKey != null) {
                    queue.addDefinitionPlain(table, known.slot, known.plainKey, value);
                    return;
                }
                if (known.path != null) {
                    queue.addDefinitionDeep(known.path, value);
                    return;
                }
            }
            queue.addDefinition(keyName, value);
        }
    }

    public static void autoExecute(ScriptEntry scriptEntry, ScriptQueue queue,
                                   @ArgLinear @ArgName("definition") @ArgRaw ElementTag definition,
                                   @ArgLinear @ArgName("value") @ArgRaw @ArgDefaultNull ObjectTag value) {
        // The legacy path set these through addObject, which stamps the key as the object's prefix - and that prefix is what
        // Debug.report prints as the label, and what duplicate() carries into the stored definition. Restored by hand here.
        definition.setPrefix("definition");
        if (value != null) {
            value.setPrefix("value");
        }
        String defName = definition.asString();
        CachedKey cached = keyFor(scriptEntry, defName);
        if (cached.hasColon) {
            DefinitionActionProvider provider = new DefinitionActionProvider();
            provider.queue = queue;
            DataAction action = actionFor(scriptEntry, cached, defName, provider);
            if (scriptEntry.debugThisLine) {
                Debug.report(scriptEntry, "DEFINE", new QueueTag(queue), action);
            }
            action.execute(scriptEntry.getContext());
            return;
        }
        if (value == null) {
            throw new InvalidArgumentsRuntimeException("Must specify a definition and value!");
        }
        if (scriptEntry.debugThisLine) {
            Debug.report(scriptEntry, "DEFINE", new QueueTag(queue), definition, value);
        }
        store(scriptEntry, queue, cached, value);
    }
}
