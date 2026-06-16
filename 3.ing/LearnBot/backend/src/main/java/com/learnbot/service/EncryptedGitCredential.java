package com.learnbot.service;

public record EncryptedGitCredential(
        String username,
        String iv,
        String ciphertext
) {
}
