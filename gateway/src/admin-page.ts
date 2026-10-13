// The /admin page: one static HTML document, no external assets. Blaze classic look: dark,
// mono labels, minimal. All dynamic text goes through textContent (no innerHTML with data).

export function adminPage(nonce: string): string {
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>Blaze Gateway · admin</title>
<style nonce="${nonce}">
:root{--bg:#1c1c1f;--raised:#26262a;--raised2:#2e2e33;--hair:#3a3a40;--text:#ececea;--muted:#9c9c96;--accent:#7b98ff;--ok:#5fd38d;--warn:#f0b35a;--bad:#ff6b6b;
--mono:ui-monospace,"SF Mono","JetBrains Mono",Menlo,Consolas,monospace;--sans:-apple-system,"Segoe UI",Inter,Roboto,sans-serif}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.45 var(--sans)}
header{display:flex;align-items:center;gap:12px;padding:14px 22px;border-bottom:1px solid var(--hair);position:sticky;top:0;background:var(--bg);z-index:2}
header b{font-family:var(--mono);letter-spacing:.04em}header .v{color:var(--muted);font:12px var(--mono)}header .sp{flex:1}
nav{display:flex;gap:4px}nav button{background:none;border:0;color:var(--muted);font:12px var(--mono);text-transform:uppercase;letter-spacing:.08em;padding:6px 10px;border-radius:6px;cursor:pointer}
nav button.on{color:var(--text);background:var(--raised)}
main{max-width:1100px;margin:0 auto;padding:22px}
.lbl{font:11px var(--mono);text-transform:uppercase;letter-spacing:.1em;color:var(--muted)}
.card{background:var(--raised);border:1px solid var(--hair);border-radius:10px;padding:14px 16px;margin:0 0 12px}
.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.grow{flex:1}
input,select,textarea{background:var(--bg);color:var(--text);border:1px solid var(--hair);border-radius:6px;padding:7px 9px;font:13px var(--mono)}
textarea{width:100%;min-height:150px;resize:vertical}
button.b{background:var(--raised2);color:var(--text);border:1px solid var(--hair);border-radius:6px;padding:6px 11px;font:12px var(--mono);cursor:pointer}
button.b:hover{border-color:var(--accent)}button.p{background:var(--accent);color:#0d1430;border-color:var(--accent)}button.d{color:var(--bad)}
table{width:100%;border-collapse:collapse;font-size:13px}th{text-align:left;font:11px var(--mono);text-transform:uppercase;letter-spacing:.08em;color:var(--muted);padding:6px 8px;border-bottom:1px solid var(--hair)}
td{padding:7px 8px;border-bottom:1px solid var(--hair);vertical-align:top}td.m{font-family:var(--mono);font-size:12px}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:6px;background:var(--muted)}.dot.ok{background:var(--ok)}.dot.warn{background:var(--warn)}.dot.bad{background:var(--bad)}
.tag{display:inline-block;font:11px var(--mono);color:var(--muted);border:1px solid var(--hair);border-radius:4px;padding:0 5px;margin:0 3px 3px 0}
.warnbox{border-color:var(--warn);color:var(--warn);font-family:var(--mono);font-size:12px}
.muted{color:var(--muted)}.hidden{display:none}#toast{position:fixed;bottom:18px;right:18px;background:var(--raised2);border:1px solid var(--hair);border-radius:8px;padding:9px 13px;font:12px var(--mono);opacity:0;transition:opacity .2s}
#toast.show{opacity:1}.login{max-width:380px;margin:12vh auto}.login input{width:100%;margin:6px 0}
.stat{font:22px var(--mono)}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px}
</style></head><body>
<div id="login" class="login hidden">
  <div class="lbl">Blaze Gateway</div><h2 style="margin:6px 0 18px;font-weight:600">Admin sign-in</h2>
  <div class="card">
    <div class="lbl">Blaze account</div>
    <input id="em" type="email" placeholder="email" autocomplete="username">
    <input id="pw" type="password" placeholder="password" autocomplete="current-password">
    <button class="b p" id="go" style="width:100%;margin-top:6px">Sign in</button>
  </div>
  <div class="card"><div class="lbl">or admin token</div><input id="tk" type="password" placeholder="ADMIN_TOKEN"><button class="b" id="gotk" style="width:100%;margin-top:6px">Use token</button></div>
  <div id="lerr" class="muted" style="font:12px var(--mono)"></div>
</div>
<div id="app" class="hidden">
<header><b>BLAZE GATEWAY</b><span class="v" id="ver"></span><nav id="nav">
<button data-t="health" class="on">Health</button><button data-t="routes">Routes</button><button data-t="providers">Providers</button><button data-t="plans">Plans</button></nav>
<span class="sp"></span><span class="v" id="who"></span><button class="b" id="out">Sign out</button></header>
<main>
<div id="warns"></div>
<section id="t-health">
  <div class="grid" style="margin-bottom:12px">
    <div class="card"><div class="lbl">Requests today</div><div class="stat" id="s-req">–</div></div>
    <div class="card"><div class="lbl">Accounts today</div><div class="stat" id="s-users">–</div></div>
    <div class="card"><div class="lbl">Tokens today</div><div class="stat" id="s-tok">–</div></div>
    <div class="card"><div class="lbl">Cooling now</div><div class="stat" id="s-cool">–</div></div>
  </div>
  <div class="card"><div class="row"><span class="lbl grow">Models · refreshes every 15 s · day is UTC</span></div>
  <table><thead><tr><th>Model</th><th>Tasks</th><th>Last ok</th><th>Last fail</th><th>Cooldown</th><th>Today</th><th></th></tr></thead><tbody id="health"></tbody></table></div>
</section>
<section id="t-routes" class="hidden">
  <div class="card muted" style="font-size:13px">One model per line: <span class="m" style="font-family:var(--mono)">provider model</span>, tried top to bottom. Models cooling down are skipped; Workers AI should stay last as the fallback. Apps send <span style="font-family:var(--mono)">blaze_task</span>, or the gateway infers it (images → vision, tools → agent, long input → long, code → code).</div>
  <div id="routes"></div>
  <button class="b d" id="resetroutes">Reset all to defaults</button>
</section>
<section id="t-providers" class="hidden">
  <div id="providers"></div>
  <div class="card"><div class="lbl" style="margin-bottom:8px">Add provider</div>
    <div class="row"><input id="np-id" placeholder="id (e.g. together)" size="14"><input id="np-name" placeholder="name" size="16"><input id="np-url" class="grow" placeholder="https://api.example.com/v1">
    <select id="np-type"><option value="openai">openai-compatible</option><option value="workers-ai">workers-ai</option></select><button class="b p" id="np-add">Add</button></div></div>
</section>
<section id="t-plans" class="hidden">
  <div class="card"><div class="lbl" style="margin-bottom:8px">Plans · new accounts start on <span id="defplan"></span> · billing isn't wired yet</div>
  <table><thead><tr><th>Plan</th><th>Price / month (USD)</th><th>Messages / day</th><th>Tokens / day (0 = no cap)</th><th></th></tr></thead><tbody id="plans"></tbody></table></div>
  <div class="card"><div class="lbl" style="margin-bottom:8px">Assign a plan to an account</div>
  <div class="row"><input id="as-email" type="email" placeholder="account email" class="grow"><select id="as-plan"></select><button class="b p" id="as-save">Save</button></div>
  <table style="margin-top:10px"><tbody id="assign"></tbody></table></div>
</section>
</main></div>
<div id="toast"></div>
<script nonce="${nonce}">
(function(){
const $=s=>document.querySelector(s);let TOKEN=sessionStorage.getItem("bz_admin")||"";let S=null;let tab="health";
function el(tag,attrs,...kids){const e=document.createElement(tag);for(const k in (attrs||{})){if(k==="class")e.className=attrs[k];else if(k.startsWith("on"))e.addEventListener(k.slice(2),attrs[k]);else e.setAttribute(k,attrs[k]);}for(const c of kids){if(c==null)continue;e.append(c.nodeType?c:document.createTextNode(String(c)));}return e;}
function toast(t){const x=$("#toast");x.textContent=t;x.classList.add("show");clearTimeout(x._t);x._t=setTimeout(()=>x.classList.remove("show"),2600);}
async function api(method,path,body){const r=await fetch("/admin/api/"+path,{method,headers:{"Content-Type":"application/json",Authorization:"Bearer "+TOKEN},body:body?JSON.stringify(body):undefined});
 const j=await r.json().catch(()=>({}));if(r.status===401){signOut(true);throw new Error("signed out");}if(!r.ok)throw new Error((j.error&&j.error.message)||j.message||("HTTP "+r.status));return j;}
function ago(t){if(!t)return "–";const s=Math.round((Date.now()-t)/1000);if(s<60)return s+"s ago";if(s<3600)return Math.round(s/60)+"m ago";if(s<86400)return Math.round(s/3600)+"h ago";return Math.round(s/86400)+"d ago";}
function inS(t){const s=Math.max(0,Math.round((t-Date.now())/1000));return s<60?s+"s":s<3600?Math.round(s/60)+"m":Math.round(s/3600)+"h";}
function show(id){for(const s of ["health","routes","providers","plans"])$("#t-"+s).classList.toggle("hidden",s!==id);for(const b of document.querySelectorAll("#nav button"))b.classList.toggle("on",b.dataset.t===id);tab=id;}
function signOut(silent){if(!silent&&TOKEN)fetch("/admin/api/logout",{method:"POST",headers:{Authorization:"Bearer "+TOKEN}}).catch(()=>{});TOKEN="";sessionStorage.removeItem("bz_admin");$("#app").classList.add("hidden");$("#login").classList.remove("hidden");}
async function signIn(body){$("#lerr").textContent="";try{const r=await fetch("/admin/api/login",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)});const j=await r.json();if(!r.ok)throw new Error((j.error&&j.error.message)||"Sign-in failed");TOKEN=j.token;
 const me=await fetch("/admin/api/me",{headers:{Authorization:"Bearer "+TOKEN}});if(!me.ok)throw new Error("Not an admin");sessionStorage.setItem("bz_admin",TOKEN);$("#pw").value="";$("#tk").value="";start();}catch(e){TOKEN="";$("#lerr").textContent=e.message;}}
$("#go").onclick=()=>signIn({email:$("#em").value,password:$("#pw").value});$("#pw").onkeydown=e=>{if(e.key==="Enter")$("#go").click();};
$("#gotk").onclick=()=>signIn({token:$("#tk").value});$("#out").onclick=()=>signOut(false);
for(const b of document.querySelectorAll("#nav button"))b.onclick=()=>show(b.dataset.t);
async function load(){S=await api("GET","state");render();}
function render(){$("#ver").textContent="v"+S.version;const w=$("#warns");w.replaceChildren(...S.warnings.map(x=>el("div",{class:"card warnbox"},"! "+x)));
 $("#s-req").textContent=S.usage_today.requests;$("#s-users").textContent=S.usage_today.users;$("#s-tok").textContent=Number(S.usage_today.tokens).toLocaleString();
 $("#s-cool").textContent=S.health.filter(h=>h.cooling_until).length;renderHealth();if(!document.activeElement||!document.activeElement.closest("#t-routes,#t-providers,#t-plans")){renderRoutes();renderProviders();renderPlans();}}
function renderHealth(){$("#health").replaceChildren(...S.health.map(h=>{const st=h.cooling_until?"warn":(h.last_fail_at>h.last_ok_at?"bad":(h.last_ok_at?"ok":""));
 const btn=el("button",{class:"b",onclick:async e=>{e.target.textContent="…";try{const r=await api("POST","test-model",{provider:h.provider,model:h.model});toast(r.ok?("ok · "+r.ms+" ms"):("fail "+r.status+" · "+(r.kind||"")+" "+(r.error||"")).slice(0,160));}catch(x){toast(x.message);}load();}},"test");
 const clr=h.cooling_until?el("button",{class:"b",onclick:async()=>{await api("POST","cooldowns/clear",{provider:h.provider,model:h.model});load();}},"clear"):null;
 return el("tr",{},el("td",{class:"m"},el("span",{class:"dot "+st}),h.provider+" · "+h.model),el("td",{},...h.tasks.map(t=>el("span",{class:"tag"},t))),el("td",{class:"m"},ago(h.last_ok_at)),
 el("td",{class:"m"},ago(h.last_fail_at)),el("td",{class:"m"},h.cooling_until?(inS(h.cooling_until)+" · "+h.reason):(h.reason||"–")),el("td",{class:"m"},h.ok_today+" ok / "+h.fail_today+" fail"+(h.avg_ms?" · "+h.avg_ms+" ms":"")),el("td",{},btn," ",clr));}));}
function renderRoutes(){$("#routes").replaceChildren(...S.tasks.map(t=>{const ta=el("textarea",{spellcheck:"false"});ta.value=(S.routes[t]||[]).map(c=>c.provider+" "+c.model).join("\\n");
 const save=el("button",{class:"b p",onclick:async()=>{const models=ta.value.split("\\n").map(l=>l.trim()).filter(Boolean).map(l=>{const i=l.search(/\\s/);return i<0?{provider:l,model:""}:{provider:l.slice(0,i),model:l.slice(i).trim()};});
  try{await api("PUT","routes/"+t,{models});toast(t+" saved");load();}catch(e){toast(e.message);}}},"Save");
 return el("div",{class:"card"},el("div",{class:"row",style:"margin-bottom:8px"},el("span",{class:"lbl grow"},t),save),ta);}));}
$("#resetroutes").onclick=async()=>{if(!confirm("Put every task's model list back to the defaults?"))return;await api("POST","routes/reset");toast("routes reset");load();};
function renderProviders(){$("#providers").replaceChildren(...S.providers.map(p=>{
 const name=el("input",{value:p.name,size:"18"});const url=el("input",{value:p.base_url,class:"grow"});if(p.type==="workers-ai")url.disabled=true;
 const en=el("input",{type:"checkbox"});en.checked=p.enabled;const notes=el("input",{value:p.notes,class:"grow"});
 const save=el("button",{class:"b p",onclick:async()=>{try{await api("PUT","providers/"+p.id,{name:name.value,base_url:url.value,type:p.type,enabled:en.checked,notes:notes.value});toast("saved");load();}catch(e){toast(e.message);}}},"Save");
 const del=p.type==="workers-ai"?null:el("button",{class:"b d",onclick:async()=>{if(!confirm("Delete "+p.id+" and its keys?"))return;await api("DELETE","providers/"+p.id);load();}},"Delete");
 const keys=el("table",{},el("tbody",{},...p.keys.map(k=>{const ke=el("input",{type:"checkbox"});ke.checked=k.enabled;ke.onchange=async()=>{await api("PATCH","keys/"+k.id,{enabled:ke.checked});load();};
  const st=k.cooling_until?"warn":(k.last_fail_at>k.last_ok_at?"bad":(k.last_ok_at?"ok":""));
  return el("tr",{},el("td",{class:"m"},el("span",{class:"dot "+st}),k.masked),el("td",{},k.label||""),el("td",{class:"m"},k.cooling_until?("cooling "+inS(k.cooling_until)):(k.last_error||(k.last_ok_at?"ok "+ago(k.last_ok_at):"untested"))),
  el("td",{},el("label",{class:"muted"},ke," on")),el("td",{},el("button",{class:"b",onclick:async e=>{e.target.textContent="…";const r=await api("POST","keys/"+k.id+"/test");toast(r.ok?("key ok · "+r.ms+" ms"+(r.models!=null?" · "+r.models+" models":"")):("key failed "+r.status+" "+(r.error||"")).slice(0,160));load();}},"test key")," ",
  el("button",{class:"b d",onclick:async()=>{if(!confirm("Delete key "+k.masked+"?"))return;await api("DELETE","keys/"+k.id);load();}},"delete")));})));
 const nk=el("input",{type:"password",placeholder:"paste a new key",class:"grow",autocomplete:"off"});const nl=el("input",{placeholder:"label (optional)",size:"14"});
 const add=el("button",{class:"b",onclick:async()=>{try{const r=await api("POST","providers/"+p.id+"/keys",{key:nk.value,label:nl.value});nk.value="";toast("key added "+r.masked);load();}catch(e){toast(e.message);}}},"Add key");
 return el("div",{class:"card"},el("div",{class:"row",style:"margin-bottom:8px"},el("span",{class:"lbl"},p.id+" · "+p.type),el("span",{class:"grow"}),el("label",{class:"muted"},en," enabled"),save,del),
  el("div",{class:"row",style:"margin-bottom:6px"},name,url),el("div",{class:"row",style:"margin-bottom:8px"},notes),
  p.type==="workers-ai"?el("div",{class:"muted",style:"font-size:12px"},"Uses the Worker's AI binding, no key."):el("div",{},keys,el("div",{class:"row",style:"margin-top:8px"},nk,nl,add)));}));}
$("#np-add").onclick=async()=>{try{await api("POST","providers",{id:$("#np-id").value,name:$("#np-name").value,base_url:$("#np-url").value,type:$("#np-type").value});toast("provider added");load();}catch(e){toast(e.message);}};
function renderPlans(){$("#defplan").textContent=S.default_plan;$("#plans").replaceChildren(...S.plans.map(p=>{const pr=el("input",{type:"number",min:"0",step:"1",value:p.price_usd,size:"6"});
 const r=el("input",{type:"number",min:"0",value:p.daily_requests,size:"8"});const t=el("input",{type:"number",min:"0",value:p.daily_tokens,size:"12"});
 return el("tr",{},el("td",{class:"m"},p.id+(p.id===S.default_plan?" (default)":"")),el("td",{},pr),el("td",{},r),el("td",{},t),el("td",{},el("button",{class:"b p",onclick:async()=>{try{await api("PUT","plans/"+p.id,{price_usd:+pr.value,daily_requests:+r.value,daily_tokens:+t.value});toast(p.id+" saved");load();}catch(e){toast(e.message);}}},"Save")));}));
 const sel=$("#as-plan");if(!sel.options.length)for(const p of S.plans)sel.append(el("option",{value:p.id},p.id));
 $("#assign").replaceChildren(...S.assignments.map(a=>el("tr",{},el("td",{class:"m"},a.email),el("td",{class:"m"},a.plan),el("td",{},el("button",{class:"b",onclick:async()=>{await api("PUT","assignments",{email:a.email,plan:S.default_plan});load();}},"back to "+S.default_plan)))));}
$("#as-save").onclick=async()=>{try{await api("PUT","assignments",{email:$("#as-email").value,plan:$("#as-plan").value});$("#as-email").value="";toast("saved");load();}catch(e){toast(e.message);}};
async function start(){$("#login").classList.add("hidden");$("#app").classList.remove("hidden");try{const me=await api("GET","me");$("#who").textContent=me.admin.email;await load();}catch(e){toast(e.message);}}
setInterval(()=>{if(TOKEN&&S&&tab==="health"&&!document.hidden)load().catch(()=>{});},15000);
if(TOKEN)start();else $("#login").classList.remove("hidden");
})();
</script></body></html>`;
}
