package com.lumisight.api.memory.controller;

import com.lumisight.api.memory.dto.MemoryCreateRequest;
import com.lumisight.api.memory.dto.MemoryEntryResponse;
import com.lumisight.api.memory.dto.MemoryHeaderResponse;
import com.lumisight.api.memory.dto.MemoryRelevantResponse;
import com.lumisight.core.support.RelevantMemoryService;
import com.lumisight.memory.MemoryService;
import com.lumisight.memory.MemoryType;
import com.lumisight.memory.MemoryWriteRequest;
import com.lumisight.memory.RelevantMemoryContext;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
// todo 这里记忆管理有问题，增改查都应该是包含在agent loop中，而不是需要单独请求
@RestController
@RequestMapping("/api/lumisight/memory")
public class MemoryController {

    private final MemoryService memoryService;
    private final RelevantMemoryService relevantMemoryService;

    public MemoryController(MemoryService memoryService, RelevantMemoryService relevantMemoryService) {
        this.memoryService = memoryService;
        this.relevantMemoryService = relevantMemoryService;
    }

    /**
     * 列出磁盘上的记忆文件摘要
     * @param repoRoot
     * @param userId
     * @return
     */
    @GetMapping("/entries")
    public List<MemoryHeaderResponse> list(
            @RequestParam(required = false) String repoRoot,
            @RequestParam(required = false) String userId
    ) {
        return memoryService.list(normalizeRepoRoot(repoRoot), normalizeUserId(userId)).stream()
                .map(MemoryHeaderResponse::from)
                .toList();
    }

    /**
     * 新增一个长期记忆文件
     * @param request
     * @return
     */
    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public MemoryEntryResponse create(@RequestBody MemoryCreateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request is required");
        }
        return MemoryEntryResponse.from(memoryService.save(
                normalizeRepoRoot(request.repoRoot()),
                normalizeUserId(request.userId()),
                new MemoryWriteRequest(
                        request.name(),
                        request.description(),
                        MemoryType.parse(request.type()),
                        request.body()
                )
        ));
    }

    /**
     * 删除一个长期记忆文件
     * @param filename
     * @param repoRoot
     * @param userId
     */
    @DeleteMapping("/entries/{filename}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable String filename,
            @RequestParam(required = false) String repoRoot,
            @RequestParam(required = false) String userId
    ) {
        boolean deleted = memoryService.delete(normalizeRepoRoot(repoRoot), normalizeUserId(userId), filename);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "memory file not found: " + filename);
        }
    }

    /**
     * 展示MEMORY.md这个记忆索引文件的内容
     * @param repoRoot
     * @param userId
     * @return
     */
    @GetMapping("/index")
    public String entrypoint(
            @RequestParam(required = false) String repoRoot,
            @RequestParam(required = false) String userId
    ) {
        return memoryService.loadEntrypoint(normalizeRepoRoot(repoRoot), normalizeUserId(userId)).content();
    }

    /**
     * 搜索和query相关的记忆文件
     * @param query
     * @param repoRoot
     * @param userId
     * @return
     */
    @GetMapping("/relevant")
    public MemoryRelevantResponse relevant(
            @RequestParam String query,
            @RequestParam(required = false) String repoRoot,
            @RequestParam(required = false) String userId
    ) {
        RelevantMemoryContext context = relevantMemoryService.resolveRelevant(
                normalizeRepoRoot(repoRoot),
                normalizeUserId(userId),
                query
        );
        return new MemoryRelevantResponse(
                context.entrypoint().content(),
                context.selectedEntries().stream().map(MemoryEntryResponse::from).toList(),
                context.remindersBlock()
        );
    }

    private String normalizeRepoRoot(String repoRoot) {
        if (StringUtils.hasText(repoRoot)) {
            return repoRoot.trim();
        }
        return Path.of("").toAbsolutePath().normalize().toString();
    }

    private String normalizeUserId(String userId) {
        if (StringUtils.hasText(userId)) {
            return userId.trim();
        }
        return "debug-user";
    }
}
