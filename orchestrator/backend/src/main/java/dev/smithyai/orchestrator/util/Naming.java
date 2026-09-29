package dev.smithyai.orchestrator.util;

import com.fasterxml.jackson.databind.JsonNode;
import dev.smithyai.orchestrator.model.RepoInfo;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Naming {

    // Issue ref in a branch: a plain number ("123") or an issue-tracker key ("ECD-4309").
    // Keys are uppercase, slugs lowercase, so the boundary is unambiguous.
    private static final Pattern ISSUE_REF_RE = Pattern.compile("^(?:smithy|architect)/((?:[A-Z][A-Z0-9_]*-)?\\d+)-");

    private Naming() {}

    public static boolean isSmithyBranch(String branch) {
        return branch.startsWith("smithy/");
    }

    public static boolean isArchitectBranch(String branch) {
        return branch != null && branch.startsWith("architect/");
    }

    public static String branchName(String issueRef, String title) {
        String slug = title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 40) slug = slug.substring(0, 40);
        return "smithy/" + issueRef + "-" + slug;
    }

    public static String repoSlug(String owner, String repo) {
        return owner + "/" + repo;
    }

    public static String planFilePath(String issueRef) {
        return ".smithy/plans/" + issueRef + ".md";
    }

    public static String resolveBaseBranch(String issueRef) {
        return (issueRef != null && !issueRef.isBlank()) ? issueRef : "";
    }

    public static String parseIssueRefFromBranch(String branch) {
        Matcher m = ISSUE_REF_RE.matcher(branch);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Human/provider-facing form of an issue ref: numeric refs get the "#"
     * prefix that GitLab/GitHub/Forgejo auto-link; tracker keys stay bare.
     */
    public static String displayRef(String issueRef) {
        return issueRef.chars().allMatch(Character::isDigit) ? "#" + issueRef : issueRef;
    }

    /**
     * A Docker-safe form of a container name a workflow asks for. Docker only
     * allows {@code [a-zA-Z0-9][a-zA-Z0-9_.-]*}, while repository paths can be
     * nested ({@code group/subgroup/repo} on GitLab): slashes become {@code --}
     * and anything else Docker rejects becomes {@code -}.
     */
    public static String containerName(String requested) {
        String name = requested.replace("/", "--").replaceAll("[^a-zA-Z0-9_.-]", "-");
        return name.isEmpty() || !Character.isLetterOrDigit(name.charAt(0)) ? "c" + name : name;
    }

    public static String contextRepoName(String repo) {
        return repo + "-context";
    }

    public static String architectBranchName(int sourcePr, String role) {
        return "architect/" + sourcePr + "-" + role;
    }

    public static RepoInfo repoInfo(JsonNode payload, String internalVcsUrl, String source) {
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
        return new RepoInfo(parts[0], parts[1], cloneUrl, source);
    }
}
