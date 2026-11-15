import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { api, ApiError } from "./client";

const reload = vi.fn();

beforeEach(() => {
  window.sessionStorage.clear();
  reload.mockClear();
  vi.stubGlobal("location", { ...window.location, reload });
});
afterEach(() => vi.unstubAllGlobals());

function respond(status: number) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(null, { status, statusText: "Unauthorized" })),
  );
}

test("a_401_from_the_api_reloads_the_page_once", async () => {
  respond(401);
  await expect(api("/api/v1/me")).rejects.toBeInstanceOf(ApiError);
  expect(reload).toHaveBeenCalledTimes(1);
  // Login did not take: a second 401 inside the window is an error, not another reload.
  await expect(api("/api/v1/me")).rejects.toBeInstanceOf(ApiError);
  expect(reload).toHaveBeenCalledTimes(1);
});

test("a_401_long_after_the_last_reload_reloads_again", async () => {
  window.sessionStorage.setItem("sgw-session-reload", String(Date.now() - 60_000));
  respond(401);
  await expect(api("/api/v1/me")).rejects.toBeInstanceOf(ApiError);
  expect(reload).toHaveBeenCalledTimes(1);
});

test("other_failures_do_not_reload", async () => {
  respond(403);
  await expect(api("/api/v1/me")).rejects.toBeInstanceOf(ApiError);
  expect(reload).not.toHaveBeenCalled();
});

test("a_refusal_carries_the_servers_detail_and_next_step", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(
      async () =>
        new Response(JSON.stringify({ detail: "needs an override", nextStep: "set an override" }), {
          status: 409,
          headers: { "Content-Type": "application/problem+json" },
        }),
    ),
  );
  const refused = await api("/api/v1/marketplaces/m/approval-posture").catch((error: unknown) => error);
  expect(refused).toBeInstanceOf(ApiError);
  expect((refused as ApiError).message).toBe("needs an override");
  expect((refused as ApiError).nextStep).toBe("set an override");
});
