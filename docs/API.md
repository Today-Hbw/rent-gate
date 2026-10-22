# 第一阶段 API

JSON 属性使用 snake_case；时间戳全部是 Unix 秒。`/health` 无需认证，返回 `{"status":"ok"}`。

## 管理员会话

浏览器 GET `/login`，使用账号 `admin` 和服务环境配置的密码登录。登录表单自带 CSRF token。后台 `/admin/**` 全部需要已登录 Session；无登录返回 401。所有管理写操作还需要 CSRF token，否则返回 403。

已登录后 GET `/admin/csrf` 返回：

```json
{"token":"<session-csrf-token>","header_name":"X-CSRF-TOKEN"}
```

后续 POST 在对应请求头发送该 token，并携带登录 Cookie。令牌与密码不可写入源码、URL 或公开文档。

| 方法 | 路径 | JSON 入参 | 返回 |
| --- | --- | --- | --- |
| GET | `/admin/devices` | 无 | 设备数组，不含任何密钥或摘要 |
| POST | `/admin/devices` | `device_id`, `name` | 201，`device_id` 与仅此次可见的 `device_key` |
| POST | `/admin/release` | `device_id`, `duration_min` | 更新后的设备；不生成订单 |
| POST | `/admin/stop` | `device_id` | 更新后的设备；下一次轮询断电 |
| POST | `/admin/rotate-key` | `device_id` | 新密钥，旧密钥立即失效 |

设备编号是 1–64 位字母、数字、下划线、短横线；名称非空且最多 80 字符；时长为 1–1440 整数分钟。非法参数 400，未知管理设备 404，重复编号 409。错误 JSON 包含 `message`，不返回数据库明细或堆栈。

设备列表字段示例：

```json
[{
  "device_id":"shuffler-001","name":"一号桌洗牌机","status":"on",
  "expire_ts":1700001800,"last_seen_ts":1700000000,
  "relay_on":false,"online":true,"remaining_seconds":1800
}]
```

`status` 为服务端当前指令（on/off）；`relay_on` 为最近心跳上报状态，尚未上报时为 null；`online` 为最近 12 秒内收到心跳。新指令的实际执行情况需要等待后续心跳，不应由 `status` 推断。

## 设备轮询

每 3 秒 `POST /poll`，无需登录、无需 CSRF；**必须传设备密钥头**。设备必须先在后台登记，拒绝自动注册。

```http
POST /poll
Content-Type: application/json
X-Device-Key: <device-key-from-console>

{"device_id":"shuffler-001","relay_on":false}
```

```json
{"action":"on","expire_ts":1700001800,"server_ts":1700000000,"remaining_seconds":1800}
```

到期或停止时 `action` 为 `off`，`remaining_seconds` 为 0；过期放行的历史 `expire_ts` 可保留，主动停止为 0。未知设备或错误/缺失密钥统一返回 401 `设备认证失败`，不会更新心跳。`relay_on` 可省略，省略后上报状态为 null。

命令行示例（先把密钥放入当前终端环境变量，不要填到脚本文件中）：

```powershell
$payload = @{device_id='shuffler-001'; relay_on=$false} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/poll `
  -Headers @{'X-Device-Key'=$env:RENT_GATE_DEVICE_KEY} `
  -ContentType application/json -Body $payload
```

Unix shell：

```bash
curl -X POST http://localhost:8080/poll \
  -H 'Content-Type: application/json' \
  -H "X-Device-Key: $RENT_GATE_DEVICE_KEY" \
  -d '{"device_id":"shuffler-001","relay_on":false}'
```

轮询只读取已持久化的放行状态，不会延长到期时间。所有针对同设备的读改写在事务内行锁串行化，避免轮询覆盖同时发生的放行或停止。
