package com.lumisight.tools;

import com.lumisight.common.LayerInfo;
import org.springframework.stereotype.Service;

@Service
public class ToolService {

    public LayerInfo describe() {
        return new LayerInfo("lumisight-tools", "负责工具注册、工具路由与外部知识库调用适配");
    }
}
