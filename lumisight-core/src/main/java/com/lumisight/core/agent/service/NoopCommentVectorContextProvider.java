package com.lumisight.core.agent.service;

import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.port.CommentVectorContextProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnMissingBean(CommentVectorContextProvider.class)
public class NoopCommentVectorContextProvider implements CommentVectorContextProvider {

    @Override
    public List<AgentContextItem> retrieveByComment(String repoRoot, String naturalLanguageQuery, Integer limit) {
        return List.of(new AgentContextItem(
                "comment_vector",
                "placeholder",
                "Comment vector context provider not wired yet.",
                Map.of("repoRoot", repoRoot)
        ));
    }
}
