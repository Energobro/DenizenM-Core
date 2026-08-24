package com.denizenscript.denizencore.flags;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.objects.core.TimeTag;
import com.denizenscript.denizencore.utilities.CoreConfiguration;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.text.StringHolder;

import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Map;

public abstract class MapTagBasedFlagTracker extends AbstractFlagTracker {

    public static StringHolder valueString = new StringHolder("__value");

    public static StringHolder expirationString = new StringHolder("__expiration");

    public static boolean isExpired(ObjectTag expirationObj) {
        if (expirationObj == null) {
            return false;
        }
        if (DenizenCore.currentTimeMillis > ((TimeTag) expirationObj).millis()) {
            return true;
        }
        return false;
    }

    public ObjectTag getFlagValueOfType(String key, StringHolder type) {
        List<String> splitKey = CoreUtilities.split(key, '.');
        MapTag map = getRootMap(splitKey.get(0));
        if (map == null) {
            return null;
        }
        if (isExpired(map.getObject(expirationString))) {
            return null;
        }
        if (splitKey.size() == 1) {
            ObjectTag returnValue = map.getObject(type);
            if (returnValue instanceof MapTag) {
                return deflaggedSubMap((MapTag) returnValue);
            }
            return returnValue;
        }
        ObjectTag rootValue = map.getObject(valueString);
        if (!(rootValue instanceof MapTag)) {
            return null;
        }
        map = (MapTag) rootValue;
        String endKey = splitKey.get(splitKey.size() - 1);
        for (int i = 1; i < splitKey.size() - 1; i++) {
            MapTag subMap = (MapTag) map.getObject(splitKey.get(i));
            if (subMap == null) {
                return null;
            }
            if (isExpired(subMap.getObject(expirationString))) {
                return null;
            }
            ObjectTag subValue = subMap.getObject(valueString);
            if (!(subValue instanceof MapTag)) {
                return null;
            }
            map = (MapTag) subValue;
        }
        MapTag obj = (MapTag) map.getObject(endKey);
        if (obj == null) {
            return null;
        }
        ObjectTag value = obj.getObject(type);
        if (value == null) {
            return null;
        }
        if (isExpired(obj.getObject(expirationString))) {
            return null;
        }
        if (value instanceof MapTag) {
            return deflaggedSubMap((MapTag) value);
        }
        return value;
    }

    @Override
    public ObjectTag getFlagValue(String key) {
        return getFlagValueOfType(key, valueString);
    }

    public MapTag deflaggedSubMap(MapTag map) {
        MapTag toReturn = new MapTag();
        for (Map.Entry<StringHolder, ObjectTag> pair : map.entrySet()) {
            MapTag subMap = (MapTag) pair.getValue();
            if (isExpired(subMap.getObject(expirationString))) {
                continue;
            }
            ObjectTag subValue = subMap.getObject(valueString);
            if (subValue instanceof MapTag) {
                subValue = deflaggedSubMap((MapTag) subValue);
            }
            toReturn.putObject(pair.getKey(), subValue);
        }
        return toReturn;
    }

    @Override
    public TimeTag getFlagExpirationTime(String key) {
        return (TimeTag) getFlagValueOfType(key, expirationString);
    }

    /**
     * Returns a copy of the given flag sub-map with every expired entry dropped, or null if there was nothing expired to drop.
     * A copy rather than an edit in place, because the map handed in may be one that a script on another thread is walking right now,
     * and dropping a key out of a live map is exactly the restructuring a reader cannot survive.
     * Only the maps that actually change are copied - untouched branches are carried over by reference.
     */
    public MapTag cleanedCopy(MapTag map) {
        if (CoreConfiguration.skipAllFlagCleanings) {
            return null;
        }
        MapTag result = null;
        for (Map.Entry<StringHolder, ObjectTag> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof MapTag)) {
                continue;
            }
            MapTag flagMap = (MapTag) entry.getValue();
            if (isExpired(flagMap.getObject(expirationString))) {
                if (result == null) {
                    result = new MapTag(map);
                }
                result.remove(entry.getKey());
                continue;
            }
            ObjectTag subValue = flagMap.getObject(valueString);
            if (subValue instanceof MapTag) {
                MapTag cleanedSub = cleanedCopy((MapTag) subValue);
                if (cleanedSub != null) {
                    if (result == null) {
                        result = new MapTag(map);
                    }
                    MapTag cleanedFlagMap = new MapTag(flagMap);
                    cleanedFlagMap.putObject(valueString, cleanedSub);
                    result.putObject(entry.getKey(), cleanedFlagMap);
                }
            }
        }
        return result;
    }

    public MapTag flaggifyMapTag(MapTag map) {
        MapTag toReturn = new MapTag();
        for (Map.Entry<StringHolder, ObjectTag> pair : map.entrySet()) {
            MapTag flagMap = new MapTag();
            if (pair.getValue() instanceof MapTag) {
                flagMap.putObject(valueString, flaggifyMapTag((MapTag) pair.getValue()));
            }
            else {
                flagMap.putObject(valueString, pair.getValue());
            }
            toReturn.putObject(pair.getKey(), flagMap);
        }
        return toReturn;
    }

    /**
     * Builds the stored form of one flag: the value under '__value', plus the expiration if the write carried one.
     */
    public MapTag buildFlagMap(ObjectTag value, TimeTag expiration, boolean doFlaggify) {
        if (value instanceof MapTag && !doFlaggify) {
            return (MapTag) value;
        }
        MapTag resultMap = new MapTag();
        if (value.shouldBeType(MapTag.class)) {
            MapTag mappified = value.asType(MapTag.class, CoreUtilities.noDebugContext);
            if (mappified != null) {
                value = flaggifyMapTag(mappified);
            }
        }
        resultMap.putObject(valueString, value);
        if (expiration != null) {
            resultMap.putObject(expirationString, expiration);
        }
        return resultMap;
    }

    @Override
    public void setFlag(String key, ObjectTag value, TimeTag expiration, boolean doFlaggify) {
        // Split and built before the lock is taken. Neither reads what is stored, and converting a value can be real work -
        // holding the lock through it would be holding it against the main thread for no reason.
        List<String> splitKey = CoreUtilities.split(key, '.');
        MapTag resultMap = value == null ? null : buildFlagMap(value, expiration, doFlaggify);
        synchronized (getWriteLock()) {
            if (splitKey.size() == 1) {
                // A flat key replaces its root map whole, and the root storage publishes that in one step - there is nothing to tear.
                setRootMap(key, resultMap);
                return;
            }
            if (resultMap != null && setDeepFlagInPlace(splitKey, resultMap)) {
                return;
            }
            setDeepFlagRebuilding(splitKey, resultMap);
        }
    }

    /**
     * Handles the ordinary case of a deep write: every map along the path is already there and the final key already exists,
     * so the write is one value replacement and no map along the way changes shape.
     * That is what makes it safe against a reader on another thread - replacing the value of a key that is already present
     * neither resizes the map nor bumps its modification count, so a walk in progress cannot notice.
     * That covers the shape of the map; what makes the new value itself safe for that reader to see is the fence below.
     * Returns false when anything about the path has to change, leaving the caller to rebuild it instead.
     */
    public boolean setDeepFlagInPlace(List<String> splitKey, MapTag resultMap) {
        MapTag rootMap = getRootMap(splitKey.get(0));
        if (rootMap == null) {
            return false;
        }
        MapTag map = null;
        for (int i = 0; i < splitKey.size() - 1; i++) {
            MapTag flagMap;
            if (i == 0) {
                flagMap = rootMap;
            }
            else {
                ObjectTag subFlagMap = map.getObject(splitKey.get(i));
                if (!(subFlagMap instanceof MapTag)) {
                    return false;
                }
                flagMap = (MapTag) subFlagMap;
            }
            if (flagMap.containsKey(expirationString)) {
                // The rebuild path clears the expiration of every map above the one being written, and removing a key is a change of shape.
                return false;
            }
            ObjectTag innerMapTag = flagMap.getObject(valueString);
            if (!(innerMapTag instanceof MapTag)) {
                return false;
            }
            map = (MapTag) innerMapTag;
        }
        String endKey = splitKey.get(splitKey.size() - 1);
        if (!map.containsKey(endKey)) {
            return false;
        }
        // The value was built outside the write lock, and this is a plain store into a map that is already published - a reader that took
        // the root before the setRootMap below is walking this tree with plain reads, and gets no happens-before from that write.
        // The fence puts every store that built resultMap ahead of the store of the reference to it, so such a reader sees either the
        // old value or a fully built new one, never a half-written map. Free on x86, one instruction on the ARM hosts this may run on.
        VarHandle.releaseFence();
        map.putObject(endKey, resultMap);
        setRootMap(splitKey.get(0), rootMap);
        return true;
    }

    /**
     * Handles the deep writes that change the shape of the path: a key appearing for the first time, a key being removed,
     * or an expiration being cleared off a map above the one written.
     * Every map that changes is copied and linked into a copy of its parent, so no map a reader might be walking is ever restructured,
     * and the whole rebuilt path becomes visible in the single setRootMap call at the end.
     * Branches the write does not touch are carried over by reference, so the cost is the width of the path, not the size of the flag.
     */
    public void setDeepFlagRebuilding(List<String> splitKey, MapTag resultMap) {
        MapTag existingRoot = getRootMap(splitKey.get(0));
        MapTag rootMap = existingRoot == null ? new MapTag() : new MapTag(existingRoot);
        MapTag map = null;
        for (int i = 0; i < splitKey.size() - 1; i++) {
            MapTag flagMap;
            if (i == 0) {
                flagMap = rootMap;
            }
            else {
                ObjectTag subFlagMap = map.getObject(splitKey.get(i));
                flagMap = subFlagMap instanceof MapTag ? new MapTag((MapTag) subFlagMap) : new MapTag();
                map.putObject(splitKey.get(i), flagMap);
            }
            flagMap.remove(expirationString);
            ObjectTag innerMapTag = flagMap.getObject(valueString);
            MapTag innerMap = innerMapTag instanceof MapTag ? new MapTag((MapTag) innerMapTag) : new MapTag();
            flagMap.putObject(valueString, innerMap);
            map = innerMap;
        }
        String endKey = splitKey.get(splitKey.size() - 1);
        if (resultMap == null) {
            map.remove(endKey);
        }
        else {
            map.putObject(endKey, resultMap);
        }
        setRootMap(splitKey.get(0), rootMap);
    }
}
