package com.denizenscript.denizencore.utilities.text;

import com.denizenscript.denizencore.utilities.CoreUtilities;

/** Helper for case-insensitive strings that remember their original casing. */
public class StringHolder {

    /** Input text with original casing. */
    public final String str;

    /** Input text, pre-lowercased. */
    public final String low;

    public StringHolder(String _str) {
        str = _str;
        low = CoreUtilities.toLowerCase(_str);
    }

    /**
     * For text the caller already knows is lowercase, skipping the scan.
     * <p>
     * That scan is not free: for any character above 127 it asks {@link Character#isUpperCase}, which reads the Unicode
     * property tables. A name in a non-Latin script pays that per character, on every definition read and write.
     * Only pass text that really is lowercase - nothing checks.
     */
    public static StringHolder ofLowered(String alreadyLower) {
        return new StringHolder(alreadyLower, alreadyLower);
    }

    public StringHolder(String _str, String _low) {
        str = _str;
        low = _low;
    }

    @Override
    public int hashCode() {
        return low.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof String) {
            return CoreUtilities.equalsIgnoreCase(low, (String) obj);
        }
        else if (obj instanceof StringHolder) {
            return low.equals(((StringHolder) obj).low);
        }
        return false;
    }

    @Override
    public String toString() {
        return str;
    }
}
