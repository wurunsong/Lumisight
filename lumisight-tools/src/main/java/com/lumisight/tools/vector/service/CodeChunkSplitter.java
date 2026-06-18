package com.lumisight.tools.vector.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class CodeChunkSplitter {

    private static final int DEFAULT_MAX_CHARS = 2600;
    private static final int DEFAULT_OVERLAP_CHARS = 240;
    private static final int MIN_MAX_CHARS = 500;
    private static final int MIN_BACKTRACK_CHARS = 200;
    private static final int NO_CUT = Integer.MIN_VALUE;

    /**
     * 把一个“已经给定的代码块”切成适合向量召回的片段。
     *
     * 这个类不负责把源码文件解析成方法/类。正常 Java 仓库入库时，VectorIngestService 会先用
     * JavaParser 抽出每个 MethodDeclaration，然后只有当某个方法体太长、不适合单独作为一个向量文档时，
     * 才调用这个 splitter 继续切方法内部。
     *
     * 这里的策略是“代码边界感知的滑动窗口”：
     * 1. 尽量让每个片段不超过 maxChunkChars；
     * 2. 在长度上限附近给候选切点打分，优先级是：
     *    空行 > 以 '}' 结束的行 > 以 ';' 结束的行 > 以 '{' 结束的行 > 普通换行；
     * 3. 下一个 overlap 片段尽量从行首开始，避免从变量名或语句中间开始；
     * 4. 如果找不到合适代码边界，才退回到按 maxChunkChars 硬切。
     *
     * CodeSearchNet 评测会直接把数据集里的 code 传进来，因为它的样本本身通常已经是函数/方法级代码。
     */
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
        List<Integer> lineStarts = lineStarts(text);
        int n = text.length();
        int start = 0;
        while (start < n) {
            int idealEnd = Math.min(start + max, n);
            int end = findCutPoint(text, start, idealEnd);
            if (end <= start) {
                end = idealEnd;
            }
            String chunk = text.substring(start, end);
            int startLine = lineOf(lineStarts, start);
            int endLine = lineOf(lineStarts, Math.max(start, end - 1));
            slices.add(new ChunkSlice(chunk, startLine, endLine));
            if (end >= n) {
                break;
            }
            start = nextStart(text, start, end, overlap);
        }
        return slices;
    }

    private int findCutPoint(String text, int start, int idealEnd) {
        if (idealEnd >= text.length()) {
            return idealEnd;
        }
        int lowerBound = Math.min(idealEnd, start + MIN_BACKTRACK_CHARS);
        int bestCut = -1;
        int bestScore = NO_CUT;
        for (int cut = lowerBound + 1; cut <= idealEnd; cut++) {
            int boundaryScore = boundaryScore(text, cut);
            if (boundaryScore == NO_CUT) {
                continue;
            }
            int proximityPenalty = (idealEnd - cut) / 4;
            int score = boundaryScore - proximityPenalty;
            if (score > bestScore) {
                bestScore = score;
                bestCut = cut;
            }
        }
        return bestCut > start ? bestCut : idealEnd;
    }

    private int boundaryScore(String text, int cut) {
        if (cut <= 0 || cut > text.length()) {
            return NO_CUT;
        }
        char c = text.charAt(cut - 1);
        if (isBlankLineEndingAt(text, cut)) {
            return 5000;
        }
        if (c == '\n' || c == '\r') {
            char previousCodeChar = previousNonWhitespace(text, cut - 2);
            if (previousCodeChar == '}') {
                return 4400;
            }
            if (previousCodeChar == ';') {
                return 4000;
            }
            if (previousCodeChar == '{') {
                return 3200;
            }
            return 1800;
        }
        if (isLineEndingBoundary(text, cut)) {
            if (c == '}') {
                return 4200;
            }
            if (c == ';') {
                return 3800;
            }
            if (c == '{') {
                return 3000;
            }
        }
        return NO_CUT;
    }

    private boolean isBlankLineEndingAt(String text, int cut) {
        if (cut < 2 || cut > text.length()) {
            return false;
        }
        char current = text.charAt(cut - 1);
        if (current != '\n' && current != '\r') {
            return false;
        }
        int previousLineEnd = cut - 2;
        while (previousLineEnd >= 0 && (text.charAt(previousLineEnd) == '\n' || text.charAt(previousLineEnd) == '\r')) {
            previousLineEnd--;
        }
        while (previousLineEnd >= 0) {
            char c = text.charAt(previousLineEnd);
            if (c == '\n' || c == '\r') {
                return true;
            }
            if (!Character.isWhitespace(c)) {
                return false;
            }
            previousLineEnd--;
        }
        return true;
    }

    private boolean isLineEndingBoundary(String text, int cut) {
        char c = text.charAt(cut - 1);
        if (c != '}' && c != ';' && c != '{') {
            return false;
        }
        for (int i = cut; i < text.length(); i++) {
            char next = text.charAt(i);
            if (next == '\n' || next == '\r') {
                return true;
            }
            if (!Character.isWhitespace(next)) {
                return false;
            }
        }
        return true;
    }

    private char previousNonWhitespace(String text, int index) {
        for (int i = Math.min(index, text.length() - 1); i >= 0; i--) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c;
            }
        }
        return '\0';
    }

    private int nextStart(String text, int currentStart, int end, int overlap) {
        if (overlap <= 0) {
            return end;
        }
        int rawStart = Math.max(currentStart + 1, end - overlap);
        int lineStart = lineStartAtOrBefore(text, rawStart);
        if (lineStart <= currentStart) {
            lineStart = lineStartAfter(text, currentStart);
        }
        if (lineStart <= currentStart || lineStart >= end) {
            return end;
        }
        return lineStart;
    }

    private int lineStartAtOrBefore(String text, int index) {
        int start = Math.min(Math.max(0, index), text.length());
        while (start > 0) {
            char previous = text.charAt(start - 1);
            if (previous == '\n' || previous == '\r') {
                break;
            }
            start--;
        }
        return start;
    }

    private int lineStartAfter(String text, int index) {
        for (int i = Math.max(0, index); i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return Math.min(i + 1, text.length());
            }
        }
        return text.length();
    }

    private List<Integer> lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && i + 1 < text.length()) {
                starts.add(i + 1);
            }
        }
        return starts;
    }

    private int lineOf(List<Integer> lineStarts, int index) {
        int insertionPoint = Collections.binarySearch(lineStarts, index);
        if (insertionPoint >= 0) {
            return insertionPoint + 1;
        }
        return -insertionPoint - 1;
    }

    public record ChunkSlice(String text, int startLine, int endLine) {
    }
}
