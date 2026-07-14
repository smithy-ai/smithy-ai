package dev.smithyai.orchestrator.service.docker.dto;

public record ExecResult(int exitCode, String stdout, String stderr) {}
