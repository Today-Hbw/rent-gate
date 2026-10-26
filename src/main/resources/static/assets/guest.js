'use strict';
const element=id=>document.getElementById(id);
const token=document.querySelector('meta[name="guest-token"]').content;
const endpoint=`/api/public/devices/${encodeURIComponent(token)}`;
let state,csrf,connected=false,loading=false,starting=false,selectedPlan=null;
let resyncCountdown=false;
const countdown=new GuestCountdown();
const escapeHtml=value=>String(value).replace(/[&<>"']/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const formatTime=value=>[Math.floor(value/3600),Math.floor(value/60)%60,value%60].map(v=>String(v).padStart(2,'0')).join(':');
function render() {
  if (!state) return;
  const now=performance.now(),remaining=countdown.remaining(now),cooldown=countdown.cooldown(now);
  if (remaining===0) element('trial-result').hidden=true;
  element('site-name').textContent=state.site_name; element('device-name').textContent=state.name;
  element('owner-name').textContent=state.owner_name?'设备归属：'+state.owner_name:state.merchant_name;
  element('merchant-name').textContent='商户：'+state.merchant_name;
  element('merchant-name').hidden=!state.owner_name||state.merchant_name===state.owner_name;
  element('type-name').textContent=state.type_name;
  element('online-badge').textContent=connected?(state.online?'设备在线':'设备离线'):'连接中断';
  element('online-badge').className=`status-pill ${connected&&state.online?'online':''}`;
  element('state-title').textContent=remaining>0?(state.status==='trial'?'正在免费试用':'设备使用中'):cooldown>0?'试用冷却中':'欢迎体验';
  element('timer-label').textContent=remaining>0?'剩余使用时间':cooldown>0?'距离下次试用':'免费试用时长';
  element('remaining').textContent=formatTime(remaining>0?remaining:cooldown>0?cooldown:state.trial_minutes*60);
  element('lease-progress').value=remaining>0?Math.min(100,remaining/Math.max(1,state.trial_minutes*60)*100):0;
  element('state-note').textContent=remaining>0?'到期后自动结束，请安心使用。':cooldown>0?'本次体验已结束，冷却到期后可再次试用。':state.trial_unavailable_reason||'点击下方按钮，免费体验设备。';
  const available=connected&&state.trial_available&&!starting&&remaining===0;
  element('trial-button').disabled=!available;
  element('trial-button').textContent=starting?'正在发送开机指令…':remaining>0?'设备使用中':cooldown>0?'冷却中，暂不可试用':!connected?'等待网络恢复':`免费试用 ${state.trial_minutes} 分钟`;
  element('trial-rule').textContent=`每次试用 ${state.trial_minutes} 分钟 · 结束后冷却 ${state.cooldown_minutes} 分钟`;
  element('guest-notice').textContent=state.guest_notice;
}
function applyState(data) {
  countdown.update(data,performance.now(),resyncCountdown);resyncCountdown=false;state=data;connected=true;render();
  const previous=selectedPlan;
  if (!data.plans.some(plan=>plan.id===selectedPlan)) selectedPlan=data.plans[0]?.id??null;
  const signature=JSON.stringify(data.plans);
  if (element('plans').dataset.signature!==signature || previous!==selectedPlan) {
    element('plans').dataset.signature=signature;
    element('plans').innerHTML=data.plans.map(plan=>`<button class="plan" data-plan="${plan.id}" aria-pressed="${plan.id===selectedPlan}"><strong>${escapeHtml(plan.name)}</strong><small>${plan.duration_min} 分钟</small><span>${Number(plan.price).toFixed(2)}</span></button>`).join('');
  }
  element('plans-empty').hidden=data.plans.length!==0;
}
async function request(url,body) {
  const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),8000);
  try {
    const options={signal:controller.signal,headers:{Accept:'application/json'}};
    if (body!==undefined) {
      options.method='POST'; options.headers['Content-Type']='application/json';
      options.headers[csrf.header_name]=csrf.token; options.body=JSON.stringify(body);
    }
    const response=await fetch(url,options),data=await response.json().catch(()=>({}));
    if (!response.ok) throw new Error(data.message||(response.status===404?'此设备入口不可用，请联系老板':response.status===403?'页面验证已过期，请刷新后重试':'暂时无法连接，请稍后重试'));
    return data;
  } finally { clearTimeout(timeout); }
}
async function refresh() {
  if (loading || starting || document.hidden) return;
  loading=true;
  try { applyState(await request(endpoint)); element('guest-error').hidden=true; }
  catch(error) {
    connected=false; render();
    if (!state) {
      element('device-name').textContent='暂时无法读取设备'; element('merchant-name').textContent='请确认二维码或联系老板';
      element('owner-name').textContent='请确认二维码或联系老板';
      element('online-badge').textContent='未连接'; element('state-title').textContent='设备入口暂不可用';
      element('state-note').textContent='网络恢复后会自动重试。'; element('trial-button').textContent='等待连接';
    }
    element('guest-error').textContent=error.message; element('guest-error').hidden=false;
  }
  finally { loading=false; }
}
element('trial-button').addEventListener('click',async()=> {
  if (starting || !state?.trial_available || !connected) return;
  starting=true; render(); element('trial-result').hidden=true;
  try {
    csrf=await request('/api/public/csrf');
    applyState(await request(`${endpoint}/trial`,{}));
    element('trial-result').textContent='开机指令已发送，设备收到后会自动启动。'; element('trial-result').hidden=false;
    element('guest-error').hidden=true;
  } catch(error) { element('guest-error').textContent=error.message; element('guest-error').hidden=false; }
  finally { starting=false; render(); refresh(); }
});
element('plans').addEventListener('click',event=> {
  const button=event.target.closest('[data-plan]'); if (!button) return;
  selectedPlan=Number(button.dataset.plan);
  element('plans').querySelectorAll('[data-plan]').forEach(plan=>plan.setAttribute('aria-pressed',String(Number(plan.dataset.plan)===selectedPlan)));
});
document.addEventListener('visibilitychange',()=>{if (!document.hidden){resyncCountdown=true;refresh();}});
refresh(); setInterval(refresh,3000); setInterval(render,250);
