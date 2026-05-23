package com.lumisight.core;

import com.lumisight.common.LayerInfo;
import org.springframework.stereotype.Service;

@Service
public class OrchestrationService {

    public LayerInfo describe() {
        return new LayerInfo("lumisight-core", "负责任务编排、状态机与执行流程");
    }
}
