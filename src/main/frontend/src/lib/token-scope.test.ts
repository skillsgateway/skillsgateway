import { expect, test } from "vitest";
import { sameReach, selectionFor, updateBodyFor, widening } from "./token-scope";

/**
 * The rules an edit of a token's reach turns on (GW_AUTH_0088). A token's own empty `scopes` means
 * every marketplace; an edit never sends one, it states `allMarketplaces`.
 *
 * @SVCs SVC_GW_AUTH_0108
 */
test("every_marketplace_is_stated_as_all_marketplaces_and_a_limited_choice_is_a_sorted_list", () => {
  expect(updateBodyFor("every", ["a", "b"])).toEqual({ allMarketplaces: true });
  expect(updateBodyFor("chosen", ["b", "a"])).toEqual({ scopes: ["a", "b"] });
  // An empty list is not a way to say anything: it is the one body the server refuses.
  expect(updateBodyFor("every", [])).not.toHaveProperty("scopes");
});

test("a_tokens_own_scopes_become_the_pickers_choice", () => {
  expect(selectionFor(undefined)).toEqual({ choice: "every", chosen: [] });
  expect(selectionFor([])).toEqual({ choice: "every", chosen: [] });
  expect(selectionFor(["b", "a"])).toEqual({ choice: "chosen", chosen: ["a", "b"] });
});

test("two_lists_reach_the_same_marketplaces_whatever_their_order_or_repeats", () => {
  expect(sameReach([], [])).toBe(true);
  expect(sameReach(["a", "b"], ["b", "a"])).toBe(true);
  expect(sameReach(["a", "a"], ["a"])).toBe(true);
  expect(sameReach(["a"], ["a", "b"])).toBe(false);
  expect(sameReach(["a"], ["b"])).toBe(false);
  // Every marketplace is not the same reach as any named list.
  expect(sameReach([], ["a"])).toBe(false);
});

test("an_edit_widens_when_it_goes_to_every_marketplace_or_adds_a_name_and_never_otherwise", () => {
  expect(widening(["a"], [])).toBe("every");
  expect(widening(["a"], ["a", "c", "b"])).toEqual(["b", "c"]);
  expect(widening(["a", "b"], ["a"])).toBeNull();
  expect(widening(["a", "b"], ["b", "a"])).toBeNull();
  expect(widening(["a"], ["b"])).toEqual(["b"]);
  // An unlimited token has nothing to be widened to.
  expect(widening([], [])).toBeNull();
  expect(widening([], ["a"])).toBeNull();
});
