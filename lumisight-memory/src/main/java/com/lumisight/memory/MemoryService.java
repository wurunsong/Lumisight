package com.lumisight.memory;

import com.lumisight.common.LayerInfo;
import org.springframework.stereotype.Service;

@Service
public class MemoryService {

    public LayerInfo describe() {
        return new LayerInfo("lumisight-memory", "负责工作记忆、会话记忆与长期记忆管理");
    }
}
