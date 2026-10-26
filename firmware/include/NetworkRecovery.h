#pragma once
#include <stdint.h>

// Only decides network actions; never changes a running relay lease.
class NetworkRecovery {
 public:
  enum class Action { None, StartPortal, StopPortal, RetryWifi };
  static constexpr uint32_t retryMs = 30000;

  Action update(uint32_t now, bool connected, bool configured, bool portalActive,
                bool portalHasClients, bool needsSetup) {
    if (connected) {
      offline = false;
    } else if (!offline) {
      offline = true;
      offlineSince = now;
    }

    if (!portalActive) {
      lastRetry = now;
      if (!configured || needsSetup || (offline && elapsed(now, offlineSince))) {
        return Action::StartPortal;
      }
    } else {
      if (connected && configured && !needsSetup) return Action::StopPortal;
      // Leave the radio alone while someone is using the phone setup page.
      if (portalHasClients || connected) lastRetry = now;
      else if (elapsed(now, lastRetry)) {
        lastRetry = now;
        return Action::RetryWifi;
      }
    }
    return Action::None;
  }

 private:
  bool offline = false;
  uint32_t offlineSince = 0;
  uint32_t lastRetry = 0;

  static bool elapsed(uint32_t now, uint32_t since) {
    return static_cast<uint32_t>(now - since) >= retryMs;
  }
};
