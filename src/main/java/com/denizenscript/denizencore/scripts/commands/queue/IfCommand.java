package com.denizenscript.denizencore.scripts.commands.queue;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.scripts.commands.Comparable;
import com.denizenscript.denizencore.utilities.CoreConfiguration;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.Deprecations;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.BracedCommand;
import com.denizenscript.denizencore.tags.TagManager;

import java.util.ArrayList;
import java.util.List;

public class IfCommand extends BracedCommand {

    public IfCommand() {
        setName("if");
        setSyntax("if [<value>] (!)(<operator> <value>) (&&/|| ...) [<commands>]");
        setRequiredArguments(1, -1);
        setParseArgs(false);
        isProcedural = true;
        asyncSafe = true; // Only touches its own queue and thread-safe data.
    }

    // <--[command]
    // @Name If
    // @Syntax if [<value>] (!)(<operator> <value>) (&&/|| ...) [<commands>]
    // @Required 1
    // @Maximum -1
    // @Short Compares values, and runs a subset of commands if they match.
    // @Group queue
    // @Guide https://guide.denizenscript.com/guides/basics/if-command.html
    //
    // @Description
    // Compares values, and runs a subset of commands if they match.
    // Works with the else command, which handles alternatives for when the comparison fails.
    // The if command is equivalent to the English phrasing "if something is true, then do the following".
    //
    // Values are compared using the comparable system. See <@link language operator> for information.
    //
    // Comparisons may be chained together using the symbols '&&' and '||' or their text equivalents 'and' and 'or'.
    // '&&' means "and", '||' means "or".
    // So, for example "if <[a]> && <[b]>:" requires both a AND b to be true.
    // "if <[a]> and <[b]>:" also requires both a AND b to be true.
    //
    // The "or" is inclusive, meaning "if <[a]> || <[b]>:" will pass for any of the following:
    // a = true, b = true
    // a = true, b = false
    // a = false, b = true
    // but will fail when a = false and b = false.
    //
    // Sets of comparisons may be grouped using ( parens ) as separate arguments.
    // So, for example "if ( <[a]> && <[b]> ) || <[c]>", or "if ( <[x]> or <[y]> or <[z]> ) and ( <[a]> or <[b]> or <[c]> )"
    // Grouping is REQUIRED when using both '&&' and '||' in one line. Otherwise, groupings should not be used at all.
    //
    // Boolean inputs and groups both support negating with the '!' symbol as a prefix.
    // This means you can do "if !<[a]>" to say "if a is NOT true".
    // Similarly, you can do "if !( <[a]> || <[b]> )", though be aware that per rules of boolean logic,
    // that example is the exactly same as "if !<[a]> && !<[b]>".
    //
    // You can also use keyword "not" as its own argument to negate a boolean or an operator.
    // For example, "if not <[a]>:" will require a to be false, and "if <[a]> not equals <[b]>:" will require that 'a' does not equal 'b'.
    //
    // When not using a specific comparison operator, true vs false will be determined by Truthiness, see <@link tag ObjectTag.is_truthy> for details.
    // For example, "- if <player||null>:" will pass if a player is linked, valid, and online.
    //
    // @Tags
    // <ObjectTag.is[<operator>].to[<element>]>
    // <ObjectTag.is[<operator>].than[<element>]>
    //
    // @Usage
    // Use to narrate a message only if a player has a flag.
    // - if <player.has_flag[secrets]>:
    //     - narrate "The secret number is 3!"
    //
    // @Usage
    // Use to narrate a different message depending on a player's money level.
    // - if <player.money> > 1000:
    //     - narrate "You're rich!"
    // - else:
    //     - narrate "You're poor!"
    //
    // @Usage
    // Use to stop a script if a player doesn't have all the prerequisites.
    // - if !<player.has_flag[quest_complete]> || !<player.has_permission[new_quests]> || <player.money> < 50:
    //     - narrate "You're not ready!"
    //     - stop
    // - narrate "Okay so your quest is to find the needle item in the haystack build next to town."
    //
    // @Usage
    // Use to perform a complicated requirements test before before changing some event.
    // - if ( poison|magic|melting contains <context.cause> and <context.damage> > 5 ) or <player.has_flag[weak]>:
    //     - determine <context.damage.mul[2]>
    //
    // -->

    /**
     * Everything about one if or else line that is the same on every run: how its arguments split, and its compiled condition.
     * <p>
     * parseArgs runs before every execution, and all of this used to be rebuilt there each time even though it is a pure
     * function of the line as written. The lists here are shared between runs, so nothing may modify them.
     */
    public static class ParsedIf {

        public boolean hasBrace, hasSubcommand, hasElsecommand;

        public List<String> bracedArgs, comparisons, subcommand, elsecommand;

        /** What BracedData carries as its key. Debug output only, but it was being rebuilt from scratch on every execution. */
        public String key;

        public Condition condition;
    }

    /** Splits an if or else line once and caches the result on the entry. {@code leadWord} is what BracedData expects at the front of its args. */
    public static ParsedIf parsedFor(ScriptEntry entry, String leadWord) {
        if (entry.internal.specialProcessedData instanceof ParsedIf) {
            return (ParsedIf) entry.internal.specialProcessedData;
        }
        ParsedIf parsed = new ParsedIf();
        List<String> original = entry.getOriginalArguments();
        parsed.bracedArgs = new ArrayList<>(original.size() + 1);
        parsed.bracedArgs.add(leadWord);
        parsed.bracedArgs.addAll(original);
        parsed.key = entry.toString();
        parsed.hasBrace = entry.getInsideList() != null;
        if (!parsed.hasBrace) {
            for (String arg : original) {
                if (arg.equals("{")) {
                    if (CoreConfiguration.debugVerbose) {
                        Debug.log("Has_brace = true");
                    }
                    parsed.hasBrace = true;
                    break;
                }
            }
        }
        parsed.subcommand = new ArrayList<>();
        parsed.elsecommand = new ArrayList<>();
        parsed.comparisons = new ArrayList<>();
        for (String arg : original) {
            if (arg.equals("{")) {
                break;
            }
            if (!parsed.hasBrace && parsed.hasSubcommand && CoreUtilities.equalsIgnoreCase(arg, "else")) {
                parsed.hasElsecommand = true;
                parsed.hasSubcommand = false;
            }
            else if (!parsed.hasBrace && !parsed.hasElsecommand && DenizenCore.commandRegistry.get(CoreUtilities.toUpperCase(arg)) != null) {
                Deprecations.ifCommandSingleLine.warn(entry);
                parsed.hasSubcommand = true;
                parsed.subcommand.add(arg);
            }
            else if (!parsed.hasBrace && parsed.hasSubcommand) {
                parsed.subcommand.add(arg);
            }
            else if (!parsed.hasBrace && parsed.hasElsecommand) {
                parsed.elsecommand.add(arg);
            }
            else {
                parsed.comparisons.add(arg);
            }
        }
        // An "else" with no "if" after it has no condition of its own - it is the fallback branch.
        List<String> conditionArgs = leadWord.equals("else") ? (parsed.bracedArgs.size() > 2 ? parsed.bracedArgs.subList(2, parsed.bracedArgs.size()) : null) : parsed.comparisons;
        parsed.condition = conditionArgs == null ? null : ArgComparer.compile(conditionArgs);
        entry.internal.specialProcessedData = parsed;
        return parsed;
    }

    @Override
    public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {
        ParsedIf parsed = parsedFor(scriptEntry, "if");
        boolean has_brace = parsed.hasBrace;
        if (scriptEntry.getInsideList() != null) {
            // 'false' - the body is NOT cloned here. parseArgs runs on every execution, and cloning every line of every branch before the
            // condition has even been read costs a ScriptEntry clone per line whether that branch runs or not. execute clones the one branch
            // it picks, through BracedCommand.duplicateBracedSection.
            List<BracedData> allData = new ArrayList<>();
            BracedData ifRef = new BracedData();
            ifRef.entry = scriptEntry;
            ifRef.value = getBracedCommands(scriptEntry, false).get(0).value;
            ifRef.key = parsed.key;
            ifRef.args = parsed.bracedArgs;
            allData.add(ifRef);
            while (scriptEntry.getResidingQueue().script_entries.size() > 0) {
                ScriptEntry nextEntry = scriptEntry.getResidingQueue().script_entries.get(0);
                if (!(nextEntry.getCommand() instanceof ElseCommand)) {
                    break;
                }
                if (nextEntry.getInsideList() == null) {
                    Debug.echoError(scriptEntry, "Upcoming else command is mis-formatted!");
                    break;
                }
                scriptEntry.getResidingQueue().script_entries.removeFirst();
                nextEntry.context = scriptEntry.context;
                nextEntry.entryData = scriptEntry.entryData;
                nextEntry.queue = scriptEntry.queue;
                BracedData elseRef = new BracedData();
                elseRef.value = getBracedCommands(nextEntry, false).get(0).value;
                elseRef.entry = nextEntry;
                ParsedIf elseParsed = parsedFor(nextEntry, "else");
                elseRef.key = elseParsed.key;
                elseRef.args = elseParsed.bracedArgs;
                allData.add(elseRef);
            }
            scriptEntry.addObject("braces", allData);
        }
        else if (has_brace) {
            scriptEntry.addObject("braces", getBracedCommands(scriptEntry, false));
        }
        if (!has_brace && parsed.hasElsecommand) {
            scriptEntry.addObject("elsecommand", parsed.elsecommand);
        }
        if (!has_brace && (parsed.hasSubcommand || parsed.hasElsecommand)) {
            scriptEntry.addObject("subcommand", parsed.subcommand);
        }
        scriptEntry.addObject("comparisons", parsed.comparisons);
    }

    @Override
    public void execute(ScriptEntry scriptEntry) {
        List<String> subcommand = (List<String>) scriptEntry.getObject("subcommand");
        List<String> elsecommand = (List<String>) scriptEntry.getObject("elsecommand");
        List<String> comparisons = (List<String>) scriptEntry.getObject("comparisons");
        List<BracedData> braces = (List<BracedData>) scriptEntry.getObject("braces");
        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, getName(), db("use_braces", braces != null));
        }
        if (CoreConfiguration.debugVerbose) {
            Debug.log("comparisons=" + comparisons + ", sc:" + subcommand + ", ec:" + elsecommand);
        }
        boolean first_set = parsedFor(scriptEntry, "if").condition.evaluate(scriptEntry);
        if (first_set && subcommand != null && subcommand.size() > 0) {
            executeCommandList(subcommand, scriptEntry);
            return;
        }
        if (!first_set && elsecommand != null && elsecommand.size() > 0) {
            executeCommandList(elsecommand, scriptEntry);
            return;
        }
        if (braces != null) {
            if (braces.isEmpty()) {
                Debug.echoError(scriptEntry, "Failed to parse IF command: mis-aligned bracing, empty subsections, or other basic formatting error.");
                return;
            }
            if (first_set) {
                if (CoreConfiguration.debugVerbose) {
                    Debug.log("Running the first set");
                }
                Debug.echoDebug(scriptEntry, "<Y>If command passed, running block.");
                List<ScriptEntry> bracedCommandsList = duplicateBracedSection(braces.get(0), braces.get(0).entry);
                if (bracedCommandsList == null) {
                    Debug.echoError(scriptEntry, "Failed to parse IF command: mis-aligned bracing, empty subsections, or other basic formatting error.");
                    return;
                }
                scriptEntry.setInstant(true);
                for (ScriptEntry entry : bracedCommandsList) {
                    entry.setInstant(true);
                }
                scriptEntry.getResidingQueue().injectEntriesAtStart(bracedCommandsList);
                return;
            }
            else {
                for (int z = 1; z < braces.size(); z++) {
                    BracedData braceSet = braces.get(z);
                    if (CoreConfiguration.debugVerbose) {
                        Debug.log("Trying: " + braceSet.key);
                    }
                    List<String> key = braceSet.args;
                    if (key.isEmpty() || !CoreUtilities.equalsIgnoreCase(key.get(0), "else")) {
                        Debug.echoError("If command has argument '" + key.get(0) + "' which is unknown.");
                        continue;
                    }
                    if (key.size() > 1) {
                        if (!CoreUtilities.equalsIgnoreCase(key.get(1), "if")) {
                            Debug.echoError("Else command has argument '" + key.get(1) + "' which is unknown.");
                            continue;
                        }
                        // Only trust the entry's cache when this really is the args list it was built from - the legacy brace path
                        // hands over BracedData that this command did not assemble, and its entry may be someone else entirely.
                        Condition elseCondition = null;
                        if (braceSet.entry != null && braceSet.entry.internal.specialProcessedData instanceof ParsedIf) {
                            ParsedIf elseParsed = (ParsedIf) braceSet.entry.internal.specialProcessedData;
                            if (elseParsed.bracedArgs == key) {
                                elseCondition = elseParsed.condition;
                            }
                        }
                        if (elseCondition == null) {
                            elseCondition = ArgComparer.compile(key.subList(2, key.size()));
                        }
                        if (!elseCondition.evaluate(braceSet.entry)) {
                            continue;
                        }
                        Debug.echoDebug(scriptEntry, "<Y>If/else-if chain entry #" + (z + 1) + " passed, running block.");
                    }
                    else {
                        Debug.echoDebug(scriptEntry, "<Y>No part of the if command passed, running ELSE block.");
                    }
                    List<ScriptEntry> bracedCommandsList = duplicateBracedSection(braceSet, braceSet.entry);
                    if (bracedCommandsList == null) {
                        Debug.echoError(scriptEntry, "Failed to parse IF command: mis-aligned bracing, empty subsections, or other basic formatting error.");
                        return;
                    }
                    scriptEntry.setInstant(true);
                    for (ScriptEntry entry : bracedCommandsList) {
                        entry.setInstant(true);
                    }
                    scriptEntry.getResidingQueue().injectEntriesAtStart(bracedCommandsList);
                    return;
                }
            }
        }
        Debug.echoDebug(scriptEntry, "<Y>No part of the if command passed, no block will run.");
    }

    public void executeCommandList(List<String> subcommand, ScriptEntry scriptEntry) {
        try {
            scriptEntry.setInstant(true);
            String cmd = subcommand.get(0);
            String[] cmdArgs = new String[subcommand.size() - 1];
            for (int i = 1; i < subcommand.size(); i++) {
                cmdArgs[i - 1] = subcommand.get(i);
            }
            ScriptEntry entry = new ScriptEntry(cmd, cmdArgs, scriptEntry.getScript() != null ? scriptEntry.getScript().getContainer() : null);
            entry.entryData = scriptEntry.entryData.clone();
            entry.updateContext();
            entry.setInstant(true);
            scriptEntry.getResidingQueue().injectEntryAtStart(entry);
        }
        catch (Exception e) {
            Debug.echoError(e);
        }
    }

    /**
     * A condition compiled out of an argument list once, then evaluated as many times as the line runs.
     * <p>
     * Compiling separates the two halves of what {@link ArgComparer} used to do on every execution: finding the structure
     * (parentheses, operators, comparison shape) and reading the values. Only the second half depends on the run,
     * so the first is done once and cached on the entry - see {@link #conditionFor}.
     */
    public interface Condition {

        boolean evaluate(ScriptEntry scriptEntry);
    }

    /**
     * Returns the compiled condition for an entry, compiling it on the first run and reusing it after.
     * <p>
     * A compiled condition only reads, so one instance is safe to share between the queues running the same script,
     * including off-thread ones - which is why it can live on the entry's shared internal data.
     */
    public static Condition conditionFor(ScriptEntry cacheEntry, List args) {
        if (cacheEntry.internal.specialProcessedData instanceof Condition) {
            return (Condition) cacheEntry.internal.specialProcessedData;
        }
        Condition compiled = ArgComparer.compile(args);
        cacheEntry.internal.specialProcessedData = compiled;
        return compiled;
    }

    public static class ArgComparer {

        public static class ArgInternal {

            boolean negative;

            ObjectTag value;

            boolean boolify() {
                return value.isTruthy() != negative;
            }

            @Override
            public String toString() {
                return negative ? "!" + value : value.toString();
            }
        }

        public static String procString(Object arg) {
            if (arg instanceof String) {
                return (String) arg;
            }
            else if (arg instanceof ScriptEntry.InternalArgument) {
                return ((ScriptEntry.InternalArgument) arg).fullOriginalRawValue;
            }
            else if (arg instanceof ArgInternal) {
                return arg.toString();
            }
            else if (arg instanceof Boolean) {
                return ((Boolean) arg) ? "true" : "false";
            }
            return arg.toString();
        }

        public static String procStringNoTag(Object arg) {
            if (arg instanceof String) {
                return (String) arg;
            }
            else if (arg instanceof ScriptEntry.InternalArgument) {
                return ((ScriptEntry.InternalArgument) arg).fullOriginalRawValue;
            }
            else if (arg instanceof ArgInternal) {
                return arg.toString();
            }
            else if (arg instanceof Condition) {
                return "<UnTaggedComparison>";
            }
            else if (arg instanceof Boolean) {
                return ((Boolean) arg) ? "true" : "false";
            }
            return arg.toString();
        }

        public static ArgInternal tagify(ScriptEntry scriptEntry, String arg, boolean canNegate) {
            ArgInternal toRet = new ArgInternal();
            if (arg.startsWith("!") && canNegate) {
                toRet.negative = true;
                arg = arg.substring(1);
            }
            toRet.value = TagManager.tagObject(arg, DenizenCore.implementation.getTagContext(scriptEntry));
            return toRet;
        }

        public static ArgInternal tagme(ScriptEntry scriptEntry, Object argObj, boolean canNegate) {
            if (argObj instanceof String) {
                return tagify(scriptEntry, (String) argObj, canNegate);
            }
            else if (argObj instanceof ScriptEntry.InternalArgument) {
                // TODO: Special case tag parsing
                return tagify(scriptEntry, ((ScriptEntry.InternalArgument) argObj).fullOriginalRawValue, canNegate);
            }
            else if (argObj instanceof ArgInternal) {
                return (ArgInternal) argObj;
            }
            return tagify(scriptEntry, procString(argObj), canNegate);
        }

        public static boolean tagbool(ScriptEntry scriptEntry, Object argObj, boolean canNegate) {
            if (argObj instanceof Condition) {
                return ((Condition) argObj).evaluate(scriptEntry);
            }
            else if (argObj instanceof Boolean) {
                return (Boolean) argObj;
            }
            return tagme(scriptEntry, argObj, canNegate).boolify();
        }

        /** Reads one element as an object for a comparison. A crunched parenthesis group reduces to its own boolean first. */
        public static ObjectTag tagvalue(ScriptEntry scriptEntry, Object argObj) {
            if (argObj instanceof Condition) {
                return new ElementTag(((Condition) argObj).evaluate(scriptEntry));
            }
            return tagme(scriptEntry, argObj, false).value;
        }

        /**
         * Builds the condition tree for an argument list.
         * <p>
         * The structure decisions here are the ones the old per-execution walk made, in the same order and with the same
         * outcomes: parentheses are crunched left to right, then the list is split at the FIRST of '||'/'or'/'&&'/'and'
         * that appears. That split is deliberately without precedence between the two - it is what every existing script
         * was written against, and it is why the command's docs require grouping when both are used on one line.
         */
        public static Condition compile(List args) {
            if (args == null || args.isEmpty()) {
                return scriptEntry -> false;
            }
            if (args.size() == 1) {
                Object only = args.get(0);
                return scriptEntry -> tagbool(scriptEntry, only, true);
            }
            List crunched = null;
            for (int i = 0; i < args.size(); i++) {
                Object rawArg = args.get(i);
                String arg = procStringNoTag(rawArg);
                if (arg.equals("(") || arg.equals("!(")) {
                    List subargs = new ArrayList(args.size());
                    int count = 0;
                    int close = -1;
                    for (int x = i + 1; x < args.size(); x++) {
                        String xarg = procStringNoTag(args.get(x));
                        if (xarg.equals("(") || xarg.equals("!(")) {
                            count++;
                            subargs.add(xarg);
                        }
                        else if (xarg.equals(")")) {
                            count--;
                            if (count == -1) {
                                close = x;
                                break;
                            }
                            else {
                                subargs.add(")");
                            }
                        }
                        else {
                            subargs.add(args.get(x));
                        }
                    }
                    if (close == -1) {
                        return scriptEntry -> false;
                    }
                    Condition sub = compile(subargs);
                    Condition group = arg.startsWith("!") ? (scriptEntry -> !sub.evaluate(scriptEntry)) : sub;
                    if (crunched == null) {
                        crunched = new ArrayList(args.size());
                        crunched.addAll(args.subList(0, i));
                    }
                    crunched.add(group);
                    i = close;
                }
                else if (arg.equals(")")) {
                    return scriptEntry -> false;
                }
                else if (crunched != null) {
                    crunched.add(rawArg);
                }
            }
            if (crunched != null) {
                args = crunched;
            }
            if (args.size() == 1) {
                Object only = args.get(0);
                return scriptEntry -> tagbool(scriptEntry, only, true);
            }
            for (int i = 0; i < args.size(); i++) {
                String argLow = CoreUtilities.toLowerCase(procStringNoTag(args.get(i)));
                boolean isOr = argLow.equals("||") || argLow.equals("or");
                if (isOr || argLow.equals("&&") || argLow.equals("and")) {
                    Condition before = compile(new ArrayList(args.subList(0, i)));
                    Condition after = compile(new ArrayList(args.subList(i + 1, args.size())));
                    if (isOr) {
                        return scriptEntry -> before.evaluate(scriptEntry) || after.evaluate(scriptEntry);
                    }
                    return scriptEntry -> before.evaluate(scriptEntry) && after.evaluate(scriptEntry);
                }
            }
            if (args.size() == 2) {
                if (CoreUtilities.toLowerCase(procStringNoTag(args.get(0))).equals("not")) {
                    Object only = args.get(1);
                    return scriptEntry -> !tagbool(scriptEntry, only, false);
                }
                return scriptEntry -> false;
            }
            String operatorArg;
            boolean negative = false;
            if (args.size() == 4 && CoreUtilities.toLowerCase(procStringNoTag(args.get(1))).equals("not")) {
                operatorArg = procStringNoTag(args.get(2));
                negative = true;
            }
            else if (args.size() == 3) {
                operatorArg = procStringNoTag(args.get(1));
                if (operatorArg.startsWith("!")) {
                    operatorArg = operatorArg.substring(1);
                    negative = true;
                }
            }
            else {
                String found = args.size() + " args: " + args;
                return scriptEntry -> {
                    Debug.echoError(scriptEntry, "If command syntax invalid - too many arguments? Found " + found);
                    return false;
                };
            }
            Comparable.Operator operator;
            try {
                operator = Comparable.getOperatorFor(operatorArg);
            }
            catch (Throwable ex) {
                operator = null;
            }
            if (operator == null) {
                String badOperator = operatorArg;
                return scriptEntry -> {
                    Debug.echoError(scriptEntry, "If command syntax invalid - invalid operator '" + badOperator + "'");
                    return false;
                };
            }
            Object leftArg = args.get(0), rightArg = args.get(args.size() - 1);
            Comparable.Operator finalOperator = operator;
            boolean finalNegative = negative;
            return scriptEntry -> {
                try {
                    ObjectTag first = tagvalue(scriptEntry, leftArg);
                    ObjectTag second = tagvalue(scriptEntry, rightArg);
                    boolean outcome = Comparable.compare(first, second, finalOperator, finalNegative, scriptEntry.context);
                    if (scriptEntry.dbCallShouldDebug()) {
                        Debug.echoDebug(scriptEntry, "Comparing if " + first + (finalNegative ? " not " : " ") + finalOperator.name() + " " + second + " ... " + outcome);
                    }
                    return outcome;
                }
                catch (Throwable ex) {
                    Debug.echoError(scriptEntry, "If command syntax invalid - possibly wrong number of arguments (check for stray spaces)? exception: " + ex.getClass().getName() + ": " + ex.getMessage());
                    if (CoreConfiguration.debugVerbose) {
                        Debug.echoError(ex);
                    }
                    return false;
                }
            };
        }

        /** Compiles and evaluates in one go, for callers that have nowhere to cache the compiled form. */
        public boolean compare(List args, ScriptEntry scriptEntry) {
            return compile(args).evaluate(scriptEntry);
        }
    }
}
