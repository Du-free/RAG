package com.example.ragdemo.controller;

import com.example.ragdemo.dto.EvaluationCaseRequest;
import com.example.ragdemo.dto.EvaluationCaseResponse;
import com.example.ragdemo.dto.EvaluationRunResponse;
import com.example.ragdemo.service.EvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/evaluation")
@RequiredArgsConstructor
public class EvaluationController {

    private final EvaluationService evaluationService;

    @PostMapping("/cases")
    public EvaluationCaseResponse createCase(@RequestBody EvaluationCaseRequest request) {
        return evaluationService.createCase(request);
    }

    @GetMapping("/cases")
    public List<EvaluationCaseResponse> listCases() {
        return evaluationService.listCases();
    }

    @PostMapping("/runs")
    public EvaluationRunResponse runEvaluation() {
        return evaluationService.runEvaluation();
    }

    @GetMapping("/runs")
    public List<EvaluationRunResponse> listRuns() {
        return evaluationService.listRuns();
    }
}
