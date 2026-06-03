package com.lumisight.core.support;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class PromptTemplateService {

    private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();

    public String render(String templateName, Map<String, ?> values) {
        String template = cache.computeIfAbsent(templateName, this::loadTemplate);
        String rendered = template;
        if (values != null) {
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                String placeholder = "<" + entry.getKey() + ">";
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                rendered = rendered.replace(placeholder, value);
            }
        }
        return rendered;
    }

    private String loadTemplate(String templateName) {
        String location = "prompt/" + templateName + ".st";
        ClassPathResource resource = new ClassPathResource(location);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Prompt template not found: " + location);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load prompt template: " + location, e);
        }
    }
}
