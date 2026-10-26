# API

JSON 字段为 snake_case，时间戳为 Unix 秒，金额为元。GET `/health` 返回 `{"status":"ok"}`。

## 会话与权限

GET `/login` 取得表单，POST `/login` 提交 username、password 与表单 CSRF。三级账号共用入口。POST `/logout` 需 CSRF。

`/admin/**` 需要 Session，未登录/已停用/密码版本失效返回 401。GET `/admin/csrf` 返回 `{"token":"…","header_name":"X-CSRF-TOKEN"}`，写操作需携带 Cookie 并在对应请求头发送 token，无 token 返回 403。

管理员访问全部数据，服务商限名下老板，老板限自己。跨范围对象一般返回 404，不允许的角色操作返回 403；参数错误 400，重复/状态冲突 409。错误含 message，不返回数据库细节。列表不含密码或设备密钥摘要。

## 账号

| 方法 | 路径 | 入参 / 作用 |
| --- | --- | --- |
| GET | `/admin/me` | 当前账号资料与角色 |
| GET | `/admin/accounts` | 管理员全部，服务商自己与名下老板，老板禁止 |
| POST | `/admin/accounts` | username, display_name, role, provider_id, password；201 |
| PUT | `/admin/accounts/{id}` | display_name, enabled；不能编辑自己/管理员 |
| POST | `/admin/accounts/{id}/password` | password；上级重置，旧会话失效 |
| POST | `/admin/me/password` | current_password, password；成功后重新登录 |

创建角色仅 provider/user，服务商只能创建自己的 user。user 必须绑定有效 provider。账号名 3–40 位字母/数字/下划线/点/短横线（首位不能点或短横线）；密码 8–64 字符，UTF-8 不超过 72 字节。角色与上级不可更新。

## 类型、商户、套餐、设置

| 方法 | 路径 | 入参 / 作用 |
| --- | --- | --- |
| GET | `/admin/device-types` | 可见通用/服务商类型 |
| POST / PUT | `/admin/device-types` / `/admin/device-types/{id}` | name, provider_id, trial_minutes, cooldown_minutes, enabled |
| GET | `/admin/merchants` | 可见商户资料 |
| PUT | `/admin/merchants/{id}` | name, wechat_mch_id, wechat_sub_mch_id, alipay_app_id |
| GET | `/admin/plans` | 可见套餐 |
| POST / PUT | `/admin/plans` / `/admin/plans/{id}` | user_id, device_type_id, name, duration_min, price, enabled |
| GET / PUT | `/admin/settings` | site_name, guest_notice, online_timeout_seconds；仅管理员写 |

名称非空且最多 80 字符。试用 0–1440 分钟，0 表示关闭；冷却 0–10080。provider_id=null 表示平台通用类型，仅管理员可编辑。服务商维护自己类型，归属不可更改。

创建账号自动创建对应商户。商户号与 APP ID 为最多 32 位数字，可传空字符串；服务商填 wechat_mch_id，老板填 wechat_sub_mch_id，另一个必须空。不接收支付私钥/证书。

套餐时长 1–1440 整数分钟，价格 0–99999.99、最多两位小数。老板可省略 user_id，服务端强制为自己；套餐归属固定。类型必须为通用或老板所属服务商类型。启用设备/套餐要求老板、上级和类型有效；停用对象仍可关闭对应设备/套餐。

online_timeout_seconds 为 6–300，默认 12；guest_notice 最多 240 字符。

## 设备与二维码

| 方法 | 路径 | 入参 / 返回 |
| --- | --- | --- |
| GET | `/admin/devices` | 权限内设备数组 |
| POST | `/admin/devices` | device_id, name, user_id, device_type_id；201，含 device_key |
| PUT | `/admin/devices/{id}` | name, user_id, device_type_id, enabled |
| POST | `/admin/release` | device_id, duration_min；1–1440 整数分钟 |
| POST | `/admin/stop` | device_id；下次轮询断电 |
| POST | `/admin/rotate-key` | device_id；返回新密钥，撤销旧密钥和配对窗口 |
| GET | `/admin/devices/{id}/credentials` | 权限内设备密钥，Cache-Control: no-store；旧设备未回连时 device_key=null |
| POST | `/admin/devices/{id}/setup` | 待机、在线且支持新协议；返回 request_id,expires_ts，有效期 5 分钟 |
| POST | `/admin/devices/{id}/pairing` | 开启/恢复 5 分钟配对窗口，返回 status,expires_ts |
| POST | `/admin/devices/{id}/pairing/confirm` | code：6 位数字；确认后生成新密钥，最多输错 5 次 |
| GET | `/admin/devices/{id}/guest-link` | `{"url":"https://…/r/<public_token>"}` |
| GET | `/admin/devices/{id}/qr.png` | 360×360 PNG，编码客人 URL |

编号 1–64 位 `[A-Za-z0-9_-]`。创建默认类型 1（洗牌机）；管理员可不分配老板，服务商必须指定名下老板，老板强制自己。merchant_id 服务端推导，不能伪造。

设备数组字段：device_id、name、status(on/off)、expire_ts、last_seen_ts、relay_on、online、remaining_seconds、user_id、owner_name、provider_id、provider_name、merchant_id、device_type_id、type_name、enabled、run_source(manual/trial)、trial_used_at、cooldown_until、public_token、firmware_version、setup_pending。不含设备密钥或配对令牌。

online 根据最近心跳判定；relay_on 为历史硬件回报，服务器 status 不能替代执行确认。手动放行覆盖到期时间，不叠加，不受试用冷却限制。运行中不能改归属/类型。停用置 expiry=0 并使公开 API 返回 404。

## 客人 H5 与免费试用

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/r/{token}` | 手机页，无需登录 |
| GET | `/api/public/csrf` | 匿名会话 CSRF |
| GET | `/api/public/devices/{token}` | 公开状态、规则、套餐、站点文案 |
| POST | `/api/public/devices/{token}/trial` | 空 JSON，需 Cookie+CSRF，成功返回新状态 |

```json
{"name":"一号桌","merchant_name":"棋牌室","type_name":"洗牌机","status":"standby",
 "online":true,"server_ts":1700000000,"expire_ts":0,"remaining_seconds":0,
 "trial_minutes":15,"cooldown_minutes":120,"cooldown_until":0,"cooldown_remaining_seconds":0,
 "trial_available":true,"trial_unavailable_reason":"","payment_enabled":false,
 "plans":[{"id":1,"name":"一小时","duration_min":60,"price":5.00}],
 "site_name":"Rent Gate","guest_notice":"欢迎体验"}
```

status 为 standby/trial/running/cooldown。公开响应不含账号 ID、商户号或控制密钥。token 为随机 UUID，可打印分享，不等于硬件密钥。

试用要求设备在线、空闲、无冷却，且设备/归属/类型有效。成功写 expiry=now+trial_minutes×60，cooldown_until=expiry+cooldown_minutes×60，run_source=trial。设备行锁保证并发只授予一次；提前停止不会绕过冷却，不创建订单，无支付 API。

## 订单与收益

GET `/admin/orders?page=1&page_size=20&device_id=…&status=paid&from=2026-10-01&to=2026-10-22`：

- 可选过滤 device_id、status(pending/paid/closed/refunded)、from/to（北京时间日期，两端均含当天）。
- page 1–1000000，page_size 1–100；返回 items,total,page,page_size。
- 条目含 order_no、device_id、device_name、user_id、owner_name、merchant_id、duration_min、amount、pay_channel、status、pay_transaction_id、created_ts。

GET `/admin/statistics` 返回 total_devices、online_devices、running_devices、trial_devices、total_orders、paid_orders、total_revenue、today_revenue、daily_revenue（最近 7 天 date/revenue）。

先隔离账号归属再过滤/分页。仅 paid 金额计收益，退款/待支付/关闭不计入；按订单创建日期及北京时间自然日统计。当前无支付写入，新库订单与收益为 0。

## 设备轮询（兼容已有固件）

每 3 秒 POST `/poll`，Content-Type application/json，头 `X-Device-Key: <key>`，无需 Session/CSRF。

```json
{"device_id":"shuffler-001","relay_on":false}
```

```json
{"action":"on","expire_ts":1700001800,"server_ts":1700000000,"remaining_seconds":1800}
```

到期/停止/停用 action=off，remaining_seconds=0。未知设备/错误/缺失密钥统一 401，不更新心跳。relay_on 可省略。轮询读取持久化截止时间，不续时；与控制和试用使用同一行锁。固件断网仍按原计时到期断电。

v0.2.0 请求另带 `firmware_version="0.2.0"`，以及上次完成配网的 `config_ack`（UUID，可省略）。响应另带 `config_request_id`（无命令为 null），同一命令重复下发直至匹配确认或 5 分钟过期；配网完成后的首次轮询发送确认。旧固件忽略新增字段仍兼容，但不可通过后台触发配网。

## 免手填密钥配对

固件留空密钥后每 3 秒 POST `/device/pair`，无需 Session/CSRF。请求 `{"device_id":"…","token":"…"}`；token 为设备产生并保存在 LittleFS 的 43 字符随机令牌，字符集 `[A-Za-z0-9_-]`。响应 `status` 为 waiting（未开启/过期）、pending（等待确认）、busy（窗口已绑定其他令牌）、approved（已确认），另有 pairing_code、device_key、expires_ts；未确认绝不返回密钥。只有匹配窗口令牌且授权后台确认短码后，才在该窗口有效期内返回新密钥，重复取回不再换钥。所有配对响应禁止缓存。

后台配对操作沿用设备归属权限和 CSRF；开始配对及配网要求设备待机，活动窗口内不允许新的放行/试用。旧密钥继续用于认证，直到确认配对才立即替换；旧摘要不会通过查看接口还原，首次成功认证后自动补存 AES-GCM 加密密钥。
