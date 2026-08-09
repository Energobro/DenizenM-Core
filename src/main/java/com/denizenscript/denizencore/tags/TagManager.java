package com.denizenscript.denizencore.tags;

import com.denizenscript.denizencore.exceptions.TagProcessingException;
import com.denizenscript.denizencore.objects.*;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.tags.core.*;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.utilities.AsciiMatcher;
import com.denizenscript.denizencore.utilities.CoreConfiguration;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.codegen.TagCodeGenerator;
import com.denizenscript.denizencore.utilities.codegen.TagNamer;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.utilities.debugging.DebugInternals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

public class TagManager {

    /** If true: a static tag parsing is occurring. Tags should return null if non-static output. */
    public static boolean isStaticParsing = false;

    public static void registerCoreTags() {
        // Objects
        new ListTagBase();
        new MapTagBase();
        new QueueTagBase();
        new ScriptTagBase();

        // Utilities
        new ContextTagBase();
        new CoreTextTagBases();
        new DefinitionTagBase();
        new ListSingleTagBase();
        new ProcedureScriptTagBase();
        new StaticTagBase();
        new TernaryTagBase();
        new UtilTagBase();
    }

    public static class TagBaseData {

        public String name;

        public TagRunnable.RootForm rootForm;

        public TagRunnable.BaseInterface<?> baseForm;

        public Class<? extends ObjectTag> returnType;

        public ObjectTagProcessor<? extends ObjectTag> processor;

        public boolean doesStaticOverride;

        /**
         * Indicates that static input to this tag base yields static output (for tag optimization usage).
         */
        public boolean isStatic;

        /**
         * If true, tags on this base must be read on the main thread - an async script reading one will hand it to the main thread and wait.
         * Set this for any tag base that reads live server/world state. See {@link TagManager#markMainThreadOnly}.
         */
        public boolean mainThreadOnly;

        /**
         * Sub-tags of a main-thread-only base that are safe to read off-thread anyway (eg flag data), by first attribute name.
         * Null means "none".
         */
        public HashSet<String> asyncSafeSubTags;

        /**
         * If true, this base written on its own - no sub-tag and no parameter - is safe to read off-thread even though the base is main-thread-only.
         * See {@link TagManager#markBareBaseAsyncSafe}.
         */
        public boolean asyncSafeBare;

        public TagBaseData() {
        }

        public <R extends ObjectTag> TagBaseData(String name, Class<R> returnType, TagRunnable.BaseInterface<R> baseForm, boolean isStatic) {
            this.name = name;
            this.returnType = returnType;
            this.baseForm = baseForm;
            this.isStatic = isStatic;
            ObjectType<R> type = ObjectFetcher.getType(returnType);
            processor = type == null ? null : type.tagProcessor;
        }
    }

    public static HashMap<String, TagBaseData> baseTags = new HashMap<>();

    public static <R extends ObjectTag, P extends ObjectTag> void registerStaticTagBaseHandler(Class<R> returnType, Class<P> paramType, String name, TagRunnable.BaseWithParamInterface<R, P> run) {
        internalRegisterTagHandler(returnType, paramType, name, run, true);
    }

    public static <R extends ObjectTag, P extends ObjectTag> void registerTagHandler(Class<R> returnType, Class<P> paramType, String name, TagRunnable.BaseWithParamInterface<R, P> run) {
        internalRegisterTagHandler(returnType, paramType, name, run, false);
    }

    public static <R extends ObjectTag, P extends ObjectTag> void internalRegisterTagHandler(Class<R> returnType, Class<P> paramType, String name, TagRunnable.BaseWithParamInterface<R, P> run, boolean isStatic) {
        internalRegisterTagHandler(returnType, name, (attribute) -> {
            if (!attribute.hasParam()) {
                return null;
            }
            ObjectTag param = attribute.getParamObject();
            P result = param.asType(paramType, attribute.context);
            if (result == null) {
                if (!TagManager.isStaticParsing) {
                    attribute.echoError("Tag '<Y>" + name + "<W>' requires input of type '<Y>" + DebugInternals.getClassNameOpti(paramType) + "<W>' but received input '<LR>" + param + "<W>'.");
                }
                return null;
            }
            return run.run(attribute, result);
        }, isStatic);
    }

    public static <R extends ObjectTag> void registerStaticTagBaseHandler(Class<R> returnType, String name, TagRunnable.BaseInterface<R> run) {
        internalRegisterTagHandler(returnType, name, run, true);
    }

    public static <R extends ObjectTag> void registerTagHandler(Class<R> returnType, String name, TagRunnable.BaseInterface<R> run) {
        internalRegisterTagHandler(returnType, name, run, false);
    }

    public static <R extends ObjectTag> void internalRegisterTagHandler(Class<R> returnType, String name, TagRunnable.BaseInterface<R> run, boolean isStatic) {
        baseTags.put(name, new TagBaseData(name, returnType, TagNamer.nameBaseInterface(name, run), isStatic));
    }

    @Deprecated
    public static void registerTagHandler(TagRunnable.RootForm run, String... names) {
        for (String name : names) {
            TagBaseData root = new TagBaseData();
            root.name = name;
            root.rootForm = run;
            baseTags.put(name, root);
        }
    }

    // <--[language]
    // @name Async Tag Safety
    // @group Tag System
    // @description
    // Scripts that run off the main thread (see <@link language Async Queues>, and '~' waited commands like <@link command define>) read their tags on that same separate thread.
    //
    // That's perfectly safe for tags that just process data - text, lists, maps, math, durations, flags, and so on.
    // It is not safe for tags that read live server state, because the server is free to change that data at the very moment the tag reads it.
    //
    // To keep scripts correct without making script writers memorize which tags are which, implementations mark those tag bases as "main thread only".
    // When an async script reads one, the tag is automatically handed to the main thread, evaluated there, and the result returned - the async script simply waits for it.
    //
    // The consequence is that such tags are safe, but slow: they cost main thread time as usual, plus up to a tick of waiting.
    // So an async script that mostly reads live server state gains nothing; async is for scripts that mostly process data.
    //
    // Reading such a value once into a definition, before the part of the script that uses it, is usually all it takes:
    // "- define name <player.name>" ahead of a loop costs one hand-off, where "<player.name>" inside the loop costs one per pass.
    //
    // Note that a base written on its own, like "<player>" or "<npc>", is not one of these: it hands back an object the script's own queue
    // is already holding, so it costs nothing. Reading anything off that object ("<player.name>") is what goes to the main thread.
    // -->

    /**
     * Marks a tag base as one that may only be read on the main thread - async scripts will automatically hand these tags to the main thread and wait for the result.
     * Use this for any tag base that reads live server/world state.
     * @param baseName the name of the tag base (must already be registered).
     * @param asyncSafeSubTags names of sub-tags that are safe to read off-thread regardless (eg "flag"), or none.
     */
    public static void markMainThreadOnly(String baseName, String... asyncSafeSubTags) {
        TagBaseData base = baseTags.get(baseName);
        if (base == null) {
            Debug.echoError("Cannot mark tag base '" + baseName + "' as main-thread-only: no such tag base is registered.");
            return;
        }
        base.mainThreadOnly = true;
        if (asyncSafeSubTags.length > 0) {
            base.asyncSafeSubTags = new HashSet<>(Arrays.asList(asyncSafeSubTags));
        }
    }

    /**
     * Marks the bare form of a main-thread-only tag base - the base written on its own, with no sub-tag and no parameter - as safe to read off-thread.
     * <p>
     * Only for a base whose bare form hands back something the script's own context already holds: "&lt;player&gt;" returns the queue's linked player
     * without asking the server anything, while "&lt;player[bob]&gt;" has to go and find that player and so stays main-thread-only.
     * The object that comes back is still main-thread-only for its own tags - this only saves the hand-off for handing the object over.
     * @param baseName the name of the tag base (must already be registered, and normally already marked by {@link #markMainThreadOnly}).
     */
    public static void markBareBaseAsyncSafe(String baseName) {
        TagBaseData base = baseTags.get(baseName);
        if (base == null) {
            Debug.echoError("Cannot mark tag base '" + baseName + "' as async-safe when bare: no such tag base is registered.");
            return;
        }
        base.asyncSafeBare = true;
    }

    /**
     * Marks an object type as one whose tags may only be read on the main thread - async scripts will automatically hand these tags to the main thread and wait.
     * <p>
     * This is separate from {@link #markMainThreadOnly} on purpose: marking the tag base only covers tags written as "&lt;player.name&gt;",
     * while marking the type also covers the same object arriving through a definition ("&lt;[my_entity].flag[x]&gt;"), a context tag, an entry tag, or a procedure result.
     * Any Bukkit-style live object should be marked both ways.
     * @param type the object type class (must already be registered with the ObjectFetcher).
     * @param asyncSafeSubTags names of sub-tags that are safe to read off-thread regardless (eg "flag"), or none.
     */
    public static void markObjectTypeMainThreadOnly(Class<? extends ObjectTag> type, String... asyncSafeSubTags) {
        ObjectType<? extends ObjectTag> objectType = ObjectFetcher.getType(type);
        if (objectType == null || objectType.tagProcessor == null) {
            Debug.echoError("Cannot mark object type '" + DebugInternals.getClassNameOpti(type) + "' as main-thread-only: it isn't registered (or has no tag processor).");
            return;
        }
        objectType.tagProcessor.mainThreadOnly = true;
        if (asyncSafeSubTags.length > 0) {
            objectType.tagProcessor.asyncSafeSubTags = new HashSet<>(Arrays.asList(asyncSafeSubTags));
        }
    }

    /**
     * Marks individual tags on an object type as main-thread-only, leaving the rest of the type readable off-thread.
     * <p>
     * The inverse of {@link #markObjectTypeMainThreadOnly}, and for the opposite kind of type: one that is a plain value carrying no live
     * server object of its own (a material, say), but that still has the odd tag which goes and reads live state.
     * @param type the object type class (must already be registered with the ObjectFetcher).
     * @param tagNames names of the tags that need the main thread.
     */
    public static void markObjectTypeTagsMainThreadOnly(Class<? extends ObjectTag> type, String... tagNames) {
        ObjectType<? extends ObjectTag> objectType = ObjectFetcher.getType(type);
        if (objectType == null || objectType.tagProcessor == null) {
            Debug.echoError("Cannot mark tags on object type '" + DebugInternals.getClassNameOpti(type) + "' as main-thread-only: it isn't registered (or has no tag processor).");
            return;
        }
        objectType.tagProcessor.mainThreadOnlyTags = new HashSet<>(Arrays.asList(tagNames));
    }

    /** Returns true if this tag must be moved to the main thread before being read by the current (non-main) thread. */
    public static boolean requiresMainThread(TagBaseData baseHandler, ReplaceableTagEvent event) {
        if (!baseHandler.mainThreadOnly) {
            return false;
        }
        if (baseHandler.asyncSafeSubTags != null || baseHandler.asyncSafeBare) {
            Attribute attribute = event.getAttributes();
            if (attribute.attributes.length > 1) {
                if (baseHandler.asyncSafeSubTags != null && baseHandler.asyncSafeSubTags.contains(attribute.attributes[1].key)) {
                    return false;
                }
            }
            else if (baseHandler.asyncSafeBare && attribute.attributes.length == 1 && attribute.attributes[0].rawParam == null) {
                // Eg "<player>" on its own, which hands back an object the context is already holding rather than looking anything up.
                return false;
            }
        }
        return true;
    }

    public static void fireEvent(ReplaceableTagEvent event) {
        if (CoreConfiguration.debugVerbose) {
            Debug.log("Tag fire: " + event.raw_tag + ", " + event.getAttributes().attributes[0].rawKey.contains("@") + ", " + event.hasAlternative() + "...");
        }
        TagBaseData baseHandler = event.alternateBase != null ? event.alternateBase : event.mainRef.tagBase;
        if (baseHandler != null && !DenizenCore.isMainThread() && requiresMainThread(baseHandler, event)) {
            // This tag reads live server state, so it can't be read from an async script's thread - let the main thread do it while we wait.
            Debug.verboseLog("Tag '" + event.raw_tag + "' must be read on the main thread, handing it over from thread '" + Thread.currentThread().getName() + "'.");
            long waitStart = System.nanoTime();
            try {
                DenizenCore.runOnMainThreadAndWait(() -> fireEvent(event));
            }
            catch (Throwable ex) {
                Debug.echoError("Failed to read tag '" + event.raw_tag + "' on the main thread (requested by an async script):");
                Debug.echoError(ex);
            }
            finally {
                ScriptQueue.recordMainThreadWait(null, System.nanoTime() - waitStart);
            }
            return;
        }
        if (baseHandler != null) {
            Attribute attribute = event.getAttributes();
            try {
                if (event.mainRef.compiledStart != null && event.alternateBase == null) {
                    ObjectTag result = event.mainRef.compiledStart.run(attribute);
                    if (result != null) {
                        event.setReplacedObject(result.getObjectAttribute(attribute));
                        return;
                    }
                }
                else if (baseHandler.baseForm != null) {
                    ObjectTag result = baseHandler.baseForm.run(attribute);
                    if (result != null) {
                        event.setReplacedObject(result.getObjectAttribute(attribute.fulfill(1)));
                        return;
                    }
                }
                else if (baseHandler.rootForm != null) {
                    baseHandler.rootForm.run(event);
                    if (event.replaced()) {
                        return;
                    }
                }
            }
            catch (Throwable ex) {
                Debug.echoError(ex);
            }
            String base = attribute.attributes[0].key;
            if (!base.isEmpty()) { // ignore def tag base, it has its own error
                attribute.echoError("Tag-base '" + base + "' returned null.");
            }
            return;
        }
        else {
            if (!event.hasAlternative()) {
                Debug.echoError("No tag-base handler for '" + event.getName() + "'.");
            }
        }
        if (CoreConfiguration.debugVerbose) {
            Debug.log("Tag unhandled!");
        }
    }

    public static boolean isInTag = false;

    public static volatile Thread tagThread = null;

    public static void executeWithTimeLimit(final ReplaceableTagEvent event, int seconds) {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        Future<?> future = executor.submit(() -> {
            try {
                tagThread = Thread.currentThread();
                DenizenCore.implementation.preTagExecute();
                if (isInTag) {
                    fireEvent(event);
                }
                else {
                    isInTag = true;
                    fireEvent(event);
                    isInTag = false;
                }
            }
            finally {
                DenizenCore.implementation.postTagExecute();
                tagThread = null;
            }
        });
        executor.shutdown();
        try {
            future.get(seconds, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Debug.echoError("Tag filling was interrupted!");
        }
        catch (ExecutionException e) {
            Debug.echoError(e);
        }
        catch (TimeoutException e) {
            future.cancel(true);
            Debug.echoError("Tag filling timed out!");
        }
        executor.shutdownNow();
    }

    public static ObjectTag readSingleTagObject(ParseableTagPiece tag, TagContext context) {
        ReplaceableTagEvent event = new ReplaceableTagEvent(tag.tagData, tag.content, context);
        return readSingleTagObject(context, event);
    }

    public static boolean recentTagError = true;

    public static ObjectTag readSingleTagObjectNoDebug(TagContext context, ReplaceableTagEvent event) {
        int tT = CoreConfiguration.tagTimeoutUnsafe ? CoreConfiguration.tagTimeout : 0;
        if (CoreConfiguration.debugVerbose) {
            Debug.log("Tag read: " + event.raw_tag + ", " + tT + "...");
        }
        TagContext last = Debug.getCurrentContext();
        Debug.setCurrentContext(context);
        try {
            // Note: the timeout mechanism hands the tag to a helper thread and marks that thread as "the" tag thread, which is only meaningful for the main thread.
            // Tags read from an async queue simply run in place - they can't freeze the server anyway.
            if (tT <= 0 || isInTag || !DenizenCore.isStrictlyMainThread() || (!Debug.shouldDebug(context) && !CoreConfiguration.tagTimeoutWhenSilent)) {
                fireEvent(event);
            }
            else {
                executeWithTimeLimit(event, tT);
            }
            if (!event.replaced() && event.hasAlternative()) {
                event.setReplacedObject(event.getAlternative());
            }
            return event.getReplacedObj();
        }
        finally {
            Debug.setCurrentContext(last);
        }
    }

    public static ObjectTag readSingleTagObject(TagContext context, ReplaceableTagEvent event) {
        readSingleTagObjectNoDebug(context, event);
        if ((context.debug || CoreConfiguration.debugOverride) && event.replaced()) {
            Debug.echoDebug(context, "<G>Filled tag <<W>" + event + "<G>> with '<W>" + event.getReplacedObj().debuggable() + "<G>'.");
        }
        if (!event.replaced()) {
            String tagStr = "<LG><" + event + "<LG>><W>";
            Debug.echoError(context, "Tag " + tagStr + " is invalid!");
            recentTagError = true;
            if (OBJECTTAG_CONFUSION_PATTERN.matcher(tagStr).matches()) {
                Debug.echoError(context, "'ObjectTag' notation is for documentation purposes, and not to be used literally."
                    + " An actual object must be inserted instead. If confused, join our Discord at https://discord.gg/Q6pZGSR to ask for help!");
            }
            if (!event.hasAlternative()) {
                Attribute attribute = event.getAttributes();
                if (attribute.fulfilled < attribute.attributes.length) {
                    Debug.echoError(context, "Unfilled or unrecognized sub-tag(s) '<LR>" + attribute.unfilledString() + "<W>' for tag <LG><" + attribute.origin + "<LG>><W>!");
                    if (attribute.lastValid != null) {
                        Debug.echoError(context, "The returned value from initial tag fragment '<LG>" + attribute.filledString() + "<W>' was: '<LG>" + attribute.lastValid.debuggable() + "<W>'.");
                    }
                    if (attribute.seemingSuccesses.size() > 0) {
                        String almost = attribute.seemingSuccesses.get(attribute.seemingSuccesses.size() - 1);
                        if (attribute.hasContextFailed) {
                            Debug.echoError(context, "Almost matched but failed (missing [context] parameter?): " + almost);
                        }
                        else {
                            Debug.echoError(context, "Almost matched but failed (possibly bad input?): " + almost);
                        }
                    }
                }
            }
            return new ElementTag(event.raw_tag);
        }
        return event.getReplacedObj();
    }

    public static Pattern OBJECTTAG_CONFUSION_PATTERN = Pattern.compile("<\\w+tag[\\[.>].*", Pattern.CASE_INSENSITIVE);

    /** Cache of pre-parsed tagged text. Concurrent, as tags can be parsed from async queues. */
    public static Map<String, ParseableTag> preCalced = new ConcurrentHashMap<>();

    public static ParseableTag DEFAULT_PARSEABLE_EMPTY = new ParseableTag("");

    public static class ParseableTagPiece {

        public String content;

        public boolean isTag = false;

        public boolean isError = false;

        public ReplaceableTagEvent.ReferenceData tagData = null;

        public ObjectTag rawObject;

        @Override
        public String toString() {
            return "(" + isError + ", " + isTag + ", " + (isTag ? tagData.rawTag : "") + ", " + content + ", " + rawObject + ")";
        }
    }

    public static ObjectTag parseChainObject(List<ParseableTagPiece> pieces, TagContext context) {
        if (CoreConfiguration.debugVerbose) {
            Debug.log("Tag parse chain: " + pieces + "...");
        }
        if (pieces.size() < 2) {
            if (pieces.isEmpty()) {
                return new ElementTag("", true);
            }
            ParseableTagPiece pzero = pieces.get(0);
            if (pzero.isError) {
                Debug.echoError(context, pzero.content);
            }
            else if (pzero.isTag) {
                return readSingleTagObject(pzero, context);
            }
            ElementTag result = new ElementTag(pieces.get(0).content);
            result.isRawInput = true;
            return result;
        }
        StringBuilder helpy = new StringBuilder();
        for (ParseableTagPiece p : pieces) {
            if (p.isError) {
                Debug.echoError(context, p.content);
            }
            else if (p.isTag) {
                helpy.append(readSingleTagObject(p, context).toString());
            }
            else {
                helpy.append(p.content);
            }
        }
        ElementTag result = new ElementTag(helpy.toString(), true);
        result.isRawInput = true;
        return result;
    }

    public static String tag(String arg, TagContext context) {
        return tagObject(arg, context).toString();
    }

    public static ParseableTag parseTextToTag(String arg, TagContext context) {
        if (arg == null) {
            return null;
        }
        ParseableTag preParsed = preCalced.get(arg);
        if (preParsed != null) {
            return preParsed;
        }
        ParseableTag result = parseTextToTagInternal(arg, context, false);
        preCalced.put(arg, result);
        return result;
    }

    public static ParseableTag parseTextToTagInternal(String arg, TagContext context, boolean useBrace) {
        if (CoreConfiguration.debugVerbose) {
            Debug.echoError("(Verbose) Parse text to tag: " + arg);
        }
        List<ParseableTagPiece> pieces = new ArrayList<>(1);
        if (!arg.contains(useBrace ? "}>" : ">") || arg.length() < 3) {
            ParseableTagPiece txt = new ParseableTagPiece();
            txt.content = arg;
            pieces.add(txt);
            ParseableTag result = new ParseableTag(arg);
            result.pieces = pieces;
            return result;
        }
        int[] positions = new int[2];
        positions[0] = -1;
        locateTag(arg, positions, 0, useBrace);
        if (positions[0] == -1) {
            ParseableTagPiece txt = new ParseableTagPiece();
            txt.content = arg;
            pieces.add(txt);
            ParseableTag result = new ParseableTag(arg);
            result.pieces = pieces;
            return result;
        }
        String orig = arg;
        while (positions[0] != -1) {
            ParseableTagPiece preText = null;
            if (positions[0] > 0) {
                preText = new ParseableTagPiece();
                preText.content = arg.substring(0, positions[0]);
                pieces.add(preText);
            }
            String tagToProc = arg.substring(positions[0] + (useBrace ? 2 : 1), positions[1] - (useBrace ? 1 : 0));
            ParseableTagPiece midTag = new ParseableTagPiece();
            midTag.content = tagToProc;
            midTag.isTag = true;
            try {
                midTag.tagData = new ReplaceableTagEvent(tagToProc, context).mainRef;
                if (midTag.tagData.rawObject != null) {
                    midTag.rawObject = midTag.tagData.rawObject;
                    midTag.content = midTag.tagData.rawObject.toString();
                    midTag.isTag = false;
                }
                else if (!midTag.tagData.noGenerate && midTag.tagData.tagBase != null && midTag.tagData.tagBase.baseForm != null) {
                    midTag.tagData.noGenerate = true;
                    midTag.tagData.compiledStart = TagCodeGenerator.generatePartialTag(midTag, context);
                }
                pieces.add(midTag);
                if (CoreConfiguration.debugVerbose) {
                    Debug.log("Tag: " + (preText == null ? "<null>" : preText.content) + " ||| " + midTag.content);
                }
            }
            catch (TagProcessingException ex) {
                Debug.echoError(context, "(Initial detection) Tag processing failed: " + ex.getMessage());
                ParseableTagPiece errorNote = new ParseableTagPiece();
                errorNote.isError = true;
                errorNote.content = "Tag processing failed: " + ex.getMessage();
                pieces.add(errorNote);
            }
            arg = arg.substring(positions[1] + 1);
            locateTag(arg, positions, 0, useBrace);
        }
        if (arg.contains(useBrace ? "<{" : "<") && !arg.contains(":<-")) {
            ParseableTagPiece errorNote = new ParseableTagPiece();
            errorNote.isError = true;
            errorNote.content = "Potential issue: inconsistent tag marks in command! (issue snippet: " + arg + "; from: " + orig + ")";
            pieces.add(errorNote);
        }
        if (arg.length() > 0) {
            ParseableTagPiece postText = new ParseableTagPiece();
            postText.content = arg;
            pieces.add(postText);
        }
        if (CoreConfiguration.debugVerbose) {
            Debug.log("Tag chainify complete: " + arg);
        }
        ParseableTagPiece priorPiece = pieces.get(0);
        for (int i = 1; i < pieces.size(); i++) {
            ParseableTagPiece currentPiece = pieces.get(i);
            if (!priorPiece.isTag && !priorPiece.isError && !currentPiece.isTag && !currentPiece.isError) {
                ParseableTagPiece newPiece = new ParseableTagPiece();
                newPiece.content = priorPiece.content + currentPiece.content;
                ElementTag element = new ElementTag(newPiece.content, true);
                element.isRawInput = true;
                newPiece.rawObject = element;
                if (CoreConfiguration.debugVerbose) {
                    Debug.log("Tag chain can simplify: " + priorPiece + " with " + currentPiece + " yields " + newPiece);
                }
                pieces.set(i - 1, newPiece);
                pieces.remove(i--);
                priorPiece = newPiece;
            }
            else {
                priorPiece = currentPiece;
            }
        }
        ParseableTag result = new ParseableTag();
        result.pieces = pieces;
        if (pieces.size() == 1) {
            ParseableTagPiece piece = pieces.get(0);
            result.hasTag = piece.isTag;
            if (piece.isTag) {
                result.singleTag = piece;
            }
            else {
                result.rawObject = piece.rawObject;
            }
        }
        else {
            result.hasTag = true;
        }
        return result;
    }

    public static ObjectTag tagObject(String arg, TagContext context) {
        return parseTextToTag(arg, context).parse(context);
    }

    public static AsciiMatcher validTagFirstCharacter = new AsciiMatcher(AsciiMatcher.LETTERS_LOWER + AsciiMatcher.LETTERS_UPPER + AsciiMatcher.DIGITS + "&_[");

    private static void locateTag(String arg, int[] holder, int start, boolean useBrace) {
        int first = arg.indexOf(useBrace ? "<{" : "<", start);
        holder[0] = first;
        if (first == -1) {
            return;
        }
        int len = arg.length();
        // Handle "<-" for the flag command
        if (!useBrace && first + 1 < len && !validTagFirstCharacter.isMatch(arg.charAt(first + 1))) {
            locateTag(arg, holder, first + 1, false);
            return;
        }
        int bracks = 1;
        for (int i = first + 1; i < len; i++) {
            if (arg.charAt(i) == '<' && (!useBrace || i + 1 < len && arg.charAt(i + 1) == '{')) {
                bracks++;
            }
            else if (arg.charAt(i) == '>' && (!useBrace || arg.charAt(i - 1) == '}')) {
                bracks--;
                if (bracks == 0) {
                    holder[1] = i;
                    return;
                }
            }
        }
        holder[0] = -1;
    }

    public static void fillArgumentObjects(ScriptEntry.InternalArgument arg, Argument ahArg, TagContext context) {
        ahArg.lower_value = null;
        ahArg.unsetValue();
        if (arg.prefix != null) {
            if (arg.prefix.value.hasTag) {
                ahArg.prefix = arg.prefix.value.parse(context).toString();
                ahArg.lower_prefix = CoreUtilities.toLowerCase(ahArg.prefix);
            }
            if (arg.value.hasTag) {
                ahArg.object = arg.value.parse(context);
            }
        }
        else {
            ahArg.object = arg.value.parse(context);
            ahArg.prefix = null;
            ahArg.lower_prefix = null;
        }
    }
}
