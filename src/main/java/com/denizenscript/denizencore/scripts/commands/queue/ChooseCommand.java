package com.denizenscript.denizencore.scripts.commands.queue;

import com.denizenscript.denizencore.scripts.commands.generator.ArgLinear;
import com.denizenscript.denizencore.scripts.commands.generator.ArgName;
import com.denizenscript.denizencore.scripts.commands.generator.ArgRaw;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.BracedCommand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ChooseCommand extends BracedCommand {

    public ChooseCommand() {
        setName("choose");
        setSyntax("choose [<option>] [<cases>]");
        setRequiredArguments(1, 1);
        isProcedural = true;
        autoCompile();
        asyncSafe = true; // Only touches its own queue and thread-safe data.
    }

    // <--[command]
    // @Name Choose
    // @Syntax choose [<option>] [<cases>]
    // @Required 1
    // @Maximum 1
    // @Short Chooses an option from the list of cases.
    // @Group queue

    // @Description
    // Chooses an option from the list of cases.
    // Intended to replace a long chain of simplistic if/else if or complicated script path selection systems.
    // Simply input the selected option, and the system will automatically jump to the most relevant case input.
    // Cases are given as a sub-set of commands inside the current command (see Usage for samples).
    //
    // Optionally, specify "default" in place of a case to give a result when all other cases fail to match.
    //
    // Cases must be static text. They may not contain tags. For multi-tag comparison, consider the IF command.
    // Any one case line can have multiple values in it - each possible value should be its own argument (separated by spaces).
    //
    // @Tags
    // None
    //
    // @Usage
    // Use to choose the only case.
    // - choose 1:
    //   - case 1:
    //     - debug LOG "Success!"
    //
    // @Usage
    // Use to choose the default case.
    // - choose 2:
    //   - case 1:
    //     - debug log "Failure!"
    //   - default:
    //     - debug log "Success!"
    //
    // @Usage
    // Use for dynamically choosing a case.
    // - choose <[entity_type]>:
    //   - case zombie:
    //     - narrate "You slayed an undead zombie!"
    //   - case skeleton:
    //     - narrate "You knocked the bones out of a skeleton!"
    //   - case creeper:
    //     - narrate "You didn't give that creeper a chance to explode!"
    //   - case pig cow chicken:
    //     - narrate "You killed an innocent farm animal!"
    //   - default:
    //     - narrate "You killed a <[entity_type].to_titlecase>!"
    //
    // -->

    public static void autoExecute(ScriptEntry scriptEntry, ScriptQueue queue,
                                   @ArgRaw @ArgLinear @ArgName("choice") ElementTag choice) {
        List<BracedData> bdlist = getBracedCommands(scriptEntry, false);
        if (bdlist == null || bdlist.isEmpty()) {
            Debug.echoError(scriptEntry, "Empty sub-commands (internal)!");
            return;
        }
        List<ScriptEntry> bracedCommandsList = bdlist.get(0).value;
        HashMap<String, Integer> lookupTable;
        if (scriptEntry.internal.specialProcessedData instanceof HashMap) {
            lookupTable = (HashMap<String, Integer>) scriptEntry.internal.specialProcessedData;
        }
        else {
            lookupTable = new HashMap<>(bracedCommandsList.size());
            for (int i = 0; i < bracedCommandsList.size(); i++) {
                ScriptEntry se = bracedCommandsList.get(i);
                String cmdName = CoreUtilities.toLowerCase(se.getCommandName());
                if (cmdName.equals("default")) {
                    lookupTable.put("\0DEFAULT", i);
                }
                else if (cmdName.equals("case")) {
                    if (se.getOriginalArguments().size() > 0) {
                        for (String arg : se.getOriginalArguments()) {
                            lookupTable.put(CoreUtilities.toLowerCase(arg), i);
                        }
                    }
                    else {
                        Debug.echoError("Unknown choose sub-command (missing arguments) '" + se + "'!");
                    }
                }
                else {
                    Debug.echoError("Unknown choose sub-command '" + cmdName + "'!");
                }
            }
            scriptEntry.internal.specialProcessedData = lookupTable;
        }
        String choice_low = choice.asLowerString();
        Integer resultIndex = lookupTable.get(choice_low);
        if (resultIndex == null) {
            resultIndex = lookupTable.get("\0DEFAULT");
            if (resultIndex == null) {
                Debug.echoDebug(scriptEntry, "No result!");
                return;
            }
        }
        ScriptEntry result = bracedCommandsList.get(resultIndex);
        List<ScriptEntry> new_command_list = caseBodyFor(scriptEntry, resultIndex, result);
        if (new_command_list == null) {
            Debug.echoError(scriptEntry, "Empty choose command case sub-commands (internal) for case '" + result.toString() + "'");
            return;
        }
        scriptEntry.setInstant(true);
        queue.injectEntriesAtStart(new_command_list);
    }

    /**
     * The copy of one case's body, built once per entry and reused.
     * <p>
     * Indexed by case, the same way {@link IfCommand#branchBodyFor} indexes branches: which case runs varies per execution, but each one is
     * the same lines every time, and the previous run's copy is always consumed from the queue before the entry can run again.
     */
    public static List<ScriptEntry> caseBodyFor(ScriptEntry scriptEntry, int index, ScriptEntry caseEntry) {
        List<List<ScriptEntry>> cases = scriptEntry.inlinedBranches;
        if (cases == null) {
            cases = new ArrayList<>(index + 1);
            scriptEntry.inlinedBranches = cases;
        }
        while (cases.size() <= index) {
            cases.add(null);
        }
        List<ScriptEntry> body = cases.get(index);
        if (body != null) {
            ScriptEntry.resetBodyForReuse(body);
            return body;
        }
        List<BracedData> caseBraces = getBracedCommands(caseEntry, false);
        if (caseBraces == null || caseBraces.isEmpty()) {
            return null;
        }
        body = duplicateBracedSection(caseBraces.get(0), scriptEntry);
        if (body == null) {
            return null;
        }
        for (ScriptEntry entry : body) {
            entry.setInstant(true);
        }
        cases.set(index, body);
        return body;
    }
}
