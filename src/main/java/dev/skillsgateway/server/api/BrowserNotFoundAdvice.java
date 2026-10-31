package dev.skillsgateway.server.api;

import io.github.reqstool.annotations.Requirements;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.UrlPathHelper;

/**
 * An address nothing serves (GW_AUTH_0067): a browser asking for a page gets the portal, answered 404,
 * and the portal's own catch-all route says "page not found" inside its shell. Every other request
 * gets the problem document Boot's handler would have written; this advice only runs ahead of it.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BrowserNotFoundAdvice {

    /** Machine surfaces keep their own refusal even when a browser asks: their formats are contracts. */
    private static final List<String> MACHINE_PREFIXES =
            List.of("/api/", "/git/", "/publish/", "/status/", "/hooks/", "/actuator/", "/v3/");

    private static final ClassPathResource PORTAL = new ClassPathResource("static/index.html");

    @ExceptionHandler(NoResourceFoundException.class)
    @Requirements({"GW_AUTH_0067"})
    public ResponseEntity<?> notFound(NoResourceFoundException exception, HttpServletRequest request)
            throws IOException {
        if (!isPageRequest(request)) {
            ProblemDetail body = exception.getBody();
            return ResponseEntity.status(exception.getStatusCode())
                    .headers(exception.getHeaders())
                    .body(body);
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(portalOrFallback());
    }

    /** A browser's page request: GET or HEAD, {@code text/html} named outright, off the machine surfaces. */
    static boolean isPageRequest(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        // Decoded, as the handlers match it, so an escaped prefix is still that surface.
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (MACHINE_PREFIXES.stream().anyMatch(prefix -> path.startsWith(prefix) || (path + "/").equals(prefix))) {
            return false;
        }
        return acceptsHtml(request.getHeader("Accept"));
    }

    /** True when the Accept header names {@code text/html} itself; a wildcard is not a request for a page. */
    public static boolean acceptsHtml(String accept) {
        if (accept == null || accept.isBlank()) {
            return false;
        }
        try {
            return MediaType.parseMediaTypes(accept).stream()
                    .anyMatch(type -> "text".equals(type.getType())
                            && "html".equals(type.getSubtype())
                            && type.getQualityValue() > 0);
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    /** The portal's entry; a build that skipped the UI has none, and gets the minimal page instead. */
    private static String portalOrFallback() throws IOException {
        if (PORTAL.exists()) {
            try (InputStream in = PORTAL.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return BrandedPage.render(
                "Page not found",
                "<p>Nothing is served at this address.</p>\n<div class=\"actions\"><a href=\"/\">Go to the overview</a></div>");
    }
}
