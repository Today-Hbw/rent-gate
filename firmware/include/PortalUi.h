#pragma once
static const char portalHead[] = R"HTML(
<meta charset="UTF-8"><script>
document.addEventListener('DOMContentLoaded',function(){
var bar=document.createElement('section');bar.style.cssText='padding:12px;border:1px solid #ddd;border-radius:8px;margin:12px 0';
bar.innerHTML='<p id="portal-message"></p><small id="portal-remaining"></small><p><a href="/wifi">返回配置</a> <button id="portal-exit" type="button">取消并关闭热点</button></p>';
document.body.prepend(bar);
var lastTouch=0;
function touch(){if(Date.now()-lastTouch<3000)return;lastTouch=Date.now();fetch('/portal/activity',{method:'POST',headers:{'X-RentGate-Portal':'1'}}).catch(function(){});}
['input','change','pointerdown','keydown'].forEach(function(event){document.addEventListener(event,touch,true);});
document.getElementById('portal-exit').onclick=function(){this.disabled=true;fetch('/portal/close',{method:'POST',headers:{'X-RentGate-Portal':'1'}}).then(function(r){if(!r.ok)throw Error();document.getElementById('portal-message').textContent='热点正在关闭，请将手机切回正常网络。';}).catch(function(){document.getElementById('portal-exit').disabled=false;});};
function status(){fetch('/portal/state').then(function(r){return r.json();}).then(function(s){document.getElementById('portal-message').textContent=s.message;document.getElementById('portal-remaining').textContent='无操作自动关闭，剩余 '+s.remaining_seconds+' 秒';}).catch(function(){});}
status();setInterval(status,2000);
});</script>
)HTML";
