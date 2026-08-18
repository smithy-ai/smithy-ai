package dev.smithyai.orchestrator.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NamingTest {

    @Test
    void branchName() {
        assertEquals("smithy/42-add-user-login", Naming.branchName("42", "Add User Login"));
        assertEquals("smithy/ECD-4309-add-user-login", Naming.branchName("ECD-4309", "Add User Login"));
    }

    @Test
    void branchNameTruncatesAt40() {
        String longTitle = "A".repeat(100);
        String branch = Naming.branchName("1", longTitle);
        // "smithy/1-" prefix + max 40 char slug
        assertTrue(branch.length() <= "smithy/1-".length() + 40);
    }

    @Test
    void branchNameSpecialChars() {
        assertEquals("smithy/5-fix-bug-in-api", Naming.branchName("5", "Fix bug in API!"));
    }

    @Test
    void repoSlug() {
        assertEquals("owner/repo", Naming.repoSlug("owner", "repo"));
    }

    @Test
    void planFilePath() {
        assertEquals(".smithy/plans/42.md", Naming.planFilePath("42"));
        assertEquals(".smithy/plans/ECD-4309.md", Naming.planFilePath("ECD-4309"));
    }

    @Test
    void resolveBaseBranch() {
        assertEquals("", Naming.resolveBaseBranch(""));
        assertEquals("", Naming.resolveBaseBranch(null));
        assertEquals("develop", Naming.resolveBaseBranch("develop"));
    }

    @Test
    void parseIssueRefFromBranch() {
        assertEquals("42", Naming.parseIssueRefFromBranch("smithy/42-add-feature"));
        assertEquals("7", Naming.parseIssueRefFromBranch("architect/7-review"));
        assertEquals("ECD-4309", Naming.parseIssueRefFromBranch("smithy/ECD-4309-add-feature"));
        assertNull(Naming.parseIssueRefFromBranch("main"));
        assertNull(Naming.parseIssueRefFromBranch("feature/something"));
    }

    @Test
    void displayRef() {
        assertEquals("#42", Naming.displayRef("42"));
        assertEquals("ECD-4309", Naming.displayRef("ECD-4309"));
    }

    @Test
    void isSmithyBranch() {
        assertTrue(Naming.isSmithyBranch("smithy/42-add-feature"));
        assertFalse(Naming.isSmithyBranch("main"));
        assertFalse(Naming.isSmithyBranch("architect/7-review"));
        assertFalse(Naming.isSmithyBranch("feature/something"));
    }

    @Test
    void contextRepoName() {
        assertEquals("myrepo-context", Naming.contextRepoName("myrepo"));
    }

    @Test
    void architectBranchName() {
        assertEquals("architect/10-learn", Naming.architectBranchName(10, "learn"));
    }
}
