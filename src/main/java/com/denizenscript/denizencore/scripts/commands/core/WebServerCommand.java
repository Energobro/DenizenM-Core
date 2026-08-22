package com.denizenscript.denizencore.scripts.commands.core;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.events.core.WebserverWebRequestScriptEvent;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.scripts.commands.generator.ArgDefaultText;
import com.denizenscript.denizencore.scripts.commands.generator.ArgName;
import com.denizenscript.denizencore.scripts.commands.generator.ArgPrefixed;
import com.denizenscript.denizencore.utilities.CoreConfiguration;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebServerCommand extends AbstractCommand {

    public WebServerCommand() {
        setName("webserver");
        setSyntax("webserver [start/stop] (port:<#>) (ignore_errors)");
        setRequiredArguments(1, 3);
        isProcedural = false;
        // Nothing here touches the Minecraft server: HttpServer.create/start/stop are plain JDK networking, and the only shared state is the
        // 'webservers' map below, which no other code reads. Starting one binds a socket, which is blocking I/O and has no business on the main
        // thread - the same reason fileread, filewrite and webget went off it. The request side is unaffected either way: handleRequest already
        // hands the script event to the main thread itself, because an event may only ever be fired from there.
        asyncSafe = true;
        autoCompile();
    }

    // <--[command]
    // @Name WebServer
    // @Syntax webserver [start/stop] (port:<#>) (ignore_errors)
    // @Required 1
    // @Maximum 3
    // @Short Creates a local HTTP web-server within your minecraft server.
    // @Group core
    //
    // @Description
    // Creates a local HTTP web-server within your minecraft server.
    //
    // The server does not provide SSL (HTTPS) security or functionality.
    // The server does not provide active abuse-prevention or routing control or etc.
    //
    // If your webserver is meant for public connection, it is very strongly recommended you put the webserver behind a reverse-proxy server, such as Nginx or Apache2.
    //
    // The port, if unspecified, defaults to 8080. You should usually manually specify a port.
    //
    // The "ignore_errors" option can be enabled to silence basic connection errors that might otherwise spam your console logs.
    //
    // You can only exactly one webserver per port.
    // If you use multiple ports, you can thus have multiple webservers.
    //
    // When using the stop instruction, you must specify the same port you used when starting.
    //
    // The webserver only does anything if you properly handle <@link event webserver web request>
    //
    // Most webserver processing is done in the event, and thus is synchronous with the minecraft thread, and thus may induce lag if not done with care.
    // Note per the event's meta, "file:" is handled async, and "cached_file:" only runs sync once per file.
    //
    // This command must be enabled in the Denizen/config.yml before it can be used.
    //
    // @Tags
    // None
    //
    // @Usage
    // Use to start a webserver on port 8081.
    // - webserver start port:8081
    //
    // @Usage
    // Use to stop the webserver on port 8081.
    // - webserver stop port:8081
    //
    // -->

    public static class WebserverInstance {

        public int port;

        public HttpServer server;

        public boolean ignoreErrors;

        public void handleRequest(HttpExchange exchange) {
            DenizenCore.runOnMainThread(() -> WebserverWebRequestScriptEvent.fire(this, exchange));
        }

        public void executor(Runnable command) {
            DenizenCore.runAsync(command);
        }

        public void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", this::handleRequest);
            server.setExecutor(this::executor);
            server.start();
        }

        public void stop() {
            server.stop(0);
        }
    }

    /** Concurrent because this command is async-safe, so two scripts on two threads can reach the start/stop below at once. Nothing outside this command reads it. */
    public static Map<Integer, WebserverInstance> webservers = new ConcurrentHashMap<>();

    public enum Mode { START, STOP }

    public static void autoExecute(ScriptEntry scriptEntry,
                                   @ArgPrefixed @ArgName("port") @ArgDefaultText("8080") int portNum,
                                   @ArgName("mode") Mode mode,
                                   @ArgName("ignore_errors") boolean ignoreErrors) {
        if (!CoreConfiguration.allowWebserver) {
            Debug.echoError("WebServer command disabled in config.yml!");
            return;
        }
        switch (mode) {
            // Both instructions run inside 'compute', so that everything one of them does to a port - the check, the bind, the shutdown -
            // happens with that key held against the other. Without it, a 'stop' arriving between a 'start' claiming the port and binding it
            // would take an instance whose server field is still null, and leave the socket that 'start' then binds running with nothing
            // holding it. Binding and closing a local socket is short work, and nothing outside this command touches the map.
            case START: {
                webservers.compute(portNum, (port, instance) -> {
                    if (instance != null) {
                        Debug.echoError("Server already running at port " + portNum + ", cannot start a new one.");
                        return instance;
                    }
                    instance = new WebserverInstance();
                    instance.port = portNum;
                    instance.ignoreErrors = ignoreErrors;
                    try {
                        instance.start();
                    }
                    catch (IOException ex) {
                        Debug.echoError("Could not start webserver due to IOException. Is the port correct?");
                        Debug.echoError(ex);
                        return null;
                    }
                    Debug.echoDebug(scriptEntry, "Webserver at port " + portNum + " started.");
                    return instance;
                });
                break;
            }
            case STOP: {
                webservers.compute(portNum, (port, instance) -> {
                    if (instance == null) {
                        Debug.echoDebug(scriptEntry, "No server running at port " + portNum + ", ignoring 'stop' instruction.");
                        return null;
                    }
                    instance.stop();
                    Debug.echoDebug(scriptEntry, "Webserver at port " + portNum + " stopped.");
                    return null;
                });
                break;
            }
        }
    }
}
