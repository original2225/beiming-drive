# ============================================================
# 北冥云盘 · 多阶段构建
#   阶段 1  前端：node 构建 Vue/Vite 产物
#   阶段 2  后端：maven 打包成可执行 jar
#   阶段 3  运行：只带 JRE + jar + 前端产物，镜像小、无构建工具
# ============================================================

# ---------- 1. 前端 ----------
FROM node:20-alpine AS web
WORKDIR /build
# 先只拷依赖清单，锁文件不变时这层能命中缓存
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci --no-audit --no-fund || npm install --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------- 2. 后端 ----------
FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /build
# 依赖单独一层，改代码不用重下依赖
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
RUN mvn -B -q -DskipTests package

# ---------- 3. 运行 ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 非 root 运行；数据目录必须可写
# 注意：这里必须把「每一个会被具名卷挂载的子目录」都建出来并 chown。
# Docker 创建新具名卷时会复制镜像内该目录的属主/权限；目录不存在时卷归 root，
# 而进程以 drive 身份跑 → Lucene 写 write.lock 直接 AccessDeniedException。
RUN addgroup -S drive && adduser -S -G drive drive \
 && mkdir -p /data/blobs /data/remote /data/search-index /app/web \
 && chown -R drive:drive /data /app

COPY --from=api /build/target/beiming-drive-*.jar /app/app.jar
COPY --from=web /build/dist /app/web

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8" \
    DRIVE_WEB_DIST=/app/web \
    DRIVE_STORAGE_ROOT=/data/blobs \
    DRIVE_WORK_DIR=/data/remote \
    DRIVE_SEARCH_INDEX=/data/search-index \
    TZ=Asia/Shanghai

USER drive
VOLUME ["/data"]
EXPOSE 8140

# 容器自身健康检查，编排器可据此判定就绪
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=4 \
  CMD wget -qO- http://127.0.0.1:8140/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]