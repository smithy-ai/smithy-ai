export interface Instance {
  containerName: string;
  workflowType: string;
  stage: string;
  lastProcessedAt: string;
  ciPaused: boolean;
  ciRetryCount: number;
  running: boolean;
}

export async function fetchInstances(): Promise<Instance[]> {
  const res = await fetch("/api/dashboard/instances");
  if (res.status === 401 || res.status === 403) {
    window.location.href = "/login";
    return [];
  }
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export async function checkAuth(): Promise<boolean> {
  const res = await fetch("/api/auth/check");
  return res.ok;
}

async function fetchLogText(url: string): Promise<string> {
  const res = await fetch(url);
  if (res.status === 401 || res.status === 403) {
    window.location.href = "/login";
    return "";
  }
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.text();
}

export function fetchOrchestratorLogs(tail = 200): Promise<string> {
  return fetchLogText(`/api/dashboard/logs/orchestrator?tail=${tail}`);
}

export function fetchInstanceLogs(containerName: string, tail = 200): Promise<string> {
  return fetchLogText(
    `/api/dashboard/logs/instance/${encodeURIComponent(containerName)}?tail=${tail}`,
  );
}
