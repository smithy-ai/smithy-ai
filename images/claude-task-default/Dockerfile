ARG BASE_IMAGE=claude-task-base:latest
FROM ${BASE_IMAGE}

# Node.js is already in the base image; add pnpm
RUN npm install -g pnpm

# Java + Maven
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-21-jdk maven \
    && rm -rf /var/lib/apt/lists/*

# Python
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip python3-venv \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
CMD ["sleep", "infinity"]
