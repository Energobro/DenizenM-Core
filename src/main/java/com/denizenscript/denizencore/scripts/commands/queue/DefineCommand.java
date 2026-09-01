package com.denizenscript.denizencore.scripts.commands.queue;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.scripts.commands.generator.ArgRaw;
import com.denizenscript.denizencore.scripts.commands.generator.ArgName;
import com.denizenscript.denizencore.scripts.commands.generator.ArgLinear;
import com.denizenscript.denizencore.scripts.commands.generator.ArgDefaultNull;
import com.denizenscript.denizencore.exceptions.InvalidArgumentsRuntimeException;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.objects.core.QueueTag;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.data.ActionableDataProvider;
import com.denizenscript.denizencore.utilities.data.DataAction;
import com.denizenscript.denizencore.utilities.data.DataActionHelper;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.utilities.text.StringHolder;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.scripts.commands.Holdable;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;

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
     */
    public static StringHolder keyFor(ScriptEntry scriptEntry, String defName) {
        if (scriptEntry.internal.specialProcessedData instanceof StringHolder) {
            return (StringHolder) scriptEntry.internal.specialProcessedData;
        }
        StringHolder key = StringHolder.ofLowered(CoreUtilities.toLowerCase(defName));
        ScriptEntry.InternalArgument[] args = scriptEntry.internal.arguments_to_use;
        if (args != null && args.length > 0 && args[0].value != null && !args[0].value.hasTag) {
            scriptEntry.internal.specialProcessedData = key;
        }
        return key;
    }

    public static class DefinitionActionProvider extends ActionableDataProvider {

        public ScriptQueue queue;

        @Override
        public ObjectTag getValueAt(String keyName) {
            return queue.getDefinitionObject(keyName);
        }

        @Override
        public void setValueAt(String keyName, ObjectTag value) {
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
        if (CoreUtilities.contains(defName, ':')) {
            DefinitionActionProvider provider = new DefinitionActionProvider();
            provider.queue = queue;
            DataAction action = DataActionHelper.parse(provider, defName, scriptEntry.context);
            if (scriptEntry.dbCallShouldDebug()) {
                Debug.report(scriptEntry, "DEFINE", new QueueTag(queue), action);
            }
            action.execute(scriptEntry.getContext());
            return;
        }
        if (value == null) {
            throw new InvalidArgumentsRuntimeException("Must specify a definition and value!");
        }
        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, "DEFINE", new QueueTag(queue), definition, value);
        }
        queue.addDefinition(keyFor(scriptEntry, defName), value.duplicate());
    }
}
