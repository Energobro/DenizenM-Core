package com.denizenscript.denizencore.scripts;

import com.denizenscript.denizencore.scripts.containers.ScriptContainer;
import com.denizenscript.denizencore.scripts.containers.core.*;
import com.denizenscript.denizencore.utilities.CoreConfiguration;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.ReflectionHelper;
import com.denizenscript.denizencore.utilities.YamlConfiguration;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.utilities.text.StringHolder;
import com.denizenscript.denizencore.DenizenCore;

import java.lang.invoke.MethodHandle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ScriptRegistry {

    /**
     * All loaded script containers, by lowercased name.
     * Concurrent, as this is read off the main thread: by script tags, by 'run', and by 'inject' reading its own arguments.
     * <p>
     * Volatile and replaced whole by {@link #buildCoreYamlScriptContainers}, never emptied in place. A reload has to look atomic from another
     * thread for the same reason it already does from the main one: the clear-and-refill runs inside a single main thread task that never yields,
     * so a main thread script cannot catch it half done - it sees all of the old scripts or all of the new ones. An async queue runs alongside
     * that task, so without the swap it could read the registry mid-refill and be told a script that plainly exists does not
     * (a '<script[...]>' answering null, a 'run' or 'inject' failing to find its target), for the couple hundred milliseconds a reload takes.
     */
    public static volatile Map<String, ScriptContainer> scriptContainers = new ConcurrentHashMap<>();
    public static Map<String, MethodHandle> typeConstructors = new HashMap<>();

    public static void _registerType(String typeName, Class<? extends ScriptContainer> scriptContainerClass) {
        typeConstructors.put(CoreUtilities.toLowerCase(typeName), ReflectionHelper.getConstructor(scriptContainerClass, YamlConfiguration.class, String.class));
    }

    public static void _registerCoreTypes() {
        _registerType("custom", CustomScriptContainer.class);
        _registerType("task", TaskScriptContainer.class);
        _registerType("procedure", ProcedureScriptContainer.class);
        _registerType("world", WorldScriptContainer.class);
        _registerType("data", DataScriptContainer.class);
        _registerType("yaml data", DataScriptContainer.class);
        _registerType("format", FormatScriptContainer.class);
    }

    public static boolean containsScript(String id, Class scriptContainerType) {
        if (!scriptContainers.containsKey(CoreUtilities.toLowerCase(id))) {
            return false;
        }
        ScriptContainer script = scriptContainers.get(CoreUtilities.toLowerCase(id));
        return scriptContainerType.isInstance(script);
    }

    public static ArrayList<Map.Entry<String, YamlConfiguration>> toPostLoadAttempt = new ArrayList<>();

    public static void postLoadScripts() {
        try {
            for (Map.Entry<String, YamlConfiguration> script : toPostLoadAttempt) {
                attemptLoadSingle(script.getValue(), script.getKey(), true);
            }
        }
        finally {
            toPostLoadAttempt.clear();
        }
    }

    public static void attemptLoadSingle(YamlConfiguration script, String scriptName, boolean shouldErrorOnType) {
        attemptLoadSingle(script, scriptName, shouldErrorOnType, scriptContainers);
    }

    /** As {@link #attemptLoadSingle(YamlConfiguration, String, boolean)}, but loading into a given map - used to build a reload's set before publishing it. */
    public static void attemptLoadSingle(YamlConfiguration script, String scriptName, boolean shouldErrorOnType, Map<String, ScriptContainer> target) {
        // Make sure the script has a type
        String type = script.getString("type");
        if (type == null) {
            Debug.echoError("Found type-less container: '<Y>" + scriptName + "<W>'.");
            ScriptHelper.setHadError();
            return;
        }
        type = CoreUtilities.toLowerCase(type);
        // Check that types is a registered type
        if (!typeConstructors.containsKey(type)) {
            if (shouldErrorOnType) {
                Debug.echoError("Trying to load an invalid script. '<A>" + scriptName + "<Y>(" + type + ")<W>' is an unknown type.");
                ScriptHelper.setHadError();
            }
            else {
                toPostLoadAttempt.add(new AbstractMap.SimpleEntry<>(scriptName, script));
            }
            return;
        }
        MethodHandle constructor = typeConstructors.get(type);
        if (CoreConfiguration.debugLoadingInfo) {
            Debug.log("Adding script " + scriptName + " as type " + type);
        }
        try {
            String nameLow = CoreUtilities.toLowerCase(scriptName);
            if (target.containsKey(nameLow)) {
                Debug.echoError("Duplicate script name '<Y>" + scriptName + "<W>'");
            }
            ScriptContainer instance = (ScriptContainer) constructor.invoke(script, scriptName);
            target.put(nameLow, instance);
        }
        catch (Throwable ex) {
            Debug.echoError(ex);
            ScriptHelper.setHadError();
        }
    }

    public static void buildCoreYamlScriptContainers(List<YamlConfiguration> yamlScripts) {
        // Built aside and published with the single write at the end, so that a reader on another thread never sees a partly filled registry - see the field.
        // The implementation's own script maps below are still cleared and refilled in place, which is fine: everything that reads them
        // (item, inventory, entity and command scripts) is main-thread-only anyway, so nothing can look at them during this.
        Map<String, ScriptContainer> newContainers = new ConcurrentHashMap<>();
        DenizenCore.implementation.refreshScriptContainers();
        if (yamlScripts == null) {
            scriptContainers = newContainers;
            return;
        }
        Debug.log("Loading <A>" + yamlScripts.size() + "<W> script files...");
        for (YamlConfiguration script : yamlScripts) {
            for (StringHolder key : script.contents.keySet()) {
                YamlConfiguration container = script.getConfigurationSection(key.str);
                if (container == null) {
                    Debug.echoError("Invalid container '" + key.str + "' in file '" + ScriptHelper.getSource(key.low) + "' - missing contents?");
                }
                else {
                    attemptLoadSingle(container, key.str, false, newContainers);
                }
            }
        }
        scriptContainers = newContainers;
    }

    public static <T extends ScriptContainer> T getScriptContainerAs(String name, Class<T> type) {
        try {
            ScriptContainer container = scriptContainers.get(CoreUtilities.toLowerCase(name));
            if (container != null) {
                return (T) container;
            }
            else {
                return null;
            }
        }
        catch (Exception e) {
        }

        return null;
    }

    public static <T extends ScriptContainer> T getScriptContainer(String name) {
        ScriptContainer container = scriptContainers.get(CoreUtilities.toLowerCase(name));
        if (container != null) {
            return (T) container;
        }
        else {
            return null;
        }
    }
}
