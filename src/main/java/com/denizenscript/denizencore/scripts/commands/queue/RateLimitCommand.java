package com.denizenscript.denizencore.scripts.commands.queue;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.objects.core.DurationTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.utilities.Deprecations;
import com.denizenscript.denizencore.utilities.debugging.Debug;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimitCommand extends AbstractCommand {

    public RateLimitCommand() {
        setName("ratelimit");
        setSyntax("ratelimit [<object>] [<duration>]");
        setRequiredArguments(2, 2);
        isProcedural = true;
        // Nothing here reaches the server: the timers are a concurrent map on the line's shared internals, the clock is DenizenCore's own tick time,
        // and the only side effect is clearing and stopping this entry's own queue - which ScriptQueue.stop already routes to that queue's thread.
        setAsyncSafe(true);
    }

    // <--[command]
    // @Name RateLimit
    // @Syntax ratelimit [<object>] [<duration>]
    // @Required 2
    // @Maximum 2
    // @Short Limits the rate that queues may process a script at.
    // @Group queue
    //
    // @Description
    // Limits the rate that queues may process a script at.
    // If another queue tries to run the same script faster than the duration, that second queue will be stopped.
    //
    // Note that the rate limiting is tracked based on two unique factors: the object input, and the specific script line.
    // That is to say: if you have a 'ratelimit <player> 10s', and then a few lines down a 'ratelimit <player> 10s',
    // those are two separate rate limiters.
    // Additionally, if you have a 'ratelimit <player> 10s' and two different players run it, they each have a separate rate limit applied.
    //
    // Note that this uses game delta tick time, not system realtime.
    //
    // @Tags
    // None
    //
    // @Usage
    // Use to show a message to a player no faster than once every ten seconds.
    // - ratelimit <player> 10s
    // - narrate "Wow!"
    // -->

    @Override
    public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {
        for (Argument arg : scriptEntry) {
            if (arg.matchesArgumentType(DurationTag.class)
                    && !scriptEntry.hasObject("duration")
                    && arg.limitToOnlyPrefix("duration")) {
                if (!scriptEntry.hasObject("object")) {
                    Deprecations.outOfOrderArgs.warn(scriptEntry);
                }
                scriptEntry.addObject("duration", arg.asType(DurationTag.class));
            }
            else if (!scriptEntry.hasObject("object")
                    && arg.limitToOnlyPrefix("object")) {
                scriptEntry.addObject("object", arg.getRawElement());
            }
            else {
                arg.reportUnhandled();
            }
        }
    }

    @Override
    public void execute(ScriptEntry scriptEntry) {
        DurationTag duration = scriptEntry.getObjectTag("duration");
        ElementTag object = scriptEntry.getElement("object");
        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, getName(), duration, object);
        }
        // The timers belong to the script line, not to one run of it, so they live on the shared internals rather than on this entry's own.
        // An async queue is handed private internals, so writing them to 'internal' directly would give every such queue its own set of timers -
        // ie no rate limiting at all for a script that is only ever run async.
        ScriptEntry.ScriptEntryInternal shared = scriptEntry.sharedInternal();
        Map<String, Long> map = (Map<String, Long>) shared.specialProcessedData;
        if (map == null) {
            // Two queues can reach an unused line at the same moment, and only one map may win - otherwise one of them keeps timers nobody reads.
            synchronized (shared) {
                map = (Map<String, Long>) shared.specialProcessedData;
                if (map == null) {
                    map = new ConcurrentHashMap<>(2);
                    shared.specialProcessedData = map;
                }
            }
        }
        String key = object.asLowerString();
        long curTime = DenizenCore.serverTimeMillis;
        long newEndTime = curTime + duration.getMillis();
        // Read and write in one atomic step: a rate limiter that two queues can pass at the same instant is not a rate limiter.
        long[] blockedUntil = new long[1];
        map.compute(key, (k, endTime) -> {
            if (endTime != null && curTime < endTime) {
                blockedUntil[0] = endTime;
                return endTime;
            }
            return newEndTime;
        });
        if (blockedUntil[0] != 0) {
            Debug.echoDebug(scriptEntry, "Rate limit applied with " + (blockedUntil[0] - curTime) + "ms left.");
            scriptEntry.getResidingQueue().clear();
            scriptEntry.getResidingQueue().stop();
        }
    }
}
