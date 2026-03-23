# Custom Task Images

Smithy-AI runs each agent task in a Docker container. You can create custom images tailored to your project's toolchain.

## Image architecture

The task images use a two-layer system:

1. **`claude-task-base`** — Foundation image with Ubuntu 24.04, Git, curl, Node.js 22, Claude Code CLI, Forgejo CLI (tea), and smithy helper scripts
2. **`claude-task-*` variants** — Built on top of the base, adding project-specific toolchains

The default image (`claude-task` / `claude-task-default`) adds pnpm, Java 21 with Maven, and Python 3 on top of the base.

## Why customize

- **Smaller images**: If you only need Node.js, skip Java and Python
- **Different toolchains**: Add Rust, Go, .NET, or other tools your project needs
- **Pre-installed dependencies**: Bake in project dependencies for faster task startup

## Creating a custom image

### 1. Create the Dockerfile

Create a new directory under `images/` and add a `Dockerfile`:

```dockerfile
# images/claude-task-myenv/Dockerfile
FROM claude-task-base:latest

# Add your toolchain
RUN apt-get update && apt-get install -y --no-install-recommends \
    your-packages-here \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
CMD ["sleep", "infinity"]
```

### 2. Build the image

```bash
docker build -t claude-task-myenv:latest images/claude-task-myenv
```

### 3. Configure the orchestrator

Set `TASK_IMAGE` in your `.env` or environment:

```bash
TASK_IMAGE=claude-task-myenv:latest
```

## Example: Node.js only

A minimal image for JavaScript/TypeScript projects:

```dockerfile
# images/claude-task-node/Dockerfile
FROM claude-task-base:latest

RUN npm install -g pnpm

WORKDIR /workspace
CMD ["sleep", "infinity"]
```

## Example: Rust

An image for Rust projects:

```dockerfile
# images/claude-task-rust/Dockerfile
FROM claude-task-base:latest

RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
ENV PATH="/root/.cargo/bin:${PATH}"

WORKDIR /workspace
CMD ["sleep", "infinity"]
```

