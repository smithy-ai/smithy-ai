FROM ubuntu:24.04

RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    curl \
    ca-certificates \
    gnupg \
    jq \
    && rm -rf /var/lib/apt/lists/*

# Install Node.js (required for Claude Code CLI)
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# Install Claude Code CLI
RUN npm install -g @anthropic-ai/claude-code

# Install Forgejo CLI (tea)
ARG TARGETARCH
RUN curl -sL "https://dl.gitea.io/tea/latest/tea-linux-${TARGETARCH}" -o /usr/local/bin/tea \
    && chmod +x /usr/local/bin/tea

# Install smithy helper scripts
COPY scripts/ /usr/local/bin/
RUN chmod +x /usr/local/bin/smithy-*

WORKDIR /workspace
CMD ["sleep", "infinity"]
