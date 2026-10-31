package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs vetting work on its own thread, interrupts it mid-run, and asserts it stopped by cancelling.
 * The thread catches its own outcome so the expected exception is asserted here rather than printed
 * to stderr by the default handler, where CI reports it as an error.
 */
final class InterruptedRun {

    private InterruptedRun() {}

    static void assertStopsWhenInterrupted(Runnable work) throws InterruptedException {
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread thread = new Thread(
                () -> {
                    try {
                        work.run();
                    } catch (Throwable thrown) {
                        outcome.set(thrown);
                    }
                },
                "vetter-under-test");
        thread.setDaemon(true);
        thread.start();
        Thread.sleep(300);
        assertThat(thread.isAlive()).as("still vetting before the interruption").isTrue();
        thread.interrupt();
        thread.join(2_000);
        assertThat(thread.isAlive())
                .as("still vetting two seconds after the interruption")
                .isFalse();
        assertThat(outcome.get()).as("the run stopped by cancelling").isInstanceOf(CancellationException.class);
    }
}
