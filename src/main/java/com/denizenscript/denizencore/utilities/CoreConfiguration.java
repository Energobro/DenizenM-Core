package com.denizenscript.denizencore.utilities;

import java.nio.charset.CharsetDecoder;

@ReflectionRefuse
public class CoreConfiguration {

    public static long deprecationWarningRate = 10000;

    public static boolean futureWarningsEnabled = false;

    public static volatile boolean debugLoadingInfo = false;

    public static boolean debugVerbose = false, debugUltraVerbose = false, debugExtraInfo = false,
            debugOverride = false, debugStackTraces = true,
            debugScriptBuilder = false, debugShowSources = false, debugShouldTrim = true,
            debugRecordingAllowed = false;

    public static int debugLimitPerTick = 5000, debugTrimLength = 1024, debugLineLength = 300;

    public static boolean allowWebget = false, allowSQL = false, allowRedis = false, allowMongo = false, allowLog = false, allowFileCopy = false, allowWebserver = false, allowFileRead = false, allowFileWrite = false, allowFileDeletion = false;

    public static boolean allowConsoleRedirection = false, allowRestrictedActions = false, allowStrangeFileSaves = false;

    public static boolean tagTimeoutWhenSilent = false, tagTimeoutUnsafe = false;

    public static int tagTimeout = 0;

    public static boolean defaultDebugMode = true;

    public static int whileMaxLoops = 10000;

    public static double scriptQueueSpeed = 0;

    public static volatile CharsetDecoder scriptEncoding;

    public static boolean skipAllFlagCleanings = false;

    public static String webserverRoot = "webroot/", filePathLimit = "data/";

    public static boolean verifyThreadMatches;

    /**
     * Whether scripts are allowed to run on threads other than the main thread (async queues, and '~' waited async commands).
     * If disabled, async requests silently fall back to normal main-thread execution.
     */
    public static boolean allowAsyncScripts = true;

    /**
     * How long (in milliseconds) an async script may block while waiting for the main thread to run a non-async-safe command before giving up with an error.
     */
    public static long mainThreadWaitTimeoutMillis = 15000;

    /**
     * How long (in milliseconds) shutdown will wait for async queues to finish their current command before abandoning them.
     */
    public static long asyncShutdownTimeoutMillis = 3000;

    /**
     * How long (in milliseconds) of each tick the main thread will spend on fire-and-forget work handed to it by async threads -
     * deferred commands, debug output, and the like. Anything left over waits for the next tick, keeping its order.
     * <p>
     * This is what stops an async script from flooding the main thread faster than it can drain: the script never waits on this
     * work, so delaying it costs the script nothing. Requests that a thread is actually blocked on are not budgeted and always run in full.
     * <p>
     * Set to 0 to run everything every tick, however much of it there is.
     */
    public static long mainThreadTaskBudgetMillis = 5;

    /**
     * Once the main thread has answered everything async scripts were waiting on, keep watching this many microseconds longer in case another
     * request arrives. Set to 0 to hand the tick back the moment the queue is empty.
     * <p>
     * An async script's requests come back to back: the instant one is answered the script runs on, and a few dozen microseconds later it asks
     * for the next one. Without this it has just missed its drain and waits for the following tick, which is why a chain of requests used to cost
     * one tick each however small they were - and why a script that reads a live value inside a loop gained nothing from being async at all.
     * <p>
     * Worth being clear about what this spends. The requests themselves are work the main thread was going to do anyway, one tick later; all that
     * changes is when it does them. What is genuinely added is the watching - at most this many microseconds at the end of a chain, and only on
     * ticks where an async script asked for something at all. An idle server pays one comparison.
     */
    public static long mainThreadWaitLingerMicros = 500;

    /**
     * Warn once when this many async queues are running at the same time. Set to 0 to never warn.
     * <p>
     * Each async queue owns a thread for its whole life, including while it sits in a 'wait', so a script that starts them in bulk
     * quietly turns into that many threads. This is the early word about it; {@link #asyncQueueCountLimit} is the backstop.
     */
    public static int asyncQueueCountWarning = 50;

    /**
     * Never run more than this many async queues at once. Set to 0 for no limit.
     * <p>
     * A queue that would exceed it runs on the main thread instead, rather than not running - a script that hits this is already
     * misbehaving, and the point is only to stop a runaway loop from turning into unbounded threads, not to break the script.
     * <p>
     * Deliberately far above anything a healthy server reaches, so that it never costs speed in normal use: it is a backstop, not a throttle.
     */
    public static int asyncQueueCountLimit = 256;

    /**
     * An <@link command async> block whose contents have never taken longer than this (in nanoseconds) runs on the main thread instead of a worker.
     * <p>
     * Handing a block to another thread costs the script up to a tick of waiting, because the main thread can only resume it on its next pass.
     * That is worth paying to keep a slow block off the main thread, and pure waste for a block the main thread would not have noticed.
     * Each block is measured as it runs, so this decides itself per script line rather than needing the script writer to guess.
     * <p>
     * Set to 0 to always hand blocks to a worker.
     */
    public static long asyncBlockInlineThresholdNanos = 250_000; // 0.25ms

    public static boolean queueIdPrefix = true, queueIdNumeric = true, queueIdWords = true;

    public static boolean listFlagsAllowed = false;

    public static boolean allowReflectionFieldReads = false, allowReflectedCoreMethods = false, allowReflectionSet = false, allowReflectionSetPrivate = false, allowReflectionSetFinal = false;

    public static boolean shouldShowDebug = true, shouldRecordDebug = false;

    public static String debugPrefix = "";
}
