'use strict';
const $ = (id) => document.getElementById(id);
const esc = (value) => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const roleName = {admin:'平台管理员', provider:'服务商', user:'普通用户（老板）'};
const state = {me:null, csrf:null, accounts:[], types:[], rows:[], page:'', generation:0, search:'', status:'', minutes:30, orderPage:1, orderFilters:{}};
const pages = {
  devices:['设备控制台','查看在线状态、配置归属，并管理设备运行。','添加设备'],
  accounts:['账号与权限','服务商管理名下老板；平台管理员管理全部账号。','创建账号'],
  types:['设备类型','按设备类型配置试用时长与试用结束后的冷却时间。','添加类型'],
  merchants:['商户资料','维护商户名称与商户号。支付接入暂未开通。'],
  plans:['时长套餐','为老板的不同设备类型设置展示套餐。','添加套餐'],
  orders:['订单查询','只读查询真实订单；试用和手动放行不会产生收费订单。'],
  statistics:['收益统计','仅统计已支付订单金额，时间按北京时间计算。'],
  settings:['系统设置','配置客人页面文案与设备在线判定。'],
  profile:['个人设置','查看账号归属并修改登录密码。']
};
async function api(path, method='GET', body) {
  const headers = {'Accept':'application/json'};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET') headers[state.csrf.header_name] = state.csrf.token;
  const response = await fetch(path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(10000)});
  if(response.status===401) { location.href='/login'; throw new Error('登录已失效'); }
  const data = await response.json().catch(()=>({}));
  if(!response.ok) throw new Error(data.message || data.detail || (response.status===403?'没有此操作权限，请刷新后重试':'操作失败，请稍后重试'));
  return data;
}
let toastTimer;
function toast(message) { $('toast').textContent=message; $('toast').hidden=false; clearTimeout(toastTimer); toastTimer=setTimeout(()=>$('toast').hidden=true,3500); }
function error(message='') { $('fetch-error').textContent=message; $('fetch-error').hidden=!message; }
function money(n) { return Number(n||0).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2}); }
function time(ts) { return ts?new Date(ts*1000).toLocaleString('zh-CN',{hour12:false}):'—'; }
function duration(seconds) { const s=Math.max(0,Math.floor(seconds)); return `${Math.floor(s/3600).toString().padStart(2,'0')}:${Math.floor(s%3600/60).toString().padStart(2,'0')}:${(s%60).toString().padStart(2,'0')}`; }
const badge = (text,kind='')=>`<span class="badge ${kind}">${esc(text)}</span>`;
const action = (name,id,label,kind='')=>`<button class="row-action ${kind}" data-action="${name}" data-id="${esc(id)}">${label}</button>`;
function table(headings,rows) { return `<div class="table-wrap"><table><thead><tr>${headings.map(h=>`<th>${esc(h)}</th>`).join('')}</tr></thead><tbody>${rows.length?rows.map(cells=>`<tr>${cells.map((c,i)=>`<td data-label="${esc(headings[i])}">${c}</td>`).join('')}</tr>`).join(''):`<tr><td colspan="${headings.length}"><div class="empty-box">暂无数据</div></td></tr>`}</tbody></table></div>`; }
function card(title,description,content) { return `<section class="section-card"><div class="panel-heading"><div><h2>${esc(title)}</h2><p class="muted">${esc(description)}</p></div></div>${content}</section>`; }
function stats(items) { return `<div class="stats">${items.map(([label,value,note])=>`<div class="stat"><span>${esc(label)}</span><strong>${esc(value)}</strong><small>${esc(note)}</small></div>`).join('')}</div>`; }
function allowed(page) { return !(page==='accounts'&&state.me.role==='user' || page==='settings'&&state.me.role!=='admin'); }
async function lookups() {
  const [types,accounts]=await Promise.all([api('/admin/device-types'),state.me.role==='user'?Promise.resolve([state.me]):api('/admin/accounts')]);
  state.types=types; state.accounts=accounts;
}
async function load(quiet=false) {
  const page=state.page, generation=state.generation;
  try {
    let data;
    if(page==='devices') data=await api('/admin/devices');
    else if(page==='statistics') data=await api('/admin/statistics');
    else if(page==='orders') { const params=new URLSearchParams({page:state.orderPage,page_size:20,...state.orderFilters}); data=await api('/admin/orders?'+params); }
    else if(page==='profile') data=await api('/admin/me');
    else data=await api('/admin/'+({types:'device-types'}[page]||page));
    if(generation!==state.generation) return;
    error(); state.rows=data;
    if(page==='devices') renderDevices(data,quiet);
    else renderPage(page,data);
    $('content').setAttribute('aria-busy','false');
    $('last-sync').textContent='最近同步 '+new Date().toLocaleTimeString('zh-CN',{hour12:false})+(page==='devices'?' · 每 3 秒刷新':'');
  } catch(e) { if(generation===state.generation) { error(e.message); $('content').setAttribute('aria-busy','false'); } }
}
function route() {
  let page=location.hash.slice(1)||'devices';
  if(!pages[page] || !allowed(page)) { location.hash='devices'; page='devices'; }
  state.page=page; state.generation++;
  document.querySelectorAll('#navigation a').forEach(a=>{a.classList.toggle('active',a.dataset.page===page); if(a.dataset.page===page)a.setAttribute('aria-current','page');else a.removeAttribute('aria-current');});
  const [title,description,create]=pages[page];
  $('page-title').textContent=title; $('page-description').textContent=description; $('breadcrumb').textContent='工作空间 / '+title;
  $('create').hidden=!create || page==='types'&&state.me.role==='user'; $('create').textContent='＋ '+(create||'添加');
  $('content').innerHTML='<div class="empty-box">正在加载…</div>'; $('content').setAttribute('aria-busy','true'); error(); load();
}
function renderDevices(rows,quiet) {
  if(!quiet || !$('device-table')) {
    $('content').innerHTML='<div id="device-stats"></div>'+card('设备列表','在线设备将于下一次轮询接收控制指令。',`<div class="toolbar"><input id="device-search" class="search-input" aria-label="搜索设备" placeholder="搜索设备编号、名称、老板"><label>状态<select id="device-filter"><option value="">全部</option><option value="online">在线</option><option value="offline">离线</option><option value="running">运行中</option><option value="disabled">已停用</option></select></label><label class="duration-label">放行时长<input id="release-minutes" type="number" min="1" max="1440" value="${state.minutes}">分钟</label></div><div id="device-table"></div><div class="panel-footer">离线设备的手动指令将在恢复连接后执行。试用冷却不因手动停止而重置。</div>`);
    $('device-search').value=state.search; $('device-filter').value=state.status;
    $('device-search').addEventListener('input',e=>{state.search=e.target.value; deviceRows();});
    $('device-filter').addEventListener('change',e=>{state.status=e.target.value; deviceRows();});
    $('release-minutes').addEventListener('input',e=>{state.minutes=e.target.value;});
  }
  $('device-stats').innerHTML=stats([['全部设备',rows.length,'当前权限可见'],['在线设备',rows.filter(d=>d.online).length,'最近心跳在有效时间内'],['运行中',rows.filter(d=>d.status==='on').length,'以服务器到期时间为准'],['试用中',rows.filter(d=>d.status==='on'&&d.run_source==='trial').length,'免费试用不计入收益']]);
  deviceRows();
}
function deviceRows() {
  const focused=document.activeElement?.closest('#device-table [data-action]');
  const focusAction=focused?.dataset.action,focusId=focused?.dataset.id;
  const q=state.search.toLowerCase();
  const rows=state.rows.filter(d=>[d.device_id,d.name,d.owner_name].join(' ').toLowerCase().includes(q) && (!state.status || ({online:d.online,offline:!d.online,running:d.status==='on',disabled:!d.enabled})[state.status]));
  $('device-table').innerHTML=table(['设备','归属 / 类型','连接状态','运行 / 剩余','操作'],rows.map(d=>[
    `<strong>${esc(d.name)}</strong><small class="mono">${esc(d.device_id)}</small>`,`${esc(d.owner_name||'未分配')}<small>${esc(d.type_name)} · ${esc(d.provider_name||'平台')}</small>`,
    badge(d.online?'在线':'离线',d.online?'green':'')+(!d.enabled?' '+badge('已停用','orange'):'')+`<small>心跳 ${esc(time(d.last_seen_ts))}</small>`,
    badge(d.status==='on'?(d.run_source==='trial'?'试用中':'手动运行'):'待机',d.status==='on'?'green':'')+`<small class="mono">${duration(d.remaining_seconds)}</small><small>继电器回报：${d.relay_on===null?'尚无':d.relay_on?'通电':'断电'}</small>`,
    `<div class="row-actions">${d.enabled?action('release',d.device_id,'放行'):''}${action('stop',d.device_id,'停止','danger')}${action('edit',d.device_id,'编辑','subtle')}${action('qr',d.device_id,'二维码','subtle')}${action('key',d.device_id,'重设密钥','subtle')}</div>`]));
  if(focused) [...$('device-table').querySelectorAll('[data-action]')].find(b=>b.dataset.action===focusAction&&b.dataset.id===focusId)?.focus({preventScroll:true});
}
function renderPage(page,rows) {
  let html='';
  if(page==='accounts') html=card('账号列表','角色与上级归属在创建后固定。停用服务商会同时阻止名下老板登录和领取试用。',table(['账号','角色 / 上级','状态','操作'],rows.map(a=>[
    `<strong>${esc(a.display_name)}</strong><small>${esc(a.username)}</small>`,`${esc(roleName[a.role])}<small>${esc(a.provider_name||'—')}</small>`,badge(a.enabled?'启用':'停用',a.enabled?'green':'orange'),
    a.role!=='admin'&&a.id!==state.me.id?`<div class="row-actions">${action('edit',a.id,'编辑')}${action('password',a.id,'重置密码','subtle')}</div>`:'—'])));
  if(page==='types') html=card('类型与试用规则','试用分钟数设为 0 可关闭该类型试用。冷却从本次试用的计划结束时间开始。',table(['类型','归属','免费试用','冷却','状态','操作'],rows.map(t=>[
    `<strong>${esc(t.name)}</strong>`,esc(t.provider_name||'平台通用'),t.trial_minutes+' 分钟',t.cooldown_minutes+' 分钟',badge(t.enabled?'启用':'停用',t.enabled?'green':'orange'),state.me.role==='admin'||state.me.role==='provider'&&t.provider_id===state.me.id?action('edit',t.id,'编辑'):'只读'])));
  if(page==='merchants') html=card('商户资料','商户号仅保存业务资料；不保存支付私钥，不会发起支付或进件。',table(['商户','账号 / 类型','微信商户号','支付宝 APP ID','操作'],rows.map(m=>[
    `<strong>${esc(m.name)}</strong>`,`${esc(m.owner_name)}<small>${m.mch_type==='provider'?'服务商普通商户':'老板小微商户'}</small>`,esc((m.mch_type==='provider'?m.wechat_mch_id:m.wechat_sub_mch_id)||'未填写'),esc(m.alipay_app_id||'未填写'),action('edit',m.id,'编辑')])));
  if(page==='plans') html=card('套餐列表','套餐只对归属老板和对应设备类型生效。停用后客人页面不再展示。',table(['套餐','归属 / 类型','时长','价格','状态','操作'],rows.map(p=>[
    `<strong>${esc(p.name)}</strong>`,`${esc(p.owner_name)}<small>${esc(p.type_name)}</small>`,p.duration_min+' 分钟','¥ '+money(p.price),badge(p.enabled?'启用':'停用',p.enabled?'green':'orange'),action('edit',p.id,'编辑')])));
  if(page==='orders') {
    const filters=`<form id="order-filter" class="toolbar"><input name="device_id" aria-label="设备编号" placeholder="设备编号" value="${esc(state.orderFilters.device_id||'')}"><label>状态<select name="status"><option value="">全部</option>${Object.entries({pending:'待支付',paid:'已支付',closed:'已关闭',refunded:'已退款'}).map(([v,l])=>`<option value="${v}" ${state.orderFilters.status===v?'selected':''}>${l}</option>`).join('')}</select></label><label>开始<input type="date" name="from" value="${esc(state.orderFilters.from||'')}"></label><label>结束<input type="date" name="to" value="${esc(state.orderFilters.to||'')}"></label><button class="button secondary">查询</button></form>`;
    html=card('订单记录','当前版本不创建支付订单；有历史记录时按当前账号归属展示。',filters+table(['订单号','设备 / 老板','金额 / 时长','状态 / 渠道','创建时间'],rows.items.map(o=>[
      `<strong class="mono">${esc(o.order_no)}</strong>`,`${esc(o.device_name||o.device_id)}<small>${esc(o.owner_name||'未分配')}</small>`,`¥ ${money(o.amount)}<small>${o.duration_min} 分钟</small>`,`${esc(({pending:'待支付',paid:'已支付',closed:'已关闭',refunded:'已退款'})[o.status]||o.status)}<small>${esc(o.pay_channel||'—')}</small>`,esc(time(o.created_ts))]))+`<div class="panel-footer"><span>共 ${rows.total} 条 · 第 ${rows.page} 页</span><div class="pagination"><button class="button secondary" data-action="previous" ${rows.page<=1?'disabled':''}>上一页</button><button class="button secondary" data-action="next" ${rows.page*rows.page_size>=rows.total?'disabled':''}>下一页</button></div></div>`);
  }
  if(page==='statistics') {
    const max=Math.max(1,...rows.daily_revenue.map(d=>Number(d.revenue)));
    const chart=rows.daily_revenue.map((d,i)=>{const h=Number(d.revenue)/max*150,x=38+i*96;return `<rect class="chart-bar" x="${x}" y="${180-h}" width="45" height="${Math.max(h,1)}" rx="4"/><text class="chart-value" x="${x+22}" y="${169-h}" text-anchor="middle">${money(d.revenue)}</text><text class="chart-label" x="${x+22}" y="205" text-anchor="middle">${esc(d.date.slice(5))}</text>`;}).join('');
    html=stats([['累计实收','¥ '+money(rows.total_revenue),'仅统计已支付订单'],['今日实收','¥ '+money(rows.today_revenue),'北京时间 00:00 起'],['已支付订单',rows.paid_orders,'全部订单 '+rows.total_orders+' 笔'],['在线 / 全部设备',`${rows.online_devices} / ${rows.total_devices}`,'运行中 '+rows.running_devices+' · 试用中 '+rows.trial_devices]])+card('最近 7 日收益','退款、关闭和待支付订单均不计入。',`<div class="revenue-chart"><svg role="img" aria-label="近七天已支付订单收益" viewBox="0 0 720 225">${chart}</svg></div>`);
  }
  if(page==='settings') html=card('基础设置','更改后会应用到所有客人页面与在线状态判定。',`<form id="settings-form" class="settings-form">${field('site_name','站点名称',rows.site_name,{max:80})}${field('guest_notice','客人提示',rows.guest_notice,{textarea:true,max:240,required:false})}${field('online_timeout_seconds','在线判定时间（秒）',rows.online_timeout_seconds,{type:'number',min:6,max:300})}<p class="hint">ESP 默认每 3 秒轮询，推荐保留 12 秒，避免弱网误判。</p><button class="button primary">保存设置</button></form>`);
  if(page==='profile') html=`<div class="profile-grid">${card('账号资料','账号角色与上级由平台管理。',`<div class="profile-details"><dl><dt>显示名称</dt><dd>${esc(rows.display_name)}</dd><dt>登录账号</dt><dd>${esc(rows.username)}</dd><dt>角色</dt><dd>${esc(roleName[rows.role])}</dd><dt>所属服务商</dt><dd>${esc(rows.provider_name||'—')}</dd><dt>创建时间</dt><dd>${esc(time(rows.created_ts))}</dd></dl></div>`)}${card('登录密码','修改成功后需重新登录。',`<form id="own-password" class="settings-form">${field('current_password','当前密码','',{type:'password',max:72})}${field('password','新密码','',{type:'password',min:8,max:64})}<p class="hint">使用 8–64 个字符。密码最多 72 个 UTF-8 字节。</p><button class="button primary">修改密码</button></form>`)}</div>`;
  $('content').innerHTML=html;
  $('order-filter')?.addEventListener('submit',e=>{e.preventDefault(); state.orderFilters=Object.fromEntries([...new FormData(e.target)].filter(([,v])=>v));state.orderPage=1;load();});
  $('settings-form')?.addEventListener('submit',async e=>{e.preventDefault();await submitForm(e.target,()=>api('/admin/settings','PUT',{...Object.fromEntries(new FormData(e.target)),online_timeout_seconds:Number(e.target.elements.online_timeout_seconds.value)}));});
  $('own-password')?.addEventListener('submit',async e=>{e.preventDefault();await submitForm(e.target,async()=>{await api('/admin/me/password','POST',Object.fromEntries(new FormData(e.target)));location.href='/login';});});
}
async function submitForm(form,save) { const button=form.querySelector('[type=submit],button:not([type])');button.disabled=true;try{await save();toast('保存成功');}catch(e){error(e.message);}finally{button.disabled=false;} }
function field(name,label,value='',options={}) {
  const attrs=`name="${name}" id="field-${name}" ${options.disabled?'disabled':''} ${options.required===false?'':'required'}`;
  if(options.check) return `<label class="field check"><input type="checkbox" name="${name}" ${value?'checked':''}>${esc(label)}</label>`;
  let input;
  if(options.options) input=`<select ${attrs}>${options.options.map(([v,l])=>`<option value="${esc(v)}" ${String(v)===String(value??'')?'selected':''}>${esc(l)}</option>`).join('')}</select>`;
  else if(options.textarea) input=`<textarea ${attrs} maxlength="${options.max||240}" rows="3">${esc(value)}</textarea>`;
  else input=`<input ${attrs} type="${options.type||'text'}" value="${esc(value)}" ${options.type==='number'?`min="${options.min??0}" max="${options.max??1440}" step="${options.step||1}"`:`maxlength="${options.max||80}" ${options.min?`minlength="${options.min}"`:''}`} ${options.pattern?`pattern="${options.pattern}"`:''} ${options.type==='password'?'autocomplete="new-password"':''}>`;
  return `<label class="field" for="field-${name}">${esc(label)}${input}${options.hint?`<small>${esc(options.hint)}</small>`:''}</label>`;
}
const providers=()=>state.accounts.filter(a=>a.role==='provider').map(a=>[a.id,a.display_name]);
const owners=()=>state.accounts.filter(a=>a.role==='user').map(a=>[a.id,a.display_name]);
function ownerField(value,disabled=false,unassigned=false) { return field('user_id','所属老板',value??'',{disabled:disabled||state.me.role==='user',options:[...(unassigned?[['','未分配（无法扫码试用）']]:[]),...owners()]}); }
let editorSave;
async function edit(row=null,kind=state.page) {
  try { await lookups(); }catch(e){error(e.message);return;}
  const isNew=!row; let fields='',title='',save;
  if(kind==='devices') {
    title=isNew?'添加设备':'编辑设备';
    fields=field('device_id','设备编号',row?.device_id||'',{disabled:!isNew,max:64,pattern:'[A-Za-z0-9_\\-]{1,64}',hint:'英文字母、数字、下划线或短横线，与硬件配网保持一致。'})+field('name','设备名称',row?.name||'')+ownerField(row?.user_id??(state.me.role==='user'?state.me.id:null),false,state.me.role==='admin')+field('device_type_id','设备类型',row?.device_type_id||1,{options:state.types.map(t=>[t.id,t.name+(!t.enabled?'（已停用）':'')])})+(!isNew?field('enabled','启用设备',row.enabled,{check:true}):'');
    save=async v=>{const body={name:v.name,user_id:v.user_id?Number(v.user_id):null,device_type_id:Number(v.device_type_id)};if(isNew){const credentials=await api('/admin/devices','POST',{...body,device_id:v.device_id});showCredentials(credentials);}else{if(row.enabled&&!v.enabled&&!await confirmAction('停用后设备将断电，并关闭客人扫码入口。确认停用？'))return false;await api('/admin/devices/'+encodeURIComponent(row.device_id),'PUT',{...body,enabled:v.enabled});}};
  } else if(kind==='accounts') {
    title=isNew?'创建账号':'编辑账号';
    fields=(isNew?field('username','登录账号','',{max:40,min:3,pattern:'[A-Za-z0-9_][A-Za-z0-9_.\\-]{2,39}'}) : '')+field('display_name','显示名称',row?.display_name||'')+(isNew?field('role','角色','user',{options:state.me.role==='admin'?[['user','普通用户（老板）'],['provider','服务商']]:[['user','普通用户（老板）']]})+field('provider_id','所属服务商',state.me.role==='provider'?state.me.id:'',{disabled:state.me.role==='provider',options:providers(),hint:'普通用户必须归属于服务商；服务商账号不需要上级。'})+field('password','登录密码','',{type:'password',min:8,max:64}):field('enabled','启用账号',row.enabled,{check:true}));
    save=v=>isNew?api('/admin/accounts','POST',{...v,provider_id:v.role==='user'?Number(v.provider_id):null}):api('/admin/accounts/'+row.id,'PUT',{display_name:v.display_name,enabled:v.enabled});
  } else if(kind==='password') {
    title='重置 '+row.display_name+' 的密码'; fields=field('password','新密码','',{type:'password',min:8,max:64});
    save=v=>api('/admin/accounts/'+row.id+'/password','POST',v);
  } else if(kind==='types') {
    title=isNew?'添加设备类型':'编辑设备类型';
    fields=field('name','类型名称',row?.name||'')+field('provider_id','所属服务商',row?.provider_id??(state.me.role==='provider'?state.me.id:''),{disabled:!isNew||state.me.role==='provider',options:[...(state.me.role==='admin'?[['','平台通用']]:[]),...providers()]})+field('trial_minutes','免费试用（分钟）',row?.trial_minutes??15,{type:'number',min:0,max:1440})+field('cooldown_minutes','试用结束后冷却（分钟）',row?.cooldown_minutes??120,{type:'number',min:0,max:10080})+field('enabled','启用类型',row?.enabled??true,{check:true});
    save=v=>api('/admin/device-types'+(isNew?'':'/'+row.id),isNew?'POST':'PUT',{...v,provider_id:v.provider_id?Number(v.provider_id):null,trial_minutes:Number(v.trial_minutes),cooldown_minutes:Number(v.cooldown_minutes)});
  } else if(kind==='merchants') {
    title='编辑商户资料'; fields=field('name','商户名称',row.name)+field(row.mch_type==='provider'?'wechat_mch_id':'wechat_sub_mch_id',row.mch_type==='provider'?'微信普通商户号':'微信小微商户号',row.mch_type==='provider'?row.wechat_mch_id:row.wechat_sub_mch_id,{max:32,pattern:'[0-9]{0,32}',required:false})+field('alipay_app_id','支付宝 APP ID',row.alipay_app_id,{max:32,pattern:'[0-9]{0,32}',required:false});
    save=v=>api('/admin/merchants/'+row.id,'PUT',{wechat_mch_id:'',wechat_sub_mch_id:'',...v});
  } else if(kind==='plans') {
    title=isNew?'添加时长套餐':'编辑时长套餐'; fields=ownerField(row?.user_id??(state.me.role==='user'?state.me.id:null),!isNew)+field('device_type_id','设备类型',row?.device_type_id||1,{options:state.types.map(t=>[t.id,t.name+(!t.enabled?'（已停用）':'')])})+field('name','套餐名称',row?.name||'')+field('duration_min','使用时长（分钟）',row?.duration_min||30,{type:'number',min:1,max:1440})+field('price','展示价格（元）',row?.price??5,{type:'number',min:0,max:99999.99,step:0.01})+field('enabled','启用套餐',row?.enabled??true,{check:true});
    save=v=>api('/admin/plans'+(isNew?'':'/'+row.id),isNew?'POST':'PUT',{...v,user_id:Number(v.user_id),device_type_id:Number(v.device_type_id),duration_min:Number(v.duration_min),price:Number(v.price)});
  }
  $('editor-title').textContent=title; $('editor-description').textContent='保存时会校验当前账号的操作权限和数据归属。'; $('editor-fields').innerHTML=fields; $('editor-error').textContent=''; editorSave=save;
  if(kind==='accounts'&&isNew) {const role=$('field-role'),parent=$('field-provider_id');const update=()=>{parent.required=role.value==='user';parent.closest('label').hidden=role.value!=='user';};role.addEventListener('change',update);update();}
  if(kind==='devices'||kind==='plans') {
    const owner=$('field-user_id'),type=$('field-device_type_id');
    const update=()=>{const old=type.value,account=state.accounts.find(a=>a.id===Number(owner.value));const available=state.types.filter(t=>t.provider_id===null || t.provider_id===account?.provider_id);type.innerHTML=available.map(t=>`<option value="${t.id}">${esc(t.name+(!t.enabled?'（已停用）':''))}</option>`).join('');if(available.some(t=>String(t.id)===old))type.value=old;};owner.addEventListener('change',update);update();
  }
  $('editor').showModal();
}
$('editor-form').addEventListener('submit',async e=>{
  e.preventDefault();const button=e.target.querySelector('[type=submit]');button.disabled=true;$('editor-error').textContent='';
  const values={}; for(const control of e.target.elements)if(control.name)values[control.name]=control.type==='checkbox'?control.checked:control.value;
  try {const result=await editorSave(values);if(result===false)return;$('editor').close();toast('保存成功');await load(true);}catch(err){$('editor-error').textContent=err.message;}finally{button.disabled=false;}
});
function confirmAction(message) { return new Promise(resolve=>{const dialog=$('confirmation');$('confirmation-text').textContent=message;dialog.returnValue='';dialog.addEventListener('close',()=>resolve(dialog.returnValue==='confirm'),{once:true});dialog.showModal();}); }
function showCredentials(value) { $('credential-id').value=value.device_id;$('credential-key').value=value.device_key;$('credentials').showModal(); }
$('credentials').addEventListener('close',()=>{$('credential-key').value='';});
async function rowAction(name,id) {
  if(name==='previous'||name==='next') {state.orderPage+=name==='next'?1:-1;await load();return;}
  const row=state.rows.find(r=>String(r.device_id??r.id)===id);
  if(!row) return;
  if(name==='edit'||name==='password') {await edit(row,name==='password'?'password':state.page);return;}
  if(name==='qr') {
    const link=await api('/admin/devices/'+encodeURIComponent(id)+'/guest-link');$('qr-title').textContent=row.name+' · '+id;$('qr-link').value=link.url;$('qr-open').href=link.url;
    $('qr-image').src='/admin/devices/'+encodeURIComponent(id)+'/qr.png';$('qr-download').href=$('qr-image').src;$('qr-download').download=id+'-qr.png';
    const host=new URL(link.url).hostname;const local=host==='localhost'||host==='127.0.0.1'||host==='[::1]';$('qr-warning').hidden=!local;$('qr-warning').textContent='当前地址仅本机可访问。手机使用前，请把服务器的 RENT_GATE_PUBLIC_BASE_URL 配置为手机可访问的域名或 IP 地址。';$('qr-dialog').showModal();return;
  }
  if(name==='release') {
    const minutes=Number(state.minutes);if(!Number.isInteger(minutes)||minutes<1||minutes>1440)throw new Error('放行时长需为 1–1440 的整数');
    if(row.status==='on'&&!await confirmAction('设备正在运行。重新放行将从现在起计算 '+minutes+' 分钟，确认继续？'))return;
    await api('/admin/release','POST',{device_id:id,duration_min:minutes});toast('放行已下发，等待设备轮询');
  }
  if(name==='stop') {if(!await confirmAction('确认停止 '+row.name+'？设备会在下一次轮询时断电。'))return;await api('/admin/stop','POST',{device_id:id});toast('停止指令已下发');}
  if(name==='key') {if(!await confirmAction('重设后旧密钥立即失效，设备需要重新填写密钥才能连接。确认继续？'))return;showCredentials(await api('/admin/rotate-key','POST',{device_id:id}));}
  await load(true);
}
$('content').addEventListener('click',e=>{const button=e.target.closest('[data-action]');if(!button||button.disabled)return;button.disabled=true;rowAction(button.dataset.action,button.dataset.id).catch(e=>error(e.message)).finally(()=>button.disabled=false);});
document.addEventListener('click',e=>{const close=e.target.closest('[data-close]');if(close)$(close.dataset.close).close();});
$('copy-key').addEventListener('click',async()=>{try{await navigator.clipboard.writeText($('credential-key').value);toast('密钥已复制');}catch{$('credential-key').select();toast('请手动复制选中的密钥');}});
$('help').addEventListener('click',()=>$('help-dialog').showModal());
$('create').addEventListener('click',()=>edit());
$('refresh').addEventListener('click',()=>load());
$('logout').addEventListener('click',()=>{const form=document.createElement('form');form.method='POST';form.action='/logout';const input=document.createElement('input');input.type='hidden';input.name='_csrf';input.value=state.csrf.token;form.append(input);document.body.append(form);form.submit();});
window.addEventListener('hashchange',()=>{if(state.me)route();});
async function init() {
  try {
    [state.me,state.csrf]=await Promise.all([api('/admin/me'),api('/admin/csrf')]);
    $('actor-role').textContent=roleName[state.me.role];$('actor-name').textContent=state.me.display_name;
    document.querySelectorAll('[data-staff]').forEach(e=>e.hidden=state.me.role==='user');document.querySelectorAll('[data-admin]').forEach(e=>e.hidden=state.me.role!=='admin');
    $('scope-note').textContent=state.me.role==='admin'?'平台全部数据':state.me.role==='provider'?'仅显示名下老板数据':'仅显示本账号数据';
    await lookups();route();
    setInterval(()=>{if(state.page==='devices'&&!document.hidden)load(true);},3000);
  }catch(e){error(e.message);}
}
init();
