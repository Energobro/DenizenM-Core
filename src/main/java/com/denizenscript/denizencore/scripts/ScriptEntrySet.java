package com.denizenscript.denizencore.scripts;

import java.util.ArrayList;
import java.util.List;

public class ScriptEntrySet {

    /** Final so that a set published to another thread (eg a script container's cached entries) is always seen fully built. */
    public final List<ScriptEntry> entries;

    public ScriptEntrySet(List<ScriptEntry> baseEntries) {
        entries = baseEntries;
    }

    public ScriptEntrySet duplicate() {
        List<ScriptEntry> newEntries = new ArrayList<>(entries.size());
        for (ScriptEntry entry : entries) {
            newEntries.add(entry.clone());
        }
        return new ScriptEntrySet(newEntries);
    }
}
