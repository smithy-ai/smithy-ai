# Deploying Smithy-AI on Kubernetes

The Smithy-AI orchestrator is a Spring Boot service (`ghcr.io/smithy-ai/orchestrator`)
that turns VCS issues and webhooks into autonomous coding tasks. On Kubernetes it
launches **each task as a Pod** (a fresh Java runtime), so the orchestrator needs
RBAC to manage Pods in its namespace. It keeps **no database** — task state lives
in the task Pods themselves.

This directory offers two install paths:

- **Helm chart** — `deploy/helm/smithy` (recommended; configurable, optional
  bundled Forgejo + knowledgebase).
- **Kustomize base** — `deploy/k8s` (plain manifests equivalent to a default
  Helm render; good for GitOps or when you don't use Helm).

Both are kept consistent: same names, namespace (`smithy`), port (`8080`), env
vars, and Secret keys.

---

## Quick start (Helm)

```bash
helm install smithy deploy/helm/smithy \
  -n smithy --create-namespace \
  -f my-values.yaml
```

Minimal `my-values.yaml` for an existing Forgejo:

```yaml
config:
  vcs:
    provider: forgejo
    forgejo:
      url: https://forgejo.internal:3000
      externalUrl: https://git.example.com

secrets:
  claudeCodeOauthToken: "sk-..."     # from `claude setup-token`
  forgejo:
    webhookSecret: "..."
    smithyToken: "..."
    architectToken: "..."            # optional
```

## Quick start (Kustomize)

```bash
# 1. Edit deploy/k8s/secret.yaml — replace every CHANGE_ME (or delete it and
#    provide your own Secret named "smithy-secrets").
# 2. Adjust deploy/k8s/configmap.yaml for your VCS.
kubectl apply -k deploy/k8s
```

The kustomize base creates the `smithy` namespace, ServiceAccount, Role +
RoleBinding, ConfigMap, Secret, Deployment, and Service.

---

## Required secrets

Set at least one Claude credential and the tokens matching your VCS provider.

| Env var (Secret key)        | Purpose                                   | Required |
|-----------------------------|-------------------------------------------|----------|
| `CLAUDE_CODE_OAUTH_TOKEN`   | Claude Code OAuth token (`claude setup-token`) | one of these two |
| `ANTHROPIC_API_KEY`         | Anthropic API key                         | one of these two |
| `WEBHOOK_SECRET`            | Forgejo webhook HMAC secret               | if provider=forgejo |
| `SMITHY_FORGEJO_TOKEN`      | Smithy bot Forgejo token                  | if provider=forgejo |
| `ARCHITECT_FORGEJO_TOKEN`   | Architect bot Forgejo token               | optional |
| `GITLAB_WEBHOOK_SECRET` / `SMITHY_GITLAB_TOKEN` / `ARCHITECT_GITLAB_TOKEN` | GitLab | if provider=gitlab |
| `GITHUB_WEBHOOK_SECRET` / `SMITHY_GITHUB_TOKEN` / `ARCHITECT_GITHUB_TOKEN` | GitHub | if provider=github |

### Bring your own Secret (recommended for production)

Rather than templating credentials into a chart-managed Secret, create the
Secret out of band and reference it:

```bash
kubectl create secret generic smithy-secrets -n smithy \
  --from-literal=CLAUDE_CODE_OAUTH_TOKEN=sk-... \
  --from-literal=WEBHOOK_SECRET=... \
  --from-literal=SMITHY_FORGEJO_TOKEN=...
```

```yaml
# my-values.yaml
secrets:
  existingSecret: smithy-secrets
```

When `secrets.existingSecret` is set the chart does **not** render its own
Secret; the Deployment reads env from the named Secret via `envFrom`. Make sure
it contains the keys listed above.

---

## Selecting the runtime

The orchestrator supports `docker` and `kubernetes` runtimes. On a cluster it
**must** run in kubernetes mode so tasks are launched as Pods:

```yaml
runtime:
  type: kubernetes            # sets SMITHY_RUNTIME=kubernetes
  kubernetes:
    namespace: ""             # empty = release namespace (recommended)
    taskImage: ghcr.io/smithy-ai/claude-task-default:dev
    taskServiceAccount: ""    # optional SA for task Pods
    imagePullSecret: ""       # optional pull secret for the task image
```

`SMITHY_RUNTIME=kubernetes` and `KUBERNETES_NAMESPACE` (defaulting to the release
namespace) are injected into the Deployment automatically. No Docker socket is
mounted.

---

## RBAC

The orchestrator needs to create, watch, delete, read logs from, and exec into
task Pods. The chart (`rbac.create: true`) and the kustomize base create a
**namespaced** `Role` + `RoleBinding` — there is **no ClusterRole**. The grant is
limited to the orchestrator's own namespace:

```yaml
rules:
  - apiGroups: [""]
    resources: ["pods", "pods/log"]
    verbs: ["get", "list", "watch", "create", "delete"]
  - apiGroups: [""]
    resources: ["pods/exec"]
    verbs: ["create"]
```

If you disable the bundled RBAC (`rbac.create: false`), you must grant the
orchestrator ServiceAccount equivalent permissions in the namespace where task
Pods run (`runtime.kubernetes.namespace`, default = release namespace), or task
launching will fail.

---

## Bring-your-own VCS vs bundled Forgejo

**Bring your own** (production default): point `config.vcs.*` at your existing
Forgejo / GitLab / GitHub and provide the matching tokens. No extra workloads are
deployed.

**Bundled Forgejo** (dev only): for a self-contained stack, enable the bundled,
minimal Forgejo Deployment + Service (ports 3000, 22) + PVC:

```yaml
forgejo:
  enabled: true
```

It is reachable in-cluster at `http://forgejo:3000` — which is already the
default `config.vcs.forgejo.url`. Not intended for production.

---

## Enabling the knowledgebase

The knowledgebase is an optional MCP server the orchestrator can query for
codebase context. Two independent switches:

- `config.knowledgebase.enabled` — tells the **orchestrator** to use a
  knowledgebase at `config.knowledgebase.url`.
- `knowledgebase.enabled` — **deploys** the bundled knowledgebase (image
  `ghcr.io/smithy-ai/knowledgebase:dev`, port 8000, PVC for `/app/vectorstore`,
  a `/config/knowledgebase.yml`).

To run the bundled one and point the orchestrator at it:

```yaml
config:
  knowledgebase:
    enabled: true
    url: http://knowledgebase:8000/mcp

knowledgebase:
  enabled: true
  openaiApiKey: "sk-..."     # required for embeddings
  # vcsUrl / vcsToken / webhookSecret default to the Forgejo url + smithy token
```

The bundled knowledgebase ships a placeholder `knowledgebase.yml` with an empty
repository list; edit the generated ConfigMap (or supply your own) to index
repos.

---

## Exposing the orchestrator

The orchestrator listens on port 8080. Endpoints: `GET /api/health` (health),
`/webhooks/{forgejo,gitlab,github}` (VCS webhooks), `/api/dashboard/*` and `/`
(dashboard + static frontend).

### Ingress

```yaml
ingress:
  enabled: true
  className: nginx
  host: smithy.example.com
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt
  tls:
    enabled: true
    secretName: smithy-tls
```

The Ingress backend targets the `smithy` Service on port 8080. Your VCS must be
able to reach the webhook path over this host.

### Port-forward (no Ingress)

```bash
kubectl port-forward svc/smithy -n smithy 8080:8080
# then: http://localhost:8080/  (dashboard)
#       curl http://localhost:8080/api/health
```

---

## Validation

```bash
helm lint deploy/helm/smithy
helm template smithy deploy/helm/smithy -n smithy
kubectl kustomize deploy/k8s
```
