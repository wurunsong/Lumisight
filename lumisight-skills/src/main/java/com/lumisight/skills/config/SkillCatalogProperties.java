package com.lumisight.skills.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "lumisight.skills")
public class SkillCatalogProperties {

    private List<String> allowedPaths = new ArrayList<>();

    public List<String> getAllowedPaths() {
        return allowedPaths;
    }

    public void setAllowedPaths(List<String> allowedPaths) {
        this.allowedPaths = allowedPaths == null ? new ArrayList<>() : allowedPaths;
    }
}

