import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { Select, ScrollArea, Text, Loader, Center, Stack, Box } from "@mantine/core";
import { fetchInstanceSession, Instance } from "../api/client";
import { parseSession } from "../lib/parseSession";
import type { ToolResultContent } from "../lib/sessionTypes";
import { MessageBubble } from "../components/MessageBubble";

export function SessionPanel({
  instances,
  selected,
  onSelectedChange,
}: {
  instances: Instance[] | undefined;
  selected: string | null;
  onSelectedChange: (value: string) => void;
}) {
  const {
    data: raw,
    isLoading,
    isError,
  } = useQuery({
    queryKey: ["session", selected],
    queryFn: () => fetchInstanceSession(selected as string),
    enabled: !!selected,
    refetchInterval: 5000,
  });

  const { messages, toolResults } = useMemo(() => {
    if (!raw) return { messages: [], toolResults: new Map<string, ToolResultContent>() };
    const parsed = parseSession(raw);
    const results = new Map<string, ToolResultContent>();
    for (const msg of parsed) {
      if (msg.type === "user" && Array.isArray(msg.message.content)) {
        for (const block of msg.message.content) {
          if (block.type === "tool_result") {
            results.set(block.tool_use_id, block);
          }
        }
      }
    }
    const displayable = parsed.filter((m) => {
      if (m.type === "system") return false;
      if (m.type === "user") {
        const content = m.message.content;
        if (Array.isArray(content)) {
          const hasText = content.some((b) => b.type === "text" && b.text?.trim());
          if (!hasText) return false;
        }
        if (typeof content === "string" && !content.trim()) return false;
      }
      return true;
    });
    return { messages: displayable, toolResults: results };
  }, [raw]);

  const options = (instances ?? []).map((inst) => ({
    value: inst.containerName,
    label: inst.containerName,
  }));

  return (
    <Stack gap="md">
      <Select
        label="Instance"
        placeholder="Select an instance"
        data={options}
        value={selected}
        onChange={(value) => value && onSelectedChange(value)}
        allowDeselect={false}
        w={320}
      />

      {!selected ? (
        <Text c="dimmed">Select an instance to view its Claude Code session.</Text>
      ) : isLoading ? (
        <Center>
          <Loader />
        </Center>
      ) : isError ? (
        <Text c="red">Failed to load session.</Text>
      ) : messages.length === 0 ? (
        <Text c="dimmed">
          No session transcript available yet. This requires the instance's container to be
          running and a Claude session to have started.
        </Text>
      ) : (
        <ScrollArea h={600} bg="dark.8" p="sm">
          <Box>
            {messages.map((msg) => (
              <MessageBubble key={msg.uuid} message={msg} toolResults={toolResults} />
            ))}
          </Box>
        </ScrollArea>
      )}
    </Stack>
  );
}
