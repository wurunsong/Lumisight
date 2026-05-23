package com.lumisight.tools.kg.util;

import com.lumisight.tools.kg.model.EdgeType;
import com.lumisight.tools.kg.model.NodeType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class NodeIdUtils {

    private NodeIdUtils() {
    }

    public static String nodeId(NodeType type, String qualifiedName) {
        return "N_" + type.name() + "_" + sha256Hex(type.name() + "|" + qualifiedName);
    }

    public static String edgeId(String fromNodeId, String toNodeId, EdgeType type) {
        return "E_" + type.name() + "_" + sha256Hex(type.name() + "|" + fromNodeId + "|" + toNodeId);
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
