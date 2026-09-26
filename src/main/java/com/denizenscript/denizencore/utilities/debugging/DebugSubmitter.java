package com.denizenscript.denizencore.utilities.debugging;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.events.ScriptEvent;
import com.denizenscript.denizencore.objects.core.DurationTag;
import com.denizenscript.denizencore.scripts.ScriptRegistry;
import com.denizenscript.denizencore.utilities.CoreUtilities;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Log submission was removed from this fork - nothing here reaches the network any more.
 * <p>
 * The type and its public members are kept because addons use them: Depenizen adds its own header line to
 * {@link #debugHeaderLines} while enabling, and removing the field made it fail to load with a NoSuchFieldError.
 */
public class DebugSubmitter extends Thread {

    /** Handed to the callback of {@link #submitCurrentRecording} in place of a paste link. */
    public static final String UNSUPPORTED = "unsupported";

    /**
     * Available for Denizen implementation and addons to add more lines to debug log submission headers.
     */
    public static List<Supplier<String>> debugHeaderLines = new ArrayList<>();

    public static Supplier<String> pasteTitleGetter = () -> "Denizen Debug Recording From A " + DenizenCore.implementation.getImplementationName() + " Impl";

    public static final String corePasteURL = "";

    public static String pasteURL = corePasteURL;

    public String title, prefixContent, recording, result;

    public static void init() {
        debugHeaderLines.add(() -> "Java Version: " + System.getProperty("java.version"));
        debugHeaderLines.add(() -> "Up-time: " + new DurationTag((CoreUtilities.monotonicMillis() - DenizenCore.startTime) / 1000.0).formatted(false));
        debugHeaderLines.add(() -> "Denizen Version: Core: " + DenizenCore.VERSION + ", " + DenizenCore.implementation.getImplementationName() + ": " + DenizenCore.implementation.getImplementationVersion());
        debugHeaderLines.add(() -> "Script Containers: " + ScriptRegistry.scriptContainers.size() + ", Events: " + ScriptEvent.totalPaths);
        debugHeaderLines.add(() -> "Last reload: " + new DurationTag((CoreUtilities.monotonicMillis() - DenizenCore.lastReloadTime) / 1000.0).formatted(false) + " ago");
    }

    public static void submitCurrentRecording(Consumer<String> processResult) {
        processResult.accept(UNSUPPORTED);
    }

    public void build() {
        title = pasteTitleGetter.get();
        StringBuilder addedLines = new StringBuilder();
        for (Supplier<String> line : debugHeaderLines) {
            try {
                addedLines.append(line.get()).append('\n');
            }
            catch (Throwable ex) {
                Debug.echoError(ex);
            }
        }
        prefixContent = addedLines.toString();
    }

    @Override
    public void run() {
        result = null;
    }
}
