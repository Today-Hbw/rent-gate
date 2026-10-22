#include <assert.h>
#include "../include/LeaseTimer.h"

int main() {
  LeaseTimer timer;
  assert(!timer.tick(0));
  assert(timer.grant(1000, 60000, 500));
  assert(timer.tick(60499));
  assert(!timer.grant(1000, 60000, 1000)); // same poll never extends deadline
  assert(!timer.tick(60500));
  assert(!timer.grant(1000, 1000, 61000)); // expired response cannot reactivate relay
  assert(!timer.active);
  assert(timer.grant(2000, 1000, 62000));
  timer.stop();
  assert(!timer.tick(62001));
  assert(timer.grant(3000, 2000, UINT32_MAX - 1000));
  assert(timer.tick(500)); // millis() wraps after ~49.7 days
  assert(!timer.tick(1000));
  assert(timer.grant(4000, 0, 5000));
  assert(!timer.tick(5000));
}
