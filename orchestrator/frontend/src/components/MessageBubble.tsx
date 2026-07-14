import { Box, Text } from "@mantine/core";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import type {
  SessionMessage,
  AssistantMessage,
  UserMessage,
  ToolResultContent,
} from "../lib/sessionTypes";
import { ThinkingBlock } from "./ThinkingBlock";
import { ToolCall } from "./ToolCall";

interface MessageBubbleProps {
  message: SessionMessage;
  toolResults: Map<string, ToolResultContent>;
}

function getUserText(msg: UserMessage): string {
  if (typeof msg.message.content === "string") {
    return msg.message.content;
  }
  return msg.message.content
    .filter((b): b is { type: "text"; text: string } => b.type === "text")
    .map((b) => b.text)
    .join("\n");
}

function formatTime(ts: string): string {
  const d = new Date(ts);
  return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

export function MessageBubble({ message, toolResults }: MessageBubbleProps) {
  if (message.type === "system") return null;

  if (message.type === "user") {
    const text = getUserText(message);
    if (!text.trim()) return null;

    return (
      <Box py={6} px={16}>
        <Box
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "baseline",
            marginBottom: 1,
          }}
        >
          <Text size="xs" fw={700} style={{ color: "#6ea1f7" }}>
            You
          </Text>
          <Text size="xs" style={{ color: "#4a5568" }}>
            {formatTime(message.timestamp)}
          </Text>
        </Box>
        <Text
          size="sm"
          style={{ whiteSpace: "pre-wrap", lineHeight: 1.45, color: "#d4dae3" }}
        >
          {text}
        </Text>
      </Box>
    );
  }

  const assistant = message as AssistantMessage;
  const content = assistant.message.content;
  return (
    <Box py={6} px={16}>
      <Box
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "baseline",
          marginBottom: 1,
        }}
      >
        <Text size="xs" fw={700} style={{ color: "#e8965a" }}>
          Claude
        </Text>
        <Text size="xs" style={{ color: "#4a5568" }}>
          {formatTime(message.timestamp)}
        </Text>
      </Box>
      {content.map((block, i) => {
        if (block.type === "thinking") {
          return <ThinkingBlock key={i} content={block.thinking} />;
        }
        if (block.type === "tool_use") {
          const result = toolResults.get(block.id);
          return (
            <ToolCall
              key={i}
              block={block}
              result={
                result
                  ? { content: result.content, is_error: result.is_error }
                  : undefined
              }
            />
          );
        }
        if (block.type === "text") {
          return (
            <Box key={i} style={{ fontSize: "0.85rem", lineHeight: 1.5, color: "#d4dae3" }}>
              <Markdown remarkPlugins={[remarkGfm]}>{block.text}</Markdown>
            </Box>
          );
        }
        return null;
      })}
    </Box>
  );
}
