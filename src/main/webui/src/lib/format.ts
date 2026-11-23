/** Human-friendly time spans for status lines: "just now", "4 min", "2 h 5 min". */
export function since(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) {
    return "";
  }
  const seconds = Math.max(0, Math.round((now.getTime() - new Date(iso).getTime()) / 1000));
  if (seconds < 10) {
    return "just now";
  }
  if (seconds < 60) {
    return `${seconds} s`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes} min`;
  }
  const hours = Math.floor(minutes / 60);
  return minutes % 60 === 0 ? `${hours} h` : `${hours} h ${minutes % 60} min`;
}

export function duration(startIso: string | null | undefined, endIso: string | null | undefined, now: Date = new Date()): string {
  if (!startIso) {
    return "";
  }
  return since(startIso, endIso ? new Date(endIso) : now).replace("just now", "under 10 s");
}

/** 12400 -> "12.4 s", 95000 -> "1 min 35 s": how long a start took. */
export function seconds(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) {
    return "";
  }
  if (ms < 60000) {
    return `${(ms / 1000).toFixed(1)} s`;
  }
  const total = Math.round(ms / 1000);
  return `${Math.floor(total / 60)} min ${total % 60} s`;
}

/** "RUNNING" -> "Running" for display; state names stay stable in the API. */
export function label(constant: string | null | undefined): string {
  if (!constant) {
    return "";
  }
  const words = constant.toLowerCase().replaceAll("_", " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function plural(count: number, word: string): string {
  return `${count} ${word}${count === 1 ? "" : "s"}`;
}

/** "4 min ago", but "just now" stays "just now". */
export function ago(iso: string | null | undefined, now: Date = new Date()): string {
  const span = since(iso, now);
  return !span || span === "just now" ? span : `${span} ago`;
}

/** 0 -> "0 B", 1536 -> "1.5 KB", 5_300_000 -> "5.1 MB". */
export function bytes(n: number): string {
  if (n < 1024) {
    return `${n} B`;
  }
  const units = ["KB", "MB", "GB"];
  let value = n / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}
