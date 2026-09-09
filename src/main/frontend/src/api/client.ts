/** Same-origin BFF client: the session cookie is the only credential. */

/**
 * Spring Security's CSRF cookie and header. The cookie is readable by script by
 * design, and echoing it back is what distinguishes a request this application
 * made from one a third-party page made with the same ambient session.
 *
 * @Requirements GW_AUTH_0030
 */
const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";

function csrfToken(): string | undefined {
  for (const entry of document.cookie.split(";")) {
    const separator = entry.indexOf("=");
    if (separator > 0 && entry.slice(0, separator).trim() === CSRF_COOKIE) {
      return decodeURIComponent(entry.slice(separator + 1));
    }
  }
  return undefined;
}

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, detail: string) {
    super(detail);
    this.status = status;
  }
}

async function parseError(response: Response): Promise<ApiError> {
  let detail = `${response.status} ${response.statusText}`;
  try {
    const body: unknown = await response.json();
    if (body && typeof body === "object" && "detail" in body && typeof body.detail === "string") {
      detail = body.detail;
    }
  } catch {
    // Non-JSON error body; keep the status line.
  }
  return new ApiError(response.status, detail);
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const token = csrfToken();
  const response = await fetch(path, {
    // Spread init first: headers must win, or a caller passing its own would
    // drop the content type and the token with it.
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token === undefined ? {} : { [CSRF_HEADER]: token }),
      ...init?.headers,
    },
  });
  if (!response.ok) {
    throw await parseError(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}
