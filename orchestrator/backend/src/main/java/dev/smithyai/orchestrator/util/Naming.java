package dev.smithyai.orchestrator.util;

import com.fasterxml.jackson.databind.JsonNode;
import dev.smithyai.orchestrator.model.RepoInfo;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Naming {

    public static final Pattern CONTAINER_RE = Pattern.compile("^(smithy|architect)\\.([^.]+)\\.([^.]+)\\.(.+)$");
    public static final Pattern ID_RE = Pattern.compile("^([A-Za-z0-9-]+)(?:\\.(refine|build))?$");
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

    public static String containerName(String type, String owner, String repo, String identifier) {
        String sanitizedOwner = owner.replace("/", "--");
        String sanitizedRepo = repo.replace("/", "--");
        String candidate = type + "." + sanitizedOwner + "." + sanitizedRepo + "." + identifier;
        if (candidate.length() <= 63) {
            return candidate;
        }
        String slug = sanitizedOwner + "." + sanitizedRepo;
        String hash = shortHash(owner + "/" + repo);
        // Fixed parts: type + "." + "." + "-" + hash + "." + identifier
        int fixedLen = type.length() + 1 + 1 + 1 + hash.length() + 1 + identifier.length();
        int available = 63 - fixedLen;
        if (available < 1) {
            available = 1;
        }
        String truncatedSlug = slug.substring(0, Math.min(slug.length(), available));
        return type + "." + truncatedSlug + "-" + hash + "." + identifier;
    }

    private static String shortHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
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
