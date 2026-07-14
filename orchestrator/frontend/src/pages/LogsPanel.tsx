import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Select, ScrollArea, Text, Loader, Center, Stack } from "@mantine/core";
import { fetchOrchestratorLogs, fetchInstanceLogs, Instance } from "../api/client";

const ORCHESTRATOR_OPTION = "__orchestrator__";

export function LogsPanel({ instances }: { instances: Instance[] | undefined }) {
  const [selected, setSelected] = useState<string>(ORCHESTRATOR_OPTION);

  const {
    data: logs,
    isLoading,
    isError,
  } = useQuery({
    queryKey: ["logs", selected],
    queryFn: () =>
      selected === ORCHESTRATOR_OPTION
        ? fetchOrchestratorLogs()
        : fetchInstanceLogs(selected),
    refetchInterval: 5000,
  });

  const options = [
    { value: ORCHESTRATOR_OPTION, label: "Orchestrator" },
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
        onChange={(value) => value && setSelected(value)}
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
