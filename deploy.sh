#!/usr/bin/env bash
# 北冥云盘 · 服务器一键部署 / 更新
#
# 用法：
#   ./deploy.sh            首次部署（会检查 .env，缺了就生成）
#   ./deploy.sh update     拉代码后重建并重启
#   ./deploy.sh status     看状态
#   ./deploy.sh logs       跟踪后端日志
#
# 需要：docker + docker compose（插件版，非 docker-compose 老命令）

set -euo pipefail
cd "$(dirname "$0")"

COMPOSE="docker compose -f compose.prod.yml"
CMD="${1:-up}"

need_docker() {
  command -v docker >/dev/null 2>&1 || { echo "[!] 没装 docker"; exit 1; }
  docker compose version >/dev/null 2>&1 || { echo "[!] 缺 docker compose 插件"; exit 1; }
}

ensure_env() {
  if [ ! -f .env ]; then
    echo "[i] 没找到 .env，从模板生成一份（密钥随机）"
    cp .env.example .env
    # 用 openssl 生成真随机；没有就退回 /dev/urandom
    if command -v openssl >/dev/null 2>&1; then
      SECRET=$(openssl rand -hex 32)
      PGPW=$(openssl rand -hex 16)
    else
      SECRET=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
      PGPW=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
    fi
    # GNU sed 和 BSD sed 的 -i 语法不同，这里用临时文件最稳
    sed "s|^DRIVE_JWT_SECRET=.*|DRIVE_JWT_SECRET=${SECRET}|; s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=${PGPW}|" \
      .env.example > .env.tmp && mv .env.tmp .env
    chmod 600 .env
    echo "[✓] .env 已生成，密码和 JWT 都是随机的，位置：$(pwd)/.env"
  fi

  # 还没改过的占位值必须拦下来，否则等于用公开密码上线
  if grep -qE "^(POSTGRES_PASSWORD|DRIVE_JWT_SECRET)=change-me" .env; then
    echo "[!] .env 里还是模板占位值，先改成真实值再启动"
    exit 1
  fi
  # JWT 太短后端会直接启动失败，提前说清楚
  LEN=$(grep '^DRIVE_JWT_SECRET=' .env | cut -d= -f2- | wc -c)
  if [ "$LEN" -lt 32 ]; then
    echo "[!] DRIVE_JWT_SECRET 不足 32 字节，后端会拒绝启动"
    exit 1
  fi
}

case "$CMD" in
  up)
    need_docker; ensure_env
    echo "[1/3] 构建镜像（首次几分钟，之后有缓存）"
    $COMPOSE build
    echo "[2/3] 启动"
    $COMPOSE up -d
    echo "[3/3] 等健康检查通过"
    for i in $(seq 1 60); do
      ST=$(docker inspect --format '{{.State.Health.Status}}' drive-app 2>/dev/null || echo "starting")
      [ "$ST" = "healthy" ] && break
      sleep 2
    done
    ST=$(docker inspect --format '{{.State.Health.Status}}' drive-app 2>/dev/null || echo unknown)
    if [ "$ST" != "healthy" ]; then
      echo "[!] 容器没到 healthy，看日志："
      $COMPOSE logs --tail 40 app
      exit 1
    fi
    PORT=$(grep '^DRIVE_PORT=' .env | cut -d= -f2-)
    IP=$(hostname -I 2>/dev/null | awk '{print $1}' || echo 127.0.0.1)
    echo ""
    echo "============================================"
    echo "  北冥云盘已就绪"
    echo "  http://${IP}:${PORT:-8140}"
    echo ""
    echo "  第一个注册的账号自动成为管理员，"
    echo "  请立刻去注册，别让别人抢先。"
    echo "============================================"
    ;;

  update)
    need_docker
    $COMPOSE up -d --build
    echo "[✓] 已重建并重启"
    ;;

  status)
    need_docker
    $COMPOSE ps
    echo ""
    docker inspect --format 'app health: {{.State.Health.Status}}' drive-app 2>/dev/null || true
    ;;

  logs)
    need_docker
    $COMPOSE logs -f --tail 100 app
    ;;

  *)
    echo "用法: $0 {up|update|status|logs}"
    exit 1
    ;;
esac