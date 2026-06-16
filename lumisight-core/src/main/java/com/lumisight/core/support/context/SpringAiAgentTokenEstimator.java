package com.lumisight.core.support.context;

import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 基于 Spring AI JTokkit 的 token 估算器。
 * 当前模型通过 OpenAI-compatible API 接入，真实服务端 tokenizer 可能略有差异，因此这里保留安全系数和字符数兜底。
 */
@Component
public class SpringAiAgentTokenEstimator implements AgentTokenEstimator {

    private static final double SAFETY_MULTIPLIER = 1.15d;

    private final TokenCountEstimator delegate = new JTokkitTokenCountEstimator();

    @Override
    public int estimate(String content) {
        if (!StringUtils.hasText(content)) {
            return 0;
        }
        try {
            // delegate 用 tokenizer 估算原始 token 数；再乘 1.15 并向上取整，给不同模型 tokenizer 差异和提示词包装留出预算余量。
            return Math.max(1, (int) Math.ceil(delegate.estimate(content) * SAFETY_MULTIPLIER));
        } catch (Exception ignored) {
            // tokenizer 不可用时走保守字符估算，保证上下文管理不会因为估算失败中断主流程。
            return Math.max(1, content.length() / 3);
        }
    }
}
