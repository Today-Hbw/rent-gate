#pragma once
#include <stdint.h>

// Pure monotonic timer. Same lease never gains time from polling or network loss.
class LeaseTimer {
 public:
  bool active = false;
  uint32_t started = 0;
  uint32_t duration = 0;
  int64_t lastExpiry = 0;

  bool grant(int64_t expiry, uint32_t remainingMs, uint32_t now) {
    if (expiry == lastExpiry) return false;
    lastExpiry = expiry;
    started = now;
    duration = remainingMs;
    active = remainingMs > 0;
    return true;
  }
  void stop() { active = false; lastExpiry = 0; }
  bool tick(uint32_t now) {
    if (active && static_cast<uint32_t>(now - started) >= duration) active = false;
    return active;
  }
};
