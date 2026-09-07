package com.denizenscript.denizencore.tags;


import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;

import java.util.List;

/**
 * Simple core representation of a section of user input that may contain tags, with methods to parse the tags inside.
 */
public class ParseableTag {

    public ObjectTag rawObject;

    public List<TagManager.ParseableTagPiece> pieces;

    public TagManager.ParseableTagPiece singleTag;

    public boolean hasTag;

    public int lengthHint = 16;

    public void setPieces(java.util.List<TagManager.ParseableTagPiece> pieces) {
        this.pieces = pieces;
        int hint = 0;
        for (int i = 0; i < pieces.size(); i++) {
            TagManager.ParseableTagPiece piece = pieces.get(i);
            hint += piece.isTag || piece.content == null ? 24 : piece.content.length();
        }
        lengthHint = hint < 16 ? 16 : hint;
    }

    /**
     * Get the object represented by this tag.
     * If the user input was plaintext (ie not a tag, or text mixed with a tag), with return an ElementTag.
     */
    public final ObjectTag parse(TagContext context) {
        if (rawObject != null) {
            return rawObject;
        }
        else if (singleTag != null) {
            return TagManager.readSingleTagObject(singleTag, context);
        }
        return TagManager.parseChainObject(pieces, context, lengthHint);
    }

    public ParseableTag() {
    }

    public ParseableTag(String text) {
        ElementTag rawElement = new ElementTag(text, true);
        rawElement.isRawInput = true;
        rawObject = rawElement;
    }

    @Override
    public String toString() {
        if (rawObject != null) {
            return rawObject.toString();
        }
        else {
            return "(ParseableTag: non-static value)";
        }
    }
}
