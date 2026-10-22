# Rent Gate · 洗牌机出租演示

第一阶段：后台手动放行 → ESP 每 3 秒拉取指令 → 继电器通电 → 本地到期断电。不接真实支付，不产生订单；订单表已预建。没有多商户、试用冷却、客人付款页等第二阶段功能。

## 快速启动

需要 **JDK 17–25**（建议 JDK 21）和联网环境。Maven Wrapper 会下载 Maven 3.9.11。默认端口 8080，后台与接口在同一个服务中，不需要另起前端或数据库。

Windows PowerShell：

```powershell
# 如果默认 Java 版本低于 17，请先将 JAVA_HOME 指向本机 JDK 17+。
$env:RENT_GATE_ADMIN_PASSWORD = Read-Host '设置管理员密码'
.\mvnw.cmd spring-boot:run
```

Linux/macOS：

```bash
read -r -s -p 'Admin password: ' RENT_GATE_ADMIN_PASSWORD; echo
export RENT_GATE_ADMIN_PASSWORD
./mvnw spring-boot:run
```

访问 **http://localhost:8080**，账号 `admin`。未设密码时，服务启动日志会显示本次启动的随机临时密码；重启后临时密码会改变。不要把真实密码、设备密钥或配网资料写进源码。

打包运行：

```powershell
.\mvnw.cmd verify
java -jar target/rent-gate-0.1.0.jar
```

## 演示步骤

1. 后台“添加设备”，填写自定义编号、名称，保存仅显示一次的设备密钥。
2. 按 [固件说明](firmware/README.md) 烧录、配网，填入服务器地址、设备编号和密钥。ESP 必须访问服务器局域网 IP，不能使用 `localhost`。
3. 设备第一次成功轮询后变为在线；每 3 秒刷新，超过 12 秒无心跳标记离线。
4. 时长默认 30 分钟。联调时先设 **1 分钟**，点击开机，观察继电器灯和吸合声。
5. 确认倒计时结束自动断开；再次放行后点击停止，观察下一轮询断开。
6. 运行中关闭 WiFi 或暂停服务，确认已有本地倒计时仍到期断开；恢复网络后按服务端最新状态工作。

没有硬件时，可在另一终端模拟设备（Python 3.10+，无需安装第三方包）：

```powershell
python scripts/simulate_device.py --device-id shuffler-001 --server http://localhost:8080
# 按提示输入后台为该设备生成的密钥。
```

## 计时与状态约定

- 手动放行写入 `device.status` 和 `device.expire_ts`，数据库是唯一指令来源。时长为 **1–1440 整数分钟**。
- 重复放行从当前时间重新计时，覆盖原到期时间，不叠加；页面对运行中的设备二次放行会提示。
- `/poll` 返回 Unix **秒**时间戳、服务端时间和剩余秒数。固件用单调时钟计时，不依赖 ESP 的时间校准；同一到期时间的重复轮询不会延长本地计时。
- 断网不立即断电，仍按最近一次有效指令在本地到期断电。断网期间无法收到新放行、停止或续时指令。PRD 中“保持当前状态”按这一规则解释，优先满足本地到期断电验收。
- ESP 重启先断开继电器，再联网获取有效放行记录；服务器重启会保留设备和到期时间。未到期的放行会恢复下发。
- 后台“放行中”表示有效服务端指令，**不等于继电器已吸合**。`relay_on` 是上一次请求上报值，下一次心跳才反映新指令的执行结果。离线时只显示历史上报。
- 自动过期任务每秒更新数据库状态；轮询与列表也依据到期时间判断，不依赖任务执行时机。
- 设备密钥仅保存 SHA-256 摘要。丢失可重置，旧密钥立即失效；ESP 需重新配网。密钥重置不会撤销已下发的本地倒计时。

## 数据与配置

默认 H2 文件库位于启动工作目录的 `data/rent-gate.mv.db`，已被 Git 忽略。不要同时启动两个实例访问同一文件库。备份时先停服务，再复制整个 `data/` 目录；演示版按单实例部署。

| 环境变量 | 默认值 / 用途 |
| --- | --- |
| `PORT` | `8080` |
| `RENT_GATE_ADMIN_PASSWORD` | 不设则启动时生成临时密码 |
| `RENT_GATE_DB_URL` | `jdbc:h2:file:./data/rent-gate;DB_CLOSE_ON_EXIT=FALSE` |
| `RENT_GATE_DB_PASSWORD` | 本地 H2 密码，默认空 |

后台登录使用 Session，30 分钟无操作过期，写操作受 CSRF 保护。设备轮询使用独立 `X-Device-Key`，不需要登录 Session。默认 HTTP 仅用于受控局域网演示；公网使用请配置 HTTPS/可信网络，避免密码与设备密钥在明文网络传输。第一阶段固件仅实现 HTTP，后续公网接入需增加证书校验。

Docker（可选，先设置管理员密码）：

```bash
docker compose up -d --build
docker compose logs -f
```

数据保存在命名卷 `device-data`。不要使用 `docker compose down -v`，它会删除设备数据。后台不能直接控制 WiFi，需要服务器端口允许来自设备网络的连接。上传已打包 JAR 的部署方式、资源限制与维护步骤见 [服务器部署说明](docs/DEPLOYMENT.md)。

## 验证

```powershell
.\mvnw.cmd verify
node --check src/main/resources/static/assets/app.js
python -m py_compile scripts/simulate_device.py
pio run -d firmware
```

后端测试覆盖放行、停止、时间边界、真实登录、CSRF、设备认证、密钥轮换、心跳超时、并发轮询和服务重启后的持久化。独立 C++ 计时器测试覆盖重复轮询、断网到期、旧指令不可重启和 `millis()` 溢出：

```bash
g++ -std=c++11 -Wall -Wextra -Werror firmware/test/lease_timer_test.cpp -o lease-test
./lease-test
```

GitHub Actions 配置了后端验证、固件编译与固件 `.bin` 构件上传。接口见 [API 文档](docs/API.md)，交付边界与待验证项见 [验收清单](docs/ACCEPTANCE.md)。

## 目录

```text
src/main/java/io/rentgate/   Spring Boot 服务与接口
src/main/resources/         数据库建表、后台页、登录页
src/test/                   后端集成与重启测试
firmware/                   ESP-01S 固件、PlatformIO 配置和计时器测试
scripts/simulate_device.py  无硬件轮询模拟器
docs/                       API 和验收说明
```

技术参考：[Spring Boot 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)、[WiFiManager](https://github.com/tzapu/WiFiManager)、[ESP8266 HTTP 示例](https://github.com/esp8266/Arduino/blob/master/libraries/ESP8266HTTPClient/examples/BasicHttpClient/BasicHttpClient.ino)。
