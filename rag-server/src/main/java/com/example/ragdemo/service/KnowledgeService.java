package com.example.ragdemo.service;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.dto.DocumentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.*;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

    /**
     * 智谱 Embedding 接口限制单次 input 数组最多 64 条；向量入库时必须分批提交。
     */
    private static final int EMBEDDING_BATCH_SIZE = 64;
    private static final String SUPPORTED_FILE_TYPES_LABEL = "PDF/TXT/MD/DOCX/XLSX/CSV/PPTX";
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "pdf",
            "txt",
            "md",
            "markdown",
            "docx",
            "xlsx",
            "csv",
            "pptx"
    );
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6}\\s+.+|第[一二三四五六七八九十百千0-9]+[章节篇部分].*|[一二三四五六七八九十]+、.+|\\d+\\.\\d+(\\.\\d+)*[、.\\s].+)$");
    private static final Pattern LIST_ITEM_PATTERN = Pattern.compile("^([\\-*>•·]\\s+|\\d+[.)、]\\s*|[一二三四五六七八九十]+[.)、]\\s*).+");

    private final JdbcTemplate jdbcTemplate;
    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final RagSettingsService ragSettingsService;

    private final RowMapper<DocumentResponse> documentRowMapper = (rs, rowNum) -> new DocumentResponse(
            rs.getLong("id"),
            rs.getString("filename"),
            rs.getString("content_type"),
            rs.getLong("file_size"),
            rs.getString("status"),
            rs.getInt("chunk_count"),
            rs.getString("error_message"),
            rs.getObject("create_time", LocalDateTime.class),
            rs.getObject("update_time", LocalDateTime.class)
    );

    /**
     * 上传文档并导入知识库：保存原文件、解析文本、分块、写入 Qdrant，并记录 MySQL 元数据。
     */
    public DocumentResponse upload(MultipartFile file) {
        validateFile(file);
        Path savedPath = saveUpload(file);
        Long documentId = insertDocument(file, savedPath);

        try {
            List<Document> chunks = splitDocument(documentId, file.getOriginalFilename(), savedPath);
            addVectorsInBatches(chunks);
            insertChunks(documentId, chunks);
            jdbcTemplate.update("""
                    UPDATE rag_document
                    SET status = 'READY', chunk_count = ?, error_message = NULL
                    WHERE id = ?
                    """, chunks.size(), documentId);
            return getDocument(documentId);
        } catch (Exception ex) {
            jdbcTemplate.update("""
                    UPDATE rag_document
                    SET status = 'FAILED', error_message = ?
                    WHERE id = ?
                    """, ex.getMessage(), documentId);
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "文档导入失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 查询可见文档列表，逻辑删除的文档不再展示。
     */
    public List<DocumentResponse> listDocuments() {
        return jdbcTemplate.query("""
                SELECT id, filename, content_type, file_size, status, chunk_count, error_message, create_time, update_time
                FROM rag_document
                WHERE status <> 'DELETED'
                ORDER BY create_time DESC
                """, documentRowMapper);
    }

    /**
     * 删除知识库文档：清理 Qdrant 向量和分块记录，但不物理删除上传文件。
     */
    public void deleteDocument(Long documentId) {
        ensureDocumentExists(documentId);
        List<String> vectorIds = jdbcTemplate.queryForList(
                "SELECT vector_id FROM rag_document_chunk WHERE document_id = ?",
                String.class,
                documentId
        );

        if (!vectorIds.isEmpty()) {
            vectorStore.delete(vectorIds);
        }

        jdbcTemplate.update("DELETE FROM rag_document_chunk WHERE document_id = ?", documentId);
        jdbcTemplate.update("""
                UPDATE rag_document
                SET status = 'DELETED', chunk_count = 0
                WHERE id = ?
                """, documentId);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "上传文件不能为空");
        }

        String extension = getExtension(file.getOriginalFilename());
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(BAD_REQUEST, "仅支持 " + SUPPORTED_FILE_TYPES_LABEL + " 文件");
        }
    }

    private Path saveUpload(MultipartFile file) {
        try {
            Path uploadDir = Paths.get(Objects.requireNonNullElse(ragProperties.uploadDir(), "uploads/knowledge"))
                    .toAbsolutePath()
                    .normalize();
            Files.createDirectories(uploadDir);

            String extension = getExtension(file.getOriginalFilename());
            String savedName = UUID.randomUUID() + "." + extension;
            Path savedPath = uploadDir.resolve(savedName).normalize();

            if (!savedPath.startsWith(uploadDir)) {
                throw new ResponseStatusException(BAD_REQUEST, "文件名不合法");
            }

            file.transferTo(savedPath);
            return savedPath;
        } catch (IOException ex) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "保存上传文件失败", ex);
        }
    }

    private Long insertDocument(MultipartFile file, Path savedPath) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO rag_document(filename, content_type, file_size, storage_path, status)
                    VALUES (?, ?, ?, ?, 'PROCESSING')
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename())));
            ps.setString(2, file.getContentType());
            ps.setLong(3, file.getSize());
            ps.setString(4, savedPath.toString());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "创建文档记录失败");
        }
        return key.longValue();
    }

    private List<Document> splitDocument(Long documentId, String filename, Path savedPath) {
        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(savedPath));
        List<Document> parsedDocuments = reader.get();
        String text = parsedDocuments.stream()
                .map(Document::getText)
                .filter(StringUtils::hasText)
                .reduce("", (left, right) -> left + "\n\n" + right)
                .trim();
        var settings = ragSettingsService.getSettings();
        List<TextChunk> splitDocuments = splitText(text, settings.chunkSize(), settings.chunkOverlap());

        List<Document> chunks = new ArrayList<>();
        for (int i = 0; i < splitDocuments.size(); i++) {
            TextChunk source = splitDocuments.get(i);
            String vectorId = UUID.randomUUID().toString();

            // 为每个分块写入稳定元数据，后续检索命中后可回显来源文件、片段和分块序号。
            chunks.add(Document.builder()
                    .id(vectorId)
                    .text(source.text())
                    .metadata(Map.of(
                            "document_id", documentId,
                            "filename", Objects.requireNonNullElse(filename, "unknown"),
                            "chunk_index", i,
                            "chunk_size", settings.chunkSize(),
                            "chunk_overlap", settings.chunkOverlap(),
                            "section_title", source.sectionTitle(),
                            "split_strategy", source.strategy()
                    ))
                    .build());
        }
        return chunks;
    }

    /**
     * 分批写入向量库，避免 Embedding 服务一次接收超过 64 条文本导致 HTTP 400。
     */
    private void addVectorsInBatches(List<Document> chunks) {
        for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, chunks.size());
            vectorStore.add(chunks.subList(start, end));
        }
    }

    /**
     * 结构化优先切块：优先保留标题和段落完整性；只有段落组过长时才使用滑窗切分。
     */
    private List<TextChunk> splitText(String text, int chunkSize, int chunkOverlap) {
        if (!StringUtils.hasText(text)) {
            throw new ResponseStatusException(BAD_REQUEST, "文档没有解析到可导入的文本内容");
        }

        List<TextChunk> chunks = new ArrayList<>();
        List<ParagraphBlock> blocks = parseParagraphBlocks(text);
        StringBuilder buffer = new StringBuilder();
        String currentSection = "";
        String bufferSection = "";
        String bufferStrategy = "paragraph";
        int safeChunkSize = Math.max(chunkSize, 200);
        int safeOverlap = Math.max(Math.min(chunkOverlap, safeChunkSize - 1), 0);

        for (ParagraphBlock block : blocks) {
            if (block.heading()) {
                flushBuffer(chunks, buffer, bufferSection, bufferStrategy);
                bufferSection = "";
                bufferStrategy = "paragraph";
            }

            if (StringUtils.hasText(block.sectionTitle())) {
                currentSection = block.sectionTitle();
            }

            String blockStrategy = determineBlockStrategy(block);
            if (!buffer.isEmpty() && !Objects.equals(bufferStrategy, blockStrategy)) {
                flushBuffer(chunks, buffer, bufferSection, bufferStrategy);
                bufferSection = "";
            }

            if (block.text().length() > safeChunkSize) {
                flushBuffer(chunks, buffer, bufferSection, bufferStrategy);
                if (block.tableLike()) {
                    chunks.addAll(splitTableRows(block.text(), safeChunkSize, currentSection));
                } else {
                    chunks.addAll(slidingWindow(block.text(), safeChunkSize, safeOverlap, currentSection, blockStrategy + "-sliding-window"));
                }
                bufferSection = "";
                continue;
            }

            String candidate = buffer.isEmpty() ? block.text() : buffer + "\n\n" + block.text();
            if (candidate.length() > safeChunkSize) {
                flushBuffer(chunks, buffer, bufferSection, bufferStrategy);
                buffer.append(block.text());
                bufferSection = currentSection;
            } else {
                if (!buffer.isEmpty()) {
                    buffer.append("\n\n");
                }
                buffer.append(block.text());
                if (!StringUtils.hasText(bufferSection)) {
                    bufferSection = currentSection;
                }
            }
            bufferStrategy = blockStrategy;
        }
        flushBuffer(chunks, buffer, bufferSection, bufferStrategy);
        return chunks;
    }

    /**
     * 将文本先拆成段落块，同时识别常见中文章节标题、Markdown 标题和数字标题。
     */
    private List<ParagraphBlock> parseParagraphBlocks(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> parts = splitSemanticParts(normalized);
        List<ParagraphBlock> blocks = new ArrayList<>();
        String currentSection = "";
        for (String part : parts) {
            String paragraph = part.trim();
            if (!StringUtils.hasText(paragraph)) {
                continue;
            }
            String firstLine = paragraph.lines().findFirst().orElse("").trim();
            boolean heading = isHeading(firstLine);
            boolean tableLike = isTableLike(paragraph);
            boolean codeLike = isCodeLike(paragraph);
            boolean listLike = isListLike(paragraph);
            if (heading) {
                currentSection = cleanHeading(firstLine);
            }
            blocks.add(new ParagraphBlock(paragraph, currentSection, heading, tableLike, codeLike, listLike));
        }
        return blocks;
    }

    /**
     * Tika 解析 DOCX 时经常只用单换行分隔标题和正文；这里在标题行出现时主动断开，
     * 避免“2.2 小节内容”被并入前后多个小节形成过大的混合 chunk。
     */
    private List<String> splitSemanticParts(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        boolean pendingTableBlank = false;
        boolean inCodeFence = false;
        for (String line : text.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (!buffer.isEmpty()) {
                    buffer.append("\n");
                }
                buffer.append(line.stripTrailing());
                inCodeFence = !inCodeFence;
                pendingTableBlank = false;
                continue;
            }
            if (inCodeFence) {
                if (!buffer.isEmpty()) {
                    buffer.append("\n");
                }
                buffer.append(line.stripTrailing());
                continue;
            }
            if (!StringUtils.hasText(trimmed)) {
                if (looksLikeOpenTable(buffer)) {
                    pendingTableBlank = true;
                } else {
                    flushPart(parts, buffer);
                }
                continue;
            }
            if (pendingTableBlank) {
                if (isPotentialTableLine(trimmed)) {
                    buffer.append("\n\n");
                } else {
                    flushPart(parts, buffer);
                }
                pendingTableBlank = false;
            }
            if (isHeading(trimmed)) {
                flushPart(parts, buffer);
            }
            if (!buffer.isEmpty()) {
                buffer.append("\n");
            }
            buffer.append(trimmed);
        }
        flushPart(parts, buffer);
        return parts;
    }

    private void flushPart(List<String> parts, StringBuilder buffer) {
        String value = buffer.toString().trim();
        if (StringUtils.hasText(value)) {
            parts.add(value);
            buffer.setLength(0);
        }
    }

    private boolean isHeading(String line) {
        return line.length() <= 80 && HEADING_PATTERN.matcher(line).matches();
    }

    private String cleanHeading(String heading) {
        return heading.replaceFirst("^#{1,6}\\s+", "").trim();
    }

    private boolean isTableLike(String paragraph) {
        String[] lines = paragraph.split("\\R");
        int tabbedLines = 0;
        int shortCellLines = 0;
        int tableishLines = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }
            if (line.indexOf('\t') >= 0) {
                tabbedLines++;
            }
            if (isPotentialTableLine(trimmed)) {
                tableishLines++;
            }
            if (trimmed.length() <= 24 && !isHeading(trimmed)) {
                shortCellLines++;
            }
        }
        return tabbedLines >= 1 || tableishLines >= 3 || lines.length >= 3 && shortCellLines >= 2;
    }

    private boolean isPotentialTableLine(String line) {
        return line.indexOf('\t') >= 0
                || line.contains("|")
                || line.length() <= 36 && !isHeading(line) && !LIST_ITEM_PATTERN.matcher(line).matches()
                && "。！？；.!?;".indexOf(line.charAt(line.length() - 1)) < 0;
    }

    private boolean looksLikeOpenTable(StringBuilder buffer) {
        String value = buffer.toString().trim();
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String[] lines = value.split("\\R");
        int from = Math.max(0, lines.length - 4);
        int tableishLines = 0;
        for (int i = from; i < lines.length; i++) {
            String line = lines[i].trim();
            if (StringUtils.hasText(line) && isPotentialTableLine(line)) {
                tableishLines++;
            }
        }
        return tableishLines >= 2;
    }

    private boolean isCodeLike(String paragraph) {
        String trimmed = paragraph.trim();
        return trimmed.startsWith("```")
                || trimmed.contains(" class ")
                || trimmed.contains(" public ")
                || trimmed.contains(" private ")
                || trimmed.contains(" return ")
                || trimmed.lines().filter(line -> line.startsWith("    ") || line.startsWith("\t")).count() >= 2;
    }

    private boolean isListLike(String paragraph) {
        long listLines = paragraph.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(line -> LIST_ITEM_PATTERN.matcher(line).matches())
                .count();
        return listLines >= 2;
    }

    private String determineBlockStrategy(ParagraphBlock block) {
        if (block.codeLike()) {
            return "code";
        }
        if (block.tableLike()) {
            return "table";
        }
        if (block.listLike()) {
            return "list";
        }
        return "paragraph";
    }

    private void flushBuffer(List<TextChunk> chunks, StringBuilder buffer, String sectionTitle, String strategy) {
        String text = buffer.toString().trim();
        if (StringUtils.hasText(text)) {
            chunks.add(new TextChunk(text, Objects.requireNonNullElse(sectionTitle, ""), strategy));
            buffer.setLength(0);
        }
    }

    /**
     * 对单个超长段落做滑窗切分；切分点尽量回退到句号、换行或空格附近，避免从句子中间硬断。
     */
    private List<TextChunk> slidingWindow(String text, int chunkSize, int overlap, String sectionTitle, String strategy) {
        List<TextChunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int preferredEnd = Math.min(start + chunkSize, text.length());
            int end = findNaturalBoundary(text, start, preferredEnd);
            String chunk = text.substring(start, end).trim();
            if (StringUtils.hasText(chunk)) {
                chunks.add(new TextChunk(chunk, Objects.requireNonNullElse(sectionTitle, ""), strategy));
            }
            if (end >= text.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    /**
     * 表格块过长时按行组拆分，尽量避免把同一行或相邻单元格从中间切开。
     */
    private List<TextChunk> splitTableRows(String text, int chunkSize, String sectionTitle) {
        List<TextChunk> chunks = new ArrayList<>();
        String[] rowGroups = text.split("\\n\\s*\\n");
        StringBuilder buffer = new StringBuilder();
        for (String rowGroup : rowGroups) {
            String row = rowGroup.trim();
            if (!StringUtils.hasText(row)) {
                continue;
            }
            String candidate = buffer.isEmpty() ? row : buffer + "\n\n" + row;
            if (!buffer.isEmpty() && candidate.length() > chunkSize) {
                flushBuffer(chunks, buffer, sectionTitle, "table-row-group");
            }
            if (row.length() > chunkSize) {
                chunks.addAll(slidingWindow(row, chunkSize, 0, sectionTitle, "table-sliding-window"));
            } else {
                if (!buffer.isEmpty()) {
                    buffer.append("\n\n");
                }
                buffer.append(row);
            }
        }
        flushBuffer(chunks, buffer, sectionTitle, "table-row-group");
        return chunks;
    }

    private int findNaturalBoundary(String text, int start, int preferredEnd) {
        if (preferredEnd >= text.length()) {
            return text.length();
        }

        int minEnd = Math.min(start + 200, preferredEnd);
        for (int i = preferredEnd; i > minEnd; i--) {
            if (i >= 2 && text.charAt(i - 1) == '\n' && text.charAt(i - 2) == '\n') {
                return i;
            }
        }
        for (int i = preferredEnd; i > minEnd; i--) {
            char value = text.charAt(i - 1);
            if ("。！？；.!?;\n ".indexOf(value) >= 0) {
                return i;
            }
        }
        return preferredEnd;
    }

    private void insertChunks(Long documentId, List<Document> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            jdbcTemplate.update("""
                            INSERT INTO rag_document_chunk(document_id, vector_id, chunk_index, content, section_title, split_strategy)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    documentId,
                    chunk.getId(),
                    i,
                    chunk.getText(),
                    Objects.toString(chunk.getMetadata().get("section_title"), ""),
                    Objects.toString(chunk.getMetadata().get("split_strategy"), "paragraph"));
        }
    }

    private DocumentResponse getDocument(Long documentId) {
        return jdbcTemplate.queryForObject("""
                SELECT id, filename, content_type, file_size, status, chunk_count, error_message, create_time, update_time
                FROM rag_document
                WHERE id = ?
                """, documentRowMapper, documentId);
    }

    private void ensureDocumentExists(Long documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_document WHERE id = ? AND status <> 'DELETED'",
                Integer.class,
                documentId
        );
        if (count == null || count == 0) {
            throw new ResponseStatusException(NOT_FOUND, "文档不存在");
        }
    }

    private String getExtension(String filename) {
        String extension = StringUtils.getFilenameExtension(StringUtils.cleanPath(Objects.requireNonNullElse(filename, "")));
        return Objects.requireNonNullElse(extension, "").toLowerCase(Locale.ROOT);
    }

    private record ParagraphBlock(String text, String sectionTitle, boolean heading, boolean tableLike,
                                  boolean codeLike, boolean listLike) {
    }

    private record TextChunk(String text, String sectionTitle, String strategy) {
    }
}
