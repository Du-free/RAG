package com.example.ragdemo.service;

import com.example.ragdemo.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.dao.DataAccessException;
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
import java.util.*;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatService {

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[\\s,，。！？?；;：:、]+");

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final RagSettingsService ragSettingsService;

    private final RowMapper<ChatMessageResponse> messageRowMapper = (rs, rowNum) -> new ChatMessageResponse(
            rs.getLong("id"),
            rs.getString("chat_id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getObject("create_time", LocalDateTime.class)
    );

    /**
     * 执行 RAG 问答：检索候选片段、可选 LLM 重排序、生成答案，并保存聊天记录。
     */
    public RagChatResponse ask(RagChatRequest request) {
        String question = requireText(request.question(), "问题不能为空");
        String chatId = StringUtils.hasText(request.chatId()) ? request.chatId().trim() : UUID.randomUUID().toString();

        insertMessage(chatId, "user", question);
        RagRunResult result = runRag(question);
        Long assistantMessageId = insertMessage(chatId, "assistant", result.answer());
        saveSources(assistantMessageId, result.selectedSources());
        return new RagChatResponse(chatId, result.answer(), result.selectedSources());
    }

    /**
     * 检索调试接口使用的问答流程：不写聊天历史，直接返回候选、重排序结果和最终答案。
     */
    public RagDebugResponse debug(RagDebugRequest request) {
        String question = requireText(request.question(), "问题不能为空");
        RagRunResult result = runRag(question);
        return new RagDebugResponse(question, result.candidates(), result.selectedSources(), result.answer());
    }

    /**
     * 评测模块复用的问答流程：避免污染用户会话历史。
     */
    public RagChatResponse askWithoutHistory(String question) {
        String requiredQuestion = requireText(question, "问题不能为空");
        RagRunResult result = runRag(requiredQuestion);
        return new RagChatResponse(null, result.answer(), result.selectedSources());
    }

    /**
     * 查询历史会话，按最后一条消息时间倒序返回。
     */
    public List<String> listSessions() {
        return jdbcTemplate.queryForList("""
                SELECT chat_id
                FROM rag_chat_message
                GROUP BY chat_id
                ORDER BY MAX(create_time) DESC
                """, String.class);
    }

    /**
     * 查询指定会话下的全部消息。
     */
    public List<ChatMessageResponse> listMessages(String chatId) {
        return jdbcTemplate.query("""
                SELECT id, chat_id, role, content, create_time
                FROM rag_chat_message
                WHERE chat_id = ?
                ORDER BY id ASC
                """, messageRowMapper, requireText(chatId, "会话ID不能为空"));
    }

    /**
     * 删除会话历史，仅删除数据库消息记录，不涉及文件或文件夹。
     */
    public void deleteSession(String chatId) {
        String requiredChatId = requireText(chatId, "会话ID不能为空");
        jdbcTemplate.update("""
                DELETE s FROM rag_answer_source s
                JOIN rag_chat_message m ON s.message_id = m.id
                WHERE m.chat_id = ?
                """, requiredChatId);
        jdbcTemplate.update("DELETE FROM rag_chat_message WHERE chat_id = ?", requiredChatId);
    }

    private RagRunResult runRag(String question) {
        RagSettingsResponse settings = ragSettingsService.getSettings();
        List<SourceResponse> candidates = retrieveCandidates(question, settings);
        List<SourceResponse> selectedSources = rerank(question, candidates, settings);

        String answer;
        if (selectedSources.isEmpty()) {
            answer = "当前知识库没有检索到足够相关的内容。请先上传相关资料，或换一种问法再试。";
        } else {
            answer = generateAnswer(question, selectedSources);
        }
        return new RagRunResult(candidates, selectedSources, answer);
    }

    /**
     * 多路召回：先用 Qdrant 向量召回，再用 MySQL 关键词召回补充可能漏掉的精确片段。
     */
    private List<SourceResponse> retrieveCandidates(String question, RagSettingsResponse settings) {
        int recallTopK = Math.max(settings.topK(), settings.rerankTopN()) * 2;
        Map<String, SourceResponse> merged = new LinkedHashMap<>();

        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(recallTopK)
                .similarityThreshold(settings.similarityThreshold())
                .build();
        List<Document> vectorMatches = vectorStore.similaritySearch(searchRequest);
        for (int i = 0; i < vectorMatches.size(); i++) {
            SourceResponse source = toSourceResponse(vectorMatches.get(i), i + 1, "向量召回", "vector");
            merged.putIfAbsent(source.chunkId(), source);
        }

        for (SourceResponse source : fullTextSearch(question, recallTopK)) {
            merged.putIfAbsent(source.chunkId(), source);
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * MySQL FULLTEXT 召回用于补齐向量召回漏掉的精确词命中；如果旧库没有全文索引或中文分词命中为空，再退回 LIKE 兜底。
     */
    private List<SourceResponse> fullTextSearch(String question, int limit) {
        List<String> terms = extractTerms(question);
        if (terms.isEmpty()) {
            return List.of();
        }

        String searchText = buildFullTextQuery(question, terms);
        try {
            List<SourceResponse> fullTextMatches = jdbcTemplate.query("""
                    SELECT c.document_id,
                           d.filename,
                           c.vector_id,
                           c.content,
                           c.section_title,
                           MATCH(c.content) AGAINST (? IN NATURAL LANGUAGE MODE) AS text_score
                    FROM rag_document_chunk c
                    JOIN rag_document d ON d.id = c.document_id
                    WHERE d.status = 'READY'
                      AND MATCH(c.content) AGAINST (? IN NATURAL LANGUAGE MODE) > 0
                    ORDER BY text_score DESC, c.id DESC
                    LIMIT ?
                    """, (rs, rowNum) -> new SourceResponse(
                    rs.getLong("document_id"),
                    rs.getString("filename"),
                    rs.getString("vector_id"),
                    truncate(rs.getString("content"), 900),
                    rs.getDouble("text_score"),
                    null,
                    rowNum + 1,
                    "全文召回",
                    "fulltext",
                    Objects.toString(rs.getString("section_title"), "")
            ), searchText, searchText, limit);
            if (!fullTextMatches.isEmpty()) {
                return fullTextMatches;
            }
        } catch (DataAccessException ex) {
            log.warn("MySQL FULLTEXT 召回失败，退回 LIKE 召回。原因：{}", ex.getMessage());
        }

        return likeSearch(terms, limit);
    }

    private List<SourceResponse> likeSearch(List<String> terms, int limit) {
        if (terms.isEmpty()) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder("""
                SELECT c.document_id, d.filename, c.vector_id, c.content, c.section_title
                FROM rag_document_chunk c
                JOIN rag_document d ON d.id = c.document_id
                WHERE d.status = 'READY' AND (
                """);
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("c.content LIKE ? OR d.filename LIKE ?");
            String like = "%" + terms.get(i) + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(") ORDER BY c.id DESC LIMIT ?");
        args.add(limit);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new SourceResponse(
                rs.getLong("document_id"),
                rs.getString("filename"),
                rs.getString("vector_id"),
                truncate(rs.getString("content"), 900),
                null,
                null,
                rowNum + 1,
                "关键词召回",
                "keyword",
                Objects.toString(rs.getString("section_title"), "")
        ), args.toArray());
    }

    private String buildFullTextQuery(String question, List<String> terms) {
        LinkedHashSet<String> queryTerms = new LinkedHashSet<>();
        queryTerms.add(question.trim());
        queryTerms.addAll(terms);
        return String.join(" ", queryTerms);
    }

    private List<String> extractTerms(String question) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : TOKEN_SPLITTER.split(question)) {
            String trimmed = token.trim();
            if (trimmed.length() >= 2 && terms.size() < 6) {
                terms.add(trimmed);
            }
        }
        String compact = question.replaceAll("[\\s,，。！？?；;：:、]", "").trim();
        if (compact.length() >= 4 && compact.length() <= 24) {
            terms.add(compact);
        }
        String core = compact
                .replaceAll("^(请问|请解释|解释一下|介绍一下|说明一下|什么是)", "")
                .replaceAll("(是什么|有哪些|有什么|为什么|怎么做|如何|吗|呢)$", "");
        if (core.length() >= 2 && core.length() <= 24) {
            terms.add(core);
        }
        // 中文问题通常没有空格分词，补充较长 n-gram 可让“电网潮流图是什么”命中“电网潮流图”。
        for (int size = Math.min(6, core.length()); size >= 3 && terms.size() < 10; size--) {
            for (int start = 0; start + size <= core.length() && terms.size() < 10; start++) {
                terms.add(core.substring(start, start + size));
            }
        }
        return new ArrayList<>(terms);
    }

    private List<SourceResponse> rerank(String question, List<SourceResponse> candidates, RagSettingsResponse settings) {
        int selectSize = Math.min(settings.rerankTopN(), candidates.size());
        if (selectSize == 0) {
            return List.of();
        }
        if (!settings.rerankEnabled()) {
            return assignRank(candidates.subList(0, selectSize), "未启用重排序");
        }
        if (candidates.size() <= selectSize) {
            return assignRank(candidates.subList(0, selectSize), "候选片段数量未超过保留数量，跳过 LLM 重排序");
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 RAG 检索重排序器。请根据用户问题，从候选片段中选择最相关的 ")
                .append(selectSize)
                .append(" 个片段，并只返回片段 ID，按相关性从高到低排列。\n");
        prompt.append("返回格式必须是一行 JSON 数组，例如：[\"id1\",\"id2\"]。\n\n");
        prompt.append("用户问题：").append(question).append("\n\n候选片段：\n");
        for (SourceResponse source : candidates) {
            prompt.append("ID: ").append(source.chunkId()).append("\n")
                    .append("文件: ").append(source.filename()).append("\n")
                    .append("内容: ").append(source.snippet()).append("\n\n");
        }

        try {
            String content = Objects.requireNonNullElse(chatClient.prompt()
                    .user(prompt.toString())
                    .call()
                    .content(), "");
            List<SourceResponse> ordered = orderByModelResponse(candidates, content, selectSize);
            if (!ordered.isEmpty()) {
                return assignRank(ordered, "LLM 重排序");
            }
        } catch (Exception ex) {
            log.warn("LLM 重排序失败，退回原始召回顺序。原因：{}", ex.getMessage());
        }
        return assignRank(candidates.subList(0, selectSize), "重排序失败，使用召回顺序");
    }

    private List<SourceResponse> orderByModelResponse(List<SourceResponse> candidates, String modelOutput, int selectSize) {
        List<SourceResponse> ordered = new ArrayList<>();
        for (SourceResponse candidate : candidates) {
            if (modelOutput.contains(candidate.chunkId())) {
                ordered.add(candidate);
            }
            if (ordered.size() >= selectSize) {
                break;
            }
        }
        for (SourceResponse candidate : candidates) {
            if (ordered.size() >= selectSize) {
                break;
            }
            boolean exists = ordered.stream().anyMatch(item -> item.chunkId().equals(candidate.chunkId()));
            if (!exists) {
                ordered.add(candidate);
            }
        }
        return ordered;
    }

    private List<SourceResponse> assignRank(List<SourceResponse> sources, String reason) {
        List<SourceResponse> ranked = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            SourceResponse source = sources.get(i);
            ranked.add(new SourceResponse(
                    source.documentId(),
                    source.filename(),
                    source.chunkId(),
                    source.snippet(),
                    source.score(),
                    1.0 - (i * 0.01),
                    i + 1,
                    reason,
                    source.stage(),
                    source.sectionTitle()
            ));
        }
        return ranked;
    }

    private String generateAnswer(String question, List<SourceResponse> sources) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请严格基于以下知识库来源回答用户问题。要求：\n");
        prompt.append("1. 只使用来源中明确支持的信息，不要编造来源中没有的结论。\n");
        prompt.append("2. 如果来源不足以回答，请直接说明缺口，并给出还需要补充的资料。\n");
        prompt.append("3. 答案使用中文，按要点组织，并在关键结论后标注来源编号，例如“【来源1】”。\n\n");
        prompt.append("知识库来源：\n");
        for (int i = 0; i < sources.size(); i++) {
            SourceResponse source = sources.get(i);
            prompt.append("【来源").append(i + 1).append("：")
                    .append(source.filename()).append("，分数：")
                    .append(source.score() == null ? "N/A" : String.format("%.3f", source.score()))
                    .append("】\n")
                    .append(source.snippet()).append("\n\n");
        }
        prompt.append("用户问题：").append(question);
        log.info("RAG answer prompt:\n{}", prompt);
        return Objects.requireNonNullElse(chatClient.prompt()
                .user(prompt.toString())
                .call()
                .content(), "").trim();
    }

    private SourceResponse toSourceResponse(Document document, int rank, String reason, String stage) {
        Object documentId = document.getMetadata().get("document_id");
        Object filename = document.getMetadata().get("filename");
        Object sectionTitle = document.getMetadata().get("section_title");
        return new SourceResponse(
                toLong(documentId),
                Objects.toString(filename, "unknown"),
                document.getId(),
                truncate(document.getText(), 900),
                document.getScore(),
                null,
                rank,
                reason,
                stage,
                Objects.toString(sectionTitle, "")
        );
    }

    private void saveSources(Long messageId, List<SourceResponse> sources) {
        for (SourceResponse source : sources) {
            jdbcTemplate.update("""
                    INSERT INTO rag_answer_source(message_id, document_id, filename, chunk_id, snippet, score)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, messageId, source.documentId(), source.filename(), source.chunkId(), source.snippet(), source.score());
        }
    }

    private Long insertMessage(String chatId, String role, String content) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO rag_chat_message(chat_id, role, content)
                    VALUES (?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, chatId);
            ps.setString(2, role);
            ps.setString(3, content);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "保存聊天记录失败");
        }
        return key.longValue();
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(BAD_REQUEST, message);
        }
        return value.trim();
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return Objects.requireNonNullElse(text, "");
        }
        return text.substring(0, maxLength) + "...";
    }

    private record RagRunResult(
            List<SourceResponse> candidates,
            List<SourceResponse> selectedSources,
            String answer
    ) {
    }
}
