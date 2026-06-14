package com.lumisight.core.support.memory;

import java.util.List;

public interface RelevantMemorySourceProvider {

    List<RelevantMemorySource> resolveSources(String repoRoot, String userId, String query);
}
