package dev.skillsgateway.server.api;

import org.springframework.web.util.HtmlUtils;

/**
 * The minimal page the gateway answers with where the portal itself cannot be: sign-in, sign-out,
 * a request it cannot parse, a sign-in that failed. Self-contained so it holds under the content
 * security policy — inline style, no script, nothing loaded from elsewhere (GW_AUTH_0067, GW_AUTH_0068).
 */
public final class BrandedPage {

    private static final String MARK = """
            <svg viewBox="0 0 48 48" width="22" height="22" fill="none" stroke="currentColor" stroke-width="4" \
            stroke-linecap="round" aria-hidden="true"><path d="M16 6v11M16 31v11"/>\
            <path d="M32 6v11M32 31v11" stroke="#7c3aed"/><circle cx="6" cy="24" r="3.5" stroke-width="3.5"/>\
            <path d="M11.5 24h24.5"/><circle cx="42" cy="24" r="5" fill="#7c3aed" stroke="none"/></svg>""";

    private static final String STYLE = """
            :root{color-scheme:light dark;--fg:#1c1917;--muted:#57534e;--bg:#fff;--line:#e7e5e4;--accent:#6d28d9;\
            --accent-fg:#fff;--chip:#f3f0fa}\
            @media (prefers-color-scheme:dark){:root{--fg:#f5f5f4;--muted:#a8a29e;--bg:#0c0a09;--line:#292524;\
            --accent:#a78bfa;--accent-fg:#1c1917;--chip:#2e1065}}\
            body{margin:0;background:var(--bg);color:var(--fg);font-family:system-ui,sans-serif;line-height:1.5}\
            main{max-width:34rem;margin:4rem auto;padding:0 1rem}\
            .brand{display:flex;align-items:center;gap:.5rem;font-weight:600;margin-bottom:2rem}\
            h1{font-size:1.5rem;margin:0 0 .75rem}p{margin:0 0 1rem;overflow-wrap:anywhere}\
            .muted{color:var(--muted)}code{background:var(--chip);padding:.1rem .3rem;border-radius:.25rem}\
            .actions{display:flex;flex-wrap:wrap;gap:.75rem;align-items:center;margin-top:1.5rem}\
            a{color:var(--accent);font-weight:500}form{margin:0}\
            button{font:inherit;font-weight:500;cursor:pointer;border:0;border-radius:.375rem;padding:.45rem .9rem;\
            background:var(--accent);color:var(--accent-fg)}button:focus-visible,a:focus-visible{outline:3px solid \
            var(--accent);outline-offset:2px}""";

    private BrandedPage() {}

    /** HTML-escapes text that is interpolated into a page. */
    public static String escape(String text) {
        return HtmlUtils.htmlEscape(text == null ? "" : text);
    }

    /**
     * A whole document. The heading is escaped here; {@code bodyHtml} is markup the caller built,
     * with every value in it passed through {@link #escape} first.
     */
    public static String render(String heading, String bodyHtml) {
        String title = escape(heading);
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s · Skills Gateway</title><style>%s</style></head>
                <body><main>
                <div class="brand">%s Skills Gateway</div>
                <h1>%s</h1>
                %s
                </main></body>
                </html>
                """.formatted(title, STYLE, MARK, title, bodyHtml);
    }
}
