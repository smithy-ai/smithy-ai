import { Box, Text } from "@mantine/core";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import type {
  SessionMessage,
  AssistantMessage,
  ToolResultContent,
} from "../lib/sessionTypes";
import { userText } from "../lib/groupTurns";
import { formatWhen } from "../lib/time";
import { ThinkingBlock } from "./ThinkingBlock";
import { ToolCall } from "./ToolCall";

interface MessageBubbleProps {
  message: SessionMessage;
  toolResults: Map<string, ToolResultContent>;
  /**
   * False for a step that continues the same speaker's run, so a turn of forty
   * tool calls reads as one block instead of forty repeated name/time lines.
   */
  showHeader?: boolean;
}

export function MessageBubble({
  message,
  toolResults,
  showHeader = true,
}: MessageBubbleProps) {
  if (message.type === "system") return null;

  if (message.type === "user") {
    const text = userText(message);
    if (!text.trim()) return null;

    return (
      <Box py={showHeader ? 6 : 1} px={16}>
        {showHeader && (
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
              {formatWhen(message.timestamp)}
            </Text>
          </Box>
        )}
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
    <Box py={showHeader ? 6 : 1} px={16}>
      {showHeader && (
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
            {formatWhen(message.timestamp)}
          </Text>
        </Box>
      )}
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
