import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Badge,
  Button,
  Center,
  Code,
  Collapse,
  Group,
  Loader,
  Table,
  Text,
} from "@mantine/core";
import {
  approveRunWait,
  cancelRun,
  fetchRunEvents,
  fetchRuns,
  fetchRunWaits,
  type Run,
} from "../api/client";

const STATUS_COLOR: Record<string, string> = {
  pending: "gray",
  running: "green",
  waiting: "blue",
  completed: "teal",
  failed: "red",
  cancelled: "gray",
};

function formatTime(iso: string | null) {
  if (!iso) return "—";
  return new Date(iso).toLocaleString();
}

/**
 * Run history. Unlike the instances table, rows here stay after the container
 * is gone, so a finished or failed run is still inspectable — and a run holding
 * at a gate can be released from here.
 */
export function RunsTable() {
  const [expanded, setExpanded] = useState<string | null>(null);

  const { data: runs = [], isLoading } = useQuery({
    queryKey: ["runs"],
    queryFn: () => fetchRuns(),
    refetchInterval: 5000,
  });

  if (isLoading) {
    return (
      <Center>
        <Loader />
      </Center>
    );
  }

  if (runs.length === 0) {
    return (
      <Text c="dimmed" p="md">
        No runs recorded yet.
      </Text>
    );
  }

  return (
    <Table highlightOnHover>
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Workflow</Table.Th>
          <Table.Th>State</Table.Th>
          <Table.Th>Status</Table.Th>
          <Table.Th>Container</Table.Th>
          <Table.Th>Started</Table.Th>
          <Table.Th>Updated</Table.Th>
          <Table.Th />
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {runs.map((run) => (
          <RunRow
            key={run.id}
            run={run}
            expanded={expanded === run.id}
            onToggle={() => setExpanded(expanded === run.id ? null : run.id)}
          />
        ))}
      </Table.Tbody>
    </Table>
  );
}

function RunRow({
  run,
  expanded,
  onToggle,
}: {
  run: Run;
  expanded: boolean;
  onToggle: () => void;
}) {
  const queryClient = useQueryClient();
  const terminal = ["completed", "failed", "cancelled"].includes(run.status);

  // Only fetch a run's timeline once the row is opened; what it is waiting on
  // is polled regardless, because that is what the row's action depends on.
  const { data: events = [] } = useQuery({
    queryKey: ["run-events", run.id],
    queryFn: () => fetchRunEvents(run.id),
    enabled: expanded,
  });

  const { data: waits = [] } = useQuery({
    queryKey: ["run-waits", run.id],
    queryFn: () => fetchRunWaits(run.id),
    enabled: !terminal,
    refetchInterval: 10000,
  });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ["runs"] });
    void queryClient.invalidateQueries({ queryKey: ["run-waits", run.id] });
    void queryClient.invalidateQueries({ queryKey: ["run-events", run.id] });
  };

  const approve = useMutation({
    mutationFn: (key: string) => approveRunWait(run.id, key),
    onSuccess: refresh,
  });

  const cancel = useMutation({
    mutationFn: () => cancelRun(run.id),
    onSuccess: refresh,
  });

  return (
    <>
      <Table.Tr
        onClick={onToggle}
        style={{ cursor: "pointer" }}
        title="Show timeline"
      >
        <Table.Td>
          <Group gap="xs">
            <Text size="sm">{run.workflowName}</Text>
            {run.parentRunId && (
              <Badge size="xs" variant="light" color="grape">
                child
              </Badge>
            )}
          </Group>
        </Table.Td>
        <Table.Td>{run.state}</Table.Td>
        <Table.Td>
          <Group gap="xs">
            <Badge color={STATUS_COLOR[run.status] ?? "gray"} variant="light">
              {run.status}
            </Badge>
            {run.live && <Badge color="green">live</Badge>}
            {waits.map((wait) => (
              <Badge key={wait.id} color="yellow" variant="light">
                waiting: {wait.waitKey}
              </Badge>
            ))}
          </Group>
        </Table.Td>
        <Table.Td>
          <Text size="xs" ff="monospace" c="dimmed">
            {run.containers.length > 0 ? run.containers.join(", ") : "—"}
          </Text>
        </Table.Td>
        <Table.Td>{formatTime(run.createdAt)}</Table.Td>
        <Table.Td>{formatTime(run.updatedAt)}</Table.Td>
        <Table.Td onClick={(event) => event.stopPropagation()}>
          <Group gap="xs" justify="flex-end" wrap="nowrap">
            {waits.map((wait) => (
              <Button
                key={wait.id}
                size="compact-xs"
                variant="light"
                color="yellow"
                loading={approve.isPending}
                onClick={() => approve.mutate(wait.waitKey)}
              >
                Approve {wait.waitKey}
              </Button>
            ))}
            {!terminal && (
              <Button
                size="compact-xs"
                variant="subtle"
                color="red"
                loading={cancel.isPending}
                onClick={() => cancel.mutate()}
              >
                Cancel
              </Button>
            )}
          </Group>
        </Table.Td>
      </Table.Tr>
      <Table.Tr>
        <Table.Td colSpan={7} p={0} style={{ border: "none" }}>
          <Collapse in={expanded}>
            {events.length === 0 ? (
              <Text size="sm" c="dimmed" p="sm">
                No events recorded for this run.
              </Text>
            ) : (
              <Table>
                <Table.Tbody>
                  {events.map((event) => (
                    <Table.Tr key={event.id}>
                      <Table.Td width={180}>
                        <Text size="xs" c="dimmed">
                          {formatTime(event.ts)}
                        </Text>
                      </Table.Td>
                      <Table.Td width={200}>
                        <Code>{event.type}</Code>
                      </Table.Td>
                      <Table.Td>
                        <Text size="xs" c="dimmed" ff="monospace">
                          {JSON.stringify(event.payload)}
                        </Text>
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            )}
          </Collapse>
        </Table.Td>
      </Table.Tr>
    </>
  );
}
