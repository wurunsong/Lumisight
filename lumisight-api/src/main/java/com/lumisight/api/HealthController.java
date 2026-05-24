package com.lumisight.api;

import com.lumisight.common.LayerInfo;
import com.lumisight.core.OrchestrationService;
import com.lumisight.memory.MemoryService;
import com.lumisight.skills.SkillService;
import com.lumisight.tools.ToolService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/lumisight")
public class HealthController {

    private final OrchestrationService orchestrationService;
    private final ToolService toolService;
    private final MemoryService memoryService;
    private final SkillService skillService;

    public HealthController(OrchestrationService orchestrationService,
                            ToolService toolService,
                            MemoryService memoryService,
                            SkillService skillService) {
        this.orchestrationService = orchestrationService;
        this.toolService = toolService;
        this.memoryService = memoryService;
        this.skillService = skillService;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        List<LayerInfo> layers = List.of(
                orchestrationService.describe(),
                toolService.describe(),
                memoryService.describe(),
                skillService.describe()
        );
        return Map.of(
                "project", "Lumisight",
                "status", "UP",
                "layers", layers
        );
    }
}
