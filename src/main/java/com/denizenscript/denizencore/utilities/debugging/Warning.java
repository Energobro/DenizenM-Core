package com.denizenscript.denizencore.utilities.debugging;

import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.containers.ScriptContainer;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.utilities.Deprecations;

public class Warning { // Note: can be called async

    public String id;

    public String message;

    public Warning(String id, String message) {
        this.id = id;
        this.message = message;
    }

    public boolean testShouldWarn() {
        return true;
    }

    public void warn(TagContext context) {
        warn(context == null ? null : context.entry);
    }

    /**
     * Warns with a one-off message in place of this warning's usual one, for a warning that wants to name what triggered it.
     * <p>
     * Prefer this over assigning to {@link #message} and then warning: that leaves the detail of one warning in place for the next,
     * and warnings can be raised from several threads at once (async queues and '~' commands), which can cross two of them over.
     */
    public void warnWith(TagContext context, String specificMessage) {
        Deprecations.firedRecently.put(id, true);
        if (!testShouldWarn()) {
            return;
        }
        Debug.echoError(context == null ? null : context.entry, specificMessage);
    }

    public void warn(ScriptEntry entry) {
        Deprecations.firedRecently.put(id, true);
        if (!testShouldWarn()) {
            return;
        }
        Debug.echoError(entry, message);
    }

    public void warn() {
        warn((ScriptQueue) null);
    }

    public void warn(ScriptQueue queue) {
        warn(queue == null ? null : queue.getLastEntryExecuted());
    }

    public void warn(ScriptContainer script) {
        Deprecations.firedRecently.put(id, true);
        if (!testShouldWarn()) {
            return;
        }
        Debug.echoError("[In Script: " + script.getName() + "] " + message);
    }
}
