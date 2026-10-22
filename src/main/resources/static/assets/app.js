'use strict';
const $ = id => document.getElementById(id);
let devices = [], csrf, fetching = false, busy = false, toastTimer;
const escapeHtml = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const duration = () => Number($('duration').value);
function toast(message) {
  $('toast').textContent = message; $('toast').hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('toast').hidden = true; }, 5000);
}
async function api(path, body) {
  const options = {headers: {'Accept':'application/json'}, redirect: 'error'};
  if (body !== undefined) {
    options.method = 'POST'; options.headers['Content-Type'] = 'application/json';
    options.headers[csrf.header_name] = csrf.token; options.body = JSON.stringify(body);
  }
  const response = await fetch(path, options);
  if (response.status === 401) { location.assign('/login'); throw new Error('登录已过期'); }
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.message || (response.status === 403 ? '操作验证已过期，请刷新页面后重试' : `请求失败（${response.status}）`));
  }
  return response.json();
}
function remaining(seconds) {
  return `${String(Math.floor(seconds/3600)).padStart(2,'0')}:${String(Math.floor(seconds/60)%60).padStart(2,'0')}:${String(seconds%60).padStart(2,'0')}`;
}
function render() {
  $('total').textContent = devices.length;
  const online = devices.filter(d => d.online).length;
  $('online').textContent = online; $('offline').textContent = devices.length - online;
  $('running').textContent = devices.filter(d => d.status === 'on').length;
  $('device-count').textContent = devices.length;
  const term = $('search').value.trim().toLowerCase(), filter = $('status-filter').value;
  const shown = devices.filter(d => (d.name.toLowerCase().includes(term) || d.device_id.toLowerCase().includes(term)) &&
    (filter === 'all' || filter === 'online' && d.online || filter === 'offline' && !d.online || filter === 'running' && d.status === 'on'));
  $('devices').innerHTML = shown.map(d => `<tr><td><div class="device-name"><span class="device-symbol">▦</span><div><strong>${escapeHtml(d.name)}</strong><small>${escapeHtml(d.device_id)}</small></div></div></td>
    <td><span class="badge ${d.online?'online':''}"><i></i>${d.online?'在线':'离线'}</span></td>
    <td><span class="badge ${d.status==='on'?'running':''}">${d.status==='on'?'放行中':'待机'}</span><span class="report">${d.relay_on === null ? '尚无继电器上报' : `${d.online?'上报':'上次上报'}：${d.relay_on?'已吸合':'已断开'}`}</span></td>
    <td><span class="time">${d.status==='on'?remaining(d.remaining_seconds):'—'}</span></td>
    <td class="heartbeat">${d.last_seen_ts === null ? '尚未连接' : new Date(d.last_seen_ts*1000).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false})}</td>
    <td><div class="row-actions"><button data-action="release" data-id="${escapeHtml(d.device_id)}" ${busy?'disabled':''}>开机 ${Number.isInteger(duration())&&duration()>0?duration():30} 分钟</button><button class="stop" data-action="stop" data-id="${escapeHtml(d.device_id)}" ${busy||d.status!=='on'?'disabled':''}>停止</button><button class="reset" data-action="rotate-key" data-id="${escapeHtml(d.device_id)}" ${busy?'disabled':''}>重置密钥</button></div></td></tr>`).join('');
  $('shown').textContent = `显示 ${shown.length} 台 / 共 ${devices.length} 台设备`;
  $('empty').hidden = shown.length !== 0;
  $('empty-title').textContent = devices.length ? '未找到匹配设备' : '还没有设备';
  $('empty-text').textContent = devices.length ? '试试其他名称、编号或连接状态。' : '添加第一台设备，开始验证开机与倒计时。';
  $('empty-add').hidden = devices.length > 0;
}
async function refresh() {
  if (fetching) return;
  fetching = true; $('refresh').disabled = true;
  try {
    devices = await api('/admin/devices'); render();
    $('fetch-error').hidden = true; $('sync-dot').classList.remove('failed');
    $('last-sync').textContent = `同步于 ${new Date().toLocaleTimeString('zh-CN',{hour12:false})} · 每 3 秒刷新`;
  } catch(error) {
    $('fetch-error').textContent = `刷新失败，当前数据可能已过期：${error.message}`; $('fetch-error').hidden = false;
    $('sync-dot').classList.add('failed'); $('last-sync').textContent = '连接异常 · 正在自动重试';
  } finally { fetching = false; $('refresh').disabled = false; }
}
function openCreate() { $('create-error').textContent=''; $('create-dialog').showModal(); }
function showCredentials(result) {
  $('credential-id').value = result.device_id; $('credential-key').value = result.device_key;
  $('key-dialog').showModal();
}
document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => $(button.dataset.close).close()));
$('key-dialog').addEventListener('close', () => { $('credential-key').value = ''; $('credential-id').value = ''; });
$('add-device').addEventListener('click', openCreate); $('empty-add').addEventListener('click', openCreate);
$('help-nav').addEventListener('click', () => $('help-dialog').showModal());
$('refresh').addEventListener('click', refresh);
['search','status-filter','duration'].forEach(id => $(id).addEventListener('input', render));
$('create-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = event.submitter; button.disabled = true; $('create-error').textContent='';
  try {
    const result = await api('/admin/devices', Object.fromEntries(new FormData(event.target)));
    $('create-dialog').close(); event.target.reset(); showCredentials(result); await refresh();
  } catch(error) { $('create-error').textContent = error.message; } finally { button.disabled = false; }
});
$('copy-key').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText($('credential-key').value); toast('密钥已复制，请妥善保存'); }
  catch { $('credential-key').select(); toast('请手动复制选中的密钥'); }
});
$('devices').addEventListener('click', async event => {
  const button = event.target.closest('button[data-action]'); if (!button || busy) return;
  const {action,id} = button.dataset, minutes = duration();
  if (action === 'release' && (!Number.isInteger(minutes) || minutes < 1 || minutes > 1440)) return toast('时长请输入 1–1440 的整数分钟');
  if (action === 'stop' && !confirm(`停止 ${id}？设备将在下一次成功轮询时断电。`)) return;
  if (action === 'rotate-key' && !confirm(`重置 ${id} 的密钥？旧密钥立即失效，设备需重新配网。已有倒计时仍会在本地到期。`)) return;
  if (action === 'release' && devices.find(d => d.device_id === id)?.status === 'on' && !confirm(`设备正在放行中。将从现在重新计时 ${minutes} 分钟，覆盖原到期时间？`)) return;
  busy = true; render();
  try {
    const result = await api(`/admin/${action}`, {device_id:id, ...(action === 'release'?{duration_min:minutes}:{})});
    if (action === 'rotate-key') showCredentials(result);
    else toast(action === 'release' ? '开机指令已保存，等待设备轮询执行' : '停止指令已保存，等待设备轮询执行');
    await refresh();
  } catch(error) { toast(error.message); } finally { busy = false; render(); }
});
$('logout').addEventListener('click', async () => {
  const form = document.createElement('form'); form.method='POST'; form.action='/logout';
  const input = document.createElement('input'); input.type='hidden'; input.name='_csrf'; input.value=csrf.token;
  form.append(input); document.body.append(form); form.submit();
});
(async () => {
  try { csrf = await api('/admin/csrf'); await refresh(); setInterval(refresh,3000); }
  catch(error) { $('fetch-error').hidden=false; $('fetch-error').textContent=error.message; }
})();
