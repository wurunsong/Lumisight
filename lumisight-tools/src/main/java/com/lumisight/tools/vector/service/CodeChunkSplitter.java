package com.lumisight.tools.vector.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CodeChunkSplitter {

    private static final int DEFAULT_MAX_CHARS = 2600;
    private static final int DEFAULT_OVERLAP_CHARS = 240;
    private static final int MIN_MAX_CHARS = 500;

    public List<ChunkSlice> split(String text, Integer maxChunkChars, Integer overlapChars) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int max = maxChunkChars == null ? DEFAULT_MAX_CHARS : Math.max(MIN_MAX_CHARS, maxChunkChars);
        int overlap = overlapChars == null ? DEFAULT_OVERLAP_CHARS : Math.max(0, overlapChars);
        if (overlap >= max) {
            overlap = Math.max(0, max / 4);
        }

        List<ChunkSlice> slices = new ArrayList<>();
        int n = text.length();
        int start = 0;
        while (start < n) {
            int idealEnd = Math.min(start + max, n);
            int end = findCutPoint(text, start, idealEnd);
            if (end <= start) {
                end = idealEnd;
            }
            String chunk = text.substring(start, end);
            int startLine = lineOf(text, start);
            int endLine = lineOf(text, Math.max(start, end - 1));
            slices.add(new ChunkSlice(chunk, startLine, endLine));
            if (end >= n) {
                break;
            }
            start = Math.max(0, end - overlap);
        }
        return slices;
    }

    private int findCutPoint(String text, int start, int idealEnd) {
        for (int i = idealEnd; i > start + 200; i--) {
            char c = text.charAt(i - 1);
            if (c == '\n' || c == '}') {
                return i;
            }
        }
        return idealEnd;
    }

    private int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    public record ChunkSlice(String text, int startLine, int endLine) {
    }
}
