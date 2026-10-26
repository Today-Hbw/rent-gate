#pragma once
#include <stdint.h>

class PortalSession {
 public:
  static constexpr uint32_t idleMs = 180000;
  static constexpr uint32_t retryDelayMs = 60000;
  void opened(uint32_t now) { active = true; lastActivity = now; suppressed = false; }
  void touch(uint32_t now) { if (active) lastActivity = now; }
  bool expired(uint32_t now) const { return active && static_cast<uint32_t>(now - lastActivity) >= idleMs; }
  uint32_t remainingSeconds(uint32_t now) const {
    uint32_t elapsed = now - lastActivity;
    return !active || elapsed >= idleMs ? 0 : (idleMs - elapsed + 999) / 1000;
  }
  void closed(uint32_t now) { active = false; suppressed = true; closedAt = now; }
  bool canOpen(uint32_t now) const { return !suppressed || static_cast<uint32_t>(now - closedAt) >= retryDelayMs; }
  void allowOpen() { suppressed = false; }
 private:
  bool active = false, suppressed = false;
  uint32_t lastActivity = 0, closedAt = 0;
};
