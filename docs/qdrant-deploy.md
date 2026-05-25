# Qdrant 部署说明

目标服务器：`47.116.133.220`

## 当前部署

```bash
mkdir -p /data/qdrant/storage
docker run -d \
  --name qdrant \
  --restart unless-stopped \
  -p 6333:6333 \
  -p 6334:6334 \
  -e QDRANT__SERVICE__API_KEY='替换为 rag-server/.env 中的 QDRANT_API_KEY' \
  -v /data/qdrant/storage:/qdrant/storage \
  docker.m.daocloud.io/qdrant/qdrant:v1.18.0
```

## 当前状态

- Docker 已安装且服务状态为 `active`。
- `/data/qdrant/storage` 已创建并挂载到容器 `/qdrant/storage`。
- Qdrant 容器 `qdrant` 已启动，版本为 `1.18.0`。
- REST `6333` 和 gRPC `6334` 已监听。
- Qdrant API Key 已启用，`/collections` 接口返回正常。
- Docker Hub 拉取 `qdrant/qdrant:latest` 超时，实际使用 `docker.m.daocloud.io/qdrant/qdrant:v1.18.0`。

## 验证命令

```bash
docker ps --filter name=qdrant
docker logs --tail 80 qdrant
curl -H "api-key: 替换为APIKey" http://127.0.0.1:6333/collections
```

## 后续建议

1. 在阿里云防火墙/安全组中仅对可信来源放行 `6333` 和 `6334`，建议使用当前本机公网 IP `/32`。
2. 如果需要升级 Qdrant，优先使用固定版本标签，不建议长期依赖 `latest`。
