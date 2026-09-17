package com.denizenscript.denizencore.utilities;

import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.utilities.text.StringHolder;

import java.util.Arrays;
import java.util.List;

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
        ObjectTag value = map.getObject(key);
        return value instanceof LoopValue holder ? holder.resolve() : value;
    }

    private static void disarm(ObjectTag previous) {
        if (previous instanceof LoopValue holder) {
            holder.installed = false;
        }
    }

    /**
     * Replaces a loop's holder with the value it stands for, so that a write walking through this key sees the real object.
     * <p>
     * A deep write ('define x.y z') has to descend into whatever 'x' holds, and a holder is not a MapTag - {@link MapTag#putDeepObject}
     * would take it for a non-map and swap the whole thing for a fresh empty one, losing the loop's value for the rest of the turn.
     * Resolving it in place first makes the write land inside that value, which is what it did before loops held their value in a box.
     * The holder is disarmed by the same step, so the loop re-installs it on its next turn.
     */
    private void materializeHolder(StringHolder key) {
        ObjectTag existing = map.getObject(key);
        if (existing instanceof LoopValue holder) {
            holder.installed = false;
            map.map.put(key, holder.resolve());
        }
    }

    private void resolveInPlace() {
        for (java.util.Map.Entry<StringHolder, ObjectTag> entry : map.map.entrySet()) {
            if (entry.getValue() instanceof LoopValue holder) {
                holder.installed = false;
                entry.setValue(holder.resolve());
            }
        }
    }

    public ObjectTag getDeepObject(String key) {
        if (!CoreUtilities.contains(key, '.')) {
            return getObject(new StringHolder(key));
        }
        List<String> subkeys = CoreUtilities.split(key, '.');
        ObjectTag current = getObject(new StringHolder(subkeys.get(0)));
        for (int i = 1; i < subkeys.size(); i++) {
            if (!(current instanceof MapTag subMap)) {
                return null;
            }
            current = subMap.getObject(subkeys.get(i));
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    public ObjectTag getDeepObject(StringHolder[] path) {
        ObjectTag current = getObject(path[0]);
        for (int i = 1; i < path.length; i++) {
            if (!(current instanceof MapTag subMap)) {
                return null;
            }
            current = subMap.getObject(path[i]);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    public void putDeepObject(StringHolder[] path, ObjectTag value) {
        materializeHolder(path[0]);
        map.putDeepObject(path, value);
        invalidate(path[0].low);
    }

    public void putObject(StringHolder key, ObjectTag value) {
        if (value == null) {
            disarm(map.getObject(key));
            map.putObject(key, value);
        }
        else {
            disarm(map.map.put(key, value));
        }
        invalidate(key.low);
    }

    public void putDeepObject(String key, ObjectTag value) {
        int dotAt = key.indexOf('.');
        materializeHolder(new StringHolder(dotAt == -1 ? key : key.substring(0, dotAt)));
        map.putDeepObject(key, value);
        if (table != null) {
            int dot = key.indexOf('.');
            invalidate(CoreUtilities.toLowerCase(dot == -1 ? key : key.substring(0, dot)));
        }
    }

    public ObjectTag getSlot(DefinitionSlots forTable, int slot, StringHolder key) {
        if (table != forTable) {
            return getObject(key);
        }
        boolean[] known = filled;
        ObjectTag[] cache = slots;
        if (slot < known.length && slot < cache.length && known[slot]) {
            ObjectTag cached = cache[slot];
            return cached instanceof LoopValue holder ? holder.resolve() : cached;
        }
        ObjectTag value = map.getObject(key);
        if (slot >= filled.length || slot >= slots.length) {
            grow();
        }
        slots[slot] = value;
        filled[slot] = true;
        return value instanceof LoopValue holder ? holder.resolve() : value;
    }

    public void putSlot(DefinitionSlots forTable, int slot, StringHolder key, ObjectTag value) {
        if (value == null) {
            disarm(map.getObject(key));
            map.putObject(key, value);
        }
        else {
            disarm(map.map.put(key, value));
        }
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
        resolveInPlace();
        DefinitionStore result = new DefinitionStore(map.duplicate());
        if (table != null) {
            result.bind(table);
        }
        return result;
    }

    public MapTag toMap() {
        resolveInPlace();
        return map;
    }
}
