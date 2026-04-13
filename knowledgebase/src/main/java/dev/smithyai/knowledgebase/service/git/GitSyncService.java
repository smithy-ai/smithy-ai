package dev.smithyai.knowledgebase.service.git;

import dev.smithyai.knowledgebase.config.KnowledgebaseConfig;
import dev.smithyai.knowledgebase.config.KnowledgebaseConfig.RepositoryConfig;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitSyncService {

    private final String vcsToken;
    private final Path basePath;

    public GitSyncService(KnowledgebaseConfig.VcsConfig vcsConfig, KnowledgebaseConfig.VectorstoreConfig vsConfig) {
        this.vcsToken = vcsConfig.token();
        // Store git repos alongside vectorstore: <vectorstore>/../git/<repoName>/
        this.basePath = Path.of(vsConfig.path()).getParent().resolve("git");
    }

    public Path repoLocalPath(RepositoryConfig repo) {
        return basePath.resolve(repo.safeKey());
    }

    public String syncRepository(RepositoryConfig repo) throws GitAPIException, IOException {
        File repoDir = repoLocalPath(repo).toFile();
        File gitDir = new File(repoDir, ".git");

        if (!gitDir.exists()) {
            log.info("Cloning {} from {}", repo.name(), repo.cloneUrl());
            return cloneRepository(repo, repoDir);
        } else {
            log.info("Pulling latest for {} from {}", repo.name(), repo.cloneUrl());
            return pullRepository(repo, repoDir);
        }
    }

    private String cloneRepository(RepositoryConfig repo, File repoDir) throws GitAPIException, IOException {
        Git git = Git.cloneRepository()
            .setURI(repo.cloneUrl())
            .setDirectory(repoDir)
            .setBranch(repo.branch())
            .setCredentialsProvider(getCredentialsProvider())
            .call();

        String commitHash = git.getRepository().resolve("HEAD").getName();
        log.info("{} cloned. Latest commit: {}", repo.name(), commitHash);
        git.close();
        return commitHash;
    }

    private String pullRepository(RepositoryConfig repo, File repoDir) throws GitAPIException, IOException {
        try (Git git = Git.open(repoDir)) {
            git.fetch().setRemote("origin").setCredentialsProvider(getCredentialsProvider()).call();
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/" + repo.branch()).call();

            String commitHash = git.getRepository().resolve("HEAD").getName();
            log.info("{} updated. Latest commit: {}", repo.name(), commitHash);
            return commitHash;
        }
    }

    private UsernamePasswordCredentialsProvider getCredentialsProvider() {
        if (vcsToken == null || vcsToken.isEmpty()) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider("git", vcsToken);
    }
}
