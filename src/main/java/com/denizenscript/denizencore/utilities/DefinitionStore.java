package com.denizenscript.denizencore.utilities;

import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.utilities.text.StringHolder;

import java.util.Arrays;

public class DefinitionStore {

    private final MapTag map;

    private DefinitionSlots table;

    private ObjectTag[] slots;

    private boolean[] filled;

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
        invalidate(key.low);
    }

    public void putDeepObject(String key, ObjectTag value) {
        map.putDeepObject(key, value);
        if (table != null) {
            int dot = key.indexOf('.');
            invalidate(CoreUtilities.toLowerCase(dot == -1 ? key : key.substring(0, dot)));
        }
    }

    public ObjectTag getSlot(DefinitionSlots forTable, int slot, StringHolder key) {
        if (table != forTable) {
            return map.getObject(key);
        }
        if (slot < filled.length && filled[slot]) {
            return slots[slot];
        }
        ObjectTag value = map.getObject(key);
        if (slot >= filled.length) {
            grow();
        }
        slots[slot] = value;
        filled[slot] = true;
        return value;
    }

    public void putSlot(DefinitionSlots forTable, int slot, StringHolder key, ObjectTag value) {
        map.putObject(key, value);
        if (table != forTable) {
            if (table != null) {
                invalidate(key.low);
                return;
            }
            bind(forTable);
        }
        if (slot >= filled.length) {
            grow();
        }
        slots[slot] = value;
        filled[slot] = true;
    }

    private void grow() {
        int size = Math.max(table.size(), filled.length + 1);
        slots = Arrays.copyOf(slots, size);
        filled = Arrays.copyOf(filled, size);
    }

    private void invalidate(String loweredName) {
        if (table == null) {
            return;
        }
        int slot = table.lookup(loweredName);
        if (slot != DefinitionSlots.NO_SLOT && slot < filled.length) {
            filled[slot] = false;
        }
    }

    public DefinitionSlots boundTable() {
        return table;
    }

    public void bindIfUnbound(DefinitionSlots forTable) {
        if (table == null) {
            bind(forTable);
        }
    }

    private void bind(DefinitionSlots forTable) {
        table = forTable;
        slots = new ObjectTag[forTable.size()];
        filled = new boolean[forTable.size()];
    }

    public DefinitionStore duplicate() {
        DefinitionStore result = new DefinitionStore(map.duplicate());
        if (table != null) {
            result.bind(table);
        }
        return result;
    }

    public MapTag toMap() {
        return map;
    }
}
