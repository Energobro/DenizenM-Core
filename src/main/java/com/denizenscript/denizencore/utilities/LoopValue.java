package com.denizenscript.denizencore.utilities;

import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;

public abstract class LoopValue implements ObjectTag {

    public boolean installed;

    public abstract ObjectTag resolve();

    @Override
    public String getPrefix() {
        return resolve().getPrefix();
    }

    @Override
    public boolean isUnique() {
        return false;
    }

    @Override
    public String identify() {
        return resolve().identify();
    }

    @Override
    public String identifySimple() {
        return resolve().identifySimple();
    }

    @Override
    public ObjectTag setPrefix(String prefix) {
        return resolve().setPrefix(prefix);
    }

    @Override
    public String toString() {
        return identify();
    }

    public static final class Counter extends LoopValue {

        public int value;

        public Counter(int value) {
            this.value = value;
        }

        @Override
        public ObjectTag resolve() {
            return new ElementTag(value);
        }
    }

    public static final class Cell extends LoopValue {

        public ObjectTag value;

        public Cell(ObjectTag value) {
            this.value = value;
        }

        @Override
        public ObjectTag resolve() {
            return value;
        }
    }
}
