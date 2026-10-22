# ESP-01S 固件

目标板 ESP-01S / ESP8266、1 MB Flash。PlatformIO 固定平台 `espressif8266@4.2.1`、WiFiManager `2.0.17`、ArduinoJson `6.21.5`。串口 115200。

## 烧录

安装 Python 3.12 与 PlatformIO，或使用 VS Code 的 PlatformIO 插件：

```powershell
python -m pip install platformio==6.1.18
pio run -d firmware
pio run -d firmware -t upload --upload-port COM3
pio device monitor -b 115200 -p COM3
```

将 COM3 替换为实际串口。使用烧录底座时将开关置为烧录状态；写入后切换到运行状态并重新上电。CH340 底座若只供烧录，请把 ESP 移至继电器底板运行。

产物 `firmware/.pio/build/esp01_1m/firmware.bin`，烧录地址 `0x0`。配置存储于 64 KB LittleFS 分区；首次挂载会按 ESP8266 LittleFS 默认机制初始化空分区，若挂载失败可先 `pio run -d firmware -t erase` 后重刷。擦除会清空 WiFi 和设备配置。

## 初次配网

1. 后台创建设备，保存编号和仅显示一次的 43 字符设备密钥。
2. 上电先保持继电器断开，串口会输出热点 `RentGate-xxxxxx` 和本次随机热点密码。热点密码仅在本地串口显示，不写入源码。
3. 手机连接热点，访问 `http://192.168.4.1`，点击 Configure WiFi。
4. 填写 WiFi 名称、密码，以及三个自定义参数：
   - Server HTTP URL：例如 `http://192.168.1.10:8080`（设备可访问的服务器 IP；不支持 HTTPS）
   - Device ID：后台登记的设备编号
   - Device key：后台生成的密钥
5. 保存后固件将自定义参数写入 LittleFS，WiFi 凭据由 WiFiManager/ESP8266 保存。重启会恢复配置，无需重新烧录。
6. 设备每 3 秒轮询，后台可看到心跳。设备与服务器需网络互通，检查路由器 AP 隔离、电脑防火墙和端口。

热点 180 秒无操作会关闭。需要重新配网时，在串口发送字符 **`c`**，设备先断电并打开新配网热点，串口显示新热点密码。后台重置设备密钥后也使用此方式更新。串口发送 `c` 不会删除后台放行记录，配置成功后仍可能按未到期放行恢复通电。

若 WiFi 密码已改变而不能连接，也可以通过串口 `c` 打开配网热点。无法使用串口时，擦除 Flash 后重刷固件，再进行首次配网。

## 继电器引脚

默认 `GPIO0`，**低电平吸合**，适配常见 ESP-01S 单路继电器底板。不同底板可能用不同有效电平，先查看实物说明，再在 `platformio.ini` 修改 `RELAY_PIN` / `RELAY_ACTIVE_LEVEL` 并重编译。

固件在设置输出模式前先写断开电平，启动及配网时默认断开。GPIO0 是启动引脚，硬件上电瞬间状态无法仅靠软件完全消除，实物需要验证。不要使用串口 GPIO1 作为继电器输出（启动日志会改变电平）。

先用 5V 单独供继电器底板，只观察指示灯/吸合声完成联调。ESP 芯片为 3.3V 器件，请区分底板 5V 输入与 ESP 管脚。220V 改装应由具备资质的人员完成；控制板供电常供电，不经过被控继电器，负载使用 COM/NO 常开触点。

## 断网与计时

- 拉到 `on` 后用 `expire_ts - server_ts` 算剩余时间，扣除本次往返延迟，不需要 NTP。
- 相同到期时间的反复响应不会重置本地计时。
- Ticker 回调只关闭 GPIO，在主循环执行 HTTP 时也能到期断电。主循环同时用单调时钟兜底检查；`millis()` 溢出也不会延长放行。
- 网络错误、超时、错误 JSON、401 不会新开机或延长已运行时长；已有倒计时继续，到期关闭。
- 重新上电先关闭继电器，再从服务器取仍有效的指令；不会把 Flash 内的旧运行状态当作开机凭据。

固件编译能验证依赖、语法及 Flash/RAM 容量，实物才能验证供电、启动瞬态、极性、配网和最终继电器动作。
