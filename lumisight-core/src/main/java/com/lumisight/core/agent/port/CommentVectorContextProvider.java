package com.lumisight.core.agent.port;

import com.lumisight.core.agent.model.AgentContextItem;

import java.util.List;

public interface CommentVectorContextProvider {

    List<AgentContextItem> retrieveByComment(String repoRoot, String naturalLanguageQuery, Integer limit);
}
