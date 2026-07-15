import { useQuery } from "@tanstack/react-query";
import { Select, ScrollArea, Text, Loader, Center, Stack } from "@mantine/core";
import { fetchOrchestratorLogs, fetchInstanceLogs, Instance } from "../api/client";

export const ORCHESTRATOR_LOG_SOURCE = "__orchestrator__";

export function LogsPanel({
  instances,
  selected,
  onSelectedChange,
}: {
  instances: Instance[] | undefined;
  selected: string;
  onSelectedChange: (value: string) => void;
}) {
  const {
    data: logs,
    isLoading,
    isError,
  } = useQuery({
    queryKey: ["logs", selected],
    queryFn: () =>
      selected === ORCHESTRATOR_LOG_SOURCE
        ? fetchOrchestratorLogs()
        : fetchInstanceLogs(selected),
    refetchInterval: 5000,
  });

  const options = [
    { value: ORCHESTRATOR_LOG_SOURCE, label: "Orchestrator" },
    ...(instances ?? []).map((inst) => ({
      value: inst.containerName,
      label: inst.containerName,
    })),
  ];

  return (
    <Stack gap="md">
      <Select
        label="Source"
        data={options}
        value={selected}
        onChange={(value) => value && onSelectedChange(value)}
        allowDeselect={false}
        w={320}
      />

      {isLoading ? (
        <Center>
          <Loader />
        </Center>
      ) : isError ? (
        <Text c="red">Failed to load logs.</Text>
      ) : (
        <ScrollArea h={500} bg="dark.8" p="sm">
          <Text
            component="pre"
            size="xs"
            ff="monospace"
            c="gray.3"
            style={{ whiteSpace: "pre-wrap", wordBreak: "break-word" }}
          >
            {logs || "No logs available."}
          </Text>
        </ScrollArea>
      )}
    </Stack>
  );
}
