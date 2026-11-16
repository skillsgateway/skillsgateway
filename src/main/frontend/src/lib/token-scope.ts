/**
 * Which marketplaces a personal access token may fetch, as the two token forms ask for it
 * (GW_AUTH_0079) and as the token list and the wizard say it (GW_AUTH_0080, GW_AUTH_0063).
 *
 * The API's rule is that an omitted or empty `scopes` means every marketplace. That is why a
 * "chosen" token with nothing chosen is never sent: it would grant the opposite of the choice.
 */
export const SCOPE_CHOICES = [
  { value: "every", label: "Every marketplace" },
  { value: "chosen", label: "Chosen marketplaces" },
] as const;

export type ScopeChoice = (typeof SCOPE_CHOICES)[number]["value"];

/** The `scopes` of the create request: the chosen names, and nothing at all for "every". */
export function scopesFor(choice: ScopeChoice, chosen: readonly string[]): string[] | undefined {
  return choice === "chosen" ? [...chosen] : undefined;
}

/** Valid means named: the server reads an empty list as every marketplace, so the client cannot. */
export function scopeChoiceValid(choice: ScopeChoice, chosen: readonly string[]): boolean {
  return choice === "every" || chosen.length > 0;
}

/**
 * The `scopes` of an edit request (GW_AUTH_0088). Unlike creation, the field is required, so
 * "every marketplace" is the explicit empty list; a limited choice is never sent empty (the
 * submit stays disabled until it names something, see {@link scopeChoiceValid}).
 */
export function scopesForUpdate(choice: ScopeChoice, chosen: readonly string[]): string[] {
  return choice === "chosen" ? sortedNames(chosen) : [];
}

/** A token's own `scopes` as the picker's choice: empty is every marketplace. */
export function selectionFor(scopes: readonly string[] | undefined): { choice: ScopeChoice; chosen: string[] } {
  const names = sortedNames(scopes ?? []);
  return names.length === 0 ? { choice: "every", chosen: [] } : { choice: "chosen", chosen: names };
}

/** Whether two token `scopes` lists reach the same marketplaces (empty is every marketplace). */
export function sameReach(a: readonly string[], b: readonly string[]): boolean {
  const left = sortedNames([...new Set(a)]);
  const right = sortedNames([...new Set(b)]);
  return left.length === right.length && left.every((name, index) => name === right[index]);
}

/**
 * What an edit adds to a token's reach, or null when it adds nothing (GW_AUTH_0088). `"every"` when
 * a limited token becomes unlimited; the names that were not there otherwise. Empty means every
 * marketplace on both sides, so nothing can be added to a token that already fetches them all.
 */
export function widening(before: readonly string[], after: readonly string[]): "every" | string[] | null {
  if (before.length === 0) return null;
  if (after.length === 0) return "every";
  const had = new Set(before);
  const added = sortedNames(after.filter((name) => !had.has(name)));
  return added.length > 0 ? added : null;
}

/** The names, in the one order every surface shows them. */
export function sortedNames(names: readonly string[]): string[] {
  return [...names].sort((a, b) => a.localeCompare(b));
}

function quoted(names: readonly string[]): string {
  return sortedNames(names)
    .map((name) => `'${name}'`)
    .join(", ");
}

/**
 * What the wizard's token reaches, in words. `scopes` is a token's own list, as the choice
 * stands or as the server returned it: empty is every marketplace. One function for both the
 * "will" before creation and the "does" after, so the two cannot drift.
 */
export function wizardReachSentence(
  scopes: readonly string[],
  marketplace: string,
  created: boolean,
): string {
  const subject = created ? "The token created here" : "A token created here";
  if (scopes.length === 0) {
    return (
      `${subject} is not limited to '${marketplace}': it fetches every marketplace this ` +
      "gateway serves you. Git keeps one credential per gateway, so the same token serves " +
      "every marketplace you add from here."
    );
  }
  return (
    `${subject} fetches only ${quoted(scopes)}; any other marketplace is answered as if it did ` +
    "not exist. Git keeps one credential per gateway, so a marketplace you add later from here " +
    "needs a token that includes it."
  );
}
