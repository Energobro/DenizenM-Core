package com.denizenscript.denizencore.scripts.commands.core;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.utilities.debugging.Debug;

/**
 * This command exists for technical reasons, to handle invalid commands.
 */
public class DebugInvalidCommand extends AbstractCommand {

    public DebugInvalidCommand() {
        setName("debug-invalid-command");
        setSyntax("");
        setRequiredArguments(0, Integer.MAX_VALUE);
        isProcedural = true;
        // Never registered under a name (see CommandRegistry), but ScriptEntry hands entries to it for two cases: a command that isn't in the
        // registry at all, and a real command written with the wrong number of arguments. Both are reachable from an async script, and both
        // would otherwise stall it a tick each - a typo inside a 'repeat 1000' costing fifty seconds of main thread to say so a thousand times.
        // Nothing here needs the main thread: the registry is filled at startup and only read afterwards, the entry internals are already
        // isolated for anything running off-thread (ScriptEngine.prepareEntry), and the re-injection goes into this queue's own entry list,
        // from the thread running that queue.
        asyncSafe = true;
    }

    @Override
    public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {
    }

    public static void informBrokenArgs(AbstractCommand command, ScriptEntry scriptEntry) {
        if (scriptEntry.getOriginalArguments().size() > command.maximumArguments) {
            Debug.echoError(scriptEntry, scriptEntry + " cannot be executed! Too many arguments - did you forget to use quotes?\nUsage: " + command.getUsageHint());
        }
        else if (command.generatedExecutor != null && scriptEntry.internal.arguments_to_use.length > command.linearHandledCount) {
            String badPrefix = null;
            for (ScriptEntry.InternalArgument arg : scriptEntry.internal.arguments_to_use) {
                if (arg.prefix != null) {
                    badPrefix = arg.prefix.fullOriginalRawValue;
                    break;
                }
            }
            if (badPrefix == null) {
                Debug.echoError(scriptEntry, scriptEntry + " cannot be executed! Too many linear arguments - did you forget to use quotes, or forget a prefix?\nUsage: " + command.getUsageHint());
            }
            else {
                Debug.echoError(scriptEntry, scriptEntry + " cannot be executed! Too many linear arguments... Prefix '" + badPrefix + "' given is unrecognized. Possible typo?\nUsage: " + command.getUsageHint());
            }
        }
        else {
            Debug.echoError(scriptEntry, scriptEntry + " cannot be executed! Too few arguments - did you forget a required input?\nUsage: " + command.getUsageHint());
        }
    }

    @Override
    public void execute(ScriptEntry scriptEntry) {
        Debug.echoDebug(scriptEntry, Debug.DebugElement.Header, "Executing command: " + scriptEntry.getCommandName());
        AbstractCommand command = DenizenCore.commandRegistry.get(scriptEntry.internal.command);
        if (scriptEntry.internal.brokenArgs) {
            informBrokenArgs(command, scriptEntry);
            // Both dead ends here must release the queue: a '~' line was put on hold by ScriptEngine before this ran, and setFinished is the
            // only thing that lifts it, so without this '- ~define' with no arguments stops its queue forever. A no-op on a line without '~'.
            scriptEntry.setFinished(true);
            return;
        }
        else {
            if (command != null) {
                int argCount = scriptEntry.getOriginalArguments().size();
                if (argCount < command.minimumArguments || argCount > command.maximumArguments) {
                    scriptEntry.internal.brokenArgs = true;
                }
                else {
                    scriptEntry.internal.actualCommand = command;
                }
                scriptEntry.getResidingQueue().injectEntryAtStart(scriptEntry);
                return;
            }
            Debug.echoError(scriptEntry, scriptEntry.getCommandName() + " is an invalid command! Are you sure it loaded?");
        }
        scriptEntry.setFinished(true);
        Debug.echoDebug(scriptEntry, Debug.DebugElement.Footer);
    }
}
