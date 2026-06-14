package com.lumisight.skills.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "lumisight.skills")
public class SkillCatalogProperties {

    private List<String> allowedPaths = new ArrayList<>();
    private int maxRegisteredSkills = 200;

    public List<String> getAllowedPaths() {
        return allowedPaths;
    }

    public void setAllowedPaths(List<String> allowedPaths) {
        this.allowedPaths = allowedPaths == null ? new ArrayList<>() : allowedPaths;
    }

    public int getMaxRegisteredSkills() {
        return maxRegisteredSkills;
    }

    public void setMaxRegisteredSkills(int maxRegisteredSkills) {
        this.maxRegisteredSkills = maxRegisteredSkills <= 0 ? 200 : maxRegisteredSkills;
    }
}
