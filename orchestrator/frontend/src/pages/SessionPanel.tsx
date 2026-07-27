import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Select,
  ScrollArea,
  Text,
  Loader,
  Center,
  Stack,
  Box,
  Button,
  Group,
  Textarea,
  Alert,
} from "@mantine/core";
import {
  fetchInstanceSession,
  releaseTakeover,
  sendTakeoverMessage,
  takeoverHeartbeat,
  Instance,
} from "../api/client";
import { parseSession } from "../lib/parseSession";
import type { ToolResultContent } from "../lib/sessionTypes";
import { MessageBubble } from "../components/MessageBubble";

const HEARTBEAT_INTERVAL_MS = 10_000;

export function SessionPanel({
  instances,
  selected,
  onSelectedChange,
}: {
  instances: Instance[] | undefined;
  selected: string | null;
  onSelectedChange: (value: string) => void;
}) {
  const queryClient = useQueryClient();
  const [takenOver, setTakenOver] = useState(false);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [takeoverError, setTakeoverError] = useState<string | null>(null);
  const controlledRef = useRef<string | null>(null);

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

  // Heartbeat loop while control is held; releases on unmount,
  // instance switch, or tab close so the agent resumes.
  useEffect(() => {
    if (!takenOver || !selected) return;

    controlledRef.current = selected;
    let cancelled = false;

    const beat = () =>
      takeoverHeartbeat(selected).catch(() => {
        if (!cancelled) {
          setTakenOver(false);
          setTakeoverError("Lost control of the session (instance gone?).");
        }
      });

    beat();
    const interval = setInterval(beat, HEARTBEAT_INTERVAL_MS);

    const onBeforeUnload = () => releaseTakeover(selected, true);
    window.addEventListener("beforeunload", onBeforeUnload);

    return () => {
      cancelled = true;
      clearInterval(interval);
      window.removeEventListener("beforeunload", onBeforeUnload);
      releaseTakeover(selected, true).catch(() => {});
      controlledRef.current = null;
    };
  }, [takenOver, selected]);

  // Switching instance drops control of the previous one (cleanup above runs).
  useEffect(() => {
    if (controlledRef.current && controlledRef.current !== selected) {
      setTakenOver(false);
    }
  }, [selected]);

  async function handleTakeOver() {
    if (!selected) return;
    setTakeoverError(null);
    try {
      await takeoverHeartbeat(selected);
      setTakenOver(true);
    } catch {
      setTakeoverError(
        "Could not take over: no live workflow instance for this container.",
      );
    }
  }

  async function handleSend() {
    if (!selected || !draft.trim() || sending) return;
    const text = draft.trim();
    setSending(true);
    setTakeoverError(null);
    try {
      await sendTakeoverMessage(selected, text);
      setDraft("");
    } catch (e) {
      setTakeoverError(e instanceof Error ? e.message : "Failed to send message.");
    } finally {
      setSending(false);
      queryClient.invalidateQueries({ queryKey: ["session", selected] });
    }
  }

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
      <Group align="flex-end" justify="space-between">
        <Select
          label="Instance"
          placeholder="Select an instance"
          data={options}
          value={selected}
          onChange={(value) => value && onSelectedChange(value)}
          allowDeselect={false}
          w={320}
        />
        {selected &&
          (takenOver ? (
            <Button color="orange" variant="light" onClick={() => setTakenOver(false)}>
              Release control
            </Button>
          ) : (
            <Button variant="light" onClick={handleTakeOver}>
              Take over
            </Button>
          ))}
      </Group>

      {takenOver && (
        <Alert color="orange" variant="light">
          You are controlling this agent. Workflow events are paused while you hold
          control; if you disconnect or release, the agent takes over again.
        </Alert>
      )}

      {takeoverError && (
        <Alert color="red" variant="light" withCloseButton onClose={() => setTakeoverError(null)}>
          {takeoverError}
        </Alert>
      )}

      {!selected ? (
        <Text c="dimmed">Select an instance to view its Claude Code session.</Text>
      ) : isLoading ? (
        <Center>
          <Loader />
        </Center>
      ) : isError ? (
        <Text c="red">Failed to load session.</Text>
      ) : (
        <>
          {messages.length === 0 ? (
            <Text c="dimmed">
              No session transcript available yet. This requires the instance's container to
              be running and a Claude session to have started.
            </Text>
          ) : (
            <ScrollArea h={takenOver ? 480 : 600} bg="dark.8" p="sm">
              <Box>
                {messages.map((msg) => (
                  <MessageBubble key={msg.uuid} message={msg} toolResults={toolResults} />
                ))}
              </Box>
            </ScrollArea>
          )}

          {takenOver && (
            <Group align="flex-end" gap="sm">
              <Textarea
                placeholder="Message the session as the agent… (Ctrl+Enter to send)"
                value={draft}
                onChange={(e) => setDraft(e.currentTarget.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
                    e.preventDefault();
                    handleSend();
                  }
                }}
                autosize
                minRows={2}
                maxRows={8}
                disabled={sending}
                style={{ flex: 1 }}
              />
              <Button onClick={handleSend} loading={sending} disabled={!draft.trim()}>
                Send
              </Button>
            </Group>
          )}
          {sending && (
            <Text size="sm" c="dimmed">
              Claude is working on your message — the transcript above updates live.
            </Text>
          )}
        </>
      )}
    </Stack>
  );
}
