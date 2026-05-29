CREATE TABLE IF NOT EXISTS rag_user (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  username VARCHAR(64) NOT NULL COMMENT '登录用户名',
  password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt密码哈希',
  role VARCHAR(32) NOT NULL COMMENT '角色 ADMIN/USER',
  enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_rag_user_username (username),
  KEY idx_rag_user_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG用户表';

CREATE TABLE IF NOT EXISTS rag_document (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  filename VARCHAR(255) NOT NULL COMMENT '原始文件名',
  content_type VARCHAR(128) NULL COMMENT '文件MIME类型',
  file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小',
  storage_path VARCHAR(512) NOT NULL COMMENT '本地保存路径',
  status VARCHAR(32) NOT NULL COMMENT '处理状态',
  chunk_count INT NOT NULL DEFAULT 0 COMMENT '分块数量',
  error_message TEXT NULL COMMENT '失败原因',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_rag_document_status (status),
  KEY idx_rag_document_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG知识库文档表';

CREATE TABLE IF NOT EXISTS rag_document_chunk (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  document_id BIGINT NOT NULL COMMENT '文档ID',
  vector_id VARCHAR(96) NOT NULL COMMENT 'Qdrant向量ID',
  chunk_index INT NOT NULL COMMENT '分块序号',
  content MEDIUMTEXT NOT NULL COMMENT '分块内容',
  section_title VARCHAR(255) NULL COMMENT '分块所属章节标题',
  split_strategy VARCHAR(64) NULL COMMENT '分块策略，如 paragraph/table/code',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_rag_chunk_vector_id (vector_id),
  KEY idx_rag_chunk_document_id (document_id),
  FULLTEXT KEY ft_rag_chunk_content (content)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG文档分块表';

CREATE TABLE IF NOT EXISTS rag_chat_message (
  user_id BIGINT NULL COMMENT '用户ID',
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  chat_id VARCHAR(64) NOT NULL COMMENT '会话ID',
  role VARCHAR(32) NOT NULL COMMENT '消息角色 user/assistant',
  content MEDIUMTEXT NOT NULL COMMENT '消息内容',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_rag_chat_user_id (user_id),
  KEY idx_rag_chat_id (chat_id),
  KEY idx_rag_chat_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG聊天消息表';

CREATE TABLE IF NOT EXISTS rag_chat_session (
  user_id BIGINT NULL COMMENT '用户ID',
  chat_id VARCHAR(64) NOT NULL COMMENT '会话ID',
  title VARCHAR(64) NOT NULL COMMENT '会话标题',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (chat_id),
  KEY idx_rag_session_user_id (user_id),
  KEY idx_rag_session_update_time (update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG聊天会话表';

CREATE TABLE IF NOT EXISTS rag_answer_source (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  message_id BIGINT NOT NULL COMMENT '助手消息ID',
  document_id BIGINT NULL COMMENT '文档ID',
  filename VARCHAR(255) NOT NULL COMMENT '来源文件名',
  chunk_id VARCHAR(96) NOT NULL COMMENT '向量分块ID',
  snippet MEDIUMTEXT NOT NULL COMMENT '命中的文本片段',
  score DOUBLE NULL COMMENT '相似度分数',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_rag_source_message_id (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG回答来源表';

CREATE TABLE IF NOT EXISTS rag_runtime_setting (
  setting_key VARCHAR(64) NOT NULL COMMENT '参数键',
  setting_value VARCHAR(255) NOT NULL COMMENT '参数值',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG运行时参数表';

CREATE TABLE IF NOT EXISTS rag_evaluation_case (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  question VARCHAR(512) NOT NULL COMMENT '评测问题',
  expected_document VARCHAR(255) NULL COMMENT '期望命中的文件名',
  reference_answer TEXT NULL COMMENT '参考答案',
  expected_keywords VARCHAR(512) NULL COMMENT '答案应包含的关键词，逗号分隔',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_rag_eval_question (question)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG评测用例表';

CREATE TABLE IF NOT EXISTS rag_evaluation_run (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  total_cases INT NOT NULL COMMENT '评测用例数量',
  hit_rate DOUBLE NOT NULL COMMENT '检索命中率',
  source_coverage_rate DOUBLE NOT NULL COMMENT '来源覆盖率',
  answer_keyword_rate DOUBLE NOT NULL COMMENT '答案关键词覆盖率',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_rag_eval_run_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG评测运行表';

CREATE TABLE IF NOT EXISTS rag_evaluation_result (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  run_id BIGINT NOT NULL COMMENT '评测运行ID',
  case_id BIGINT NOT NULL COMMENT '评测用例ID',
  question VARCHAR(512) NOT NULL COMMENT '评测问题',
  answer MEDIUMTEXT NOT NULL COMMENT '模型答案',
  hit TINYINT(1) NOT NULL COMMENT '是否有来源命中',
  source_covered TINYINT(1) NOT NULL COMMENT '是否覆盖期望文件',
  keyword_matched TINYINT(1) NOT NULL COMMENT '答案是否包含期望关键词',
  expected_document VARCHAR(255) NULL COMMENT '期望命中的文件名',
  matched_sources TEXT NULL COMMENT '实际命中的来源文件名',
  reference_answer TEXT NULL COMMENT '参考答案',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_rag_eval_result_run_id (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG评测结果表';
