const test=require('node:test');
const assert=require('node:assert/strict');
const GuestCountdown=require('../src/main/resources/static/assets/guest-clock.js');

test('jittery three-second polls never reset or double-step the countdown',()=>{
  const clock=new GuestCountdown();
  clock.update({server_ts:1000,expire_ts:1060,cooldown_until:1120},0);
  let previous=clock.remaining(0);
  for(let now=250;now<=65000;now+=250){
    if([3250,6000,9500,12250,15500].includes(now))
      clock.update({server_ts:1000+Math.floor(now/1000)+1,expire_ts:1060,cooldown_until:1120},now);
    const current=clock.remaining(now);
    assert.ok(previous-current===0||previous-current===1,`${previous} -> ${current} at ${now}`);
    previous=current;
  }
  assert.equal(clock.remaining(60000),0);
  assert.equal(clock.cooldown(65000),55);
});
test('changed expiries and explicit stop update immediately, stale repeats do not reactivate',()=>{
  const clock=new GuestCountdown();
  clock.update({server_ts:1000,expire_ts:1060,cooldown_until:1120},0);
  clock.update({server_ts:1005,expire_ts:1125,cooldown_until:1120},5000);
  assert.equal(clock.remaining(5000),120);
  clock.update({server_ts:1006,expire_ts:0,cooldown_until:1120},6000);
  assert.equal(clock.remaining(6000),0);
  clock.update({server_ts:999,expire_ts:0,cooldown_until:1120},7000);
  assert.equal(clock.remaining(7000),0);
  assert.equal(clock.cooldown(7000),113);
});
test('returning from a suspended page resynchronizes and server expiry cannot stay running',()=>{
  const clock=new GuestCountdown();
  clock.update({server_ts:1000,expire_ts:1060,cooldown_until:1120},0);
  clock.update({server_ts:1030,expire_ts:1060,cooldown_until:1120},1000,true);
  assert.equal(clock.remaining(1000),30);
  clock.update({server_ts:1060,expire_ts:1060,cooldown_until:1120},2000);
  assert.equal(clock.remaining(2000),0);
});
