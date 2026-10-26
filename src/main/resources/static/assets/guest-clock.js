'use strict';
// Keep one monotonic deadline per server expiry. Polling updates metadata, not the tick phase.
class GuestCountdown {
  constructor() { this.expiry=null;this.cooldownExpiry=null;this.deadline=0;this.cooldownDeadline=0; }
  update(data,now,resync=false) {
    if(data.expire_ts!==this.expiry||resync) {
      this.expiry=data.expire_ts;this.deadline=now+Math.max(0,data.expire_ts-data.server_ts)*1000;
    } else if(data.expire_ts<=data.server_ts) this.deadline=Math.min(this.deadline,now);
    if(data.cooldown_until!==this.cooldownExpiry||resync) {
      this.cooldownExpiry=data.cooldown_until;this.cooldownDeadline=now+Math.max(0,data.cooldown_until-data.server_ts)*1000;
    } else if(data.cooldown_until<=data.server_ts) this.cooldownDeadline=Math.min(this.cooldownDeadline,now);
  }
  remaining(now) { return Math.max(0,Math.ceil((this.deadline-now)/1000)); }
  cooldown(now) { return Math.max(0,Math.ceil((this.cooldownDeadline-now)/1000)); }
}
if(typeof module!=='undefined')module.exports=GuestCountdown;
