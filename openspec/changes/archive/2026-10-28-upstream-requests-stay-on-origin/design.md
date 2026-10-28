# Design: upstream-requests-stay-on-origin

## Context

See proposal.md, Why. `UpstreamGit.connect` set a connection factory only when
a credential was selected (`UpstreamCredentials.Selected.Factory`), and that
factory decided only whether to attach `Authorization`. JGit's `TransportHttp`
resolves each redirect itself and asks the factory for a connection to the new
URL.

## Decisions

### D1 — Pin at `create`, before any connection

JGit calls `create(url)` for every hop. Refusing there means the other origin
is never contacted. The JDK is never allowed to follow a redirect itself,
because a hop it took would bypass `create`.

### D2 — Origin is scheme, host and port of the registered URL

This matches what `GuardedHttpConnectionFactory` pins for external sources.
A same-origin path change is allowed, because forges move repositories that
way. The credential's prefix rule still applies inside the origin.

### D3 — No address policy on the registered host

An upstream is chosen by an administrator and is often an internal forge, so
the private-address tier used for external sources would refuse legitimate
registrations. With the origin pinned, the only host the gateway connects to
is the one the administrator registered. Egress isolation remains the control
for what that host may be.

### D4 — One wrapper, the credentialed factory inside it

`UpstreamOriginFactory` decides *where*; `Selected.Factory` still decides
*what is attached*. Each keeps one job, and an anonymous upstream gets the
same pin as a credentialed one.

## Risks / Trade-offs

- [An upstream that redirects to another host stops registering] → It is
  registered at its final URL. The refusal names the redirect target, so the
  fix is evident.
