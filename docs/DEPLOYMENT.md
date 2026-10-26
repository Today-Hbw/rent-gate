# 服务器部署与维护

本项目支持上传已经通过 `mvnw verify` 的 JAR，在服务器构建 Java 21 运行镜像。无需服务器安装 JDK、Maven，也不需要上传本地数据库或设备凭据。

## 准备文件

本地运行 `mvnw verify`，将以下文件通过 SSH/SCP 上传到服务器的专用目录：

- `target/rent-gate-0.3.1.jar` → `releases/<版本>/app.jar`
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
# 只在首次部署时创建；后续更新保留已有文件和密码。
if [ ! -e .env ]; then
  (set -C; cat > .env <<EOF
RENT_GATE_IMAGE=rent-gate:<版本>
RENT_GATE_HTTP_PORT=8080
RENT_GATE_ADMIN_PASSWORD=$(openssl rand -hex 16)
RENT_GATE_DB_PASSWORD=$(openssl rand -hex 24)
EOF
  )
  chmod 600 .env
fi
docker compose -p rent-gate --env-file .env -f compose.yml config --quiet
docker compose -p rent-gate --env-file .env -f compose.yml up -d
curl --fail http://127.0.0.1:8080/health
```

腾讯云安全组需允许选定的访问端口。若使用其他端口，只修改 `.env` 的 `RENT_GATE_HTTP_PORT` 后重新 `up -d`，固件配网时也填写对应端口。

管理员账号为 `admin`，`config/.env` 中的密码仅用于首次创建账号。初始化后密码保存在数据库，后台修改密码后重启仍保留，不会被环境变量覆盖。设备密钥可在权限内点击“查看密钥”，或通过手机短码配对自动下发。当前固件使用 HTTP；公网长期设备接入需后续补充 HTTPS 及证书校验。

v0.2.0 的 V3 迁移保留旧密钥摘要和到期时间，增加配网命令、配对窗口和加密密钥列。旧设备成功认证一次后自动补存可查看密钥，不会强制轮换。`/app/data/device-key-encryption.key` 首次启动自动生成在既有数据卷，必须连同数据库备份；丢失该文件将无法解密已保存的密钥。后台部署不会替设备烧录，按需配网与免手填配对需要另行刷入 v0.2.0 固件。

## 日常检查

```bash
cd "$HOME/apps/rent-gate/config"
docker compose -p rent-gate --env-file .env -f compose.yml ps
docker compose -p rent-gate --env-file .env -f compose.yml logs --tail 100
```

容器有 HTTP 健康检查、自动重启策略、512 MB 内存上限、1 核 CPU 上限和日志轮转。数据保存在 `rent-gate_device-data` 命名卷中，更新镜像不会清空数据。

## 更新与回滚

1. 停机备份数据库和配置，再验证新版本 JAR，上传到新的 `releases/<版本>/` 目录并构建新镜像。
2. 更新 `config/.env` 的 `RENT_GATE_IMAGE`，保留其他变量。
3. 执行 `docker compose -p rent-gate --env-file .env -f compose.yml up -d`。
4. 确认 `/health`、登录、设备列表及设备轮询正常。

本版使用 Flyway 自动迁移旧数据库。旧设备编号、密钥摘要、到期时间和订单保留，新增归属默认为空，登录管理员后分配给老板。旧设备未分配时不能领取试用。迁移会新增账号、类型、商户、套餐、冷却与系统设置。

数据库升级后回滚旧版，必须停机同时恢复升级前数据备份与旧镜像，不能只换镜像。设备已获得的本地倒计时在更新期间继续；新指令要等服务恢复后下发。

## 数据备份

备份前先停止 Rent Gate 容器，再备份命名卷；完成后启动容器。备份同时需要安全保留 `config/.env` 中的数据库密码，不能将备份或密码提交 Git。不要执行 `docker compose down -v`，它会删除数据卷。

## 手机扫码地址

在私有 `.env` 增加 `RENT_GATE_PUBLIC_BASE_URL=https://rent.example.com`（或手机可访问的 HTTP IP+端口），重新 `up -d` 后生成二维码。该变量只接受源地址，不带路径、用户名、查询参数。留空时用打开后台时的访问源生成，不能从 localhost 生成后直接给手机使用。

## 网页 HTTPS 反向代理（可选）

`deploy/nginx.conf.example` 提供浏览器 HTTPS 模板。准备自己的域名和证书，将示例域名/证书路径替换后，按服务器既有 Nginx 管理方式载入，并先执行 `nginx -t`。证书与私钥不进仓库。本次功能开发不购买域名、不修改现有服务器。

后台被代理到同机 127.0.0.1:8080。二维码源设为 HTTPS 域名。当前 ESP 配网仍填写可达 HTTP 地址；若还需要直接访问 8080，按实际网络控制入口。模板只向后端转发请求，不信任外部自带的转发头，也不改变应用 Session 行为。
