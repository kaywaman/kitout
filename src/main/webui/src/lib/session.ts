/**
 * The session key: handed over once in the URL fragment by `kitout serve`
 * (`http://127.0.0.1:7878/#token=...`), then remembered by this browser (localStorage), so the plain
 * address works afterwards, also after kitout restarts (the daemon keeps its key). The fragment is
 * removed from the address bar right away, so it is not bookmarked or shared. When the daemon no
 * longer accepts the key (reset, new machine), it is forgotten and the link is asked for again.
 */
const KEY = "kitout.token";
let memoryToken: string | null = null;

export function tokenFromHash(hash: string): string | null {
  const match = /(?:^#|&)token=([A-Za-z0-9_-]+)/.exec(hash);
  return match?.[1] ?? null;
}

export function captureToken(): void {
  const token = tokenFromHash(window.location.hash);
  if (!token) {
    return;
  }
  safeSet(token);
  const rest = window.location.hash.replace(/(^#|&)token=[A-Za-z0-9_-]+/, "").replace(/^#&?/, "#");
  history.replaceState(null, "", window.location.pathname + (rest === "#" ? "" : rest));
}

function read(storage: () => Storage): string | null {
  try {
    return storage().getItem(KEY);
  } catch {
    return null;
  }
}

export function sessionToken(): string | null {
  return read(() => localStorage) ?? read(() => sessionStorage);
}

function safeSet(token: string): void {
  memoryToken = token;
  for (const storage of [() => localStorage, () => sessionStorage]) {
    try {
      storage().setItem(KEY, token);
      return;
    } catch {
      // blocked storage: try the next, else the key lives only until the next reload
    }
  }
}

/** The daemon refused the key: forget it, so the page asks for the link instead of failing on every call. */
export function forgetToken(): void {
  memoryToken = null;
  for (const storage of [() => localStorage, () => sessionStorage]) {
    try {
      storage().removeItem(KEY);
    } catch {
      // nothing stored
    }
  }
}

export function currentToken(): string | null {
  return sessionToken() ?? memoryToken;
}
