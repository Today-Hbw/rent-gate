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

#ifndef RELAY_PIN
#define RELAY_PIN 0
#endif
#ifndef RELAY_ACTIVE_LEVEL
#define RELAY_ACTIVE_LEVEL LOW
#endif

static char serverUrl[121] = "";
static char deviceId[65] = "";
static char deviceKey[65] = "";
static WiFiManager wifi;
static WiFiManagerParameter serverParam("server", "Server HTTP URL (e.g. http://192.168.1.10:8080)", "", 120);
static WiFiManagerParameter idParam("device", "Device ID", "", 64);
static WiFiManagerParameter keyParam("key", "Device key", "", 64, "type=\"password\"");
static Ticker cutoff;
static LeaseTimer lease;
static volatile bool relayOn = false;
static bool shouldSave = false;
static bool fsReady = false;
static uint32_t lastPoll = 0;

static void relayOff() {
  // Ticker callback must stay short: no allocation, serial, HTTP or filesystem calls.
  digitalWrite(RELAY_PIN, !RELAY_ACTIVE_LEVEL);
  relayOn = false;
}

static bool validConfig(const char* server, const char* id, const char* key) {
  String url(server);
  if (!url.startsWith("http://") || url.length() <= 7 || url.length() > 120) return false;
  // No query/fragment/userinfo: all credentials travel only in the header.
  if (url.indexOf('?') >= 0 || url.indexOf('#') >= 0 || url.indexOf('@') >= 0 || url.indexOf(' ') >= 0) return false;
  size_t length = strlen(id);
  if (!length || length > 64 || strlen(key) != 43) return false;
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
  StaticJsonDocument<512> doc;
  if (deserializeJson(doc, file)) return;
  const char* server = doc["server"] | "";
  const char* id = doc["device"] | "";
  const char* key = doc["key"] | "";
  if (!validConfig(server, id, key)) return;
  strlcpy(serverUrl, server, sizeof(serverUrl));
  strlcpy(deviceId, id, sizeof(deviceId));
  strlcpy(deviceKey, key, sizeof(deviceKey));
}

static void saveConfig() {
  shouldSave = false;
  const char* server = serverParam.getValue();
  const char* id = idParam.getValue();
  const char* key = keyParam.getValue();
  if (!validConfig(server, id, key)) {
    Serial.println("Invalid config. Use HTTP URL, valid device ID and 43-character key. Send c to reopen portal.");
    return;
  }
  if (!fsReady) { Serial.println("Cannot persist config: filesystem unavailable."); return; }
  StaticJsonDocument<512> doc;
  doc["server"] = server; doc["device"] = id; doc["key"] = key;
  File file = LittleFS.open("/config.tmp", "w");
  if (!file || !serializeJson(doc, file)) { Serial.println("Config write failed."); return; }
  file.close();
  if (!LittleFS.rename("/config.tmp", "/config.json")) { Serial.println("Config save failed."); return; }
  strlcpy(serverUrl, server, sizeof(serverUrl));
  while (strlen(serverUrl) > 7 && serverUrl[strlen(serverUrl) - 1] == '/') serverUrl[strlen(serverUrl) - 1] = '\0';
  strlcpy(deviceId, id, sizeof(deviceId));
  strlcpy(deviceKey, key, sizeof(deviceKey));
  Serial.println("Config saved. Credentials are not printed.");
}

static void startPortal() {
  char name[28], password[16];
  snprintf(name, sizeof(name), "RentGate-%06X", ESP.getChipId());
  snprintf(password, sizeof(password), "rg%08lx", static_cast<unsigned long>(os_random()));
  Serial.printf("Setup AP: %s | password: %s | open http://192.168.4.1\n", name, password);
  wifi.startConfigPortal(name, password);
}

static void pollServer() {
  if (wifi.getConfigPortalActive() || WiFi.status() != WL_CONNECTED || !validConfig(serverUrl, deviceId, deviceKey)) return;
  WiFiClient client;
  HTTPClient http;
  http.setTimeout(1500);
  if (!http.begin(client, String(serverUrl) + "/poll")) return;
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Device-Key", deviceKey);
  StaticJsonDocument<192> request;
  request["device_id"] = deviceId;
  request["relay_on"] = relayOn;
  String body;
  serializeJson(request, body);
  uint32_t sent = millis();
  int code = http.POST(body);
  if (code == HTTP_CODE_OK && http.getSize() <= 512) {
    String payload = http.getString();
    uint32_t latency = millis() - sent;
    StaticJsonDocument<512> response;
    if (!deserializeJson(response, payload)) {
      const char* action = response["action"] | "";
      if (strcmp(action, "off") == 0) {
        cutoff.detach(); lease.stop(); relayOff();
      } else if (strcmp(action, "on") == 0 && response["expire_ts"].is<int64_t>() && response["server_ts"].is<int64_t>()) {
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
    Serial.println("Device authentication failed. Check ID/key; send c to reconfigure.");
  }
  http.end();
  // HTTP errors and WiFi loss preserve the existing lease; they never extend it.
}

void setup() {
  // Set output latch before changing pin mode; keep relay off during boot/config.
  digitalWrite(RELAY_PIN, !RELAY_ACTIVE_LEVEL);
  pinMode(RELAY_PIN, OUTPUT);
  Serial.begin(115200);
  Serial.println("\nRent Gate starting. Relay OFF. Send c to configure.");
  loadConfig();
  serverParam.setValue(serverUrl, 120);
  idParam.setValue(deviceId, 64);
  keyParam.setValue(deviceKey, 64);
  wifi.addParameter(&serverParam); wifi.addParameter(&idParam); wifi.addParameter(&keyParam);
  wifi.setSaveParamsCallback([]() { shouldSave = true; });
  wifi.setConfigPortalBlocking(false);
  wifi.setConfigPortalTimeout(180);
  wifi.setDebugOutput(false);
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin();
  if (!validConfig(serverUrl, deviceId, deviceKey)) startPortal();
  lastPoll = millis() - 3000;
}

void loop() {
  wifi.process();
  if (shouldSave) saveConfig();
  if (!lease.tick(millis()) && relayOn) { cutoff.detach(); relayOff(); }
  if (Serial.available() && Serial.read() == 'c') {
    cutoff.detach(); lease.stop(); relayOff();
    startPortal();
  }
  if (millis() - lastPoll >= 3000) { lastPoll = millis(); pollServer(); }
  delay(5);
}
