package com.lumisight.tools.kg.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class HashUtils {

    private HashUtils() {
    }

    public static String sha256(Path path) {
        try {
            byte[] content = Files.readAllBytes(path);
            return sha256Bytes(content);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to hash file: " + path, e);
        }
    }

    public static String sha256Hex(String input) {
        try {
            return sha256Bytes(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to hash input string", e);
        }
    }

    private static String sha256Bytes(byte[] content) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
