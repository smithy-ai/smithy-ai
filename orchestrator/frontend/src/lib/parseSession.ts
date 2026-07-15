import type { SessionMessage } from "./sessionTypes";

const DISPLAYABLE_TYPES = new Set(["user", "assistant", "system"]);

export function parseSession(jsonlText: string): SessionMessage[] {
  const lines = jsonlText.split("\n").filter((l) => l.trim());
  const messages: SessionMessage[] = [];

  for (const line of lines) {
    try {
      const parsed = JSON.parse(line);

      if (!DISPLAYABLE_TYPES.has(parsed.type)) continue;
      if (parsed.isMeta) continue;
      if (parsed.isSidechain) continue;

      messages.push(parsed as SessionMessage);
    } catch {
      // skip malformed lines
    }
  }

  return messages.sort(
    (a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime(),
  );
}
