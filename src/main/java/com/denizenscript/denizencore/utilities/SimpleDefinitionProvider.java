package com.denizenscript.denizencore.utilities;

import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.utilities.text.StringHolder;

public class SimpleDefinitionProvider implements DefinitionProvider {

    private final MapTag definitions;

    public SimpleDefinitionProvider(MapTag map) {
        this.definitions = new MapTag(map);
    }

    public SimpleDefinitionProvider() {
        this.definitions = new MapTag();
    }

    public SimpleDefinitionProvider(DefinitionProvider definitionProvider) {
        this(definitionProvider.getAllDefinitions());
    }

    @Override
    public void addDefinition(String definition, ObjectTag value) {
        definitions.putDeepObject(definition, value);
    }

    @Override
    public void addDefinition(String definition, String value) {
        addDefinition(definition, new ElementTag(value));
    }

    @Override
    public MapTag getAllDefinitions() {
        return this.definitions;
    }

    @Override
    public ObjectTag getDefinitionObject(String definition) {
        if (definition == null) {
            return null;
        }
        return definitions.getDeepObject(definition);
    }

    /** Reads by a key the caller already lowered, so the holder is not rebuilt and the text not scanned again. */
    @Override
    public ObjectTag getDefinitionObject(StringHolder definition) {
        if (definition == null) {
            return null;
        }
        if (!CoreUtilities.contains(definition.low, '.')) {
            return definitions.getObject(definition);
        }
        return definitions.getDeepObject(definition.str);
    }

    public void addDefinition(StringHolder definition, ObjectTag value) {
        if (!CoreUtilities.contains(definition.low, '.')) {
            definitions.putObject(definition, value);
        }
        else {
            definitions.putDeepObject(definition.str, value);
        }
    }

    @Override
    public String getDefinition(String definition) {
        if (definition == null) {
            return null;
        }
        return CoreUtilities.stringifyNullPass(getDefinitionObject(definition));
    }

    @Override
    public boolean hasDefinition(String definition) {
        return getDefinitionObject(definition) != null;
    }

    @Override
    public void removeDefinition(String definition) {
        addDefinition(definition, (ObjectTag) null);
    }
}
