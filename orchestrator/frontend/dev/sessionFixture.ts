import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Plugin } from "vite";

/**
 * Serves the dashboard from a Claude Code transcript on disk instead of a live
 * orchestrator, so the Session tab can be worked on without a running
 * container. Drop a .jsonl in dev/fixtures/ (see the README there) or point
 * SMITHY_SESSION_FIXTURE at one; with neither, the plugin does not load and
 * every request proxies as usual.
 */
const DEFAULT_FIXTURE = "dev/fixtures/session.jsonl";
const CONTAINER = "fixture.session.local";

function resolveFixture(): string | null {
  const configured = process.env.SMITHY_SESSION_FIXTURE;
  if (configured) {
    return configured.startsWith("~")
      ? path.join(os.homedir(), configured.slice(1))
      : path.resolve(configured);
  }
  const fallback = path.resolve(DEFAULT_FIXTURE);
  return fs.existsSync(fallback) ? fallback : null;
}

type Reply = (res: ServerResponse) => void;

function json(body: unknown): Reply {
  return (res) => {
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify(body));
  };
}

function plain(body: string): Reply {
  return (res) => {
    res.setHeader("Content-Type", "text/plain; charset=utf-8");
    res.end(body);
  };
}

const fakeInstance = () => ({
  containerName: CONTAINER,
  workflowType: "smithy-development",
  stage: "implementing",
  lastProcessedAt: new Date().toISOString(),
  ciPaused: false,
  ciRetryCount: 0,
  running: true,
  humanControlled: false,
});

/** The endpoints the dashboard needs to render; anything else falls through. */
function reply(url: string, method: string, file: string): Reply | null {
  if (url === "/api/dashboard/instances") return json([fakeInstance()]);
  if (url === "/api/dashboard/metrics") return json({ counts: {} });
  if (url === "/api/dashboard/runs") return json([]);
  if (url === "/api/logout") return (res) => res.end();

  if (url.startsWith("/api/dashboard/session/")) {
    return plain(fs.readFileSync(file, "utf-8"));
  }
  if (url.startsWith("/api/dashboard/logs/")) {
    return plain("(no logs in fixture mode)\n");
  }
  if (url.startsWith("/api/dashboard/takeover/")) {
    if (method === "DELETE") return (res) => res.end();
    if (url.endsWith("/message")) return plain("ok");
    return json({ active: true, expiresAt: null });
  }
  return null;
}

export function sessionFixture(): Plugin | false {
  const file = resolveFixture();
  if (!file) return false;

  return {
    name: "smithy-session-fixture",
    apply: "serve",
    configureServer(server) {
      if (!fs.existsSync(file)) {
        server.config.logger.error(`SMITHY_SESSION_FIXTURE: no such file: ${file}`);
        return;
      }
      server.config.logger.info(`session fixture: ${file} as ${CONTAINER}`);

      server.middlewares.use(
        (req: IncomingMessage, res: ServerResponse, next: () => void) => {
          const url = (req.url ?? "").split("?")[0];
          const handler = reply(url, req.method ?? "GET", file);
          if (handler) handler(res);
          else next();
        },
      );
    },
  };
}
