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
  finishTakeover,
  releaseTakeover,
  sendTakeoverMessage,
  takeoverHeartbeat,
  Instance,
} from "../api/client";
import { parseSession } from "../lib/parseSession";
import { groupTurns } from "../lib/groupTurns";
import type { ToolResultContent } from "../lib/sessionTypes";
import { TurnItem } from "../components/TurnItem";

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
  // Whether the workflow would act on a hand-back right now; the server says.
  const [canFinish, setCanFinish] = useState(false);
  const [finishing, setFinishing] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  // Every turn starts collapsed; the list is an index you open into.
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
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
      takeoverHeartbeat(selected)
        .then((state) => {
          // The run moves while you hold it (a turn you sent may change its
          // state), so what hand-back means is re-read with every beat.
          if (!cancelled) setCanFinish(state.canFinish);
        })
        .catch(() => {
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
    setExpanded(new Set());
    setNotice(null);
  }, [selected]);

  async function handleTakeOver() {
    if (!selected) return;
    setTakeoverError(null);
    setNotice(null);
    try {
      const state = await takeoverHeartbeat(selected);
      setCanFinish(state.canFinish);
      setTakenOver(true);
    } catch {
      setTakeoverError(
        "Could not take over: no live workflow instance for this container.",
      );
    }
  }

  async function handleHandBack() {
    if (!selected || finishing) return;
    setFinishing(true);
    setTakeoverError(null);
    try {
      await finishTakeover(selected);
      // The cleanup effect's release is then a harmless no-op on the server.
      setTakenOver(false);
      setNotice(
        "Handed back. The workflow is continuing with what you discussed — " +
          "its next turn shows up in the transcript below.",
      );
    } catch (e) {
      setTakeoverError(e instanceof Error ? e.message : "Failed to hand back.");
    } finally {
      setFinishing(false);
      queryClient.invalidateQueries({ queryKey: ["session", selected] });
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

  const { turns, toolResults } = useMemo(
    () =>
      raw
        ? groupTurns(parseSession(raw))
        : { turns: [], toolResults: new Map<string, ToolResultContent>() },
    [raw],
  );

  const latestTurnId = turns.length > 0 ? turns[turns.length - 1].id : null;

  function toggleTurn(id: string) {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

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
            <Group gap="xs">
              {canFinish && (
                <Button onClick={handleHandBack} loading={finishing}>
                  Hand back to workflow
                </Button>
              )}
              <Button
                color="orange"
                variant="light"
                onClick={() => setTakenOver(false)}
                disabled={finishing}
              >
                Release control
              </Button>
            </Group>
          ) : (
            <Button variant="light" onClick={handleTakeOver}>
              Take over
            </Button>
          ))}
      </Group>

      {takenOver && (
        <Alert color="orange" variant="light">
          You are controlling this agent. Workflow events that arrive while you hold
          control are held and handled after you let go.{" "}
          {canFinish ? (
            <>
              <b>Hand back to workflow</b> when you are done and want the workflow to act
              on what you discussed (a planning session posts the final plan on the story);{" "}
              <b>Release control</b> just lets the agent carry on.
            </>
          ) : (
            <>If you disconnect or release, the agent carries on where it was.</>
          )}
        </Alert>
      )}

      {notice && (
        <Alert color="green" variant="light" withCloseButton onClose={() => setNotice(null)}>
          {notice}
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
          {turns.length === 0 ? (
            <Text c="dimmed">
              No session transcript available yet. This requires the instance's container to
              be running and a Claude session to have started.
            </Text>
          ) : (
            <ScrollArea.Autosize mah={takenOver ? 480 : 600} bg="dark.8">
              <Box>
                {turns
                  .slice()
                  .reverse()
                  .map((turn) => (
                    <TurnItem
                      key={turn.id}
                      turn={turn}
                      toolResults={toolResults}
                      opened={expanded.has(turn.id)}
                      isLatest={turn.id === latestTurnId}
                      onToggle={() => toggleTurn(turn.id)}
                    />
                  ))}
              </Box>
            </ScrollArea.Autosize>
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
