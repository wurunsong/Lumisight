package com.lumisight.api.agent.controller;

import com.lumisight.api.agent.dto.request.AgentCronJobUpsertRequest;
import com.lumisight.api.agent.dto.response.AgentCronJobResponse;
import com.lumisight.api.agent.support.AgentCronJobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/lumisight/agent/cron-jobs")
public class AgentCronJobController {

    private final AgentCronJobService cronJobService;

    public AgentCronJobController(AgentCronJobService cronJobService) {
        this.cronJobService = cronJobService;
    }

    @GetMapping
    public List<AgentCronJobResponse> list() {
        return cronJobService.list();
    }

    @GetMapping("/{jobId}")
    public AgentCronJobResponse get(@PathVariable String jobId) {
        return cronJobService.get(jobId);
    }

    @PostMapping
    public ResponseEntity<AgentCronJobResponse> create(@RequestBody AgentCronJobUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cronJobService.create(request));
    }

    @PutMapping("/{jobId}")
    public AgentCronJobResponse update(@PathVariable String jobId, @RequestBody AgentCronJobUpsertRequest request) {
        return cronJobService.update(jobId, request);
    }

    @PostMapping("/{jobId}/trigger")
    public AgentCronJobResponse triggerNow(@PathVariable String jobId) {
        return cronJobService.triggerNow(jobId);
    }

    @DeleteMapping("/{jobId}")
    public ResponseEntity<Void> delete(@PathVariable String jobId) {
        cronJobService.delete(jobId);
        return ResponseEntity.noContent().build();
    }
}
