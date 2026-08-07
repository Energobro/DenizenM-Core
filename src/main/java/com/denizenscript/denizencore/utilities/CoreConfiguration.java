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
