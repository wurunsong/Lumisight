package com.lumisight.core.port;

import com.lumisight.core.model.AgentContextItem;

import java.util.List;

public interface CommentVectorContextProvider {

    List<AgentContextItem> retrieveByComment(String repoRoot, String naturalLanguageQuery, Integer limit);
}
