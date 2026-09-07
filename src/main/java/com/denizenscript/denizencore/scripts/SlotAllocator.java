package com.denizenscript.denizencore.scripts;

import com.denizenscript.denizencore.tags.TagManager;
import com.denizenscript.denizencore.utilities.DefinitionSlots;

import java.util.List;

public class SlotAllocator {

    public static void allocate(List<ScriptEntry> entries, DefinitionSlots table) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (ScriptEntry entry : entries) {
            collectReads(entry, table);
        }
        for (ScriptEntry entry : entries) {
            entry.internal.slotTable = table;
        }
    }

    private static void collectReads(ScriptEntry entry, DefinitionSlots table) {
        forEachPiece(entry, piece -> {
            if (piece.tagData != null && piece.tagData.plainDefinitionKey != null) {
                table.assign(piece.tagData.plainDefinitionKey.low);
            }
        });
    }

    private static void forEachPiece(ScriptEntry entry, java.util.function.Consumer<TagManager.ParseableTagPiece> handler) {
        if (entry.internal.all_arguments == null) {
            return;
        }
        for (ScriptEntry.InternalArgument arg : entry.internal.all_arguments) {
            if (arg == null) {
                continue;
            }
            visit(arg, handler);
            if (arg.prefix != null) {
                visit(arg.prefix, handler);
            }
        }
    }

    private static void visit(ScriptEntry.InternalArgument arg, java.util.function.Consumer<TagManager.ParseableTagPiece> handler) {
        if (arg.value == null) {
            return;
        }
        if (arg.value.singleTag != null) {
            handler.accept(arg.value.singleTag);
        }
        if (arg.value.pieces != null) {
            for (TagManager.ParseableTagPiece piece : arg.value.pieces) {
                handler.accept(piece);
            }
        }
    }
}
