# 服务器部署与维护

本项目支持上传已经通过 `mvnw verify` 的 JAR，在服务器构建 Java 21 运行镜像。无需服务器安装 JDK、Maven，也不需要上传本地数据库或设备凭据。

## 准备文件

本地运行 `mvnw verify`，将以下文件通过 SSH/SCP 上传到服务器的专用目录：

- `target/rent-gate-0.1.0.jar` → `releases/<版本>/app.jar`
- `deploy/Dockerfile.runtime` → `releases/<版本>/Dockerfile`
- `deploy/compose.yml` → `config/compose.yml`

建议目录为 `$HOME/apps/rent-gate/`。实际 IP、SSH 用户、私钥路径和管理员密码只放在私有运行环境中，不写入仓库。上传后比较本地 SHA-256 与服务器 `sha256sum app.jar`，确保文件完整。

## 首次部署

在服务器执行，替换 `<版本>` 为当前发布编号：

```bash
cd "$HOME/apps/rent-gate/releases/<版本>"
docker build --label org.opencontainers.image.source=https://github.com/Today-Hbw/rent-gate \
  -t rent-gate:<版本> .

cd "$HOME/apps/rent-gate/config"
umask 077
# 只在首次部署时创建；后续更新应保留密码。
test ! -e .env
cat > .env <<EOF
RENT_GATE_IMAGE=rent-gate:<版本>
RENT_GATE_HTTP_PORT=8080
RENT_GATE_ADMIN_PASSWORD=$(openssl rand -hex 16)
RENT_GATE_DB_PASSWORD=$(openssl rand -hex 24)
EOF
chmod 600 .env
docker compose -p rent-gate --env-file .env -f compose.yml config --quiet
docker compose -p rent-gate --env-file .env -f compose.yml up -d
curl --fail http://127.0.0.1:8080/health
```

腾讯云安全组需允许选定的访问端口。若使用其他端口，只修改 `.env` 的 `RENT_GATE_HTTP_PORT` 后重新 `up -d`，固件配网时也填写对应端口。

管理员账号为 `admin`，密码保存在服务器 `config/.env`。设备密钥只能从后台创建/重置时获得。第一阶段固件使用 HTTP；当前部署用于演示设备控制，公网长期运行需要后续补充 HTTPS 设备接入及证书校验。

## 日常检查

```bash
cd "$HOME/apps/rent-gate/config"
docker compose -p rent-gate --env-file .env -f compose.yml ps
docker compose -p rent-gate --env-file .env -f compose.yml logs --tail 100
```

容器有 HTTP 健康检查、自动重启策略、512 MB 内存上限、1 核 CPU 上限和日志轮转。数据保存在 `rent-gate_device-data` 命名卷中，更新镜像不会清空数据。

## 更新与回滚

1. 验证新版本 JAR，上传到新的 `releases/<版本>/` 目录并构建新镜像。
2. 更新 `config/.env` 的 `RENT_GATE_IMAGE`，保留其他变量。
3. 执行 `docker compose -p rent-gate --env-file .env -f compose.yml up -d`。
4. 确认 `/health`、登录、设备列表及设备轮询正常。

回滚时将 `RENT_GATE_IMAGE` 改回旧镜像标签，再执行 `up -d`。设备已获得的本地倒计时在更新期间继续；新的指令要等服务恢复后才能下发。

## 数据备份

备份前先停止 Rent Gate 容器，再备份命名卷；完成后启动容器。备份同时需要安全保留 `config/.env` 中的数据库密码，不能将备份或密码提交 Git。不要执行 `docker compose down -v`，它会删除数据卷。
