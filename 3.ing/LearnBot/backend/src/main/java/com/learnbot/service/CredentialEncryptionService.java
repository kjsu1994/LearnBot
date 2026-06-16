package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class CredentialEncryptionService {
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec keySpec;

    public CredentialEncryptionService(LearnBotProperties properties) {
        this.keySpec = new SecretKeySpec(sha256(properties.getCode().getCredentialSecret()), "AES");
    }

    public EncryptedGitCredential encrypt(String username, String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("저장할 Git token을 입력하세요.");
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(token.trim().getBytes(StandardCharsets.UTF_8));

            return new EncryptedGitCredential(
                    username == null || username.isBlank() ? null : username.trim(),
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(encrypted)
            );
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Git token 암호화에 실패했습니다.", ex);
        }
    }

    public GitAccessToken decrypt(EncryptedGitCredential credential) {
        try {
            byte[] iv = Base64.getDecoder().decode(credential.iv());
            byte[] ciphertext = Base64.getDecoder().decode(credential.ciphertext());

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            String token = new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            return new GitAccessToken(credential.username(), token);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("저장된 Git token을 복호화하지 못했습니다. credential secret을 확인하거나 token을 다시 입력하세요.", ex);
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }
}
