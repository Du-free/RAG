# RAG 检索增强 Demo

本项目是一个本地知识库问答 Demo，后端使用 Spring Boot + Spring AI，前端使用 React/Vite，向量库使用 Qdrant，业务元数据和聊天历史存入 MySQL。

## 目录

- `rag-server`：后端服务，默认端口 `8083`
- `rag-web`：前端界面，默认请求 `http://localhost:8083`

## 环境变量

后端会优先读取 `D:/work/AIProject/spring-ai/chatBot/.env`，也支持在 `rag-server/.env` 或项目根目录 `.env` 中覆盖：

```properties
ZHI_PU_API_KEY=你的智谱APIKey
MYSQL_USERNAME=你的MySQL用户名
MYSQL_PASSWORD=你的MySQL密码
QDRANT_API_KEY=你的Qdrant API Key
```

## Maven 本地仓库

构建时统一使用 D 盘 Maven 仓库：

```powershell
$env:MAVEN_USER_HOME='D:\work\Java\maven'
$env:MAVEN_OPTS='-Dmaven.repo.local=D:\work\Java\maven\maven_lib'
D:\work\AIProject\spring-ai\mvnw.cmd -f D:\work\AIProject\RAG\pom.xml -pl rag-server spring-boot:run
```

如果依赖缺失需要联网下载，请先确认后再执行。

## 前端启动

```powershell
cd D:\work\AIProject\RAG\rag-web
npm install
npm run dev
```

访问 `http://localhost:5173`。

## 后端构建

```powershell
$env:MAVEN_USER_HOME='D:\work\Java\maven'
$env:MAVEN_OPTS='-Dmaven.repo.local=D:\work\Java\maven\maven_lib'
D:\work\AIProject\spring-ai\mvnw.cmd -f D:\work\AIProject\RAG\pom.xml -pl rag-server -DskipTests package
```

## Qdrant

计划部署在 `47.116.133.220`，数据目录为 `/data/qdrant`，开放 `6333/6334` 并启用 API Key。启动 Docker 容器和修改服务器配置前需要单独确认。

当前 Docker Hub 拉取 `qdrant/qdrant:latest` 失败，需要先提供可用镜像加速器或允许配置 Docker daemon 镜像源。
