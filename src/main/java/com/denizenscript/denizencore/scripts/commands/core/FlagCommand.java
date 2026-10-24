package com.denizenscript.denizencore.scripts.commands.core;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.flags.AbstractFlagTracker;
import com.denizenscript.denizencore.flags.FlaggableObject;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.*;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.data.ActionableDataProvider;
import com.denizenscript.denizencore.utilities.data.DataAction;
import com.denizenscript.denizencore.utilities.data.DataActionHelper;
import com.denizenscript.denizencore.utilities.data.DataActionType;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.utilities.text.StringHolder;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class FlagCommand extends AbstractCommand {

    public FlagCommand() {
        setName("flag");
        setSyntax("flag [<object>|...] [<name>([<#>])](:<action>)[:<value>] (expire:<time>)");
        setRequiredArguments(1, 3);
        isProcedural = false;
        allowedDynamicPrefixes = true;
        // Async-safe as a plain field rather than per entry, because which targets a line writes to is only known after parseArgs,
        // while isAsyncSafe(ScriptEntry) is asked before it. So the split happens inside execute instead: a line whose every target
        // keeps its flags in Denizen's own storage runs on the script's thread, and anything else hands the whole line over exactly as before.
        // Deferring is not an option for this command at all - scripts read a flag on the very next line after writing it.
        setAsyncSafe(true);
    }

    // <--[language]
    // @name Flag System
    // @group Denizen Scripting Language
    // @description
    // The flag system is a core feature of Denizen, that allows for persistent data storage linked to objects.
    //
    // "Persistent" means the data is still around even after a server restart or anything else, and is only removed when you choose for it to be removed.
    // "Linked to objects" means rather than purely global values, flags are associated with a player, or an NPC, or a block, or whatever else.
    //
    // See also the guide page at <@link url https://guide.denizenscript.com/guides/basics/flags.html>.
    //
    // For non-persistent temporary memory, see instead <@link command define>.
    // For more generic memory options, see <@link command yaml> or <@link command sql>.
    //
    // Flags can be sub-mapped with the '.' character, meaning a flag named 'x.y.z' is actually a flag 'x' as a MapTag with key 'y' as a MapTag with key 'z' as the final flag value.
    // In other words, "<server.flag[a.b.c]>" is equivalent to "<server.flag[a].get[b].get[c]>"
    //
    // Server flags can be set by specifying 'server' as the object, essentially a global flag target, that will store data in the file "plugins/Denizen/server_flags.dat"
    //
    // Most unique object types are flaggable - refer to any given object type's language documentation for details.
    //
    // Most flag sets are handled by <@link command flag>, however some non-unique objects (such as items) are flagged with specialty commands or tags unique to the type.
    // Any supported object type, including the 'server' base tag, can use the tags
    // <@link tag FlaggableObject.flag>, <@link tag FlaggableObject.has_flag>, <@link tag FlaggableObject.flag_expiration>, <@link tag FlaggableObject.list_flags>.
    //
    // Flags have an expiration system, which is used by specifying a time at which they should expire (or via a duration which internally calculates the date/time of expiration by adding the duration input to the current date/time).
    // Expirations are then *checked for* in flag tags - meaning, the flag tags will internally compare a stored date/time against the real current date/time,
    // and if the flag's expiration time is in the past, the flag tag will return values equivalent to if the flag doesn't exist.
    // There is no system actively monitoring for flag expirations or applying them. There is no event for expirations occurring, as they don't "occur" per se.
    // In other words, it is correct to say a flag "is expired" or a flag "is not expired",
    // but it is incorrect to say a flag "expires", as it is not an active action (though this wording can be convenient when speaking informally).
    // Expired flags are sometimes 'cleaned up' (meaning, any expired flags get actually removed from internal storage), usually when a flag save file is loaded into the server.
    //
    // As a bonus feature-combo, it is possible to transmit sets of flags exactly in-place and reapply them, this is particular useful for example to synchronize player data across Bungee servers.
    // To do this, you can read raw flag data with the tag <@link tag FlaggableObject.flag_map> and the '__raw' prefix in a flag command. For example:
    // <code>
    // # Gather the original data
    // - define playerdata <player.flag_map[flag1|flag2|taco|potato|waffle|etc]>
    // # Now reapply it elsewhere (eg a different Bungee server)
    // - flag <player> __raw:<[playerdata]>
    // </code>
    // -->

    // <--[command]
    // @Name Flag
    // @Syntax flag [<object>|...] [<name>([<#>])](:<action>)[:<value>] (expire:<time>)
    // @Required 2
    // @Maximum 3
    // @Short Sets or modifies a flag on any flaggable object.
    // @Group core
    // @Guide https://guide.denizenscript.com/guides/basics/flags.html
    //
    // @Description
    // The flag command sets or modifies custom data values stored on any flaggable object (the server, a player/NPC/entity, a block location, ...).
    // See also <@link language flag system>.
    //
    // This command supports data actions, see <@link language data actions>.
    //
    // Flags by default are added permanently (or for the lifetime of the object they're attached to).
    // You can optionally specify a system time the flag will expire at, using either a DurationTag or a TimeTag.
    // If a DurationTag is used, it will be equivalent to: <util.time_now.add[<your_duration_here>]>
    //
    // @Tags
    // <FlaggableObject.flag[<flag_name>]>
    // <FlaggableObject.has_flag[<flag_name>]>
    // <FlaggableObject.flag_expiration[<flag_name>]>
    // <FlaggableObject.list_flags>
    //
    // @Usage
    // Use to create or set a flag on a player.
    // - flag <player> playstyle:aggressive
    //
    // @Usage
    // Use to set a temporary flag for five minutes on a player.
    // - flag <player> just_did_something expire:5m
    //
    // @Usage
    // Use to flag an npc with a given tag value.
    // - flag <npc> location:<npc.location>
    //
    // @Usage
    // Use to apply mathematical changes to a flag's value on a unique object.
    // - flag <context.damager> damage_dealt:+:<context.damage>
    //
    // @Usage
    // Use to add an item to a server flag as a new value without removing existing values.
    // - flag server cool_people:->:<[player]>
    //
    // @Usage
    // Use to add multiple items as individual new values to a server flag that is already a list.
    // - flag server cool_people:|:<[player]>|<[someplayer]>
    //
    // @Usage
    // Use to remove an entry from a server flag.
    // - flag server cool_people:<-:<[someplayer]>
    //
    // @Usage
    // Use to completely remove a flag.
    // - flag server cool_people:!
    //
    // @Usage
    // Use to modify a specific index in a list flag.
    // - flag server myflag[3]:HelloWorld
    // -->

    public static List<Consumer<TabCompletionsBuilder>> flagTabCompleters = new ArrayList<>();

    public static void tabCompleteFlag(TabCompletionsBuilder tab) {
        if (tab.arg.contains(":")) {
            return;
        }
        for (String flagName : DenizenCore.serverFlagMap.listAllFlags()) {
            if (!flagName.startsWith("__")) {
                tab.add(flagName);
            }
        }
        for (Consumer<TabCompletionsBuilder> consumer : flagTabCompleters) {
            consumer.accept(tab);
        }
    }

    @Override
    public void addCustomTabCompletions(TabCompletionsBuilder tab) {
        tabCompleteFlag(tab);
    }

    @Override
    public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {
        for (Argument arg : scriptEntry) {
            if (!scriptEntry.hasObject("targets")
                && !arg.hasPrefix()) {
                scriptEntry.addObject("targets", targetsFor(scriptEntry, arg));
            }
            else {
                boolean expirationPrefix = !scriptEntry.hasObject("expiration") && arg.matchesPrefix("expire", "expires", "expiration", "duration");
                CachedAction knownDuration = expirationPrefix ? knownDurationFor(scriptEntry, arg) : null;
                if (knownDuration != null) {
                    scriptEntry.addObject("expiration", new TimeTag(DenizenCore.currentTimeMillis + knownDuration.expireMillis, ZoneId.systemDefault()));
                    continue;
                }
                int expiration = expirationPrefix ? expirationKindOf(arg) : EXPIRATION_NONE;
                if (expiration == EXPIRATION_TIME) {
                    scriptEntry.addObject("expiration", arg.asType(TimeTag.class));
                }
                else if (expiration == EXPIRATION_DURATION) {
                    long millis = arg.asType(DurationTag.class).getMillis();
                    rememberDuration(scriptEntry, arg, millis);
                    scriptEntry.addObject("expiration", new TimeTag(DenizenCore.currentTimeMillis + millis, ZoneId.systemDefault()));
                }
                else if (!scriptEntry.hasObject("flag_action")) {
                    scriptEntry.addObject("flag_action", flagActionFor(scriptEntry, arg));
                }
                else {
                    arg.reportUnhandled();
                }
            }
        }
        if (!scriptEntry.hasObject("targets")) {
            throw new InvalidArgumentsException("Must specify flag target(s)!");
        }
        if (!scriptEntry.hasObject("flag_action")) {
            throw new InvalidArgumentsException("Must specify a flag to set!");
        }
    }

    public static final int EXPIRATION_NONE = 0, EXPIRATION_TIME = 1, EXPIRATION_DURATION = 2;

    public static int expirationKindOf(Argument arg) {
        if (arg.matchesArgumentType(TimeTag.class)) {
            return EXPIRATION_TIME;
        }
        return arg.matchesArgumentType(DurationTag.class) ? EXPIRATION_DURATION : EXPIRATION_NONE;
    }

    public static boolean isFixedText(Argument arg) {
        return arg.object instanceof ElementTag element && element.isRawInput;
    }

    public static CachedAction knownDurationFor(ScriptEntry scriptEntry, Argument arg) {
        CachedAction cached = cacheOf(scriptEntry);
        if (cached != null && cached.expireText != null && isFixedText(arg) && cached.expireText.equals(arg.object.toString())) {
            return cached;
        }
        return null;
    }

    public static void rememberDuration(ScriptEntry scriptEntry, Argument arg, long millis) {
        if (!isFixedText(arg)) {
            return;
        }
        CachedAction updated = CachedAction.copyOf(cacheOf(scriptEntry));
        updated.expireText = arg.object.toString();
        updated.expireMillis = millis;
        scriptEntry.internal.specialProcessedData = updated;
    }

    public static class FlagActionProvider extends ActionableDataProvider {

        public AbstractFlagTracker tracker;

        public TimeTag expiration;

        @Override
        public ObjectTag getValueAt(String keyName) {
            return tracker.getFlagValue(keyName);
        }

        @Override
        public void setValueAt(String keyName, ObjectTag value) {
            if (keyName.equals("__raw")) {
                MapTag toSetMap = value.asType(MapTag.class, CoreUtilities.noDebugContext);
                ObjectTag toClear = toSetMap.getObject("__clear");
                if (toClear != null) {
                    for (String key : toClear.asType(ListTag.class, CoreUtilities.noDebugContext)) {
                        if (toSetMap.getObject(key) == null) {
                            tracker.setRootMap(key, null);
                        }
                    }
                }
                for (Map.Entry<StringHolder, ObjectTag> mapData : toSetMap.entrySet()) {
                    if (!mapData.getKey().low.equals("__clear")) {
                        tracker.setRootMap(mapData.getKey().str, (MapTag) mapData.getValue());
                    }
                }
                return;
            }
            if (keyName.startsWith("__")) {
                Debug.echoError("Assigning flag value to reserved '__' namespace (key: '" + keyName + "')");
            }
            tracker.setFlag(keyName, value, expiration);
        }
    }

    /**
     * Returns true if every target of this line keeps its flags somewhere that can be reached from another thread.
     * Only asked off the main thread, so the ordinary case never runs it.
     * Resolving the target objects is itself safe - what it costs is a lookup in Denizen's own storage, not a trip to the live server;
     * it is fetching their trackers that would, which is why the question is asked of the object instead.
     */
    public boolean canWriteOffThread(ListTag targets, ScriptEntry scriptEntry) {
        for (ObjectTag object : targets.objectForms) {
            if (CoreUtilities.equalsIgnoreCase(object.toString(), "server")) {
                continue;
            }
            FlaggableObject flaggable = object instanceof FlaggableObject ? (FlaggableObject) object
                    : DenizenCore.implementation.simpleWordToFlaggable(object.toString(), scriptEntry);
            if (flaggable == null || !flaggable.isFlagTrackerAsyncSafe()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The parsed shape of a flag action, remembered on the line when the argument reads the same text again.
     * <p>
     * Parsing it costs a split, a key scan and an {@link com.denizenscript.denizencore.objects.ObjectFetcher} lookup for the value,
     * and a line like 'flag server counter:5' hands over the same text on every execution. The text compared here is the argument's
     * value after tags are filled in, so a line whose value really does change per run misses the cache. When only the value after
     * 'key:action:' changes, as in 'flag server list:->:<[value]>', the key and the action are kept and only the value is read again.
     */
    public static class CachedAction {
        public String text;
        public DataActionType type;
        public String key;
        public int index;
        public ObjectTag inputValue;
        public String targetsText;
        public ListTag targets;
        public String prefix;
        public DataActionType prefixType;
        public String prefixKey;
        public int prefixIndex;
        public String objectPrefix;
        public String objectKey;
        public int objectIndex;
        public String expireText;
        public long expireMillis;

        public static CachedAction copyOf(CachedAction cached) {
            CachedAction updated = new CachedAction();
            if (cached != null) {
                updated.text = cached.text;
                updated.type = cached.type;
                updated.key = cached.key;
                updated.index = cached.index;
                updated.inputValue = cached.inputValue;
                updated.targetsText = cached.targetsText;
                updated.targets = cached.targets;
                updated.prefix = cached.prefix;
                updated.prefixType = cached.prefixType;
                updated.prefixKey = cached.prefixKey;
                updated.prefixIndex = cached.prefixIndex;
                updated.objectPrefix = cached.objectPrefix;
                updated.objectKey = cached.objectKey;
                updated.objectIndex = cached.objectIndex;
                updated.expireText = cached.expireText;
                updated.expireMillis = cached.expireMillis;
            }
            return updated;
        }
    }

    /**
     * A filled cache is published in one reference write to the volatile {@code specialProcessedData}, never filled in place.
     * <p>
     * Two threads can run the same flag line at the same moment with different values. Filling a published object field by field
     * would let one of them match the text while still reading another's half-written action, and write the wrong value.
     */
    private static CachedAction cacheOf(ScriptEntry scriptEntry) {
        return scriptEntry.internal.specialProcessedData instanceof CachedAction cached ? cached : null;
    }

    /**
     * The target list, remembered on the line when the argument reads the same text again.
     * <p>
     * Only a list whose every entry is a plain {@link ElementTag} is kept: those are read but never written by the flag write,
     * while a {@link FlaggableObject} entry has its tracker reapplied onto it, and handing the same instance to two threads at
     * once would be a race.
     */
    public static ListTag targetsFor(ScriptEntry scriptEntry, Argument arg) {
        if (arg.object instanceof FlaggableObject) {
            return arg.asType(ListTag.class);
        }
        String text = arg.getRawValue();
        CachedAction cached = cacheOf(scriptEntry);
        if (cached != null && cached.targets != null && cached.targetsText.equals(text)) {
            return cached.targets;
        }
        ListTag parsed = arg.asType(ListTag.class);
        for (ObjectTag object : parsed.objectForms) {
            if (!(object instanceof ElementTag)) {
                return parsed;
            }
        }
        CachedAction updated = CachedAction.copyOf(cached);
        updated.targetsText = text;
        updated.targets = parsed;
        scriptEntry.internal.specialProcessedData = updated;
        return parsed;
    }

    public static DataAction flagActionFor(ScriptEntry scriptEntry, Argument arg) {
        if (arg.hasPrefix() && !isFixedText(arg)) {
            return objectActionFor(scriptEntry, arg);
        }
        String text = arg.getRawValue();
        CachedAction cached = cacheOf(scriptEntry);
        if (cached != null && cached.text != null && cached.text.equals(text)) {
            DataAction reused = new DataAction();
            reused.provider = new FlagActionProvider();
            reused.type = cached.type;
            reused.key = cached.key;
            reused.index = cached.index;
            reused.inputValue = cached.inputValue;
            return reused;
        }
        boolean textParsed = (arg.object instanceof ElementTag element && element.isRawInput) || !arg.hasPrefix();
        if (textParsed && cached != null && cached.prefix != null && text.startsWith(cached.prefix)) {
            String value = text.substring(cached.prefix.length());
            DataAction reused = new DataAction();
            reused.provider = new FlagActionProvider();
            reused.type = cached.prefixType;
            reused.key = cached.prefixKey;
            reused.index = cached.prefixIndex;
            reused.inputValue = cached.prefixType == DataActionType.INSERT ? ObjectFetcher.pickObjectFor(value, scriptEntry.context) : new ElementTag(value);
            return reused;
        }
        DataAction parsed = DataActionHelper.parse(new FlagActionProvider(), arg, scriptEntry.context);
        String prefix = textParsed ? valuePrefixOf(text) : null;
        boolean cacheText = parsed.inputValue == null || parsed.inputValue instanceof ElementTag;
        if (!cacheText && prefix == null) {
            return parsed;
        }
        CachedAction updated = new CachedAction();
        if (cached != null) {
            updated.targetsText = cached.targetsText;
            updated.targets = cached.targets;
            updated.objectPrefix = cached.objectPrefix;
            updated.objectKey = cached.objectKey;
            updated.objectIndex = cached.objectIndex;
            updated.expireText = cached.expireText;
            updated.expireMillis = cached.expireMillis;
        }
        if (cacheText) {
            updated.text = text;
            updated.type = parsed.type;
            updated.key = parsed.key;
            updated.index = parsed.index;
            updated.inputValue = parsed.inputValue;
        }
        if (prefix != null) {
            updated.prefix = prefix;
            updated.prefixType = parsed.type;
            updated.prefixKey = parsed.key;
            updated.prefixIndex = parsed.index;
        }
        scriptEntry.internal.specialProcessedData = updated;
        return parsed;
    }

    public static DataAction objectActionFor(ScriptEntry scriptEntry, Argument arg) {
        CachedAction cached = cacheOf(scriptEntry);
        if (cached != null && cached.objectPrefix != null && cached.objectPrefix.equals(arg.prefix)) {
            DataAction reused = new DataAction();
            reused.provider = new FlagActionProvider();
            reused.type = DataActionType.SET;
            reused.key = cached.objectKey;
            reused.index = cached.objectIndex;
            reused.inputValue = arg.object;
            return reused;
        }
        DataAction parsed = DataActionHelper.parse(new FlagActionProvider(), arg, scriptEntry.context);
        CachedAction updated = CachedAction.copyOf(cached);
        updated.objectPrefix = arg.prefix;
        updated.objectKey = parsed.key;
        updated.objectIndex = parsed.index;
        scriptEntry.internal.specialProcessedData = updated;
        return parsed;
    }

    public static String valuePrefixOf(String text) {
        int keyEnd = text.indexOf(':');
        if (keyEnd < 0) {
            return null;
        }
        int actionEnd = text.indexOf(':', keyEnd + 1);
        if (actionEnd < 0) {
            return null;
        }
        return switch (text.substring(keyEnd + 1, actionEnd)) {
            case "->", "<-", "|", "!|", "+", "-", "*", "/" -> text.substring(0, actionEnd + 1);
            default -> null;
        };
    }

    @Override
    public void execute(ScriptEntry scriptEntry) {
        ListTag targets = scriptEntry.getObjectTag("targets");
        if (!DenizenCore.isMainThread() && !canWriteOffThread(targets, scriptEntry)) {
            // Hands the line over as a whole, which is what an unmarked command would have cost anyway - one crossing, targets still in order.
            long startTime = System.nanoTime();
            DenizenCore.runOnMainThreadAndWait(() -> writeFlags(scriptEntry, targets));
            ScriptQueue.recordMainThreadWait(scriptEntry.getResidingQueue(), System.nanoTime() - startTime);
            return;
        }
        writeFlags(scriptEntry, targets);
    }

    public void writeFlags(ScriptEntry scriptEntry, ListTag targets) {
        TimeTag expiration = scriptEntry.getObjectTag("expiration");
        DataAction flagAction = (DataAction) scriptEntry.getObject("flag_action");
        if (scriptEntry.debugThisLine) {
            Debug.report(scriptEntry, getName(), targets, expiration, flagAction);
        }
        ((FlagActionProvider) flagAction.provider).expiration = expiration;
        for (ObjectTag object : targets.objectForms) {
            AbstractFlagTracker tracker;
            if (CoreUtilities.equalsIgnoreCase(object.toString(), "server")) {
                tracker = DenizenCore.serverFlagMap;
            }
            else if (object instanceof FlaggableObject) {
                tracker = ((FlaggableObject) object).getFlagTracker();
                if (tracker == null) {
                    Debug.echoError("The object type '" + object.getDenizenObjectType() + "' is flaggable, however the instance '" + object + "' is not: " + ((FlaggableObject) object).getReasonNotFlaggable());
                    continue;
                }
            }
            else {
                FlaggableObject obj = DenizenCore.implementation.simpleWordToFlaggable(object.toString(), scriptEntry);
                if (obj != null) {
                    tracker = obj.getFlagTracker();
                }
                else {
                    if (object instanceof ElementTag && !object.toString().contains("@")) {
                        Debug.echoError("Cannot flag '" + object + "': that does not appear to be an object! Only objects (like an EntityTag) and special keyword 'server' are flaggable!");
                    }
                    else {
                        Debug.echoError("Cannot flag '" + object + "': that object type is not flaggable!");
                    }
                    continue;
                }
            }
            if (tracker == null) {
                Debug.echoError("Something went wrong, cannot flag '" + object + "'...");
                continue;
            }
            ((FlagActionProvider) flagAction.provider).tracker = tracker;
            // Held across the whole action, not just the write inside it: an action like ':+:1' reads the old value and writes the new one,
            // and two scripts doing that at the same instant would otherwise both read the same old value and one increment would vanish.
            synchronized (tracker.getWriteLock()) {
                flagAction.execute(scriptEntry.getContext());
            }
            if (object instanceof FlaggableObject) {
                ((FlaggableObject) object).reapplyTracker(tracker);
            }
        }
    }
}
