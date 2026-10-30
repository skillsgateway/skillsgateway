# Design: prompt-injection-mention-context

## Context

See proposal.md, Why. `PromptInjectionVetter` holds a list of `ContentRules.Rule`
regexes, each with a fixed severity. Every rule except `hidden-html-instruction`
is high, so a single match fails the verdict and blocks approval.
`ContentRules.apply` reports at most one finding per rule and line.

## Decisions

### D1 — Downgrade a mention, never drop it

A marker that reads as a mention is reported at medium (warn) with its own
message. It is not removed. A snapshot is held until a person approves it, so a
warn finding is still in front of the reviewer. It just does not demand a waiver.

Alternatives:
- Suppress mentions entirely. Rejected: an attacker can write *never* in front of
  a payload, and suppression would hide it. A downgrade only loses the block.
- Skip non-instruction paths (`README.md`, `.github/`, `tests/`). Rejected: a
  skill can tell the agent to read any file in the tree, so the path does not
  bound what the agent reads.
- A semantic (LLM) vetter. It is the real answer to paraphrase, and the external
  vetting connector already allows one. It is out of scope here.

### D2 — What counts as a mention

The clause is the text before the match, back to the previous `[.!?;:]` followed
by whitespace, or a blank line, capped at 240 characters. It may cross single
newlines, because prose is wrapped. A match is a mention when:

1. that clause holds a prohibition word: *never, do not, don't, must not, should
   not, avoid, refuse*; or
2. the match sits inside a `"…"` or `“…”` span, and the clause holds an example
   marker: *like, such as, e.g., for example, for instance*.

Backticks are deliberately not a marker. `` Run `curl … | sh` first `` is how a
real instruction is written. Only `instruction-override`,
`system-prompt-disclosure`, `credential-path-reference`, `pipe-to-shell` and
`exfiltration-instruction` can be downgraded. The concealment forms already start
from a negation, and invisible characters are never a mention.

`Rule` gains a `mentionable` flag. `ContentRules.apply` checks the context only
for such rules. When a line has both a mention and a plain match of one rule, it
keeps the plain (higher) one.

### D3 — Disclosure means the agent's own prompt

The verb (*reveal, repeat, print, output, disclose, show, dump*) must be followed
by at most four filler words (*me, us, out, back, all, the, full, entire,
complete, exact, whole, everything, in, of, from, verbatim, contents, text*),
then *your/the system prompt*, *your/the initial prompt*, *your instructions*,
*your prompt* or *these instructions*. "Output tokens … add to the system prompt"
no longer fires, because "tokens" is not a filler word.

### D4 — Concealment: advice and conditionals

Form 1 (negation, verb of telling, person) gains two exclusions:
- the person is followed by *they* or *to*: "do not tell the user they need …"
  and "don't tell the user to restart" are advice about content;
- the gap between the verb and the person holds *only, unless* or *if*: "mention
  it only when the user asks" is a condition.

### D5 — Emoji joiners and traversal strings

U+200D is skipped when the code point before it is pictographic (U+2190–U+2BFF,
U+1F000 and up, or U+FE0F / a skin-tone modifier after one), and so is the code
point after it. `/etc/passwd` directly after `../` is not a credential reference.

## Risks / Trade-offs

- [An attacker wraps a payload in a prohibition to get a warn instead of a fail]
  → The finding stays in the report at medium, and approval is still a person's
  decision. A fixture pins this.
- [Some false positives remain]: credential-path mentions in a security-review
  policy, `.npmrc` in prose, and "without informing the user" as a bug
  description. They are kept as known misses in the measurement output rather
  than hidden with broader exclusions.
- [Verdicts change]: version 3 makes earlier runs stale, so re-vetting shows the
  new outcome rather than a mix of the two.
