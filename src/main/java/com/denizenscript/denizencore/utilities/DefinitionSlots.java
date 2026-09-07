package com.denizenscript.denizencore.utilities;

import java.util.concurrent.ConcurrentHashMap;

public class DefinitionSlots {

    public static final int NO_SLOT = -1;

    private final ConcurrentHashMap<String, Integer> byName = new ConcurrentHashMap<>();

    private volatile int count = 0;

    public int size() {
        return count;
    }

    public synchronized int assign(String loweredName) {
        if (loweredName.isEmpty() || loweredName.startsWith("__") || CoreUtilities.contains(loweredName, '.')) {
            return NO_SLOT;
        }
        Integer existing = byName.get(loweredName);
        if (existing != null) {
            return existing;
        }
        int slot = count;
        byName.put(loweredName, slot);
        count = slot + 1;
        return slot;
    }

    public int lookup(String loweredName) {
        Integer slot = byName.get(loweredName);
        return slot == null ? NO_SLOT : slot;
    }
}
