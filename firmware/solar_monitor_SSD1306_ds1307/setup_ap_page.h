#pragma once

// The Wi-Fi setup page served by setup_ap.cpp at http://192.168.4.1/.
// Self-contained (no internet on the hotspot): inline CSS and JS only. Talks
// to /scan.json, /save and /status.json. Network names come from any router
// in range, so they are only ever inserted as text (textContent), never HTML.
// tests/firmware/portal_test.js serves this exact string against mock
// endpoints in a phone-sized browser.

static const char SETUP_PAGE[] = R"SETUPPAGE(<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Solar Monitor Wi-Fi setup</title>
<style>
:root{--p:#1e6b2e;--on-p:#fff;--pc:#a8f4a9;--on-pc:#00210a;--sc:#d3e8d0;--on-sc:#0e1f10;
--bg:#f6fbf2;--card:#fff;--tx:#181d17;--mu:#424940;--ol:#72796f;--olv:#c2c9bd;--err:#ba1a1a;}
@media (prefers-color-scheme:dark){:root{--p:#8cd88f;--on-p:#003915;--pc:#005321;--on-pc:#a8f4a9;
--sc:#394b39;--on-sc:#d3e8d0;--bg:#101510;--card:#181d17;--tx:#dfe4db;--mu:#c2c9bd;--ol:#8c9388;
--olv:#424940;--err:#ffb4ab;}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--tx);font:16px/1.45 -apple-system,BlinkMacSystemFont,Roboto,"Segoe UI",Arial,sans-serif}
main{max-width:460px;margin:0 auto;padding:20px 16px 32px}
.head{display:flex;align-items:center;gap:12px;margin-bottom:16px}
.logo{width:44px;height:44px;border-radius:50%;background:var(--pc);color:var(--on-pc);display:flex;align-items:center;justify-content:center;flex:none}
h1{font-size:1.3rem;margin:0}
.sub{color:var(--mu);font-size:.85rem}
.card{background:var(--card);border:1px solid var(--olv);border-radius:24px;padding:18px;margin-bottom:14px}
h2{font-size:.8rem;text-transform:uppercase;letter-spacing:.05em;color:var(--mu);margin:0 0 10px}
.nets{display:flex;flex-direction:column;gap:6px;max-height:300px;overflow-y:auto}
.net{display:flex;align-items:center;gap:10px;width:100%;padding:12px 14px;border:1px solid var(--olv);border-radius:16px;background:transparent;color:var(--tx);font:inherit;text-align:left;cursor:pointer}
.net.on{background:var(--sc);color:var(--on-sc);border-color:transparent;font-weight:600}
.net .n{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.bars{font-size:.75rem;color:var(--mu);white-space:nowrap}
.row{display:flex;gap:8px;align-items:center;margin-top:10px}
label{display:block;font-size:.8rem;font-weight:600;color:var(--mu);margin:12px 0 6px}
input{width:100%;min-height:46px;padding:10px 14px;border:1px solid var(--ol);border-radius:12px;background:var(--card);color:var(--tx);font:inherit}
input:focus{outline:2px solid var(--p);border-color:var(--p)}
.pw{position:relative}
.pw input{padding-right:70px}
.pw button{position:absolute;right:6px;top:6px;height:34px;padding:0 12px;border:0;border-radius:999px;background:var(--sc);color:var(--on-sc);font-family:inherit;font-weight:600;font-size:.8rem}
.btn{display:block;width:100%;min-height:48px;margin-top:16px;border:0;border-radius:999px;background:var(--p);color:var(--on-p);font-family:inherit;font-weight:600;font-size:1rem;cursor:pointer}
.btn:disabled{opacity:.5}
.link{border:0;background:none;color:var(--p);font-family:inherit;font-weight:600;font-size:.9rem;padding:6px 0;cursor:pointer}
.msg{margin-top:14px;padding:12px 14px;border-radius:16px;font-size:.95rem}
.msg.info{background:var(--sc);color:var(--on-sc)}
.msg.ok{background:var(--pc);color:var(--on-pc)}
.msg.bad{background:color-mix(in srgb,var(--err) 14%,transparent);color:var(--err)}
.muted{color:var(--mu);font-size:.85rem}
[hidden]{display:none!important}
</style>
</head><body><main>
<div class="head">
  <div class="logo"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41"/></svg></div>
  <div><h1>Wi-Fi setup</h1><div class="sub" id="dev">Solar Monitor</div></div>
</div>

<form class="card" id="form" autocomplete="off">
  <h2>Choose your Wi-Fi</h2>
  <div class="nets" id="nets"><div class="muted" id="nets-msg">Looking for networks...</div></div>
  <div class="row"><button type="button" class="link" id="rescan">Scan again</button>
    <span style="flex:1"></span><button type="button" class="link" id="other">Other network...</button></div>
  <div id="other-box" hidden>
    <label for="ssid">Network name</label>
    <input id="ssid" maxlength="32" autocapitalize="none" autocorrect="off" spellcheck="false">
  </div>
  <label for="pass">Password</label>
  <div class="pw"><input id="pass" type="password" maxlength="63" autocapitalize="none" autocorrect="off" spellcheck="false">
    <button type="button" id="show">Show</button></div>
  <button class="btn" id="save" type="submit">Save and connect</button>
  <div class="msg" id="msg" hidden></div>
</form>
<p class="muted">Your phone may warn that this network has no internet &mdash; that is expected: choose <b>Stay connected</b> / <b>Keep Wi-Fi</b>. The device keeps measuring while you set it up, and this setup network closes by itself once the device is online.</p>
</main>
<script>
(function(){
var $=function(id){return document.getElementById(id)};
var chosen=null, hotspot='', polling=null;
function say(kind,text){var m=$('msg');m.hidden=false;m.className='msg '+kind;m.textContent=text}
function bars(r){return r>=-55?'Strong':r>=-67?'Good':r>=-75?'Fair':'Weak'}
function pick(name,btn){chosen=name;$('other-box').hidden=true;
  Array.prototype.forEach.call(document.querySelectorAll('.net'),function(b){b.classList.toggle('on',b===btn)});
  $('pass').focus()}
function render(list){
  var box=$('nets');box.textContent='';
  var seen={};
  list.forEach(function(n){
    if(!n.s||seen[n.s])return;seen[n.s]=1;
    var b=document.createElement('button');b.type='button';b.className='net';
    var name=document.createElement('span');name.className='n';name.textContent=n.s;
    var q=document.createElement('span');q.className='bars';q.textContent=(n.e?'🔒 ':'')+bars(n.r);
    b.appendChild(name);b.appendChild(q);
    b.onclick=function(){pick(n.s,b)};
    if(n.s===chosen)b.classList.add('on');
    box.appendChild(b);
  });
  if(!box.children.length){var d=document.createElement('div');d.className='muted';
    d.textContent='No networks found. Tap Scan again, or use Other network.';box.appendChild(d)}
}
function getJSON(url){return fetch(url,{cache:'no-store'}).then(function(r){return r.json()})}
// The list from the scan made when the hotspot opened; a fresh scan only on
// "Scan again" (scanning knocks the phone off this network for a moment).
function scan(force){
  $('nets').textContent='';var d=document.createElement('div');d.className='muted';
  d.textContent='Looking for networks...';$('nets').appendChild(d);
  getJSON('/scan.json'+(force?'?refresh=1':'')).then(function(first){
    if(!first.queued&&first.v>0){render(first.networks||[]);return}
    var base=first.queued?first.v:0,tries=0;
    (function wait(){getJSON('/scan.json').then(function(j){
      if(j.v!==base||tries++>10){render(j.networks||[]);return}
      setTimeout(wait,1200)}).catch(function(){setTimeout(wait,1500)})})();
  }).catch(function(){render([])});
}
$('rescan').onclick=function(){scan(true)};
$('other').onclick=function(){chosen=null;$('other-box').hidden=false;
  Array.prototype.forEach.call(document.querySelectorAll('.net'),function(b){b.classList.remove('on')});
  $('ssid').focus()};
$('show').onclick=function(){var p=$('pass');var s=p.type==='password';p.type=s?'text':'password';this.textContent=s?'Hide':'Show'};
var REASONS={wrong_password:'Wrong password. Check it and try again.',
  not_found:'That network was not found. Move closer to the router or check the name.',
  other:'Could not connect. Check the name and password and try again.'};
function poll(){
  var lost=0;clearInterval(polling);
  polling=setInterval(function(){getJSON('/status.json').then(function(s){
    lost=0;
    if(s.state==='connected'){clearInterval(polling);
      say('ok','Connected to '+s.ssid+(s.ip?' ('+s.ip+')':'')+'. All done - this setup network closes in a minute; your phone will go back to its usual Wi-Fi.');
      $('save').disabled=false;}
    else if(s.state==='failed'){clearInterval(polling);say('bad',REASONS[s.reason]||REASONS.other);$('save').disabled=false}
    else say('info','Connecting to '+s.ssid+'...');
  }).catch(function(){if(++lost>=3)say('info','Still connecting. If this page stops updating, your phone may have left "'+(hotspot||'SolarMonitor')+'" - rejoin it to see the result.')})},1500);
}
$('form').onsubmit=function(e){
  e.preventDefault();
  var ssid=chosen||$('ssid').value.trim(),pass=$('pass').value;
  if(!ssid){say('bad','Choose a network first.');return}
  if(pass.length>0&&pass.length<8){say('bad','Wi-Fi passwords are at least 8 characters.');return}
  $('save').disabled=true;say('info','Saving...');
  var body='ssid='+encodeURIComponent(ssid)+'&password='+encodeURIComponent(pass);
  fetch('/save',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})
    .then(function(r){return r.json()}).then(function(j){
      if(!j.ok){say('bad',j.error||'Could not save.');$('save').disabled=false;return}
      say('info','Connecting to '+ssid+'...');poll()})
    .catch(function(){say('bad','Lost contact with the device. Rejoin its setup network and try again.');$('save').disabled=false});
};
getJSON('/status.json').then(function(s){hotspot=s.hotspot||'';
  $('dev').textContent='Device '+(s.device||'')+(hotspot?' · '+hotspot:'')}).catch(function(){});
scan();
})();
</script>
</body></html>)SETUPPAGE";
