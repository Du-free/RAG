package com.example.ragdemo.dto;

public record RagSettingsRequest(
        // 文档切块大小：控制每个知识片段的目标最大长度。
        Integer chunkSize,
        // 切块重叠长度：主要用于超长段落滑窗切分时保留上下文。
        Integer chunkOverlap,
        // 初始检索数量：控制从向量库召回多少个核心候选片段。
        Integer topK,
        // 相似度阈值：低于阈值的召回片段会被过滤掉。
        Double similarityThreshold,
        // 重排序保留数量：控制最终进入回答 Prompt 的来源片段数。
        Integer rerankTopN,
        // 是否启用 LLM 重排序：启用后更准但会增加耗时和 token。
        Boolean rerankEnabled
) {
}
