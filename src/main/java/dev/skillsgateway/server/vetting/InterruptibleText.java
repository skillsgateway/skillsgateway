package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import java.util.concurrent.CancellationException;

/**
 * Text that stops a regular-expression match once its thread is interrupted (GW_VETTING_0062).
 * {@code java.util.regex} never polls interruption itself, so cancelling a timed-out vetter would
 * otherwise only set a flag while the match runs on. Every pattern a vetter runs over snapshot
 * content matches through this.
 */
@Requirements({"GW_VETTING_0062"})
public final class InterruptibleText implements CharSequence {

    private final CharSequence text;

    private InterruptibleText(CharSequence text) {
        this.text = text;
    }

    /** {@code text}, made interruptible. */
    public static CharSequence of(CharSequence text) {
        return text instanceof InterruptibleText ? text : new InterruptibleText(text);
    }

    @Override
    public char charAt(int index) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("vetting was cancelled");
        }
        return text.charAt(index);
    }

    @Override
    public int length() {
        return text.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new InterruptibleText(text.subSequence(start, end));
    }

    @Override
    public String toString() {
        return text.toString();
    }
}
