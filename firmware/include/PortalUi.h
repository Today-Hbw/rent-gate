#pragma once
static const char portalHead[] = R"HTML(
<meta charset="UTF-8"><script>
document.addEventListener('DOMContentLoaded',function(){
var bar=document.createElement('section');bar.style.cssText='padding:12px;border:1px solid #ddd;border-radius:8px;margin:12px 0';
bar.innerHTML='<p id="portal-binding"></p><small id="portal-hardware"></small><p id="portal-message"></p><small id="portal-remaining"></small><p><a href="/">配对首页</a> <a href="/wifi">手动配置</a> <button id="portal-exit" type="button">取消并关闭热点</button></p>';
document.body.prepend(bar);
var lastTouch=0;
function touch(){if(Date.now()-lastTouch<3000)return;lastTouch=Date.now();fetch('/portal/activity',{method:'POST',headers:{'X-RentGate-Portal':'1'}}).catch(function(){});}
['input','change','pointerdown','keydown'].forEach(function(event){document.addEventListener(event,touch,true);});
document.getElementById('portal-exit').onclick=function(){this.disabled=true;fetch('/portal/close',{method:'POST',headers:{'X-RentGate-Portal':'1'}}).then(function(r){if(!r.ok)throw Error();document.getElementById('portal-message').textContent='热点正在关闭，请将手机切回正常网络。';}).catch(function(){document.getElementById('portal-exit').disabled=false;});};
function status(){fetch('/portal/state').then(function(r){return r.json();}).then(function(s){document.getElementById('portal-message').textContent=s.message;document.getElementById('portal-remaining').textContent='无操作自动关闭，剩余 '+s.remaining_seconds+' 秒';document.getElementById('portal-binding').textContent=s.device_id?(s.binding_verified?'当前绑定：':'本地配置（待认证）：')+(s.device_name||'名称待同步')+'（'+s.device_id+'）':'尚未绑定后台设备';document.getElementById('portal-hardware').textContent='本机 MAC：'+s.hardware_id+' · 固件 '+s.firmware_version;var ssid=document.getElementById('quick-ssid');if(ssid&&!ssid.dataset.initialized){ssid.value=s.wifi_ssid||'';ssid.dataset.initialized='1';}}).catch(function(){});}
status();setInterval(status,2000);
});</script>
)HTML";
static const char portalHome[] = R"HTML(
<style>body{font-family:system-ui,sans-serif;max-width:440px;margin:20px auto;padding:0 16px;color:#244b3c}input,button{box-sizing:border-box;font:inherit;padding:12px;border:1px solid #ccc;border-radius:6px}label{display:block;margin:16px 0}input{display:block;width:100%;margin-top:6px}button[type=submit]{width:100%;background:#147d68;color:white}small{color:#687d71}#quick-result{white-space:pre-wrap}</style>
<h1>设备配对</h1><p>填写 WiFi 和后台生成的验证码，保存后关闭热点并自动配对。</p>
<form id="quick-form"><label>WiFi 名称<input id="quick-ssid" name="ssid" maxlength="32" required autocomplete="off"></label><label>WiFi 密码<input name="password" type="password" maxlength="64" placeholder="留空沿用相同 WiFi 的原密码" autocomplete="off"></label><label>配对验证码<input name="code" inputmode="numeric" pattern="[0-9]{8}" minlength="8" maxlength="8" required autocomplete="off"></label><button type="submit">保存并关闭热点</button></form>
<p id="quick-result" role="status"></p><p><a href="/wifi">手动配置（Config）</a></p>
<script>document.getElementById('quick-form').onsubmit=function(e){e.preventDefault();var form=e.target,button=form.querySelector('button');button.disabled=true;fetch('/portal/quick-pair',{method:'POST',headers:{'X-RentGate-Portal':'1'},body:new URLSearchParams(new FormData(form))}).then(function(r){return r.json().then(function(data){if(!r.ok)throw Error(data.message);return data;});}).then(function(){document.getElementById('quick-result').textContent='已保存，热点正在关闭。设备将在后台连接 WiFi 并配对，请将手机切回正常网络。';}).catch(function(error){document.getElementById('quick-result').textContent=error.message||'保存失败，请重试';button.disabled=false;});};</script>
)HTML";
