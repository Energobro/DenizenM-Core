package com.denizenscript.denizencore.utilities.scheduling;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncSchedulable extends Schedulable {

    /** Numbers the worker threads, so that '<util.current_thread>' and stack traces name Denizen instead of a generic 'pool-N-thread-M'. */
    private static final AtomicInteger threadCounter = new AtomicInteger(1);

    public static final Executor executor = Executors.newCachedThreadPool(run -> new Thread(run, "Denizen Async #" + threadCounter.getAndIncrement()));
    protected final Schedulable schedulable;

    public AsyncSchedulable(Schedulable schedulable) {
        this.schedulable = schedulable;
        final Runnable runnable = schedulable.run;
        this.schedulable.run = () -> {
            executor.execute(runnable);
        };
    }

    @Override
    public boolean isSync() {
        return false;
    }

    @Override
    public boolean tick(float seconds) {
        return this.schedulable.tick(seconds);
    }
}
