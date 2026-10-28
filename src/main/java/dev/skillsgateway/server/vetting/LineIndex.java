package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import java.util.Arrays;

/**
 * The line of any offset in one text, from the offsets of its newlines indexed once (GW_VETTING_0063).
 * Rescanning the text up to each match made locating every finding quadratic in a file dense with
 * matches; this is linear to build and logarithmic per lookup.
 */
@Requirements({"GW_VETTING_0063"})
public final class LineIndex {

    private final int[] newlines;

    private LineIndex(int[] newlines) {
        this.newlines = newlines;
    }

    public static LineIndex of(CharSequence text) {
        int[] found = new int[16];
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (count == found.length) {
                    found = Arrays.copyOf(found, count * 2);
                }
                found[count++] = i;
            }
        }
        return new LineIndex(Arrays.copyOf(found, count));
    }

    /** The 1-based line holding {@code offset}: one more than the newlines before it. */
    public int lineOf(int offset) {
        int position = Arrays.binarySearch(newlines, offset);
        return (position >= 0 ? position : -position - 1) + 1;
    }
}
