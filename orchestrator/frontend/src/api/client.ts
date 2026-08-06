export interface Instance {
  containerName: string;
  workflowType: string;
  stage: string;
  lastProcessedAt: string;
  ciPaused: boolean;
  ciRetryCount: number;
  running: boolean;
  humanControlled: boolean;
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

export interface MetricsSummary {
  counts: Record<string, number>;
  avgPlanReviewRounds: number;
}

export async function fetchMetrics(): Promise<MetricsSummary> {
  const res = await fetch("/api/dashboard/metrics");
  if (res.status === 401 || res.status === 403) {
    window.location.href = "/login";
    return { counts: {}, avgPlanReviewRounds: 0 };
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

export function fetchInstanceSession(containerName: string): Promise<string> {
  return fetchLogText(`/api/dashboard/session/${encodeURIComponent(containerName)}`);
}

export interface TakeoverState {
  active: boolean;
  expiresAt: string | null;
}

export async function takeoverHeartbeat(containerName: string): Promise<TakeoverState> {
  const res = await fetch(`/api/dashboard/takeover/${encodeURIComponent(containerName)}`, {
    method: "POST",
  });
  if (res.status === 401 || res.status === 403) {
    window.location.href = "/login";
    return { active: false, expiresAt: null };
  }
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export function releaseTakeover(containerName: string, keepalive = false): Promise<Response> {
  return fetch(`/api/dashboard/takeover/${encodeURIComponent(containerName)}`, {
    method: "DELETE",
    keepalive,
  });
}

export async function sendTakeoverMessage(
  containerName: string,
  text: string,
): Promise<string> {
  const res = await fetch(
    `/api/dashboard/takeover/${encodeURIComponent(containerName)}/message`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text }),
    },
  );
  if (res.status === 401 || res.status === 403) {
    window.location.href = "/login";
    return "";
  }
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
  return res.text();
}
