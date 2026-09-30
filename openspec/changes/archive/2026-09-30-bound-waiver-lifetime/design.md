# Design: bound-waiver-lifetime

## D1. A constant, ninety days

The configuration budget (`ConfigSurfaceBudgetTests`) is at its ratchet, and
`HeldContentController.MAX_HOLDINGS` is the precedent for a bound nobody has
tuned. Ninety days matches `Tokens.DEFAULT_MACHINE_MAX_TTL` (a quarter) and is
three times the portal form's 30-day default. If a deployment ever needs longer,
that is the argument the ratchet hears then.

## D2. Refuse, never clamp

A clamped waiver would record an expiry the reviewer did not choose, and the
ledger entry would disagree with the request. The message tells the reviewer to
renew rather than extend.

## D3. The portal's `max` is the server's own test

The form posts `YYYY-MM-DDT23:59:59Z`. The `max` is therefore the day of
`now + 90 days - 23:59:59`, the last day whose end is not past the cap, so the
control never offers a day the server refuses.
