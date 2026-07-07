FROM ubuntu:24.04

RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    curl \
    ca-certificates \
    gnupg \
    jq \
    && rm -rf /var/lib/apt/lists/*

# Install Node.js (required for agent CLIs)
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# Install supported agent CLIs
RUN npm install -g @anthropic-ai/claude-code @openai/codex

# Install Forgejo CLI (tea)
ARG TARGETARCH
RUN curl -sL "https://dl.gitea.io/tea/latest/tea-linux-${TARGETARCH}" -o /usr/local/bin/tea \
    && chmod +x /usr/local/bin/tea

# Install smithy helper scripts
COPY scripts/ /usr/local/bin/
RUN chmod +x /usr/local/bin/smithy-*

WORKDIR /workspace
CMD ["sleep", "infinity"]
