package com.lumisight.skills;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.lumisight.skills.config.SkillCatalogProperties;
import com.lumisight.skills.dto.ParsedSkillDocument;
import com.lumisight.skills.dto.RegisteredSkill;

/**
 * 启动时注册skill
 */
@Component
public class SkillCatalog {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalog.class);

    private final SkillCatalogProperties properties;
    private final SkillMarkdownParser parser;

    private final Map<String, RegisteredSkill> byId = new LinkedHashMap<>();
    private final Map<String, RegisteredSkill> byName = new LinkedHashMap<>();
    private final Map<String, RegisteredSkill> byPath = new LinkedHashMap<>();

    public SkillCatalog(SkillCatalogProperties properties, SkillMarkdownParser parser) {
        this.properties = properties;
        this.parser = parser;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    public synchronized void refresh() {
        byId.clear();
        byName.clear();
        byPath.clear();

        List<Path> roots = normalizeRoots(properties.getAllowedPaths());
        for (Path root : roots) {
            scanRoot(root);
        }
        log.info("skill_catalog loaded, roots={}, skills={}", roots.size(), byId.size());
    }

    public synchronized Optional<RegisteredSkill> resolve(String ref) {
        if (!StringUtils.hasText(ref)) {
            return Optional.empty();
        }
        String text = ref.trim();
        RegisteredSkill skill = byId.get(text);
        if (skill != null) {
            return Optional.of(skill);
        }
        skill = byName.get(text.toLowerCase(Locale.ROOT));
        if (skill != null) {
            return Optional.of(skill);
        }
        Path path = toAbsPath(text);
        if (path != null) {
            skill = byPath.get(path.toString());
            if (skill != null) {
                return Optional.of(skill);
            }
        }
        return Optional.empty();
    }

    public synchronized List<RegisteredSkill> allSkills() {
        return new ArrayList<>(byId.values());
    }

    private List<Path> normalizeRoots(List<String> roots) {
        List<Path> result = new ArrayList<>();
        for (String root : roots) {
            if (!StringUtils.hasText(root)) {
                continue;
            }
            Path path = toAbsPath(root);
            if (path != null && Files.exists(path) && Files.isDirectory(path)) {
                result.add(path);
            }
        }
        return result;
    }

    private void scanRoot(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> registerSkill(path, root));
        } catch (Exception e) {
            log.warn("skill_catalog scan failed, root={}, error={}", root, e.getMessage());
        }
    }

    private void registerSkill(Path file, Path root) {
        try {
            String content = Files.readString(file);
            ParsedSkillDocument doc = parser.parse(content);
            String relative = root.relativize(file).toString().replace('\\', '/');
            String id = relative;
            RegisteredSkill skill = new RegisteredSkill(id, doc.name(), doc.summary(), file.toAbsolutePath().normalize());
            byId.put(id, skill);
            byName.put(doc.name().toLowerCase(Locale.ROOT), skill);
            byPath.put(skill.path().toString(), skill);
        } catch (Exception e) {
            log.warn("skill_catalog parse failed, file={}, error={}", file, e.getMessage());
        }
    }

    private Path toAbsPath(String text) {
        try {
            return Path.of(text).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }
}
