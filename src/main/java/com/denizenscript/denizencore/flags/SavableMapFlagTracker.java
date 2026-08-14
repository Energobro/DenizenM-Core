package com.denizenscript.denizencore.flags;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.objects.core.TimeTag;
import com.denizenscript.denizencore.utilities.AsciiMatcher;
import com.denizenscript.denizencore.utilities.CoreConfiguration;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.utilities.text.StringHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SavableMapFlagTracker extends MapTagBasedFlagTracker {

    public static class SaveOptimizedFlag {

        public volatile MapTag map;

        public volatile String string;

        public boolean canExpire;

        public MapTag getMap() {
            // Note: builds into a local first, so that another thread can never see a half-filled map through the volatile field.
            MapTag result = map;
            if (result == null) {
                if (string.startsWith("map@")) {
                    result = MapTag.valueOf(string, CoreUtilities.noDebugContext);
                }
                else {
                    result = new MapTag();
                    result.putObject(valueString, ObjectFetcher.pickObjectFor(string, CoreUtilities.noDebugContext));
                }
                map = result;
            }
            return result;
        }

        public String getString() {
            String result = string;
            if (result == null) {
                MapTag mapCopy = map;
                if (mapCopy.containsKey(expirationString) || mapCopy.getObject(valueString) instanceof MapTag) {
                    result = mapCopy.savable();
                }
                else {
                    result = mapCopy.getObject(valueString).savable();
                }
                string = result;
            }
            return result;
        }
    }

    /**
     * The raw flag data.
     * Concurrent, because flags (especially the server flag map) are commonly read by async queues and '~' async commands while the main thread writes them.
     * Note that this only protects the map structure itself - a flag being rewritten while an async script reads it may still return the old value.
     */
    public Map<StringHolder, SaveOptimizedFlag> map;

    public volatile boolean modified;

    public SavableMapFlagTracker() {
        map = new ConcurrentHashMap<>();
    }

    public SavableMapFlagTracker(String input) {
        input = input.replace("\r", "");
        map = new ConcurrentHashMap<>(Math.max(16, input.length() / 50));
        int eol = input.indexOf('\n');
        int startOfLine = 0;
        while (eol != -1) {
            int colon = input.indexOf(':', startOfLine);
            if (colon != -1) {
                String key = input.substring(startOfLine, colon);
                boolean expirable = key.startsWith("\\ex");
                if (expirable) {
                    key = key.substring("\\ex".length());
                }
                key = unescapeKey(key);
                String value = unescapeValue(input.substring(colon + 1, eol));
                SaveOptimizedFlag flag = new SaveOptimizedFlag();
                flag.canExpire = expirable;
                flag.string = value;
                map.put(new StringHolder(key), flag);
                if (CoreConfiguration.debugVerbose) {
                    Debug.log("Verbose: MapFlagTracker, loading flag " + key + " as " + value);
                }
            }
            startOfLine = eol + 1;
            eol = input.indexOf('\n', eol + 1);
        }
    }

    @Override
    public void doTotalClean() {
        if (CoreConfiguration.debugVerbose) {
            Debug.echoError("Verbose - savable tracker is beginning doTotalClean");
        }
        ArrayList<StringHolder> toRemove = new ArrayList<>();
        for (Map.Entry<StringHolder, SaveOptimizedFlag> entry : map.entrySet()) {
            SaveOptimizedFlag val = entry.getValue();
            if (!val.canExpire) {
                continue;
            }
            TimeTag expireTime = null;
            boolean hasSubMap = false;
            if (val.map != null) {
                expireTime = (TimeTag) val.map.getObject(expirationString);
                hasSubMap = val.map.getObject(valueString).canBeType(MapTag.class);
            }
            else if (val.string.startsWith("map@")) {
                MapTag quickMap = MapTag.valueOf(val.string, CoreUtilities.noDebugContext, false);
                if (CoreConfiguration.debugVerbose) {
                    Debug.log("Verbose: MapFlagTracker, quickMap = " + quickMap.debuggable());
                }
                ObjectTag time = quickMap.getObject(expirationString);
                if (time != null) {
                    expireTime = TimeTag.valueOf(time.toString(), CoreUtilities.noDebugContext);
                }
                hasSubMap = quickMap.getObject(valueString).canBeType(MapTag.class);
            }
            if (isExpired(expireTime)) {
                toRemove.add(entry.getKey());
                modified = true;
            }
            else if (hasSubMap) {
                MapTag rootMap = val.getMap();
                ObjectTag subValue = rootMap.getObject(valueString);
                if (subValue instanceof MapTag) {
                    MapTag cleaned = cleanedCopy((MapTag) subValue);
                    if (cleaned != null) {
                        // Republished rather than edited in place: this map is live, and an async script may be reading it right now.
                        MapTag cleanedRoot = new MapTag(rootMap);
                        cleanedRoot.putObject(valueString, cleaned);
                        setRootMap(entry.getKey().str, cleanedRoot);
                    }
                }
            }
        }
        if (CoreConfiguration.debugVerbose) {
            Debug.echoError("Verbose - savable tracker has finished doTotalClean and will remove " + toRemove.size());
        }
        for (StringHolder str : toRemove) {
            map.remove(str);
        }
    }

    @Override
    public MapTag getRootMap(String key) {
        SaveOptimizedFlag flag = map.get(new StringHolder(key));
        if (flag == null) {
            return null;
        }
        return flag.getMap();
    }

    @Override
    public void setRootMap(String key, MapTag value) {
        modified = true;
        if (value == null) {
            map.remove(new StringHolder(key));
            return;
        }
        SaveOptimizedFlag flag = new SaveOptimizedFlag();
        flag.map = value;
        flag.string = null;
        if (value.containsKey(expirationString) || value.getObject(valueString) instanceof MapTag) {
            flag.canExpire = true;
        }
        map.put(new StringHolder(key), flag);
    }

    @Override
    public Collection<String> listAllFlags() {
        ArrayList<String> keys = new ArrayList<>(map.size());
        for (StringHolder string : map.keySet()) {
            keys.add(string.str);
        }
        return keys;
    }

    public static AsciiMatcher valueEscapeNeededMatcher = new AsciiMatcher("\0\n\\");

    public static String unescapeValue(String key) {
        if (!CoreUtilities.contains(key, '\\')) {
            return key;
        }
        key = CoreUtilities.replace(key, "\\nl", "\n");
        key = CoreUtilities.replace(key, "\\bs", "\\");
        return key;
    }

    public static String escapeValue(String key) {
        if (!valueEscapeNeededMatcher.containsAnyMatch(key)) {
            return key;
        }
        key = CoreUtilities.replace(key, "\\", "\\bs");
        key = CoreUtilities.replace(key, "\n", "\\nl");
        key = CoreUtilities.replace(key, "\0", "");
        return key;
    }

    public static AsciiMatcher keyEscapeNeededMatcher = new AsciiMatcher("\0:\n\\");

    public static String unescapeKey(String key) {
        if (!CoreUtilities.contains(key, '\\')) {
            return key;
        }
        key = CoreUtilities.replace(key, "\\co", ":");
        key = CoreUtilities.replace(key, "\\nl", "\n");
        key = CoreUtilities.replace(key, "\\bs", "\\");
        return key;
    }

    public static String escapeKey(String key) {
        if (!keyEscapeNeededMatcher.containsAnyMatch(key)) {
            return key;
        }
        key = CoreUtilities.replace(key, "\\", "\\bs");
        key = CoreUtilities.replace(key, ":", "\\co");
        key = CoreUtilities.replace(key, "\n", "\\nl");
        key = CoreUtilities.replace(key, "\0", "");
        return key;
    }

    @Override
    public String toString() {
        StringBuilder toOutput = new StringBuilder(map.size() * 100);
        for (Map.Entry<StringHolder, SavableMapFlagTracker.SaveOptimizedFlag> flag : map.entrySet()) {
            if (flag.getValue().canExpire) {
                toOutput.append("\\ex");
            }
            toOutput.append(escapeKey(flag.getKey().str)).append(":").append(escapeValue(flag.getValue().getString())).append('\n');
        }
        return toOutput.toString();
    }

    public static SavableMapFlagTracker loadFlagFile(String filePath, boolean doClean) {
        if (CoreConfiguration.debugVerbose) {
            Debug.echoError("Verbose - loading flag file path at " + filePath);
        }
        String content = CoreUtilities.journallingLoadFile(filePath + ".dat");
        if (CoreConfiguration.debugVerbose) {
            Debug.echoError("Verbose - loaded flag content for " + filePath + " as " + (content == null ? "null" : content.length()));
        }
        if (content == null) {
            return new SavableMapFlagTracker();
        }
        SavableMapFlagTracker tracker = new SavableMapFlagTracker(content);
        if (CoreConfiguration.debugVerbose) {
            Debug.echoError("Verbose - loading flag file path at " + filePath + " to tracker of " + tracker.map.size() + " flags... doClean=" + doClean);
        }
        if (doClean && !CoreConfiguration.skipAllFlagCleanings) {
            tracker.doTotalClean();
        }
        return tracker;
    }

    public void saveToFile(String filePath) {
        saveToFile(filePath, true);
    }

    public void saveToFile(String filePath, boolean lockUntilDone) {
        String data = toString();
        Runnable run = () -> CoreUtilities.journallingFileSave(filePath + ".dat", data);
        if (lockUntilDone) {
            run.run();
        }
        else {
            DenizenCore.runAsync(run);
        }
    }
}
