package dev.smithyai.knowledgebase.model;

public record Chunk(String content, String filePath, String section, int startLine, int endLine) {}
