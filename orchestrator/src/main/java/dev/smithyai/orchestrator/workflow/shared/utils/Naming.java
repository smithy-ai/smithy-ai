package dev.smithyai.orchestrator.workflow.shared.utils;

import com.fasterxml.jackson.databind.JsonNode;
import dev.smithyai.orchestrator.model.RepoInfo;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Naming {

    public static final Pattern CONTAINER_RE = Pattern.compile("^(smithy|architect)\\.([^.]+)\\.([^.]+)\\.(.+)$");
    public static final Pattern ID_RE = Pattern.compile("^(\\d+)(?:\\.(refine|build))?$");
    private static final Pattern ISSUE_ID_RE = Pattern.compile("^(?:smithy|architect)/(\\d+)-");

    private Naming() {}

    public static boolean isSmithyBranch(String branch) {
        return branch.startsWith("smithy/");
    }

    public static String branchName(int issueId, String title) {
        String slug = title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 40) slug = slug.substring(0, 40);
        return "smithy/" + issueId + "-" + slug;
    }

    public static String repoSlug(String owner, String repo) {
        return owner + "/" + repo;
    }

    public static String planFilePath(int issueId) {
        return ".smithy/plans/" + issueId + ".md";
    }

    public static String resolveBaseBranch(String issueRef) {
        return (issueRef != null && !issueRef.isBlank()) ? issueRef : "main";
    }

    public static Integer parseIssueIdFromBranch(String branch) {
        Matcher m = ISSUE_ID_RE.matcher(branch);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return null;
    }

    public static String contextRepoName(String repo) {
        return repo + "-context";
    }

    public static String architectBranchName(int sourcePr, String role) {
        return "architect/" + sourcePr + "-" + role;
    }

    public static RepoInfo repoInfo(JsonNode payload, String internalVcsUrl) {
        var repoNode = payload.get("repository");
        String fullName = repoNode.get("full_name").asText();
        String[] parts = fullName.split("/", 2);
        String cloneUrl = repoNode.get("clone_url").asText();
        URI publicUri = URI.create(cloneUrl);
        URI internalUri = URI.create(internalVcsUrl);
        cloneUrl = cloneUrl.replaceFirst(
            Pattern.quote(publicUri.getScheme() + "://" + publicUri.getAuthority()),
            internalUri.getScheme() + "://" + internalUri.getAuthority()
        );
        return new RepoInfo(parts[0], parts[1], cloneUrl);
    }
}
