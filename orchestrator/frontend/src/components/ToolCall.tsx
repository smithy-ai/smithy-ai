import { UnstyledButton, Collapse, Box, Text, Code } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { diffLines } from "diff";
import type { ToolUseBlock } from "../lib/sessionTypes";

interface ToolCallProps {
  block: ToolUseBlock;
  result?: {
    content: string | Array<{ type: string; text?: string }>;
    is_error?: boolean;
  };
}

function summarizeInput(input: Record<string, unknown>): string {
  if (input.command && typeof input.command === "string") {
    const cmd = input.command as string;
    return cmd.length > 100 ? cmd.slice(0, 100) + "..." : cmd;
  }
  if (input.file_path && typeof input.file_path === "string") {
    return input.file_path as string;
  }
  if (input.pattern && typeof input.pattern === "string") {
    return `"${input.pattern}"`;
  }
  if (input.prompt && typeof input.prompt === "string") {
    const p = input.prompt as string;
    return p.length > 80 ? p.slice(0, 80) + "..." : p;
  }
  const keys = Object.keys(input);
  return keys.length > 0 ? keys.slice(0, 3).join(", ") : "";
}

function getResultText(
  content: string | Array<{ type: string; text?: string }>,
): string {
  if (typeof content === "string") return content;
  return content
    .map((c) => {
      if (c.type === "text" && c.text) return c.text;
      if (c.type === "tool_reference") return `[tool: ${(c as any).tool_name}]`;
      return "";
    })
    .filter(Boolean)
    .join("\n");
}

const codeBlockStyle = {
  fontSize: "0.75rem",
  maxHeight: 300,
  overflow: "auto" as const,
  background: "#080a0f",
  color: "#8892a0",
  border: "1px solid #1a1f2e",
};

function renderEditInput(input: Record<string, unknown>) {
  const filePath = input.file_path as string;
  const oldStr = (input.old_string as string) || "";
  const newStr = (input.new_string as string) || "";
  const replaceAll = input.replace_all as boolean;
  const changes = diffLines(oldStr, newStr);

  return (
    <Box>
      <Text size="xs" style={{ color: "#4a5568", marginBottom: 4 }}>
        {filePath}
        {replaceAll && <span style={{ color: "#6b7585" }}> (replace all)</span>}
      </Text>
      <Box
        style={{
          ...codeBlockStyle,
          padding: "6px 8px",
          borderRadius: 4,
          whiteSpace: "pre-wrap",
          fontFamily: "var(--mantine-font-family-monospace)",
          lineHeight: 1.45,
        }}
      >
        {changes.map((part, i) => {
          if (part.added) {
            return part.value
              .split("\n")
              .filter((_, li, arr) => li < arr.length - 1 || _ !== "")
              .map((line, j) => (
                <div
                  key={`${i}-${j}`}
                  style={{ background: "rgba(93, 168, 126, 0.1)", color: "#5da87e" }}
                >
                  + {line}
                </div>
              ));
          }
          if (part.removed) {
            return part.value
              .split("\n")
              .filter((_, li, arr) => li < arr.length - 1 || _ !== "")
              .map((line, j) => (
                <div
                  key={`${i}-${j}`}
                  style={{ background: "rgba(232, 93, 93, 0.1)", color: "#e85d5d" }}
                >
                  - {line}
                </div>
              ));
          }
          return part.value
            .split("\n")
            .filter((_, li, arr) => li < arr.length - 1 || _ !== "")
            .map((line, j) => (
              <div key={`${i}-${j}`} style={{ color: "#6b7585" }}>
                {"  "}
                {line}
              </div>
            ));
        })}
      </Box>
    </Box>
  );
}

function renderBashInput(input: Record<string, unknown>) {
  return (
    <Code block style={codeBlockStyle}>
      {input.command as string}
    </Code>
  );
}

function renderWriteInput(input: Record<string, unknown>) {
  const filePath = input.file_path as string;
  const content = input.content as string;
  const lines = content.split("\n");
  const truncated = lines.length > 50;
  const displayContent = truncated
    ? lines.slice(0, 50).join("\n") + `\n\n... ${lines.length - 50} more lines`
    : content;

  return (
    <Box>
      <Text size="xs" style={{ color: "#4a5568", marginBottom: 4 }}>
        {filePath}
      </Text>
      <Code block style={codeBlockStyle}>
        {displayContent}
      </Code>
    </Box>
  );
}

function renderReadInput(input: Record<string, unknown>) {
  return (
    <Text size="xs" style={{ color: "#4a5568" }}>
      {input.file_path as string}
    </Text>
  );
}

function renderToolInput(block: ToolUseBlock) {
  switch (block.name) {
    case "Edit":
      return renderEditInput(block.input);
    case "Bash":
      return renderBashInput(block.input);
    case "Write":
      return renderWriteInput(block.input);
    case "Read":
      return renderReadInput(block.input);
    default:
      return (
        <Code block style={codeBlockStyle}>
          {JSON.stringify(block.input, null, 2)}
        </Code>
      );
  }
}

export function ToolCall({ block, result }: ToolCallProps) {
  const [opened, { toggle }] = useDisclosure(false);
  const summary = summarizeInput(block.input);

  return (
    <Box>
      <UnstyledButton
        onClick={toggle}
        style={{
          fontSize: "0.8rem",
          display: "flex",
          alignItems: "baseline",
          gap: 6,
          width: "100%",
          padding: "1px 0",
          lineHeight: 1.4,
        }}
      >
        <Text span size="xs" style={{ color: "#4a5568" }}>
          {opened ? "v" : ">"}
        </Text>
        <Text span size="xs" fw={600} style={{ color: "#e8965a", flexShrink: 0 }}>
          {block.name}
        </Text>
        {summary && (
          <Text
            span
            size="xs"
            style={{
              color: "#6b7585",
              overflow: "hidden",
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
              flex: 1,
            }}
          >
            {summary}
          </Text>
        )}
        {result?.is_error && (
          <Text span size="xs" fw={600} style={{ color: "#e85d5d", flexShrink: 0 }}>
            error
          </Text>
        )}
      </UnstyledButton>
      <Collapse in={opened}>
        <Box
          style={{
            borderLeft: "2px solid #1a1f2e",
            paddingLeft: 10,
            marginTop: 2,
            marginBottom: 4,
            marginLeft: 6,
          }}
        >
          {renderToolInput(block)}
          {result && (
            <Box mt={4}>
              <Text
                size="xs"
                fw={600}
                mb={2}
                style={{ color: result.is_error ? "#e85d5d" : "#5da87e" }}
              >
                Result{result.is_error ? " (error)" : ""}:
              </Text>
              <Code
                block
                style={{
                  ...codeBlockStyle,
                  maxHeight: 400,
                }}
              >
                {getResultText(result.content)}
              </Code>
            </Box>
          )}
        </Box>
      </Collapse>
    </Box>
  );
}
