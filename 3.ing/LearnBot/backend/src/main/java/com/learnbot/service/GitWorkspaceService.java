package com.learnbot.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class GitWorkspaceService {
    public String sync(CodeRepositoryRecord repository, GitAccessToken accessToken) {
        try {
            Path localPath = Path.of(repository.localPath());
            Files.createDirectories(localPath.getParent());
            boolean existingRepository = Files.exists(localPath.resolve(".git"));

            try (Git git = existingRepository
                    ? Git.open(localPath.toFile())
                    : cloneRepository(repository, accessToken, localPath)) {
                if (existingRepository) {
                    pull(repository, accessToken, git);
                }
                ObjectId head = git.getRepository().resolve("HEAD");
                if (head == null) {
                    throw new IllegalArgumentException("Git 저장소의 HEAD commit을 확인할 수 없습니다.");
                }
                return head.getName();
            }
        } catch (GitAPIException | IOException ex) {
            throw new IllegalArgumentException(toFriendlyGitMessage(repository.gitUrl(), ex), ex);
        }
    }

    private Git cloneRepository(CodeRepositoryRecord repository, GitAccessToken accessToken, Path localPath) throws GitAPIException {
        var command = Git.cloneRepository()
                .setURI(repository.gitUrl())
                .setDirectory(localPath.toFile())
                .setCloneAllBranches(false);
        if (!"HEAD".equalsIgnoreCase(repository.branch())) {
            command.setBranch(repository.branch());
        }
        CredentialsProvider credentialsProvider = credentials(accessToken);
        if (credentialsProvider != null) {
            command.setCredentialsProvider(credentialsProvider);
        }
        return command.call();
    }

    private void pull(CodeRepositoryRecord repository, GitAccessToken accessToken, Git git) throws GitAPIException {
        CredentialsProvider credentialsProvider = credentials(accessToken);
        var fetch = git.fetch();
        if (credentialsProvider != null) {
            fetch.setCredentialsProvider(credentialsProvider);
        }
        fetch.call();

        if (!"HEAD".equalsIgnoreCase(repository.branch())) {
            try {
                git.checkout().setName(repository.branch()).call();
            } catch (RefNotFoundException ex) {
                git.checkout()
                        .setCreateBranch(true)
                        .setName(repository.branch())
                        .setStartPoint("origin/" + repository.branch())
                        .call();
            }
        }
        var command = git.pull();
        if (credentialsProvider != null) {
            command.setCredentialsProvider(credentialsProvider);
        }
        command.call();
    }

    private CredentialsProvider credentials(GitAccessToken accessToken) {
        if (accessToken == null || !accessToken.hasToken()) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider(accessToken.effectiveUsername(), accessToken.token().trim());
    }

    private String toFriendlyGitMessage(String gitUrl, Exception ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        if (isSshUrl(gitUrl)) {
            return "SSH Git 주소에 접근하지 못했습니다. 컨테이너에 SSH 키가 없거나 known_hosts 설정이 없을 수 있습니다. "
                    + "가능하면 같은 저장소의 HTTPS URL과 TOKEN 인증으로 다시 등록하세요. 원인: " + message;
        }
        if (message.toLowerCase().contains("auth")) {
            return "Git 인증에 실패했습니다. private 저장소라면 username/token을 입력해서 다시 인덱싱하세요. 원인: " + message;
        }
        return "Git 저장소 동기화에 실패했습니다. URL, branch, 네트워크 접근 권한을 확인하세요. 원인: " + message;
    }

    private boolean isSshUrl(String gitUrl) {
        if (gitUrl == null) {
            return false;
        }
        String lower = gitUrl.toLowerCase();
        return lower.startsWith("git@") || lower.startsWith("ssh://");
    }
}
