import { expect, test } from "vitest";
import { sameReach, scopesForUpdate, selectionFor, widening } from "./token-scope";

/**
 * The rules an edit of a token's reach turns on (GW_AUTH_0088). An empty list means every
 * marketplace, which is the one fact each function below has to keep straight.
 *
 * @SVCs SVC_GW_AUTH_0108
 */
test("every_marketplace_is_the_explicit_empty_list_and_a_limited_choice_is_never_sent_empty", () => {
  expect(scopesForUpdate("every", ["a", "b"])).toEqual([]);
  expect(scopesForUpdate("chosen", ["b", "a"])).toEqual(["a", "b"]);
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
