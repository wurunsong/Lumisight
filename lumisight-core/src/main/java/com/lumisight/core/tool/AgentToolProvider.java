package com.lumisight.core.tool;

import java.util.List;

public interface AgentToolProvider {

    List<PermissionedAgentTool<?>> tools();
}
