package com.denizenscript.denizencore.utilities;

import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.utilities.text.StringHolder;

public class DefinitionStore {

    private final MapTag map;

    public DefinitionStore() {
        this.map = new MapTag();
    }

    public DefinitionStore(MapTag map) {
        this.map = map;
    }

    public ObjectTag getObject(StringHolder key) {
        return map.getObject(key);
    }

    public ObjectTag getDeepObject(String key) {
        return map.getDeepObject(key);
    }

    public void putObject(StringHolder key, ObjectTag value) {
        map.putObject(key, value);
    }

    public void putDeepObject(String key, ObjectTag value) {
        map.putDeepObject(key, value);
    }

    public DefinitionStore duplicate() {
        return new DefinitionStore(map.duplicate());
    }

    public MapTag toMap() {
        return map;
    }
}
