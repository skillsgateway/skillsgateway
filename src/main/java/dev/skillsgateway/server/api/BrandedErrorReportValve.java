package dev.skillsgateway.server.api;

import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.ActionCode;
import org.springframework.http.HttpStatus;

/**
 * What Tomcat answers with when the application never sees the request — an address it cannot
 * decode, chiefly (GW_AUTH_0067). A browser gets the gateway's minimal page; anything else one line
 * of plain text, never the container's own page naming the container.
 *
 * <p>Installed as the Host's error report valve class by {@link ErrorPagesConfig}, so Tomcat adds it
 * at Host start, innermost, and it reports before Spring Boot's own valve, which then finds the
 * error already reported.
 */
public class BrandedErrorReportValve extends ErrorReportValve {

    @Override
    @Requirements({"GW_AUTH_0067"})
    protected void report(Request request, Response response, Throwable throwable) {
        int status = response.getStatus();
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        AtomicBoolean ioAllowed = new AtomicBoolean(true);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, ioAllowed);
        if (!ioAllowed.get()) {
            return;
        }
        HttpStatus known = HttpStatus.resolve(status);
        String reason = known == null ? null : known.getReasonPhrase();
        boolean html = BrowserNotFoundAdvice.acceptsHtml(request.getHeader("Accept"));
        try {
            response.setContentType(html ? "text/html" : "text/plain");
            response.setCharacterEncoding("utf-8");
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write(
                        html
                                ? BrandedPage.render(
                                        reason == null ? "Request not served" : reason,
                                        "<p class=\"muted\">The gateway could not serve this request (status %d)."
                                                        .formatted(status)
                                                + " Check the address.</p>\n"
                                                + "<div class=\"actions\"><a href=\"/\">Go to the overview</a></div>")
                                : status + (reason == null ? "" : " " + reason) + "\n");
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException e) {
            // Nothing more can be said to this client; the status is already set.
        }
    }
}
