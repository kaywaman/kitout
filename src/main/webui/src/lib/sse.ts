/**
 * Incremental parser for server-sent events, following the HTML spec's rules for `data:` lines.
 * Feed it chunks as they arrive; it returns the complete events found so far.
 *
 * We read SSE through fetch (not EventSource) because the API needs the session token header.
 */
export class SseParser {
  private buffer = "";
  private data: string[] = [];

  push(chunk: string): string[] {
    this.buffer += chunk;
    const events: string[] = [];
    let newline: number;
    while ((newline = this.buffer.search(/\r\n|\r|\n/)) >= 0) {
      const line = this.buffer.slice(0, newline);
      const width = this.buffer.startsWith("\r\n", newline) ? 2 : 1;
      this.buffer = this.buffer.slice(newline + width);
      if (line === "") {
        if (this.data.length > 0) {
          events.push(this.data.join("\n"));
          this.data = [];
        }
      } else if (line.startsWith("data:")) {
        const value = line.slice(5);
        this.data.push(value.startsWith(" ") ? value.slice(1) : value);
      }
      // other fields (event:, id:, retry:) and comments (":") are not used by kitout
    }
    return events;
  }
}
