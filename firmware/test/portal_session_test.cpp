#include <assert.h>
#include "../include/PortalSession.h"
#include "../include/LeaseTimer.h"
int main() {
  PortalSession session;
  session.opened(100);
  assert(session.remainingSeconds(100) == 180);
  assert(!session.expired(180099));
  assert(session.expired(180100)); // polling status never renews the timer
  session.touch(180099);
  assert(!session.expired(180100));
  assert(session.expired(360099));
  session.closed(360100);
  assert(!session.canOpen(420099));
  assert(session.canOpen(420100));
  session.closed(420100);
  session.allowOpen(); // a new explicit backend request can open immediately
  assert(session.canOpen(420101));
  session.opened(UINT32_MAX - 1000);
  assert(!session.expired(178998));
  assert(session.expired(178999));
  LeaseTimer lease;
  lease.grant(1, 1000, 0);
  session.closed(500);
  assert(lease.tick(999));
  assert(!lease.tick(1000)); // closing setup never changes a running lease
}
