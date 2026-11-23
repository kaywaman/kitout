/** "0.0.0.0:5432->5432/tcp, :::5432->5432/tcp" -> ["5432→5432"]: the host ports a developer connects to, once each. */
export function publishedPorts(ports: string): string[] {
  const seen = new Set<string>();
  for (const part of ports.split(",")) {
    const m = /:(\d+)->(\d+)\//.exec(part.trim());
    if (m) {
      seen.add(`${m[1]}→${m[2]}`);
    }
  }
  return [...seen];
}
