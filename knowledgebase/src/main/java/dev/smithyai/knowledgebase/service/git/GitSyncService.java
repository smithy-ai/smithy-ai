package dev.smithyai.knowledgebase.service.git;

import dev.smithyai.knowledgebase.config.KnowledgebaseConfig;
import java.io.File;
import java.io.IOException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitSyncService {

    private final String repositoryUrl;
    private final String branch;
    private final String accessToken;

    @Getter
    private final String localPath;

    public GitSyncService(KnowledgebaseConfig.GitConfig gitConfig) {
        this.repositoryUrl = gitConfig.repositoryUrl();
        this.branch = gitConfig.branch();
        this.accessToken = gitConfig.accessToken();
        this.localPath = gitConfig.localPath();
    }

    public String syncRepository() throws GitAPIException, IOException {
        File repoDir = new File(localPath);
        File gitDir = new File(repoDir, ".git");

        if (!gitDir.exists()) {
            log.info("Cloning repository from {}", repositoryUrl);
            return cloneRepository(repoDir);
        } else {
            log.info("Pulling latest changes from {}", repositoryUrl);
            return pullRepository(repoDir);
        }
    }

    private String cloneRepository(File repoDir) throws GitAPIException, IOException {
        if (repositoryUrl == null || repositoryUrl.isEmpty()) {
            throw new IllegalStateException("GIT_REPO_URL is not configured");
        }

        Git git = Git.cloneRepository()
            .setURI(repositoryUrl)
            .setDirectory(repoDir)
            .setBranch(branch)
            .setCredentialsProvider(getCredentialsProvider())
            .call();

        String commitHash = git.getRepository().resolve("HEAD").getName();
        log.info("Repository cloned. Latest commit: {}", commitHash);

        git.close();
        return commitHash;
    }

    private String pullRepository(File repoDir) throws GitAPIException, IOException {
        try (Git git = Git.open(repoDir)) {
            git.fetch().setRemote("origin").setCredentialsProvider(getCredentialsProvider()).call();

            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/" + branch).call();

            String commitHash = git.getRepository().resolve("HEAD").getName();
            log.info("Repository updated. Latest commit: {}", commitHash);
            return commitHash;
        }
    }

    private UsernamePasswordCredentialsProvider getCredentialsProvider() {
        if (accessToken == null || accessToken.isEmpty()) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider("git", accessToken);
    }

    public boolean isConfigured() {
        return repositoryUrl != null && !repositoryUrl.isEmpty();
    }
}
