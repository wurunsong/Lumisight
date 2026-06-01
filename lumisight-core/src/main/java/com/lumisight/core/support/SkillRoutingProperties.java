package com.lumisight.core.support;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "lumisight.agent.skill-routing")
public class SkillRoutingProperties {

    private double minConfidence = 0.60d;
    private LowConfidenceFallback lowConfidenceFallback = LowConfidenceFallback.NONE;
    private String defaultSkillId = "";

    public double getMinConfidence() {
        return minConfidence;
    }

    public void setMinConfidence(double minConfidence) {
        this.minConfidence = minConfidence;
    }

    public LowConfidenceFallback getLowConfidenceFallback() {
        return lowConfidenceFallback;
    }

    public void setLowConfidenceFallback(LowConfidenceFallback lowConfidenceFallback) {
        this.lowConfidenceFallback = lowConfidenceFallback;
    }

    public String getDefaultSkillId() {
        return defaultSkillId;
    }

    public void setDefaultSkillId(String defaultSkillId) {
        this.defaultSkillId = defaultSkillId;
    }

    public enum LowConfidenceFallback {
        NONE,
        DEFAULT_SKILL
    }
}

