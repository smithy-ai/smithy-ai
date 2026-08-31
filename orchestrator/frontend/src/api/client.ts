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

/**
 * A workflow run. Unlike an Instance, this exists for finished and failed runs
 * too — the runs list is history, not a view of the running containers.
 */
export interface Run {
  id: string;
  workflowName: string;
  status: string;
  state: string;
  parentRunId: string | null;
  containers: string[];
  live: boolean;
  createdAt: string;
  updatedAt: string;
  terminalAt: string | null;
}

export async function fetchRuns(limit = 100): Promise<Run[]> {
  const res = await fetch(`/api/dashboard/runs?limit=${limit}`);
  if (res.status === 401 || res.status === 403) {
    window.location.href = "/login";
    return [];
  }
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

/** Something a run is blocked on: an approval nobody has given, a sibling still working. */
export interface RunWait {
  id: number;
  runId: string;
  kind: string;
  waitKey: string;
  satisfiedAt: string | null;
  createdAt: string;
}

export async function fetchRunWaits(runId: string): Promise<RunWait[]> {
  const res = await fetch(`/api/dashboard/runs/${encodeURIComponent(runId)}/waits`);
  if (res.status === 401 || res.status === 403) {
    window.location.href = "/login";
    return [];
  }
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

/**
 * Release a gate from here. A coordinator's plan spans repositories, so the
 * approval it waits for cannot always be a label on one issue.
 */
export async function approveRunWait(runId: string, key: string): Promise<void> {
  const res = await fetch(
    `/api/dashboard/runs/${encodeURIComponent(runId)}/waits/${encodeURIComponent(key)}`,
    { method: "POST" },
  );
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
}

export async function cancelRun(runId: string): Promise<void> {
  const res = await fetch(`/api/dashboard/runs/${encodeURIComponent(runId)}`, {
    method: "DELETE",
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
}

export interface RunEvent {
  id: number;
  runId: string;
  seq: number;
  ts: string;
  type: string;
  payload: Record<string, unknown>;
}

export async function fetchRunEvents(runId: string): Promise<RunEvent[]> {
  const res = await fetch(`/api/dashboard/runs/${encodeURIComponent(runId)}/events`);
  if (res.status === 401 || res.status === 403) {
    window.location.href = "/login";
    return [];
  }
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export interface MetricsSummary {
  counts: Record<string, number>;
}

export async function fetchMetrics(): Promise<MetricsSummary> {
  const res = await fetch("/api/dashboard/metrics");
  if (res.status === 401 || res.status === 403) {
    window.location.href = "/login";
    return { counts: {} };
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

/**
 * A turn is answered by the agent, so this is slow by nature — but not endless.
 * Without a deadline a request that never comes back leaves the composer stuck
 * with the draft still in it and no error, which reads as a prompt that was
 * never sent. Kept above the server's takeover budget so the server's own
 * message wins the race and says something useful.
 */
const TAKEOVER_MESSAGE_TIMEOUT_MS = 6 * 60 * 1000;

export async function sendTakeoverMessage(
  containerName: string,
  text: string,
  screenshots: File[] = [],
): Promise<string> {
  // Words alone stay JSON, which is the plainer contract; images make it
  // multipart, because base64 in a JSON field inflates a paste for nothing.
  const body =
    screenshots.length > 0
      ? (() => {
          const form = new FormData();
          form.append("text", text);
          screenshots.forEach((file) => form.append("screenshots", file, file.name));
          return form;
        })()
      : JSON.stringify({ text });

  let res: Response;
  try {
    res = await fetch(
      `/api/dashboard/takeover/${encodeURIComponent(containerName)}/message`,
      {
        method: "POST",
        // FormData sets its own multipart boundary; naming a type here breaks it.
        headers: body instanceof FormData ? undefined : { "Content-Type": "application/json" },
        body,
        signal: AbortSignal.timeout(TAKEOVER_MESSAGE_TIMEOUT_MS),
      },
    );
  } catch (e) {
    if (e instanceof DOMException && e.name === "TimeoutError") {
      throw new Error(
        "The agent did not reply within 6 minutes. It may still be working — " +
          "check the transcript before sending again.",
      );
    }
    throw e;
  }
  if (res.status === 401 || res.status === 403) {
    window.location.href = "/login";
    return "";
  }
  // 409 is the session being mid-turn, 504 the turn running out of budget, 400
  // a rejected attachment: each carries a sentence worth showing as-is.
  if (res.status === 400 || res.status === 409 || res.status === 504) {
    throw new Error(await res.text());
  }
  // The server refuses an upload past its multipart ceiling before any handler
  // of ours sees it, so the explanation has to come from here.
  if (res.status === 413) {
    throw new Error("That image is too large to attach (the limit is 10MB).");
  }
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
  return res.text();
}
