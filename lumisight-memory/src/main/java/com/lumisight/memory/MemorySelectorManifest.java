package com.lumisight.memory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;

final class MemorySelectorManifest {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());

    private MemorySelectorManifest() {
    }

    static String render(List<MemoryHeader> headers) {
        if (headers == null || headers.isEmpty()) {
            return "- none";
        }
        StringJoiner joiner = new StringJoiner("\n");
        headers.stream()
                .sorted(Comparator.comparingLong(MemoryHeader::mtimeMs).reversed())
                .forEach(header -> joiner.add("- [%s] %s (%s): %s".formatted(
                        header.type().wireValue(),
                        header.filename(),
                        DATE_FORMATTER.format(Instant.ofEpochMilli(header.mtimeMs())),
                        header.description()
                )));
        return joiner.toString();
    }
}
