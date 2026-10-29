package com.denizenscript.denizencore.scripts.queues.core;

import com.denizenscript.denizencore.scripts.queues.ScriptEngine;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;

public class InstantQueue extends ScriptQueue {

    public InstantQueue(String id) {
        super(id);
    }

    @Override
    public void onStart() {
        ScriptQueue prior = ScriptEngine.enterQueue(this);
        try {
            while (is_started) {
                if (!hasMoreWork() && holdingOn == null) {
                    stop();
                    break;
                }
                ScriptEngine.revolve(this);
            }
            if (replacementQueue == null) {
                releaseAsyncCopies();
            }
        }
        finally {
            ScriptEngine.leaveQueue(prior);
        }
    }

    @Override
    public String getName() {
        return "InstantQueue";
    }
}
