package com.denizenscript.denizencore.scripts.containers;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.DurationTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.objects.core.ScriptTag;
import com.denizenscript.denizencore.scripts.*;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.tags.ParseableTag;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.tags.TagManager;
import com.denizenscript.denizencore.utilities.CoreConfiguration;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.DefinitionSlots;
import com.denizenscript.denizencore.utilities.Deprecations;
import com.denizenscript.denizencore.utilities.YamlConfiguration;
import com.denizenscript.denizencore.utilities.text.StringHolder;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.utilities.debugging.DebugInternals;
import com.denizenscript.denizencore.utilities.debugging.Debuggable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ScriptContainer implements Debuggable {

    // <--[language]
    // @name Script Container
    // @group Script Container System
    // @description
    // Script Containers are the basic structure that Denizen uses inside its YAML-based scripting files found in your
    // plugins/Denizen/scripts/ folder. Regardless of type, all script containers have basic parts that can usually be
    // described as keys, list keys, parent keys, child keys, values, and list values. While specific container types
    // probably have more specific names, just remember that no matter how complicated a script, this basic structure
    // still applies.
    //
    // It's important to keep in mind that all child keys, including all the main keys of the script, must line up with
    // one another, hierarchically. If you are familiar with YAML, great, because all script containers use it at the
    // core. Every value, in one way or another, belongs to some kind of 'key'. To define a key, use a string value plus
    // a colon (:). Keys can have a single value, a list value, or own another key:
    //
    // <code>
    // script name:
    //     key: value
    //     list key:
    //         - list value
    //         - ...
    //     parent key:
    //         child key: value
    // </code>
    //
    // And here's a container, put into a more familiar context:
    //
    // <code>
    // a haiku script:
    //     type: task
    //     script:
    //       - narrate "A simple script,"
    //       - narrate "with a key value relationship."
    //       - narrate "Oh look, a list!"
    // </code>
    //
    // -->

    public ScriptFormattingContext formattingContext;

    public ScriptContainer(YamlConfiguration configurationSection, String scriptContainerName) {
        if (configurationSection == null) {
            Debug.echoError("Null configuration section while generating a ScriptContainer?!");
            throw new RuntimeException("Null configuration section while generating a ScriptContainer");
        }
        contents = configurationSection;
        configurationSection.forceLoweredRootKey("type");
        configurationSection.forceLoweredRootKey("debug");
        configurationSection.forceLoweredRootKey("script");
        configurationSection.forceLoweredRootKey("speed");
        configurationSection.forceLoweredRootKey("enabled");
        this.name = CoreUtilities.toUpperCase(scriptContainerName);
        this.loweredName = CoreUtilities.toLowerCase(this.name);
    }

    public <T extends ObjectTag> T tagObject(String text, Class<T> type) {
        TagContext context = DenizenCore.implementation.getTagContext(this);
        return TagManager.tagObject(text, context).asType(type, context);
    }

    public String tag(String text) {
        return TagManager.tag(text, DenizenCore.implementation.getTagContext(this));
    }

    public ParseableTag getCachedTag(String path) {
        String rawString = getString(path);
        return rawString != null ? TagManager.parseTextToTag(rawString, DenizenCore.implementation.getTagContext(this)) : null;
    }

    public Boolean enabledCache = null;

    public boolean shouldEnable() {
        if (enabledCache != null) {
            return enabledCache;
        }
        String enabledText = contents.getString("enabled");
        if (enabledText == null) {
            return enabledCache = true;
        }
        String result = tag(enabledText);
        return enabledCache = (result == null || CoreUtilities.equalsIgnoreCase(result, "true"));
    }

    public void postCheck() {
        this.formattingContext = ScriptFormattingContext.parseFromConfiguration(this);
    }

    public ScriptFormattingContext getFormattingContext() {
        return formattingContext;
    }

    /**
     * Whether this script container type can run script logic.
     * If this is false, this is a data-only script container.
     */
    public boolean canRunScripts = true;

    /**
     * The contents of the script container
     */
    YamlConfiguration contents;

    /**
     * Gets the contents of the container.
     *
     * @return a ConfigurationSection object
     */
    public YamlConfiguration getContents() {
        return contents;
    }

    // <--[language]
    // @name Script Name
    // @group Script Container System
    // @description
    // Typically refers to the name of a script container. When using the object fetcher with dScript objects,
    // (ScriptTag_name), the script_name referred to is the name of the container.
    //
    // <code>
    // script name:         <--- script name
    //   type: script_type
    //   script:            <--- base script
    //     - script entries
    //     - ...
    //   local script:      <--- local script path
    //     - script entries
    //     - ...
    // </code>
    //
    // -->

    /**
     * The name of the script container
     */
    private String name;

    private String loweredName;

    public String getLoweredName() {
        return loweredName;
    }

    /**
     * Gets the name of the script container.
     *
     * @return the script container name.
     */
    public String getName() {
        return name;
    }

    public String getFileName() {
        return ScriptHelper.getSource(getName());
    }

    private String relativeFileName;

    public String getRelativeFileName() {
        if (relativeFileName != null) {
            return relativeFileName;
        }
        try {
            String fn = getFileName();
            if (fn == null) {
                return "(Error: script source is missing?)";
            }
            fn = fn.replace(scriptFolderRoot(), "");
            while (fn.startsWith("/")) {
                fn = fn.substring(1);
            }
            return relativeFileName = fn;
        }
        catch (Exception e) {
            Debug.echoError(e);
            return getFileName();
        }
    }

    private static File scriptFolderRootSource;

    private static String scriptFolderRootPath;

    private static String scriptFolderRoot() throws IOException {
        File folder = DenizenCore.implementation.getScriptFolder();
        if (folder != scriptFolderRootSource) {
            scriptFolderRootPath = folder.getParentFile().getCanonicalPath();
            scriptFolderRootSource = folder;
        }
        return scriptFolderRootPath;
    }

    public String getOriginalName() {
        return ScriptHelper.getOriginalName(getName());
    }

    /**
     * Gets a ScriptTag object that represents this container.
     *
     * @return a ScriptTag object linking this script container.
     */
    public ScriptTag getAsScriptArg() {
        return new ScriptTag(this);
    }

    // <--[language]
    // @name Script Type
    // @group Script Container System
    // @description
    // The type of container that a script is in. For example, 'task script' is a script type that has some sort of utility.
    //
    // <code>
    // script name:
    //   type: script_type  <--- script type
    //   script:
    //     - script entries
    //     - ...
    // </code>
    //
    // -->

    /**
     * Gets the value of the type: node specified in the script container structure.
     *
     * @return the type of container
     */
    public String getContainerType() {
        String type = contents.getString("type");
        return type == null ? null : CoreUtilities.toUpperCase(type);
    }

    /**
     * Checks the ConfigurationSection for the key/path to key specified.
     *
     * @param path the path of the key
     * @return true if the key exists
     */
    public boolean contains(String path) {
        return contents.contains(path);
    }

    public boolean containsScriptSection(String path) {
        return contains(path, List.class, "script section");
    }

    public boolean contains(String path, Class type) {
        return contains(path, type, null);
    }

    public boolean contains(String path, Class type, String typeName) {
        Object o = contents.get(path);
        if (o == null) {
            return false;
        }
        if (type.isAssignableFrom(o.getClass())) {
            return true;
        }
        if (type == YamlConfiguration.class) {
            return o instanceof Map;
        }
        Debug.echoError("Script '<Y>" + getName() + "<W>' contains path '<Y>" + path + "<W>' but it is not required type '<Y>" + (typeName != null ? typeName : DebugInternals.getClassNameOpti(type)) + "<W>'");
        return false;
    }

    public String getString(String path) {
        return contents.getString(path);
    }

    public String getString(String path, String def) {
        return contents.getString(path, def);
    }

    public String getString(String path, String def, boolean parseTags) {
        String str = contents.getString(path);
        if (str == null) {
            return def;
        }
        return parseTags ? tag(str) : str;
    }

    public List<String> getStringList(String path) {
        return getStringList(path, false);
    }

    public List<String> getStringList(String path, boolean parseTags) {
        Object obj = contents.get(path);
        if (obj == null) {
            return null;
        }
        if (obj instanceof List list) {
            List<String> strs = YamlConfiguration.patchListNonsense(list);
            ArrayList<String> output = new ArrayList<>(strs.size());
            for (String str : strs) {
                 str = ScriptBuilder.stripLinePrefix(str);
                if (parseTags) {
                    str = tag(str);
                }
                output.add(str);
            }
            return output;
        }
        else if (parseTags) {
            return tagObject(obj.toString(), ListTag.class);
        }
        return null;
    }

    public YamlConfiguration getConfigurationSection(String path) {
        if (path.isEmpty()) {
            return contents;
        }
        return contents.getConfigurationSection(path);
    }

    public void set(String path, Object object) {
        contents.set(path, object);
    }

    public static final long NO_SPEED_SET = Long.MIN_VALUE + 1;

    private volatile long speedTicks = Long.MIN_VALUE;

    private final Map<String, ObjectTag> dataKeyForms = new ConcurrentHashMap<>();

    public ObjectTag dataKeyTagForm(String key, YamlConfiguration section, TagContext context) {
        ObjectTag cached = dataKeyForms.get(key);
        if (cached != null) {
            return cached;
        }
        Object raw = section.get(key);
        if (raw == null) {
            return null;
        }
        ObjectTag form = CoreUtilities.objectToTagForm(raw, context, true);
        if (form == null) {
            return null;
        }
        dataKeyForms.put(key, form);
        return form;
    }

    private static final String[] NO_DEFINITION_NAMES = new String[0];

    private volatile String[] definitionNames;

    private volatile StringHolder[] definitionHolders;

    /** {@link #getDefinitionNames()} as pre-lowered keys, so a caller binding them does not scan the text again per call. */
    public StringHolder[] getDefinitionHolders() {
        StringHolder[] cached = definitionHolders;
        if (cached != null) {
            return cached.length == 0 ? null : cached;
        }
        String[] names = getDefinitionNames();
        if (names == null) {
            definitionHolders = NO_DEFINITION_HOLDERS;
            return null;
        }
        cached = new StringHolder[names.length];
        for (int i = 0; i < names.length; i++) {
            cached[i] = new StringHolder(names[i]);
        }
        definitionHolders = cached;
        return cached;
    }

    private static final StringHolder[] NO_DEFINITION_HOLDERS = new StringHolder[0];

    public String[] getDefinitionNames() {
        String[] cached = definitionNames;
        if (cached == null) {
            if (contains("definitions", String.class)) {
                List<String> split = CoreUtilities.split(getString("definitions"), '|');
                cached = new String[split.size()];
                for (int i = 0; i < cached.length; i++) {
                    String name = split.get(i).trim();
                    int squareBracket = name.indexOf('[');
                    cached[i] = squareBracket == -1 ? name : name.substring(0, squareBracket).trim();
                }
            }
            else {
                cached = NO_DEFINITION_NAMES;
            }
            definitionNames = cached;
        }
        return cached.length == 0 ? null : cached;
    }

    public long getSpeedTicks() {
        long cached = speedTicks;
        if (cached != Long.MIN_VALUE) {
            return cached;
        }
        long computed = NO_SPEED_SET;
        if (contains("SPEED", String.class)) {
            DurationTag parsed = DurationTag.valueOf(getString("SPEED", "0"), DenizenCore.implementation.getTagContext(this));
            if (parsed != null) {
                computed = parsed.getTicks();
            }
        }
        speedTicks = computed;
        return computed;
    }

    public List<ScriptEntry> getBaseEntries(ScriptEntryData data) {
        return getBaseEntries(data, null);
    }

    public List<ScriptEntry> getBaseEntries(ScriptEntryData data, ScriptQueue intoQueue) {
        // Note: no separate cache field for this one - 'getSetFor' already caches, in a map that can be read from any thread.
        return cleanDup(data, getSetFor("script"), intoQueue);
    }

    public static List<ScriptEntry> cleanDup(ScriptEntryData data, ScriptEntrySet set) {
        return cleanDup(data, set, null);
    }

    public static List<ScriptEntry> cleanDup(ScriptEntryData data, ScriptEntrySet set, ScriptQueue intoQueue) {
        if (set == null) {
            return null;
        }
        List<ScriptEntry> entries = new ArrayList<>(set.entries.size());
        for (ScriptEntry entry : set.entries) {
            entries.add(entry.cloneWithData(data, intoQueue));
        }
        return entries;
    }

    public List<ScriptEntry> getEntries(ScriptEntryData data, String path) {
        return getEntries(data, path, null);
    }

    public List<ScriptEntry> getEntries(ScriptEntryData data, String path, ScriptQueue intoQueue) {
        if (path == null) {
            path = "script";
        }
        return cleanDup(data, getSetFor(path), intoQueue);
    }

    public ScriptEntrySet getSetFor(String path) {
        ScriptEntrySet got = scriptsMap.get(path);
        if (got != null) {
            return got;
        }
        List<Object> stringEntries = contents.getList(path);
        if (stringEntries == null || stringEntries.isEmpty()) {
            return null;
        }
        List<ScriptEntry> entries = ScriptBuilder.buildScriptEntries(stringEntries, this, null);
        if (entries == null) {
            return null;
        }
        got = new ScriptEntrySet(entries);
        SlotAllocator.allocate(entries, new DefinitionSlots());
        scriptsMap.put(path, got);
        return got;
    }

    /** Cache of built script entry sets per path. Concurrent, as scripts can be launched from other threads. */
    private final Map<String, ScriptEntrySet> scriptsMap = new ConcurrentHashMap<>();

    /////////////
    // DEBUGGABLE
    /////////

    // Cached debug value to avoid repeated complex YAML calls
    private Boolean shouldDebug = null;

    @Override
    public boolean shouldDebug() {
        if (shouldDebug == null) {
            if (!contents.contains("debug")) {
                shouldDebug = CoreConfiguration.defaultDebugMode;
            }
            else {
                shouldDebug = !CoreUtilities.equalsIgnoreCase(contents.getString("debug"), "false");
            }
        }
        return shouldDebug;
    }

    @Override
    public String toString() {
        return "s@" + CoreUtilities.toLowerCase(getName());
    }

    public void handlePseudoTagBasesDeprecation(String key, String... tags) {
        String value = getString(key);
        if (value == null) {
            return;
        }
        for (String tag : tags) {
            if (value.contains(tag)) {
                Deprecations.pseudoTagBases.warn(this);
                return;
            }
        }
    }
}
