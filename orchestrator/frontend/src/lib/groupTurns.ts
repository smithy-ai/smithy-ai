import type {
  SessionMessage,
  UserMessage,
  AssistantMessage,
  ToolResultContent,
} from "./sessionTypes";

/**
 * One exchange: a human prompt and everything the agent did before the next
 * prompt arrived. Assistant messages that precede the first prompt — a session
 * picked up mid-flight — form a leading turn with no user message.
 */
export interface Turn {
  /** The uuid of the turn's first message; stable while the turn grows. */
  id: string;
  label: string;
  userMessage: UserMessage | null;
  /** The turn's messages, oldest first. */
  messages: SessionMessage[];
  startedAt: string;
  lastActivityAt: string;
  stepCount: number;
  toolCount: number;
  hasError: boolean;
  preview: string;
}

const PREVIEW_MAX = 160;

export function userText(msg: UserMessage): string {
  if (typeof msg.message.content === "string") {
    return msg.message.content;
  }
  return msg.message.content
    .filter((b): b is { type: "text"; text: string } => b.type === "text")
    .map((b) => b.text)
    .join("\n");
}

/** A user message carrying only tool results is plumbing, not a prompt. */
function isPrompt(msg: UserMessage): boolean {
  return userText(msg).trim().length > 0;
}

function condense(text: string): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length > PREVIEW_MAX ? flat.slice(0, PREVIEW_MAX) + "…" : flat;
}

function previewOf(group: SessionMessage[], userMessage: UserMessage | null): string {
  if (userMessage) return condense(userText(userMessage));

  for (const msg of group) {
    if (msg.type !== "assistant") continue;
    for (const block of msg.message.content) {
      if (block.type === "text" && block.text.trim()) return condense(block.text);
      if (block.type === "tool_use") return block.name;
    }
  }
  return "";
}

/**
 * Splits a parsed transcript into turns and collects every tool result by the
 * id of the call it answers. Results live on the user message that follows the
 * call, which is not itself a turn boundary, so they are gathered up front.
 */
export function groupTurns(messages: SessionMessage[]): {
  turns: Turn[];
  toolResults: Map<string, ToolResultContent>;
} {
  const toolResults = new Map<string, ToolResultContent>();
  for (const msg of messages) {
    if (msg.type === "user" && Array.isArray(msg.message.content)) {
      for (const block of msg.message.content) {
        if (block.type === "tool_result") toolResults.set(block.tool_use_id, block);
      }
    }
  }

  const groups: SessionMessage[][] = [];
  for (const msg of messages) {
    if (msg.type === "system") continue;
    if (msg.type === "user" && !isPrompt(msg)) continue;

    if (msg.type === "user" || groups.length === 0) {
      groups.push([msg]);
    } else {
      groups[groups.length - 1].push(msg);
    }
  }

  let promptCount = 0;
  const turns = groups.map((group) => {
    const first = group[0];
    const userMessage = first.type === "user" ? (first as UserMessage) : null;
    if (userMessage) promptCount++;

    const assistants = group.filter(
      (m): m is AssistantMessage => m.type === "assistant",
    );

    let toolCount = 0;
    let hasError = false;
    for (const msg of assistants) {
      for (const block of msg.message.content) {
        if (block.type !== "tool_use") continue;
        toolCount++;
        if (toolResults.get(block.id)?.is_error) hasError = true;
      }
    }

    return {
      id: first.uuid,
      label: userMessage ? `Turn ${promptCount}` : "Session start",
      userMessage,
      messages: group,
      startedAt: first.timestamp,
      lastActivityAt: group[group.length - 1].timestamp,
      stepCount: assistants.length,
      toolCount,
      hasError,
      preview: previewOf(group, userMessage),
    };
  });

  return { turns, toolResults };
}
