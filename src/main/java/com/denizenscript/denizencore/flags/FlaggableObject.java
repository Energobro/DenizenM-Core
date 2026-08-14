package com.denizenscript.denizencore.flags;

import com.denizenscript.denizencore.objects.ObjectTag;

public interface FlaggableObject extends ObjectTag {

    // <--[ObjectType]
    // @name FlaggableObject
    // @prefix None
    // @base None
    // @format
    // N/A
    //
    // @description
    // "FlaggableObject" is a pseudo-ObjectType that represents any type of object that can hold flags,
    // for use with <@link command flag> or any other flag related tags and mechanisms.
    //
    // Just because an ObjectType implements FlaggableObject, does not mean a specific instance of that object type is flaggable.
    // For example, LocationTag implements FlaggableObject, but a LocationTag-Vector (a location without a world) cannot hold a flag.
    //
    // -->

    AbstractFlagTracker getFlagTracker();

    default AbstractFlagTracker getFlagTrackerForTag() {
        return getFlagTracker();
    }

    void reapplyTracker(AbstractFlagTracker tracker);

    /**
     * Returns true if this object's flag tracker can be reached and written from a thread other than the main one.
     * That is a question about how the tracker is stored and fetched, not about the flag data itself: writes to the map based trackers
     * have been safe to do off-thread since flag writes started publishing rebuilt paths, so what is left to ask is whether
     * getFlagTracker itself touches the live server - which is what rules out entities, chunks and NPCs.
     * Answered per object type rather than per tracker because the tracker cannot be fetched until the answer is known.
     * The '- flag' command asks this only when it is already running off the main thread, and hands the whole line over if any target says no.
     */
    default boolean isFlagTrackerAsyncSafe() {
        return false;
    }

    default String getReasonNotFlaggable() {
        return "unknown reason - something went wrong";
    }
}
