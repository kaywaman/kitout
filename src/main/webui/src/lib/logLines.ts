/** How a log line is coloured: kitout's own markers, errors, warnings, or plain. */
export type LineLevel = "kitout" | "error" | "warn" | undefined;

export function lineLevel(line: string): LineLevel {
  if (line.startsWith("=== kitout:")) {
    return "kitout";
  }
  if (/\b(ERROR|FATAL|SEVERE|BUILD FAILURE)\b|\w*Exception\b/.test(line)) {
    return "error";
  }
  if (/\bWARN(ING)?\b/.test(line)) {
    return "warn";
  }
  return undefined;
}
