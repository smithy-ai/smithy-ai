package dev.smithyai.orchestrator.service.vcs.dto;

import java.util.List;

public record DiscussionEntry(String id, boolean resolvable, boolean resolved, List<DiscussionNote> notes) {}
