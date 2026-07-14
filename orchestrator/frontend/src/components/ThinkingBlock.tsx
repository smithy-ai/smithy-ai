import { UnstyledButton, Collapse, Text, Box } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";

interface ThinkingBlockProps {
  content: string;
}

export function ThinkingBlock({ content }: ThinkingBlockProps) {
  const [opened, { toggle }] = useDisclosure(false);

  return (
    <Box>
      <UnstyledButton
        onClick={toggle}
        style={{
          display: "flex",
          alignItems: "baseline",
          gap: 6,
          padding: "1px 0",
        }}
      >
        <Text span size="xs" style={{ color: "#4a5568" }}>
          {opened ? "v" : ">"}
        </Text>
        <Text span size="xs" style={{ color: "#6b7585" }}>
          Thinking...
        </Text>
      </UnstyledButton>
      <Collapse in={opened}>
        <Box
          style={{
            borderLeft: "2px solid #1a1f2e",
            paddingLeft: 10,
            marginLeft: 6,
            marginTop: 2,
            marginBottom: 4,
            whiteSpace: "pre-wrap",
            fontSize: "0.78rem",
            color: "#6b7585",
            lineHeight: 1.4,
          }}
        >
          {content}
        </Box>
      </Collapse>
    </Box>
  );
}
