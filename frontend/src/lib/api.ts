/** An HTTP error from the backend. status 0 means the server could not be reached. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

type ApiInit = Omit<RequestInit, "body"> & { json?: unknown };

const FALLBACK_MESSAGES: Record<number, string> = {
  401: "Please log in",
  403: "You do not have access to this",
  404: "Not found",
  429: "Too many requests",
  500: "The server is unavailable right now. Please try again in a moment.",
  502: "The server is unavailable right now. Please try again in a moment.",
  503: "The service is busy. Please try again.",
  504: "The server took too long to answer. Please try again.",
};

/**
 * Calls the backend through the Next.js /api proxy. The login cookie travels automatically.
 * Resolves with the parsed JSON body, or throws ApiError with the backend's {"error": "..."} message.
 */
export async function api<T>(path: string, init: ApiInit = {}): Promise<T> {
  const { json, headers: rawHeaders, ...rest } = init;
  const headers = new Headers(rawHeaders);
  let body: string | undefined;
  if (json !== undefined) {
    headers.set("Content-Type", "application/json");
    body = JSON.stringify(json);
  }
  const url = new URL(path, window.location.origin).toString();

  let res: Response;
  try {
    res = await fetch(url, { ...rest, headers, body, credentials: "same-origin", cache: "no-store" });
  } catch {
    throw new ApiError(0, "Can't reach the server. Check your connection and try again.");
  }

  const text = res.status === 204 ? "" : await res.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (!res.ok) {
    const message = (data as { error?: string } | null)?.error ?? FALLBACK_MESSAGES[res.status] ?? `Request failed (${res.status})`;
    throw new ApiError(res.status, message);
  }
  return data as T;
}

export function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : "Something went wrong";
}
