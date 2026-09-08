package dev.skillsgateway.server.mirror;

import dev.skillsgateway.server.storage.ServedContentChangedEvent;
import io.github.reqstool.annotations.Requirements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The only wire between the gateway's enforcement acts and the mirror (GW_FACADE_0021).
 *
 * <p>It is one line, and the {@code catch} around it is the point. Spring's default multicaster
 * runs a listener on the publishing thread and lets its exceptions escape into the publisher, so
 * the approval or revocation that raised the event is exactly what an unguarded listener could
 * fail. Nothing may reach that caller from here: the queueing call is already non-blocking and
 * already swallows its own refusals, and this is the second latch on the same door.
 */
@Component
public class MirrorPublicationListener {

    private static final Logger log = LoggerFactory.getLogger(MirrorPublicationListener.class);

    private final ForgeMirrorService mirror;

    public MirrorPublicationListener(ForgeMirrorService mirror) {
        this.mirror = mirror;
    }

    @EventListener
    @Requirements({"GW_FACADE_0020", "GW_FACADE_0021", "GW_FACADE_0022"})
    public void onServedContentChanged(ServedContentChangedEvent event) {
        try {
            mirror.reconcileLater(event.marketplace(), event.reason());
        } catch (RuntimeException e) {
            log.warn("forge mirror could not be notified of {} on {}", event.reason(), event.marketplace(), e);
        }
    }
}
