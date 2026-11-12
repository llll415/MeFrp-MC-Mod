package com.mefrp.mc.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Lightweight, machine-bound token protection.
 *
 * The AES key is derived from the machine id with SHA-256 and an explicit
 * domain separator. A random salt is included in the derivation and a random
 * 12-byte IV is used for every encryption. The ciphertext (including the GCM
 * tag) is stored in the config. Neither the machine id nor the key is stored.
 */
public final class ConfigCrypto {
    public static final String ALGORITHM = "AES-128-GCM-SHA256";
    public static final int SALT_BYTES = 16;
    public static final int IV_BYTES = 12;
    public static final int KEY_BYTES = 16;
    public static final int TAG_BITS = 128;
    public static final int TAG_BYTES = TAG_BITS / 8;

    private static final byte[] KEY_DOMAIN =
            "MeFrp-MC-Mod|token-encryption-key|v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_CIPHERTEXT_BYTES = 8192;

    private ConfigCrypto() {
    }

    public static boolean isSupportedAlgorithm(String algorithm) {
        return ALGORITHM.equals(algorithm);
    }

    public static EncryptedToken encrypt(String token, String machineId) throws GeneralSecurityException {
        byte[] plaintext = token.getBytes(StandardCharsets.UTF_8);
        if (plaintext.length > MAX_CIPHERTEXT_BYTES - TAG_BYTES) {
            throw new GeneralSecurityException("token is too long");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(machineId, salt), new GCMParameterSpec(TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        return new EncryptedToken(
                ALGORITHM,
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(iv),
                Base64.getEncoder().encodeToString(ciphertext));
    }

    public static String decrypt(EncryptedToken encrypted, String machineId)
            throws ConfigCryptoException {
        if (encrypted == null || !isSupportedAlgorithm(encrypted.getAlgorithm())) {
            throw new ConfigCryptoException("unsupported token encryption algorithm");
        }
        byte[] salt = decodeBase64Strict(encrypted.getSalt(), SALT_BYTES, 24, "salt");
        byte[] iv = decodeBase64Strict(encrypted.getIv(), IV_BYTES, 16, "iv");
        byte[] ciphertext = decodeBase64Strict(encrypted.getCiphertext(), -1,
                (MAX_CIPHERTEXT_BYTES / 3 + 1) * 4 + 4, "ciphertext");
        if (ciphertext.length < TAG_BYTES) {
            throw new ConfigCryptoException("token ciphertext is too short");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(machineId, salt), new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            String token = new String(plaintext, StandardCharsets.UTF_8);
            if (token.trim().isEmpty()) {
                throw new ConfigCryptoException("decrypted token is empty");
            }
            return token;
        } catch (GeneralSecurityException e) {
            throw new ConfigCryptoException("failed to decrypt token", e);
        }
    }

    private static SecretKeySpec deriveKey(String machineId, byte[] salt) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(KEY_DOMAIN);
        digest.update((byte) 0);
        digest.update(salt);
        digest.update((byte) 0);
        digest.update(machineId.getBytes(StandardCharsets.UTF_8));
        byte[] derived = digest.digest();
        return new SecretKeySpec(Arrays.copyOf(derived, KEY_BYTES), "AES");
    }

    private static byte[] decodeBase64Strict(String value, int expectedLength, int maxEncodedLength, String field)
            throws ConfigCryptoException {
        if (value == null || value.isEmpty() || value.length() > maxEncodedLength) {
            throw new ConfigCryptoException("invalid " + field + " length");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new ConfigCryptoException("invalid " + field + " base64", e);
        }
        if (!Base64.getEncoder().encodeToString(decoded).equals(value)) {
            throw new ConfigCryptoException("non-canonical " + field + " base64");
        }
        if (expectedLength >= 0 && decoded.length != expectedLength) {
            throw new ConfigCryptoException("invalid " + field + " length");
        }
        return decoded;
    }
}
