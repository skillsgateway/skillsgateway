# Design: portal-polish

## Context

- An address no handler serves reaches Spring's static resource handler, which throws
  `NoResourceFoundException`; with `spring.mvc.problemdetails.enabled` that becomes an
  `application/problem+json` 404 whatever the client asked for.
- A request Tomcat cannot decode (`/%zz`) never reaches the application. It is answered by the
  `ErrorReportValve` in the Host pipeline (Spring Boot adds one with server info and report hidden).
- `/login` and `/logout` (GET) are Spring Security's `DefaultLoginPageGeneratingFilter` and
  `DefaultLogoutPageGeneratingFilter`, installed by `DefaultLoginPageConfigurer`. With a single
  client registration the authentication entry point redirects straight to the identity provider,
  so the generated login page is only ever seen when someone opens it, or as the default logout
  success target `/login?logout` (which the portal's sign-out `fetch` follows and discards).
- PR #783 rewrites the web chain's CSRF repository and request cache; this change stays out of
  those lines.

## Goals / Non-Goals

**Goals:** each finding in #776 fixed at the narrowest point; machine surfaces byte-for-byte
unchanged.

**Non-Goals:** no new API, configuration, role or estate object; no change to the sign-in flow or
the menu's sign-out; `/tokens` stays out of the sidebar (proposal, "Parked").

## Decisions

1. **"Browser navigation" means a `GET`/`HEAD` whose `Accept` names `text/html` explicitly**, not
   via `*/*`. That is what a browser sends for a page and what `fetch`, git and curl do not. On top
   of that, paths under `/api/`, `/git/`, `/publish/`, `/status/`, `/hooks/`, `/actuator/` and
   `/v3/` are never answered with a page, whatever they accept: a person pasting an API URL into a
   browser still reads the API's own refusal. *Alternative:* `Sec-Fetch-Mode: navigate` — not
   sent by every client that renders HTML, and absent in tests.
2. **Unknown address → the portal entry, status 404.** A `@ControllerAdvice` ordered ahead of
   Boot's problem-details advice handles `NoResourceFoundException`: for a browser navigation it
   returns `static/index.html` with 404, and the router's catch-all route renders "Page not found"
   inside the shell; otherwise it returns the exception's own `ProblemDetail` exactly as Boot's
   handler would. When the bundle is absent (a build that skipped the UI) it falls back to the
   minimal page. *Alternative:* a low-precedence `/**` controller with `produces=text/html` —
   `*/*` matches it, so it would also swallow asset requests.
3. **Malformed request → a Host error valve of ours.** `StandardHost` adds its error valve at
   start unless one of the configured class is already present, so a context customizer setting
   `errorReportValveClass` to our subclass makes Tomcat add it after every customizer has run,
   which puts it innermost; it reports first, and `setErrorReported()` makes Boot's own valve a
   no-op. It writes the minimal page to a client accepting HTML and a one-line plain-text status
   otherwise. *Alternative:* `errorCode.nnn` properties on the stock valve — they take a file on
   disk, and the page lives in the jar.
4. **Sign-in pages are ours, behind the generated ones' addresses.** `DefaultLoginPageConfigurer`
   is disabled on the web chain and a controller answers `GET /login` and `GET /logout`, both
   permitted to anonymous requests. Each states the reader's actual state: signed in as *name*
   (with a way back to the portal), or not signed in (with a link to `/`, which starts sign-in).
   `/logout` offers a form posting to the unchanged `POST /logout` with the CSRF token as `_csrf`,
   only to a session that has one; under the development escape hatch it says there is no session
   to end, as the menu does. The logout success target stays `/login?logout`; the page simply no
   longer claims an event.
5. **One minimal-page renderer** for the sign-in pages, the not-found fallback, the error valve and
   the existing sign-in failure page, so they look alike: inline style only (the CSP allows inline
   style, not script), the brand mark as inline SVG, light and dark via `prefers-color-scheme`,
   every interpolated value HTML-escaped.
6. **Tab title from the shell.** One hook sets `document.title`; the shell computes the title from
   the same route knowledge as its breadcrumb, so no page has to remember to call it. The catch-all
   route declares its own title through the route `handle`.
7. **`continue` is stripped client-side** with `history.replaceState`-equivalent navigation
   (`replace`), keeping every other parameter. The server's request cache needs it only on the
   request that carries it, which has already been answered when the portal runs.
8. **Unknown snapshot** is stated only once the marketplace list is settled (not refetching), so a
   freshly ingested snapshot the header just navigated to does not flash as "not found".
9. **Review queue extras come from data already fetched**: waiting time from `createdAt`, the
   finding count from the vetting view each row already reads for its badge, sorting client-side
   by waiting time or marketplace. Sorting by finding count would need every row's report before
   sorting, so it is not offered.
10. **Lifetimes are shared**: the wizard's list moves to one module that both forms import, so the
    two cannot drift.

## Risks / Trade-offs

- [The error valve depends on Tomcat's Host start order] → a real-HTTP test sends a malformed
  request and asserts our page, so an upgrade that changes the order fails the build.
- [Disabling the default login page could change the entry point] → the single-registration
  redirect is decided by the OAuth2 login configurer independently; the e2e login and sign-out
  tests exercise the real flow.
- [A `*` client route could mask a missing `SpaController` entry] → it cannot: an unlisted route
  still answers 404 on a cold load, and `SpaRoutesTests` asserts 200 for every router path.
