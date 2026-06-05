package com.lumisight.core.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "lumisight.agent.self-heal")
public class AgentSelfHealProperties {

    private boolean enabled = true;
    private boolean runCompile = true;
    private boolean runLint = true;
    private boolean requireSuccessBeforeFinal = true;
    private int maxValidationFiles = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRunCompile() {
        return runCompile;
    }

    public void setRunCompile(boolean runCompile) {
        this.runCompile = runCompile;
    }

    public boolean isRunLint() {
        return runLint;
    }

    public void setRunLint(boolean runLint) {
        this.runLint = runLint;
    }

    public boolean isRequireSuccessBeforeFinal() {
        return requireSuccessBeforeFinal;
    }

    public void setRequireSuccessBeforeFinal(boolean requireSuccessBeforeFinal) {
        this.requireSuccessBeforeFinal = requireSuccessBeforeFinal;
    }

    public int getMaxValidationFiles() {
        return maxValidationFiles;
    }

    public void setMaxValidationFiles(int maxValidationFiles) {
        this.maxValidationFiles = maxValidationFiles;
    }
}
