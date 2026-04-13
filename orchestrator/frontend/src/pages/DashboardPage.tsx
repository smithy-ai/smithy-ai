import { useQuery } from "@tanstack/react-query";
import {
  AppShell,
  Badge,
  Container,
  Group,
  Table,
  Text,
  Title,
  Loader,
  Center,
  Button,
} from "@mantine/core";
import { fetchInstances } from "../api/client";

export function DashboardPage() {
  const { data: instances, isLoading } = useQuery({
    queryKey: ["instances"],
    queryFn: fetchInstances,
    refetchInterval: 5000,
  });

  function formatTime(iso: string) {
    if (!iso) return "-";
    const d = new Date(iso);
    return d.toLocaleString();
  }

  return (
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Title order={4}>Smithy-AI Dashboard</Title>
          <Button
            variant="subtle"
            size="sm"
            onClick={() => {
              fetch("/api/logout", { method: "POST" }).then(
                () => (window.location.href = "/login"),
              );
            }}
          >
            Sign out
          </Button>
        </Group>
      </AppShell.Header>

      <AppShell.Main>
        <Container size="lg">
          <Title order={3} mb="md">
            Workflow Instances
          </Title>

          {isLoading ? (
            <Center>
              <Loader />
            </Center>
          ) : !instances || instances.length === 0 ? (
            <Text c="dimmed">No active workflow instances.</Text>
          ) : (
            <Table striped highlightOnHover>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Container</Table.Th>
                  <Table.Th>Type</Table.Th>
                  <Table.Th>Stage</Table.Th>
                  <Table.Th>Last Active</Table.Th>
                  <Table.Th>CI Paused</Table.Th>
                  <Table.Th>Status</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {instances.map((inst) => (
                  <Table.Tr key={inst.containerName}>
                    <Table.Td>
                      <Text size="sm" ff="monospace">
                        {inst.containerName}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <Badge
                        variant="light"
                        color={
                          inst.workflowType === "architect" ? "violet" : "blue"
                        }
                      >
                        {inst.workflowType}
                      </Badge>
                    </Table.Td>
                    <Table.Td>{inst.stage}</Table.Td>
                    <Table.Td>{formatTime(inst.lastProcessedAt)}</Table.Td>
                    <Table.Td>
                      {inst.ciPaused && (
                        <Badge color="yellow" variant="light">
                          paused ({inst.ciRetryCount})
                        </Badge>
                      )}
                    </Table.Td>
                    <Table.Td>
                      <Badge color={inst.running ? "green" : "red"}>
                        {inst.running ? "Running" : "Stopped"}
                      </Badge>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </Container>
      </AppShell.Main>
    </AppShell>
  );
}
