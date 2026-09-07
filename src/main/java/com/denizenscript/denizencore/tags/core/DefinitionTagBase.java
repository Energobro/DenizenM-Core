package com.denizenscript.denizencore.tags.core;

import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.tags.TagRunnable;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.tags.Attribute;
import com.denizenscript.denizencore.tags.TagManager;
import com.denizenscript.denizencore.utilities.text.StringHolder;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.DefinitionProvider;

public class DefinitionTagBase {

    public DefinitionTagBase() {

        // <--[tag]
        // @attribute <definition[<name>]>
        // @returns ObjectTag
        // @description
        // Returns a definition from the current queue.
        // The object will be returned as the most-valid type based on the input.
        // In most usages, the tag name is left blank, like "<[defhere]>".
        // You can use "." in a definition name to read a submapped key if the root definition is a MapTag.
        // @example
        // - define x 3
        // # Narrates '3'
        // - narrate <[x]>
        // @example
        // - definemap mymap:
        //     mykey: example
        // # Narrates 'example'
        // - narrate <[mymap.mykey]>
        // -->
        TagRunnable.BaseWithParamInterface<ObjectTag, ElementTag> defTag = (attribute, defName) -> {
            DefinitionProvider definitionProvider = attribute.context.definitionProvider;
            if (definitionProvider == null) {
                attribute.echoError("No definitions are provided in this tag's context!");
                return null;
            }
            Attribute.AttributeComponent component = attribute.currentComponent();
            StringHolder key = component == null ? null : component.definitionKey;
            if (key == null) {
                // asLowerString has already lowercased this, so the key does not need scanning again - see StringHolder.ofLowered.
                key = StringHolder.ofLowered(defName.asLowerString());
                if (component != null && component.paramParsed != null && !component.paramParsed.hasTag) {
                    component.definitionKey = key;
                }
            }
            ObjectTag def = component != null && component.definitionKey != null && definitionProvider instanceof ScriptQueue queue
                    ? queue.getDefinitionSlot(component, key)
                    : definitionProvider.getDefinitionObject(key);
            if (def == null) {
                attribute.echoError("Invalid definition name '" + defName + "'.");
                return null;
            }
            if (attribute.attributes.length == 1) {
                return def.refreshState();
            }
            return CoreUtilities.fixType(def, attribute.context);
        };
        TagManager.registerTagHandler(ObjectTag.class, ElementTag.class, "def", defTag);
        TagManager.registerTagHandler(ObjectTag.class, ElementTag.class, "definition", defTag);
        TagManager.registerTagHandler(ObjectTag.class, ElementTag.class, "", defTag);
    }
}
