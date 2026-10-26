#include <Arduino.h>
#include <ArduinoJson.h>
#include <ESP8266HTTPClient.h>
#include <ESP8266WiFi.h>
#include <LittleFS.h>
#include <Ticker.h>
#include <WiFiManager.h>
extern "C" {
#include <user_interface.h>
}
#include "LeaseTimer.h"
#include "NetworkRecovery.h"
#include "PortalSession.h"
#include "PortalUi.h"

#ifndef RELAY_PIN
#define RELAY_PIN 0
#endif
#ifndef RELAY_ACTIVE_LEVEL
#define RELAY_ACTIVE_LEVEL LOW
#endif

static char serverUrl[121] = "http://162.14.76.232:8080";
static char deviceId[65] = "";
static char deviceKey[65] = "";
static char deviceName[241] = "";
static constexpr char firmwareVersion[] = "0.3.1";
static bool bindingVerified = false;
static char pairingToken[44] = "";
static char enrollmentCode[9] = "";
static char enrollmentServer[121] = "";
static bool enrollmentFailed = false;
static char configAck[37] = "";
static char configCloseAck[37] = "";
static String previousSsid, previousPassword;
static String queuedSsid, queuedPassword;
static bool connectAfterClose = false;
static const char* portalMessage = "填写 WiFi 和后台生成的验证码即可配对；不填验证码时，请完整填写手动配置。";
static WiFiManager wifi;
static WiFiManagerParameter serverParam("server", "服务器 HTTP 地址（如 http://192.168.1.10:8080）", "", 120);
static WiFiManagerParameter codeParam("code", "配对验证码（后台生成的 8 位数字；手动配置时留空）", "", 8, "inputmode=\"numeric\" autocomplete=\"off\"");
static WiFiManagerParameter idParam("device", "设备编号", "", 64);
static WiFiManagerParameter keyParam("key", "设备密钥（手动配置时填写；验证码配对可留空）", "", 64, "type=\"password\"");
static WiFiManagerParameter pairingHelp(
    "<p>填写验证码时会自动获取设备编号和密钥；不填验证码时，使用下方完整手动配置。所有配置项均可修改。</p>");
static constexpr char setupPassword[] = "RentGate888";
static Ticker cutoff;
static LeaseTimer lease;
static NetworkRecovery networkRecovery;
static PortalSession portalSession;
static volatile bool relayOn = false;
static bool shouldSave = false;
static bool needsSetup = false;
static bool closeRequested = false, restoreOnClose = true;
static bool fsReady = false;
static uint32_t lastPoll = 0;

static void relayOff() {
  // Ticker callback must stay short: no allocation, serial, HTTP or filesystem calls.
  digitalWrite(RELAY_PIN, !RELAY_ACTIVE_LEVEL);
  relayOn = false;
}

static bool validServer(const char* server) {
  String url(server);
  if (!url.startsWith("http://") || url.length() <= 7 || url.length() > 120) return false;
  // No query/fragment/userinfo: all credentials travel only in the header.
  if (url.indexOf('?') >= 0 || url.indexOf('#') >= 0 || url.indexOf('@') >= 0 || url.indexOf(' ') >= 0) return false;
  return true;
}
static bool validCode(const char* code) {
  if (strlen(code) != 8) return false;
  for (size_t i = 0; i < 8; ++i) if (code[i] < '0' || code[i] > '9') return false;
  return true;
}
static bool validConfig(const char* server, const char* id, const char* key) {
  if (!validServer(server)) return false;
  size_t length = strlen(id);
  if (!length || length > 64 || (strlen(key) != 0 && strlen(key) != 43)) return false;
  for (size_t i = 0; i < length; ++i) {
    char c = id[i];
    if (!isalnum(static_cast<unsigned char>(c)) && c != '_' && c != '-') return false;
  }
  return true;
}

static void loadConfig() {
  fsReady = LittleFS.begin();
  if (!fsReady) { Serial.println("LittleFS unavailable. Erase flash and upload firmware again."); return; }
  File file = LittleFS.open("/config.json", "r");
  if (!file) return;
  StaticJsonDocument<1024> doc;
  if (deserializeJson(doc, file)) return;
  const char* server = doc["server"] | "";
  const char* id = doc["device"] | "";
  const char* key = doc["key"] | "";
  if (!validConfig(server, id, key)) return;
  strlcpy(serverUrl, server, sizeof(serverUrl));
  strlcpy(deviceId, id, sizeof(deviceId));
  strlcpy(deviceKey, key, sizeof(deviceKey));
  strlcpy(deviceName, doc["device_name"] | "", sizeof(deviceName));
  const char* token = doc["pairing_token"] | "";
  if (strlen(token) == 43) strlcpy(pairingToken, token, sizeof(pairingToken));
}

static void newPairingToken() {
  static const char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
  for (size_t i = 0; i < sizeof(pairingToken) - 1; ++i) pairingToken[i] = alphabet[os_random() & 63];
  pairingToken[43] = '\0';
}

static void loadEnrollment() {
  if (!fsReady) return;
  File file = LittleFS.open("/pending.json", "r");
  if (!file) return;
  StaticJsonDocument<512> doc;
  if (deserializeJson(doc, file)) return;
  const char* server = doc["server"] | "";
  const char* code = doc["code"] | "";
  const char* token = doc["token"] | "";
  if (!validServer(server) || !validCode(code) || strlen(token) != 43) return;
  strlcpy(enrollmentServer, server, sizeof(enrollmentServer));
  strlcpy(enrollmentCode, code, sizeof(enrollmentCode));
  strlcpy(pairingToken, token, sizeof(pairingToken));
  queuedSsid = doc["ssid"] | ""; queuedPassword = doc["password"] | "";
  connectAfterClose = queuedSsid.length() > 0;
  // Resume a durable saved request in the background rather than reopening setup.
  portalSession.closed(millis());
}

static bool writeConfig(const char* server, const char* id, const char* key, const char* token, const char* name = deviceName) {
  if (!fsReady) { wifi.setTitle("RentGate - Storage unavailable"); return false; }
  StaticJsonDocument<1024> doc;
  doc["server"] = server; doc["device"] = id; doc["key"] = key; doc["pairing_token"] = token; doc["device_name"] = name;
  File file = LittleFS.open("/config.tmp", "w");
  if (!file || !serializeJson(doc, file)) { wifi.setTitle("RentGate - Save failed; retry"); return false; }
  file.close();
  if (!LittleFS.rename("/config.tmp", "/config.json")) { wifi.setTitle("RentGate - Save failed; retry"); return false; }
  return true;
}

static void clearEnrollment() {
  enrollmentCode[0] = '\0'; enrollmentServer[0] = '\0'; pairingToken[0] = '\0';
  enrollmentFailed = false; codeParam.setValue("", 8);
  queuedSsid = ""; queuedPassword = ""; connectAfterClose = false;
  if (fsReady && LittleFS.exists("/pending.json")) LittleFS.remove("/pending.json");
}

static bool queueEnrollment(const char* server, const char* code, const String& ssid, const String& password) {
  if (!validServer(server) || !validCode(code) || !ssid.length() || ssid.length() > 32 || password.length() > 64) {
    portalMessage = "请填写 WiFi 名称、有效服务器地址和 8 位验证码。"; return false;
  }
  if (!fsReady) { portalMessage = "存储不可用，配置未保存。"; return false; }
  if (!*pairingToken || strcmp(code, enrollmentCode) != 0 || strcmp(server, enrollmentServer) != 0) newPairingToken();
  StaticJsonDocument<512> doc;
  doc["server"] = server; doc["code"] = code; doc["token"] = pairingToken; doc["ssid"] = ssid; doc["password"] = password;
  File file = LittleFS.open("/pending.tmp", "w");
  if (!file || !serializeJson(doc, file)) { portalMessage = "保存失败，请重试。"; return false; }
  file.close();
  if (!LittleFS.rename("/pending.tmp", "/pending.json")) { portalMessage = "保存失败，请重试。"; return false; }
  strlcpy(enrollmentServer, server, sizeof(enrollmentServer));
  while (strlen(enrollmentServer) > 7 && enrollmentServer[strlen(enrollmentServer) - 1] == '/') enrollmentServer[strlen(enrollmentServer) - 1] = '\0';
  strlcpy(enrollmentCode, code, sizeof(enrollmentCode));
  queuedSsid = ssid; queuedPassword = password; connectAfterClose = true;
  enrollmentFailed = false;
  closeRequested = true; restoreOnClose = false;
  portalMessage = "已保存，正在后台连接 WiFi 并配对。";
  return true;
}

static bool saveConfig() {
  shouldSave = false;
  const char* server = serverParam.getValue();
  const char* id = idParam.getValue();
  const char* key = keyParam.getValue();
  const char* code = codeParam.getValue();
  bool byCode = *code;
  if (!validServer(server) || (byCode ? !validCode(code) : (!validConfig(server, id, key) || strlen(key) != 43))) {
    portalMessage = "配置有误：填写有效 HTTP 地址和 8 位验证码，或留空验证码并完整填写设备编号、43 字符密钥。";
    return false;
  }
  if (byCode) {
    return queueEnrollment(server, code, WiFi.SSID(), WiFi.psk());
  }
  bool sameDevice = strcmp(id, deviceId) == 0;
  if (!writeConfig(server, id, key, "", sameDevice ? deviceName : "")) { portalMessage = "保存失败，请重试。"; return false; }
  strlcpy(serverUrl, server, sizeof(serverUrl));
  while (strlen(serverUrl) > 7 && serverUrl[strlen(serverUrl) - 1] == '/') serverUrl[strlen(serverUrl) - 1] = '\0';
  strlcpy(deviceId, id, sizeof(deviceId));
  strlcpy(deviceKey, key, sizeof(deviceKey));
  if (!sameDevice) deviceName[0] = '\0';
  bindingVerified = false;
  clearEnrollment();
  queuedSsid = WiFi.SSID(); queuedPassword = WiFi.psk(); connectAfterClose = queuedSsid.length() > 0;
  closeRequested = true; restoreOnClose = false;
  wifi.setTitle("RentGate setup");
  Serial.println("Config saved. Credentials are not printed.");
  return true;
}

static void startPortal() {
  if (wifi.getConfigPortalActive() || !portalSession.canOpen(millis())) return;
  previousSsid = WiFi.SSID(); previousPassword = WiFi.psk();
  char name[28];
  snprintf(name, sizeof(name), "RentGate-%06X", ESP.getChipId());
  Serial.printf("Setup AP: %s | open http://192.168.4.1 on your phone\n", name);
  wifi.startConfigPortal(name, setupPassword);
  if (wifi.getConfigPortalActive()) portalSession.opened(millis());
}

static void closePortal(bool restore) {
  closeRequested = false; shouldSave = false; needsSetup = false;
  bool active = wifi.getConfigPortalActive();
  if (active) wifi.stopConfigPortal();
  if (restore) {
    clearEnrollment();
    if (active && previousSsid.length() && (previousSsid != WiFi.SSID() || previousPassword != WiFi.psk())) {
      WiFi.persistent(true);
      WiFi.begin(previousSsid.c_str(), previousPassword.c_str());
      WiFi.persistent(false);
    }
    serverParam.setValue(serverUrl, 120); idParam.setValue(deviceId, 64); keyParam.setValue(deviceKey, 64);
  }
  portalSession.closed(millis());
  WiFi.setAutoReconnect(true);
  if (WiFi.status() != WL_CONNECTED && !connectAfterClose) WiFi.begin();
  lastPoll = millis() - 3000;
  portalMessage = "填写 WiFi 和后台生成的验证码即可配对；不填验证码时，请完整填写手动配置。";
}

static void maintainNetwork() {
  bool configured = validConfig(serverUrl, deviceId, deviceKey) && strlen(deviceKey) == 43 && !*enrollmentCode;
  // With no saved SSID there is nothing to wait for on first boot.
  bool hasSavedWifi = WiFi.SSID().length() > 0;
  NetworkRecovery::Action action = networkRecovery.update(
      millis(), WiFi.status() == WL_CONNECTED, configured && hasSavedWifi,
      wifi.getConfigPortalActive(), WiFi.softAPgetStationNum() > 0, needsSetup);
  switch (action) {
    case NetworkRecovery::Action::StartPortal:
      startPortal();
      break;
    case NetworkRecovery::Action::StopPortal:
      closePortal(false);
      break;
    case NetworkRecovery::Action::RetryWifi:
      // WiFiManager disables STA when opening an offline portal. Re-enable it
      // without closing the AP, so the original network can recover unattended.
      if (hasSavedWifi) WiFi.begin();
      break;
    case NetworkRecovery::Action::None:
      break;
  }
}

static void pollPairing() {
  if (WiFi.status() != WL_CONNECTED || !validCode(enrollmentCode) || !*pairingToken || enrollmentFailed) return;
  WiFiClient client;
  HTTPClient http;
  http.setTimeout(1500);
  if (!http.begin(client, String(enrollmentServer) + "/device/enroll")) return;
  http.addHeader("Content-Type", "application/json");
  StaticJsonDocument<192> request;
  request["code"] = enrollmentCode; request["token"] = pairingToken;
  String hardware = WiFi.macAddress(); hardware.replace(":", ""); request["hardware_id"] = hardware;
  String body; serializeJson(request, body);
  int result = http.POST(body);
  if (result == HTTP_CODE_OK && http.getSize() <= 768) {
    StaticJsonDocument<768> response;
    if (!deserializeJson(response, http.getString())) {
      const char* id = response["device_id"] | "";
      const char* key = response["device_key"] | "";
      const char* name = response["device_name"] | "";
      if (validConfig(enrollmentServer, id, key) && strlen(key) == 43 && writeConfig(enrollmentServer, id, key, "", name)) {
        strlcpy(serverUrl, enrollmentServer, sizeof(serverUrl)); strlcpy(deviceId, id, sizeof(deviceId));
        strlcpy(deviceKey, key, sizeof(deviceKey));
        strlcpy(deviceName, name, sizeof(deviceName)); bindingVerified = true;
        serverParam.setValue(serverUrl, 120); idParam.setValue(deviceId, 64); keyParam.setValue(deviceKey, 64);
        clearEnrollment(); needsSetup = false; closeRequested = true; restoreOnClose = false;
        Serial.println("Pairing complete. Device key saved.");
      }
    }
  } else if (result == 401 || result == 429 || result == 400) {
    enrollmentFailed = true;
    portalMessage = "验证码无效、过期、已被使用或尝试过多。请在后台重新生成验证码，返回配置填写并保存。";
  } else {
    portalMessage = "无法连接配对服务器，正在重试；请检查服务器地址，也可取消退出。";
  }
  http.end();
}

static void pollServer() {
  if (WiFi.status() != WL_CONNECTED || strlen(deviceKey) != 43 || !validConfig(serverUrl, deviceId, deviceKey)) return;
  WiFiClient client;
  HTTPClient http;
  http.setTimeout(1500);
  if (!http.begin(client, String(serverUrl) + "/poll")) return;
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Device-Key", deviceKey);
  StaticJsonDocument<384> request;
  request["device_id"] = deviceId;
  request["relay_on"] = relayOn;
  request["firmware_version"] = firmwareVersion;
  String hardware = WiFi.macAddress(); hardware.replace(":", ""); request["hardware_id"] = hardware;
  request["config_active"] = wifi.getConfigPortalActive();
  if (*configAck) request["config_ack"] = configAck;
  if (*configCloseAck) request["config_close_ack"] = configCloseAck;
  String body;
  serializeJson(request, body);
  uint32_t sent = millis();
  int code = http.POST(body);
  if (code == HTTP_CODE_OK && http.getSize() <= 768) {
    String payload = http.getString();
    uint32_t latency = millis() - sent;
    StaticJsonDocument<768> response;
    if (!deserializeJson(response, payload)) {
      bindingVerified = true;
      const char* name = response["device_name"] | "";
      if (*name && strcmp(name, deviceName) != 0) {
        strlcpy(deviceName, name, sizeof(deviceName));
        writeConfig(serverUrl, deviceId, deviceKey, "", deviceName);
      }
      const char* action = response["action"] | "";
      const char* configRequest = response["config_request_id"] | "";
      if (strlen(configRequest) == 36 && strcmp(configRequest, configAck) != 0) {
        strlcpy(configAck, configRequest, sizeof(configAck));
        needsSetup = true;
        portalSession.allowOpen();
      }
      const char* closeRequest = response["config_close_id"] | "";
      if (strlen(closeRequest) == 36 && strcmp(closeRequest, configCloseAck) != 0) {
        strlcpy(configCloseAck, closeRequest, sizeof(configCloseAck));
        closeRequested = true; restoreOnClose = true;
      }
      if (strcmp(action, "off") == 0) {
        cutoff.detach(); lease.stop(); relayOff();
      } else if (!wifi.getConfigPortalActive() && !needsSetup && !*enrollmentCode && strcmp(action, "on") == 0 && response["expire_ts"].is<int64_t>() && response["server_ts"].is<int64_t>()) {
        int64_t expiry = response["expire_ts"].as<int64_t>();
        int64_t remaining = expiry - response["server_ts"].as<int64_t>();
        if (remaining > 0 && remaining <= 86400 && response["remaining_seconds"].as<int64_t>() == remaining) {
          // Subtract full round-trip time. Never depend on ESP wall-clock/NTP.
          uint32_t duration = remaining * 1000 > latency ? static_cast<uint32_t>(remaining * 1000 - latency) : 0;
          if (lease.grant(expiry, duration, millis())) {
            cutoff.detach();
            if (lease.active) {
              relayOn = true;
              digitalWrite(RELAY_PIN, RELAY_ACTIVE_LEVEL);
              // Runs independently of blocking network operations in loop().
              cutoff.once_ms(duration, relayOff);
            } else relayOff();
          }
        }
      }
    }
  } else if (code == 401) {
    bindingVerified = false;
    Serial.println("Device authentication failed. Opening phone setup to update ID/key.");
    needsSetup = true;
  }
  http.end();
  // HTTP errors and WiFi loss preserve the existing lease; they never extend it.
}

void setup() {
  // Set output latch before changing pin mode; keep relay off during boot/config.
  digitalWrite(RELAY_PIN, !RELAY_ACTIVE_LEVEL);
  pinMode(RELAY_PIN, OUTPUT);
  Serial.begin(115200);
  Serial.println("\nRent Gate starting. Relay OFF. Phone setup opens automatically when needed.");
  loadConfig();
  loadEnrollment();
  serverParam.setValue(*enrollmentServer ? enrollmentServer : serverUrl, 120);
  codeParam.setValue(enrollmentCode, 8);
  idParam.setValue(deviceId, 64);
  keyParam.setValue(deviceKey, 64);
  wifi.addParameter(&codeParam); wifi.addParameter(&serverParam); wifi.addParameter(&idParam); wifi.addParameter(&keyParam);
  wifi.addParameter(&pairingHelp);
  wifi.setWebServerCallback([]() {
    wifi.server->collectHeaders("X-RentGate-Portal");
    wifi.server->on("/", HTTP_GET, []() {
      String html = "<!doctype html><html lang='zh-CN'><head><meta name='viewport' content='width=device-width,initial-scale=1'><title>设备配对</title>";
      html += portalHead; html += "</head><body>"; html += portalHome; html += "</body></html>";
      wifi.server->sendHeader("Cache-Control", "no-store");
      wifi.server->send(200, "text/html; charset=utf-8", html);
    });
    wifi.server->on("/portal/quick-pair", HTTP_POST, []() {
      if (wifi.server->header("X-RentGate-Portal") != "1") { wifi.server->send(403, "application/json", "{\"message\":\"Forbidden\"}"); return; }
      String ssid = wifi.server->arg("ssid"), password = wifi.server->arg("password"), code = wifi.server->arg("code");
      if (ssid == WiFi.SSID() && password.isEmpty()) password = WiFi.psk();
      if (!queueEnrollment(serverParam.getValue(), code.c_str(), ssid, password)) {
        StaticJsonDocument<256> error; error["message"] = portalMessage; String body; serializeJson(error, body);
        wifi.server->send(400, "application/json", body); return;
      }
      portalSession.touch(millis());
      wifi.server->send(200, "application/json", "{\"status\":\"saved\"}");
    });
    wifi.server->on("/portal/state", HTTP_GET, []() {
      StaticJsonDocument<768> doc;
      doc["message"] = portalMessage;
      doc["remaining_seconds"] = portalSession.remainingSeconds(millis());
      doc["device_id"] = deviceId; doc["device_name"] = deviceName; doc["binding_verified"] = bindingVerified;
      doc["hardware_id"] = WiFi.macAddress(); doc["firmware_version"] = firmwareVersion; doc["wifi_ssid"] = WiFi.SSID();
      String body; serializeJson(doc, body);
      wifi.server->sendHeader("Cache-Control", "no-store");
      wifi.server->send(200, "application/json", body);
    });
    wifi.server->on("/portal/activity", HTTP_POST, []() {
      if (wifi.server->header("X-RentGate-Portal") != "1") { wifi.server->send(403, "text/plain", "Forbidden"); return; }
      portalSession.touch(millis());
      wifi.server->send(204, "text/plain", "");
    });
    wifi.server->on("/portal/close", HTTP_POST, []() {
      if (wifi.server->header("X-RentGate-Portal") != "1") { wifi.server->send(403, "text/plain", "Forbidden"); return; }
      if (!closeRequested) { closeRequested = true; restoreOnClose = true; }
      wifi.server->send(200, "application/json", "{\"status\":\"closing\"}");
    });
  });
  wifi.setCustomHeadElement(portalHead);
  wifi.setSaveParamsCallback([]() { shouldSave = true; portalSession.touch(millis()); });
  wifi.setConfigPortalBlocking(false);
  wifi.setConfigPortalTimeout(0);
  // Close only after both WiFi and our own persisted device settings are valid.
  wifi.setDisableConfigPortal(false);
  wifi.setSaveConnect(false);
  wifi.setSaveConnectTimeout(15);
  wifi.setWiFiAutoReconnect(true);
  wifi.setTitle("RentGate setup");
  const char* menu[] = {"wifi", "info"};
  wifi.setMenu(menu, 2);
  wifi.setDebugOutput(false);
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin();
  maintainNetwork();
  lastPoll = millis() - 3000;
}

void loop() {
  wifi.process();
  if (!closeRequested && shouldSave) needsSetup = !saveConfig();
  if (!lease.tick(millis()) && relayOn) { cutoff.detach(); relayOff(); }
  if (Serial.available() && Serial.read() == 'c') {
    cutoff.detach(); lease.stop(); relayOff();
    needsSetup = true;
    portalSession.allowOpen();
    startPortal();
  }
  if (portalSession.expired(millis()) && wifi.getConfigPortalActive()) { closeRequested = true; restoreOnClose = true; }
  if (closeRequested) closePortal(restoreOnClose);
  if (connectAfterClose && !wifi.getConfigPortalActive()) {
    WiFi.persistent(true); WiFi.begin(queuedSsid.c_str(), queuedPassword.c_str()); WiFi.persistent(false);
    connectAfterClose = false; queuedSsid = ""; queuedPassword = "";
  }
  maintainNetwork();
  if (millis() - lastPoll >= 3000) { lastPoll = millis(); pollPairing(); if (!closeRequested) pollServer(); }
  delay(5);
}
