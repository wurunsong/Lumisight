package com.lumisight.api.rageval.controller;

import com.lumisight.api.rageval.dto.CodeSearchNetEvalResponse;
import com.lumisight.api.rageval.dto.CodeSearchNetEvalRunRequest;
import com.lumisight.api.rageval.support.CodeSearchNetRagEvalService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/lumisight/rag-eval")
public class RagEvalController {

    private final CodeSearchNetRagEvalService codeSearchNetRagEvalService;

    public RagEvalController(CodeSearchNetRagEvalService codeSearchNetRagEvalService) {
        this.codeSearchNetRagEvalService = codeSearchNetRagEvalService;
    }

    @PostMapping("/codesearchnet/run")
    @ResponseStatus(HttpStatus.OK)
    public CodeSearchNetEvalResponse runCodeSearchNetEval(@RequestBody CodeSearchNetEvalRunRequest request) {
        return codeSearchNetRagEvalService.run(request);
    }
}
