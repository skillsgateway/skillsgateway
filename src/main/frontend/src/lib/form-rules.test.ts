import { expect, test } from "vitest";
import { normalizeCloneUrl } from "./form-rules";

/**
 * Mirrors the server's `CloneUrlNormalizerTests` (GW_INGEST_0029): the same input variants must
 * normalize to the same value on both sides, or the client's pre-submission warning and the
 * server's authoritative one could disagree.
 */
test("host_case_a_trailing_slash_and_a_git_suffix_all_normalize_the_same", () => {
  const canonical = "https://github.com/acme/marketplace";
  expect(normalizeCloneUrl("https://GitHub.com/acme/marketplace")).toBe(canonical);
  expect(normalizeCloneUrl("https://github.com/acme/marketplace/")).toBe(canonical);
  expect(normalizeCloneUrl("https://github.com/acme/marketplace.git")).toBe(canonical);
  expect(normalizeCloneUrl("https://github.com/acme/marketplace.GIT")).toBe(canonical);
  expect(normalizeCloneUrl("https://github.com/acme/marketplace/.git")).toBe(canonical);
  expect(normalizeCloneUrl("https://GITHUB.COM/acme/marketplace.git/")).toBe(canonical);
});

test("the_path_keeps_its_case", () => {
  expect(normalizeCloneUrl("https://github.com/Acme/Marketplace")).toBe(
    "https://github.com/Acme/Marketplace",
  );
  expect(normalizeCloneUrl("https://github.com/Acme/Marketplace")).not.toBe(
    normalizeCloneUrl("https://github.com/acme/marketplace"),
  );
});

test("scheme_and_port_are_part_of_the_identity", () => {
  expect(normalizeCloneUrl("https://github.com/acme/marketplace")).not.toBe(
    normalizeCloneUrl("http://github.com/acme/marketplace"),
  );
  expect(normalizeCloneUrl("https://example.com:8443/acme/marketplace")).toBe(
    "https://example.com:8443/acme/marketplace",
  );
});

test("blank_and_unparseable_values_normalize_to_null", () => {
  expect(normalizeCloneUrl("")).toBeNull();
  expect(normalizeCloneUrl("   ")).toBeNull();
  expect(normalizeCloneUrl("not a url")).toBeNull();
});
