# Running Smithy-AI on Kubernetes

This is the Kubernetes equivalent of [`examples/full/docker-compose.yml`](../../examples/full/docker-compose.yml).
It deploys the **orchestrator** (and optionally the **knowledgebase**) as a
[Kustomize](https://kubectl.docs.kubernetes.io/) app.

## How it works (and why there's a dind sidecar)

The orchestrator doesn't just run itself — for every task it **creates, execs into,
and tears down "task" containers** through the Docker CLI (see
`ContainerService` / `DockerCli` in the backend). In docker-compose this works by
bind-mounting the host's `/var/run/docker.sock`.

On Kubernetes we keep that exact behaviour without changing any application code by
running a **Docker-in-Docker (`dind`) sidecar** next to the orchestrator in the same
pod. The orchestrator's `docker` CLI talks to it over pod-local loopback
(`DOCKER_HOST=tcp://127.0.0.1:2375`), and task containers run *inside* dind — so this
works on any cluster regardless of whether nodes use containerd, CRI-O, etc.

```
┌──────────────── Pod: orchestrator ────────────────┐
│  ┌───────────────┐        ┌────────────────────┐  │
│  │ orchestrator  │ docker │ dind (privileged)  │  │
│  │ :8080  ───────┼───────▶│ :2375  /var/lib/…  │  │
│  └───────────────┘  CLI   └─────────┬──────────┘  │
│         ▲                           │ runs        │
└─────────┼───────────────────────────┼─────────────┘
     Ingress (was Caddy)         task containers (claude-task-*)
```

> **Requirement:** dind runs `privileged: true`. The `namespace.yaml` sets the
> `pod-security.kubernetes.io/enforce: privileged` label so Pod Security Admission
> allows it. If your cluster forbids privileged pods entirely, see
> [Alternative: no privileged dind](#alternative-no-privileged-dind).

## Layout

```
deploy/k8s/
├── kustomization.yaml            # top-level: images, generators, enable knowledgebase here
├── base/                         # orchestrator Deployment(+dind), Service, Ingress, PVC, Namespace
├── components/knowledgebase/     # optional add-on (Kustomize component)
└── secrets/                      # your env files (real ones are git-ignored)
```

## Quick start

```sh
cd deploy/k8s

# 1. Create your config + secret env files from the templates
cp secrets/orchestrator.config.env.example secrets/orchestrator.config.env
cp secrets/orchestrator.secret.env.example secrets/orchestrator.secret.env
#   ...then edit both. At minimum set VCS_PROVIDER, the provider URLs + tokens,
#   the webhook secret, and a Claude credential.

# 2. Set your hostname + TLS in base/orchestrator-ingress.yaml
#    (replace smithy.example.com and the cert-manager cluster-issuer annotation).

# 3. Pick your image tag in kustomization.yaml (images: newTag), default "dev".

# 4. Review, then apply
kubectl kustomize .            # render and eyeball the output
kubectl apply -k .             # create everything in the `smithy` namespace
```

Watch it come up:

```sh
kubectl -n smithy get pods -w
kubectl -n smithy logs deploy/orchestrator -c orchestrator -f   # app
kubectl -n smithy logs deploy/orchestrator -c dind -f           # docker daemon
```

The orchestrator container waits for dind, creates the `smithy-net` docker network,
then boots Spring. Readiness is gated on `GET /api/health`.

### Point your git provider's webhook at it

The Ingress replaces Caddy. Configure the webhook in GitLab/Forgejo/GitHub to:

```
https://<your SMITHY_HOST>/webhooks/gitlab     # or /webhooks/forgejo, /webhooks/github
```

Dashboard: `https://<your SMITHY_HOST>/`. If you left `ADMIN_PASSWORD_HASH` empty,
a random password is printed in the orchestrator logs at startup.

## Enabling the knowledgebase (optional)

Mirrors the `knowledgebase` compose profile.

```sh
cd deploy/k8s

# 1. Secret lives inside the component dir (Kustomize component sandboxing)
cp components/knowledgebase/secret.env.example components/knowledgebase/secret.env
#   ...set OPENAI_API_KEY, VCS_URL, VCS_TOKEN, WEBHOOK_SECRET.

# 2. List repos to index in components/knowledgebase/knowledgebase.yml

# 3. In secrets/orchestrator.config.env set:  KNOWLEDGEBASE_ENABLED=true

# 4. In kustomization.yaml, uncomment:
#      components:
#        - components/knowledgebase

# 5. Set the KB hostname in components/knowledgebase/ingress.yaml
#    (or delete that ingress from its kustomization if the KB needs no external webhooks)

kubectl apply -k .
```

The orchestrator reaches it in-cluster at `http://knowledgebase:8000/mcp` (already the
default `KNOWLEDGEBASE_URL`).

## How secrets & config map over from compose

| compose (`.env`)                     | here                                                     |
| ------------------------------------ | -------------------------------------------------------- |
| non-secret vars (URLs, bot users…)   | `secrets/orchestrator.config.env` → ConfigMap (`envFrom`) |
| tokens / webhook secrets / API keys  | `secrets/orchestrator.secret.env` → Secret (`envFrom`)    |
| `caddy` service + `SMITHY_HOST`      | `base/orchestrator-ingress.yaml`                          |
| `/var/run/docker.sock` mount         | `dind` sidecar + `DOCKER_HOST=tcp://127.0.0.1:2375`       |
| `kb-vectorstore` volume              | `knowledgebase-vectorstore` PVC                           |
| docker named cache volumes           | live inside dind, persisted on the `smithy-dind-storage` PVC |
| `IMAGE_TAG`                          | `images[].newTag` in `kustomization.yaml`                 |

Config/secret ConfigMaps and Secrets are generated with a content hash suffix, so
editing an env file and re-applying triggers a rolling restart automatically.

## Operational notes

- **Single replica, `Recreate` strategy.** Only one dind may own the PVC and the
  managed task containers at a time. The orchestrator itself is stateless and, on
  restart, recovers in-flight work by re-listing containers labelled
  `smithy.managed=true`.
- **Restarting the pod discards running task containers** (they live in dind). This
  differs from compose, where restarting the orchestrator left task containers alive.
- **Private registries:** if `TASK_IMAGE` (or the app images) are private, dind pulls
  them — configure a `docker login` / imagePullSecret path for dind, and add an
  `imagePullSecrets` to the pod for the app images.
- **Sizing:** dind's memory limit (default 4Gi) bounds how heavy concurrent task
  builds can get; raise it and the PVC size for large monorepos.

## Alternative: no privileged dind

If your cluster forbids privileged pods and its nodes *do* expose a Docker socket,
you can drop the dind sidecar and instead mount the node socket into the orchestrator:

```yaml
# in base/orchestrator-deployment.yaml — remove the `dind` container, then:
        env:
          - name: DOCKER_HOST
            value: unix:///var/run/docker.sock
        volumeMounts:
          - name: docker-sock
            mountPath: /var/run/docker.sock
      volumes:
        - name: docker-sock
          hostPath:
            path: /var/run/docker.sock
            type: Socket
```

This only works when the node's container runtime is Docker (increasingly rare) and
grants the pod control over the node's daemon — evaluate the security trade-off. The
dind sidecar is the portable default for this reason.
