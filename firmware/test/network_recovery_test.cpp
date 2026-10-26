#include <assert.h>
#include "../include/LeaseTimer.h"
#include "../include/NetworkRecovery.h"

using Action = NetworkRecovery::Action;

int main() {
  NetworkRecovery fresh;
  assert(fresh.update(0, false, false, false, false, false) == Action::StartPortal);
  assert(fresh.update(180001, false, false, true, true, false) == Action::None);

  NetworkRecovery network;
  assert(network.update(100, false, true, false, false, false) == Action::None);
  assert(network.update(30099, false, true, false, false, false) == Action::None);
  assert(network.update(30100, false, true, false, false, false) == Action::StartPortal);
  assert(network.update(60099, false, true, true, false, false) == Action::None);
  assert(network.update(60100, false, true, true, false, false) == Action::RetryWifi);
  // A connected phone prevents retries from disrupting WiFi setup.
  assert(network.update(90100, false, true, true, true, false) == Action::None);
  assert(network.update(120099, false, true, true, false, false) == Action::None);
  assert(network.update(120100, false, true, true, false, false) == Action::RetryWifi);
  assert(network.update(120101, true, true, true, false, false) == Action::StopPortal);

  // A brief drop recovers without a portal; each new outage gets its own grace.
  assert(network.update(130000, false, true, false, false, false) == Action::None);
  assert(network.update(131000, true, true, false, false, false) == Action::None);
  assert(network.update(200000, false, true, false, false, false) == Action::None);
  assert(network.update(229999, false, true, false, false, false) == Action::None);
  assert(network.update(230000, false, true, false, false, false) == Action::StartPortal);

  // Invalid device settings and rotated keys must be editable even on good WiFi.
  assert(network.update(230001, true, false, true, true, false) == Action::None);
  assert(network.update(230002, true, true, true, true, true) == Action::None);
  assert(network.update(230003, true, true, true, true, false) == Action::StopPortal);
  assert(network.update(230004, true, true, false, false, true) == Action::StartPortal);

  NetworkRecovery wrapping;
  assert(wrapping.update(UINT32_MAX - 10000, false, true, false, false, false) == Action::None);
  assert(wrapping.update(19998, false, true, false, false, false) == Action::None);
  assert(wrapping.update(19999, false, true, false, false, false) == Action::StartPortal);

  // Starting/retrying a portal cannot cancel or extend an active rental lease.
  LeaseTimer lease;
  NetworkRecovery outage;
  assert(lease.grant(1000, 60000, 0));
  assert(outage.update(0, false, true, false, false, false) == Action::None);
  assert(outage.update(30000, false, true, false, false, false) == Action::StartPortal);
  assert(lease.tick(59999));
  assert(outage.update(60000, false, true, true, false, false) == Action::RetryWifi);
  assert(!lease.tick(60000));
}
