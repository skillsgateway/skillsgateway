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
