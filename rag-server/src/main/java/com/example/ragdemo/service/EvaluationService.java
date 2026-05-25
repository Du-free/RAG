package com.example.ragdemo.service;

import com.example.ragdemo.dto.EvaluationCaseRequest;
import com.example.ragdemo.dto.EvaluationCaseResponse;
import com.example.ragdemo.dto.EvaluationResultResponse;
import com.example.ragdemo.dto.EvaluationRunResponse;
import com.example.ragdemo.dto.RagChatResponse;
import com.example.ragdemo.dto.SourceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final JdbcTemplate jdbcTemplate;
    private final RagChatService ragChatService;

    private final RowMapper<EvaluationCaseResponse> caseRowMapper = (rs, rowNum) -> new EvaluationCaseResponse(
            rs.getLong("id"),
            rs.getString("question"),
            rs.getString("expected_document"),
            rs.getString("reference_answer"),
            rs.getString("expected_keywords"),
            rs.getObject("create_time", LocalDateTime.class)
    );

    /**
     * 查看评测问题集，用于持续对比不同检索参数下的效果。
     */
    public List<EvaluationCaseResponse> listCases() {
        return jdbcTemplate.query("""
                SELECT id, question, expected_document, reference_answer, expected_keywords, create_time
                FROM rag_evaluation_case
                ORDER BY id ASC
                """, caseRowMapper);
    }

    /**
     * 新增一条评测问题；expectedKeywords 使用逗号分隔，便于做轻量答案覆盖判断。
     */
    public EvaluationCaseResponse createCase(EvaluationCaseRequest request) {
        String question = requireText(request.question(), "评测问题不能为空");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO rag_evaluation_case(question, expected_document, reference_answer, expected_keywords)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, question);
            ps.setString(2, trimToNull(request.expectedDocument()));
            ps.setString(3, trimToNull(request.referenceAnswer()));
            ps.setString(4, trimToNull(request.expectedKeywords()));
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "创建评测用例失败");
        }
        return getCase(key.longValue());
    }

    /**
     * 删除用例库中的评测问题；历史评测结果保留问题和答案快照，不随用例一起删除。
     */
    public void deleteCase(Long id) {
        int affectedRows = jdbcTemplate.update("""
                DELETE FROM rag_evaluation_case
                WHERE id = ?
                """, id);
        if (affectedRows == 0) {
            throw new ResponseStatusException(NOT_FOUND, "评测用例不存在");
        }
    }

    /**
     * 运行一次全量评测，并把每道题的答案、来源和轻量指标保存下来。
     */
    public EvaluationRunResponse runEvaluation() {
        List<EvaluationCaseResponse> cases = listCases();
        if (cases.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "暂无评测用例");
        }

        List<EvaluationResultResponse> results = cases.stream().map(this::runCase).toList();
        double hitRate = rate(results.stream().filter(EvaluationResultResponse::hit).count(), results.size());
        double sourceCoverageRate = rate(results.stream().filter(EvaluationResultResponse::sourceCovered).count(), results.size());
        double answerKeywordRate = rate(results.stream().filter(EvaluationResultResponse::keywordMatched).count(), results.size());

        Long runId = insertRun(results.size(), hitRate, sourceCoverageRate, answerKeywordRate);
        for (EvaluationResultResponse result : results) {
            insertResult(runId, result);
        }
        return getRun(runId);
    }

    public List<EvaluationRunResponse> listRuns() {
        return jdbcTemplate.query("""
                SELECT id, total_cases, hit_rate, source_coverage_rate, answer_keyword_rate, create_time
                FROM rag_evaluation_run
                ORDER BY id DESC
                LIMIT 20
                """, (rs, rowNum) -> new EvaluationRunResponse(
                rs.getLong("id"),
                rs.getInt("total_cases"),
                rs.getDouble("hit_rate"),
                rs.getDouble("source_coverage_rate"),
                rs.getDouble("answer_keyword_rate"),
                rs.getObject("create_time", LocalDateTime.class),
                listResults(rs.getLong("id"))
        ));
    }

    private EvaluationResultResponse runCase(EvaluationCaseResponse testCase) {
        RagChatResponse response = ragChatService.askWithoutHistory(testCase.question());
        List<SourceResponse> sources = Objects.requireNonNullElse(response.sources(), List.of());
        String matchedSources = sources.stream()
                .map(SourceResponse::filename)
                .distinct()
                .collect(Collectors.joining(", "));
        boolean hit = !sources.isEmpty();
        boolean sourceCovered = !StringUtils.hasText(testCase.expectedDocument())
                || sources.stream().anyMatch(source -> source.filename().contains(testCase.expectedDocument()));
        boolean keywordMatched = keywordsMatched(response.answer(), testCase.expectedKeywords());

        return new EvaluationResultResponse(
                testCase.id(),
                testCase.question(),
                response.answer(),
                hit,
                sourceCovered,
                keywordMatched,
                testCase.expectedDocument(),
                matchedSources,
                testCase.referenceAnswer()
        );
    }

    private Long insertRun(int totalCases, double hitRate, double sourceCoverageRate, double answerKeywordRate) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO rag_evaluation_run(total_cases, hit_rate, source_coverage_rate, answer_keyword_rate)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, totalCases);
            ps.setDouble(2, hitRate);
            ps.setDouble(3, sourceCoverageRate);
            ps.setDouble(4, answerKeywordRate);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "保存评测运行失败");
        }
        return key.longValue();
    }

    private void insertResult(Long runId, EvaluationResultResponse result) {
        jdbcTemplate.update("""
                INSERT INTO rag_evaluation_result(
                    run_id, case_id, question, answer, hit, source_covered, keyword_matched,
                    expected_document, matched_sources, reference_answer
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                runId,
                result.caseId(),
                result.question(),
                result.answer(),
                result.hit(),
                result.sourceCovered(),
                result.keywordMatched(),
                result.expectedDocument(),
                result.matchedSources(),
                result.referenceAnswer()
        );
    }

    private EvaluationRunResponse getRun(Long runId) {
        return jdbcTemplate.queryForObject("""
                SELECT id, total_cases, hit_rate, source_coverage_rate, answer_keyword_rate, create_time
                FROM rag_evaluation_run
                WHERE id = ?
                """, (rs, rowNum) -> new EvaluationRunResponse(
                rs.getLong("id"),
                rs.getInt("total_cases"),
                rs.getDouble("hit_rate"),
                rs.getDouble("source_coverage_rate"),
                rs.getDouble("answer_keyword_rate"),
                rs.getObject("create_time", LocalDateTime.class),
                listResults(runId)
        ), runId);
    }

    private List<EvaluationResultResponse> listResults(Long runId) {
        return jdbcTemplate.query("""
                SELECT case_id, question, answer, hit, source_covered, keyword_matched,
                       expected_document, matched_sources, reference_answer
                FROM rag_evaluation_result
                WHERE run_id = ?
                ORDER BY id ASC
                """, (rs, rowNum) -> new EvaluationResultResponse(
                rs.getLong("case_id"),
                rs.getString("question"),
                rs.getString("answer"),
                rs.getBoolean("hit"),
                rs.getBoolean("source_covered"),
                rs.getBoolean("keyword_matched"),
                rs.getString("expected_document"),
                rs.getString("matched_sources"),
                rs.getString("reference_answer")
        ), runId);
    }

    private EvaluationCaseResponse getCase(Long id) {
        return jdbcTemplate.queryForObject("""
                SELECT id, question, expected_document, reference_answer, expected_keywords, create_time
                FROM rag_evaluation_case
                WHERE id = ?
                """, caseRowMapper, id);
    }

    private boolean keywordsMatched(String answer, String expectedKeywords) {
        if (!StringUtils.hasText(expectedKeywords)) {
            return true;
        }
        String safeAnswer = Objects.requireNonNullElse(answer, "");
        return Arrays.stream(expectedKeywords.split("[,，]"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .allMatch(safeAnswer::contains);
    }

    private double rate(long matched, int total) {
        return total == 0 ? 0 : Math.round((matched * 10000.0 / total)) / 100.0;
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
