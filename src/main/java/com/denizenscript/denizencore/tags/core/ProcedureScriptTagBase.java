package com.denizenscript.denizencore.tags.core;

import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.*;
import com.denizenscript.denizencore.scripts.ScriptRegistry;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.scripts.containers.core.ProcedureScriptContainer;
import com.denizenscript.denizencore.utilities.ScriptUtilities;
import com.denizenscript.denizencore.utilities.CoreConfiguration;
import com.denizenscript.denizencore.utilities.SimpleDefinitionProvider;
import com.denizenscript.denizencore.tags.ParseableTag;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.tags.TagManager;
import com.denizenscript.denizencore.utilities.text.StringHolder;

import java.util.Map;

public class ProcedureScriptTagBase {

    private static final StringHolder RAW_CONTEXT = new StringHolder("raw_context");

    public ProcedureScriptTagBase() {

        // <--[tag]
        // @attribute <proc[<procedure_script_name>]>
        // @returns ObjectTag
        // @description
        // Returns the 'determine' result of a procedure script.
        // -->
        TagManager.registerTagHandler(ObjectTag.class, ElementTag.class, "proc", (attribute, param) -> {
            ScriptTag script;
            String path = null;
            Object registryToken = ScriptRegistry.scriptContainers;
            script = (ScriptTag) attribute.typedParamFor(registryToken);
            if (script == null) {
                String name = param.asString();
                int dotIndex = name.indexOf('.');
                if (dotIndex > 0) {
                    path = name.substring(dotIndex + 1);
                    script = ScriptTag.valueOf(name.substring(0, dotIndex), attribute.context);
                }
                else {
                    script = param.asType(ScriptTag.class, attribute.context);
                    if (script != null) {
                        attribute.rememberTypedParam(registryToken, script);
                    }
                }
            }
            if (script == null) {
                attribute.echoError("Missing script for procedure script tag '" + param.asString() + "'!");
                return null;
            }
            if (!(script.getContainer() instanceof ProcedureScriptContainer)) {
                attribute.echoError("Chosen script is not a procedure script!");
                return null;
            }
            ListTag definitions = null;
            MapTag mappedDefinitions;

            // <--[tag]
            // @attribute <proc[<procedure_script_name>].context[<object>|...]>
            // @returns ObjectTag
            // @description
            // Returns the 'determine' result of a procedure script with the given context.
            // -->
            if (attribute.startsWith("context", 2)) {
                mappedDefinitions = null;
                definitions = attribute.contextAsType(2, ListTag.class);
                attribute.fulfill(1);
            }

            // <--[tag]
            // @attribute <proc[<procedure_script_name>].context_map[<map>]>
            // @returns ObjectTag
            // @description
            // Returns the 'determine' result of a procedure script with the given context.
            // -->
            else if (attribute.startsWith("context_map", 2)) {
                mappedDefinitions = attribute.contextAsType(2, MapTag.class);
                attribute.fulfill(1);
            }
            else {
                mappedDefinitions = null;
            }
            if (path == null && mappedDefinitions == null && !CoreConfiguration.debugOverride) {
                ParseableTag direct = ((ProcedureScriptContainer) script.getContainer()).directDetermineValue();
                if (direct != null) {
                    TagContext procContext = attribute.context.clone();
                    procContext.debug = false;
                    procContext.script = script;
                    procContext.contextSource = null;
                    SimpleDefinitionProvider provider = new SimpleDefinitionProvider();
                    if (definitions != null) {
                        StringHolder[] names = script.getContainer().getDefinitionHolders();
                        int index = 1;
                        for (ObjectTag definition : definitions.objectForms) {
                            if (names != null && names.length >= index) {
                                provider.addDefinition(names[index - 1], definition);
                            }
                            else {
                                provider.addDefinition(String.valueOf(index), definition);
                            }
                            index++;
                        }
                        provider.addDefinition(RAW_CONTEXT, definitions);
                    }
                    procContext.definitionProvider = provider;
                    ObjectTag result = direct.parse(procContext);
                    if (result == null) {
                        attribute.echoError("Procedure call did not determine any value.");
                        return null;
                    }
                    return result.refreshState();
                }
            }
            ScriptQueue queue = ScriptUtilities.createAndStartQueue(script.getContainer(), path, attribute.context.getScriptEntryData(), null, (q) -> {
                if (mappedDefinitions != null) {
                    for (Map.Entry<StringHolder, ObjectTag> val : mappedDefinitions.entrySet()) {
                        q.addDefinition(val.getKey().str, val.getValue());
                    }
                }
                q.procedural = true;
            }, new DurationTag(0), null, definitions, script.getContainer());
            if (queue == null) {
                attribute.echoError("Procedure queue start failed.");
                return null;
            }
            if (queue.determinations == null || queue.determinations.isEmpty()) {
                attribute.echoError("Procedure call did not determine any value.");
                return null;
            }
            return queue.determinations.getObject(0);
        });

    }
}
