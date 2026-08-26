import { useState } from "react";
import { UnstyledButton, Collapse, Box, Text } from "@mantine/core";
import type { Turn } from "../lib/groupTurns";
import type { ToolResultContent } from "../lib/sessionTypes";
import { MessageBubble } from "./MessageBubble";
import { formatWhen } from "../lib/time";

interface TurnItemProps {
  turn: Turn;
  toolResults: Map<string, ToolResultContent>;
  opened: boolean;
  isLatest: boolean;
  onToggle: () => void;
}

function plural(n: number, word: string): string {
  return `${n} ${word}${n === 1 ? "" : "s"}`;
}

function summarize(turn: Turn): string {
  const parts: string[] = [];
  if (turn.stepCount > 0) parts.push(plural(turn.stepCount, "step"));
  if (turn.toolCount > 0) parts.push(plural(turn.toolCount, "tool"));
  return parts.join(" · ");
}

export function TurnItem({
  turn,
  toolResults,
  opened,
  isLatest,
  onToggle,
}: TurnItemProps) {
  const [hovered, setHovered] = useState(false);
  const meta = summarize(turn);

  return (
    <Box style={{ borderBottom: "1px solid #1a1f2e" }}>
      <UnstyledButton
        onClick={onToggle}
        onMouseEnter={() => setHovered(true)}
        onMouseLeave={() => setHovered(false)}
        style={{
          display: "block",
          width: "100%",
          padding: "7px 12px",
          background: hovered ? "#12161f" : opened ? "#0d1017" : undefined,
        }}
      >
        <Box style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
          <Text span size="xs" style={{ color: "#4a5568", width: 8, flexShrink: 0 }}>
            {opened ? "v" : ">"}
          </Text>
          <Text
            span
            size="xs"
            fw={700}
            style={{ color: turn.userMessage ? "#6ea1f7" : "#6b7585", flexShrink: 0 }}
          >
            {turn.label}
          </Text>
          {isLatest && (
            <Text span size="xs" fw={600} style={{ color: "#5da87e", flexShrink: 0 }}>
              latest
            </Text>
          )}
          {meta && (
            <Text span size="xs" style={{ color: "#4a5568", flexShrink: 0 }}>
              {meta}
            </Text>
          )}
          {turn.hasError && (
            <Text span size="xs" fw={600} style={{ color: "#e85d5d", flexShrink: 0 }}>
              error
            </Text>
          )}
          <Text
            span
            size="xs"
            style={{ color: "#4a5568", marginLeft: "auto", flexShrink: 0 }}
          >
            {formatWhen(turn.lastActivityAt)}
          </Text>
        </Box>
        {!opened && turn.preview && (
          <Text
            size="xs"
            style={{
              color: "#6b7585",
              marginLeft: 16,
              marginTop: 1,
              overflow: "hidden",
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
            }}
          >
            {turn.preview}
          </Text>
        )}
      </UnstyledButton>
      <Collapse in={opened}>
        <Box pb={6} style={{ background: "#0d1017" }}>
          {turn.messages.map((msg, i) => (
            <MessageBubble
              key={msg.uuid}
              message={msg}
              toolResults={toolResults}
              showHeader={msg.type !== turn.messages[i - 1]?.type}
            />
          ))}
        </Box>
      </Collapse>
    </Box>
  );
}
