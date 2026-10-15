// The /admin page: one static HTML document, no external assets. Blaze look: dark, rounded
// (no square corners), orange + blue accents, mono labels. All dynamic text goes through
// textContent (no innerHTML with data).

export function adminPage(nonce: string): string {
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>Blaze Gateway · admin</title>
<style nonce="${nonce}">
:root{--bg:#1c1c1f;--raised:#26262a;--raised2:#2e2e33;--hair:#3a3a40;--text:#ececea;--muted:#aeaea8;--accent:#7b98ff;--fire:#ff8a3d;--ok:#5fd38d;--warn:#f0b35a;--bad:#ff6b6b;
--r:14px;--r-s:10px;--mono:ui-monospace,"SF Mono","JetBrains Mono",Menlo,Consolas,monospace;--sans:-apple-system,"Segoe UI",Inter,Roboto,sans-serif}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.45 var(--sans)}
header{display:flex;align-items:center;gap:12px;padding:12px 22px;border-bottom:1px solid var(--hair);position:sticky;top:0;background:rgba(28,28,31,.96);z-index:3;flex-wrap:wrap}
header b{font-family:var(--mono);letter-spacing:.04em;background:linear-gradient(90deg,var(--fire),var(--accent));-webkit-background-clip:text;background-clip:text;color:transparent}
header .v{color:var(--muted);font:12px var(--mono)}header .sp{flex:1}
nav{display:flex;gap:4px;background:var(--raised);border-radius:999px;padding:3px}nav button{background:none;border:0;color:var(--muted);font:12px var(--mono);text-transform:uppercase;letter-spacing:.08em;padding:6px 12px;border-radius:999px;cursor:pointer}
nav button.on{color:#1c1c1f;background:var(--fire)}
main{max-width:1100px;margin:0 auto;padding:22px}
.lbl{font:11px var(--mono);text-transform:uppercase;letter-spacing:.1em;color:var(--muted)}
.card{background:var(--raised);border:1px solid var(--hair);border-radius:var(--r);padding:14px 16px;margin:0 0 12px}
.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.grow{flex:1;min-width:120px}
input,select,textarea{background:var(--bg);color:var(--text);border:1px solid var(--hair);border-radius:var(--r-s);padding:7px 10px;font:13px var(--mono)}
input:focus,select:focus,textarea:focus{outline:none;border-color:var(--accent)}
textarea{width:100%;min-height:130px;resize:vertical}
button.b{background:var(--raised2);color:var(--text);border:1px solid var(--hair);border-radius:999px;padding:6px 13px;font:12px var(--mono);cursor:pointer}
button.b:hover{border-color:var(--accent)}button.b:disabled{opacity:.45;cursor:default}
button.p{background:var(--fire);color:#1c1c1f;border-color:var(--fire);font-weight:600}button.p:hover{border-color:#ffb27d}
button.a{background:transparent;color:var(--accent);border-color:var(--accent)}button.d{color:var(--bad)}
button.i{padding:3px 9px;min-width:30px}
table{width:100%;border-collapse:separate;border-spacing:0;font-size:13px}th{text-align:left;font:11px var(--mono);text-transform:uppercase;letter-spacing:.08em;color:var(--muted);padding:6px 8px;border-bottom:1px solid var(--hair)}
td{padding:7px 8px;border-bottom:1px solid var(--hair);vertical-align:top}td.m{font-family:var(--mono);font-size:12px}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:6px;background:var(--muted)}.dot.ok{background:var(--ok)}.dot.warn{background:var(--warn)}.dot.bad{background:var(--bad)}
.tag{display:inline-block;font:11px var(--mono);color:var(--muted);border:1px solid var(--hair);border-radius:999px;padding:0 7px;margin:0 3px 3px 0}
.tag.pv{color:#a9bbff;background:rgba(123,152,255,.12);border-color:rgba(123,152,255,.5)}.tag.w{color:var(--warn);border-color:rgba(240,179,90,.5)}.tag.x{color:var(--bad);border-color:rgba(255,107,107,.5)}
.warnbox{border-color:var(--warn);color:var(--warn);font-family:var(--mono);font-size:12px}
.muted{color:var(--muted)}.hidden{display:none}
#toast{position:fixed;top:64px;left:50%;transform:translateX(-50%);background:var(--raised2);border:1px solid var(--accent);border-radius:999px;padding:8px 16px;font:12px var(--mono);opacity:0;transition:opacity .2s;pointer-events:none;z-index:5;max-width:90vw}
#toast.show{opacity:1}.login{max-width:380px;margin:12vh auto}.login input{width:100%;margin:6px 0}
.stat{font:22px var(--mono)}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px}
.note{margin-top:8px;padding:7px 12px;border-radius:var(--r-s);font:12px var(--mono);background:rgba(123,152,255,.10);border:1px solid rgba(123,152,255,.35);color:var(--text)}
.note.ok{background:rgba(95,211,141,.10);border-color:rgba(95,211,141,.4)}.note.bad{background:rgba(255,107,107,.10);border-color:rgba(255,107,107,.45)}.note.warn{background:rgba(240,179,90,.10);border-color:rgba(240,179,90,.45)}
.task h3{margin:0;font-size:16px;font-weight:600}.task .desc{color:var(--muted);font-size:13px;margin:2px 0 10px}
.slot{display:flex;align-items:center;gap:8px;background:var(--bg);border:1px solid var(--hair);border-radius:var(--r-s);padding:7px 10px;margin:0 0 6px}
.slot .n{font:12px var(--mono);color:var(--fire);width:18px;text-align:right}.slot .nm{flex:1;min-width:0}
.slot details{display:inline-block;margin-left:6px}.slot summary{display:inline-block;cursor:pointer;font:11px var(--mono);color:#a9bbff;list-style:none;border:1px solid rgba(123,152,255,.4);border-radius:999px;padding:0 7px}
.slot summary::-webkit-details-marker{display:none}.slot code{font:11px var(--mono);color:var(--text);word-break:break-all}
.slot.last{border-style:dashed}.pick{background:var(--raised2);border-radius:var(--r-s);padding:9px;margin-top:8px}
details.adv>summary{cursor:pointer;font:12px var(--mono);color:#c9c9c3;margin-top:10px}
.res{max-height:260px;overflow:auto;margin-top:8px;border:1px solid var(--hair);border-radius:var(--r-s);background:var(--bg);padding:4px}
.res .g{font:11px var(--mono);text-transform:uppercase;letter-spacing:.08em;color:var(--fire);padding:8px 10px 4px}
.res button{display:flex;width:100%;gap:8px;align-items:center;text-align:left;background:none;border:0;border-radius:8px;color:var(--text);font:13px var(--sans);padding:6px 10px;cursor:pointer}
.res button:hover,.res button:focus{background:var(--raised2);outline:none}.res button .id{margin-left:auto;font:11px var(--mono);color:var(--muted)}
.res .empty{padding:8px 10px;color:var(--muted);font-size:13px}
input::placeholder{color:#8e8e88}.task{scroll-margin-top:70px}
.dirty{color:var(--fire);font:11px var(--mono)}
.badge{display:inline-block;margin-left:6px;min-width:18px;padding:0 5px;border-radius:999px;background:var(--accent);color:#1c1c1f;font:11px var(--mono);text-align:center}
.grp{font:11px var(--mono);text-transform:uppercase;letter-spacing:.08em;color:var(--fire);padding:12px 8px 4px}
h4{margin:14px 0 6px;font-size:14px;font-weight:600}.sw{display:flex;gap:8px;align-items:center;cursor:pointer}
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
<button data-t="health" class="on">Health</button><button data-t="routes">Routes</button><button data-t="providers">Providers</button><button data-t="plans">Plans</button><button data-t="access">Waiting list<span id="wl-badge"></span></button></nav>
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
  <div class="card">
    <div class="row"><div class="grow"><div class="lbl">Routes</div><div class="muted" style="font-size:13px;margin-top:4px">Each task tries its models top to bottom and skips any that are busy. Pick models by name; only free models are offered. Keep Cloudflare Workers AI last as the always-on fallback.</div></div></div>
    <div class="row" style="margin-top:10px"><button class="b p" id="suggest">Suggest best free lineup</button><button class="b a" id="saveall">Save all changes</button><button class="b" id="discall">Find models (all providers)</button><span class="grow"></span><button class="b d" id="resetroutes">Reset to defaults</button></div>
    <div id="routes-note"></div>
  </div>
  <div id="routes"></div>
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
  <div class="card"><div class="lbl" style="margin-bottom:8px">Assign a plan to an account · also approves it</div>
  <div class="row"><input id="as-email" type="email" placeholder="account email" class="grow"><select id="as-plan"></select><button class="b p" id="as-save">Save</button></div>
  <div id="as-note"></div>
  <table style="margin-top:10px"><tbody id="assign"></tbody></table></div>
</section>
<section id="t-access" class="hidden">
  <div class="card"><div class="row"><div class="grow"><div class="lbl">Waiting list</div><div class="muted" style="font-size:13px;margin-top:4px">Blaze accounts that aren't approved get "You're on the Blaze waiting list" and show up here. Admins are always allowed.</div></div>
  <label class="sw"><input type="checkbox" id="wl-req"> Require approval for new accounts</label></div><div id="wl-note"></div></div>
  <div class="card"><div class="lbl">Pending · <span id="wl-np">0</span></div><table><tbody id="wl-pending"></tbody></table></div>
  <div class="card"><div class="lbl">Approved · <span id="wl-na">0</span></div><div id="wl-legacy" class="muted" style="font-size:12px;margin-top:4px"></div><table><tbody id="wl-approved"></tbody></table></div>
  <div class="card"><div class="lbl">Rejected · <span id="wl-nr">0</span></div><table><tbody id="wl-rejected"></tbody></table></div>
</section>
</main></div>
<div id="toast"></div>
<script nonce="${nonce}">
(function(){
const $=s=>document.querySelector(s);let TOKEN=sessionStorage.getItem("bz_admin")||"";let S=null;let tab="health";
let CAT={models:[],discovery:[]};let MI=new Map();const DRAFT={};const NOTES={};const PICK={};
const CAPWORD={vision:"can't see images",tools:"no tool calling",long:"short memory",fast:"not a fast model"};
function el(tag,attrs,...kids){const e=document.createElement(tag);for(const k in (attrs||{})){if(k==="class")e.className=attrs[k];else if(k.startsWith("on"))e.addEventListener(k.slice(2),attrs[k]);else e.setAttribute(k,attrs[k]);}for(const c of kids){if(c==null)continue;e.append(c.nodeType?c:document.createTextNode(String(c)));}return e;}
function toast(t){const x=$("#toast");x.textContent=t;x.classList.add("show");clearTimeout(x._t);x._t=setTimeout(()=>x.classList.remove("show"),3200);}
function note(key,text,kind){NOTES[key]={text,kind:kind||"",at:Date.now()};}
function noteEl(key){const n=NOTES[key];if(!n||Date.now()-n.at>12000)return null;return el("div",{class:"note "+n.kind},n.text);}
async function api(method,path,body){const r=await fetch("/admin/api/"+path,{method,headers:{"Content-Type":"application/json",Authorization:"Bearer "+TOKEN},body:body?JSON.stringify(body):undefined});
 const j=await r.json().catch(()=>({}));if(r.status===401){signOut(true);throw new Error("signed out");}if(!r.ok)throw new Error((j.error&&j.error.message)||j.message||("HTTP "+r.status));return j;}
function ago(t){if(!t)return "–";const s=Math.round((Date.now()-t)/1000);if(s<60)return s+"s ago";if(s<3600)return Math.round(s/60)+"m ago";if(s<86400)return Math.round(s/3600)+"h ago";return Math.round(s/86400)+"d ago";}
function inS(t){const s=Math.max(0,Math.round((t-Date.now())/1000));return s<60?s+"s":s<3600?Math.round(s/60)+"m":Math.round(s/3600)+"h";}
function show(id){for(const s of ["health","routes","providers","plans","access"])$("#t-"+s).classList.toggle("hidden",s!==id);for(const b of document.querySelectorAll("#nav button"))b.classList.toggle("on",b.dataset.t===id);tab=id;if(id==="routes")loadModels().catch(e=>toast(e.message));}
function signOut(silent){if(!silent&&TOKEN)fetch("/admin/api/logout",{method:"POST",headers:{Authorization:"Bearer "+TOKEN}}).catch(()=>{});TOKEN="";sessionStorage.removeItem("bz_admin");$("#app").classList.add("hidden");$("#login").classList.remove("hidden");}
async function signIn(body){$("#lerr").textContent="";try{const r=await fetch("/admin/api/login",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)});const j=await r.json();if(!r.ok)throw new Error((j.error&&j.error.message)||"Sign-in failed");TOKEN=j.token;
 const me=await fetch("/admin/api/me",{headers:{Authorization:"Bearer "+TOKEN}});if(!me.ok)throw new Error("Not an admin");sessionStorage.setItem("bz_admin",TOKEN);$("#pw").value="";$("#tk").value="";start();}catch(e){TOKEN="";$("#lerr").textContent=e.message;}}
$("#go").onclick=()=>signIn({email:$("#em").value,password:$("#pw").value});$("#pw").onkeydown=e=>{if(e.key==="Enter")$("#go").click();};
$("#gotk").onclick=()=>signIn({token:$("#tk").value});$("#out").onclick=()=>signOut(false);
for(const b of document.querySelectorAll("#nav button"))b.onclick=()=>show(b.dataset.t);
/* force=true after the admin's own actions (always re-render); the 15 s refresh passes false so it never wipes what's being typed. */
async function load(force){S=await api("GET","state");render(force!==false);}
async function loadModels(){CAT=await api("GET","models");MI=new Map(CAT.models.map(m=>[m.provider+"|"+m.id,m]));renderRoutes();}
function editing(){const a=document.activeElement;return !!(a&&a.closest&&a.closest("#t-routes,#t-providers,#t-plans,#t-access")&&(a.tagName==="INPUT"||a.tagName==="TEXTAREA"||a.tagName==="SELECT"));}
function render(force){$("#ver").textContent="v"+S.version;const w=$("#warns");w.replaceChildren(...S.warnings.map(x=>el("div",{class:"card warnbox"},"! "+x)));
 $("#s-req").textContent=S.usage_today.requests;$("#s-users").textContent=S.usage_today.users;$("#s-tok").textContent=Number(S.usage_today.tokens).toLocaleString();
 $("#s-cool").textContent=S.health.filter(h=>h.cooling_until).length;renderHealth();renderBadge();if(force||!editing()){renderRoutes();renderProviders();renderPlans();renderAccess();}}
function renderHealth(){$("#health").replaceChildren(...S.health.map(h=>{const st=h.cooling_until?"warn":(h.last_fail_at>h.last_ok_at?"bad":(h.last_ok_at?"ok":""));
 const btn=el("button",{class:"b",onclick:async e=>{e.target.textContent="…";try{const r=await api("POST","test-model",{provider:h.provider,model:h.model});toast(r.ok?("ok · "+r.ms+" ms"):("fail "+r.status+" · "+(r.kind||"")+" "+(r.error||"")).slice(0,160));}catch(x){toast(x.message);}load();}},"test");
 const clr=h.cooling_until?el("button",{class:"b",onclick:async()=>{await api("POST","cooldowns/clear",{provider:h.provider,model:h.model});load();}},"clear"):null;
 const m=MI.get(h.provider+"|"+h.model);
 return el("tr",{},el("td",{class:"m"},el("span",{class:"dot "+st}),(m?m.name+" · ":"")+h.provider+" · "+h.model),el("td",{},...h.tasks.map(t=>el("span",{class:"tag"},t))),el("td",{class:"m"},ago(h.last_ok_at)),
 el("td",{class:"m"},ago(h.last_fail_at)),el("td",{class:"m"},h.cooling_until?(inS(h.cooling_until)+" · "+h.reason):(h.reason||"–")),el("td",{class:"m"},h.ok_today+" ok / "+h.fail_today+" fail"+(h.avg_ms?" · "+h.avg_ms+" ms":"")),el("td",{},btn," ",clr));}));}

/* ---------- routes: friendly, ordered fallback lists ---------- */
function provName(id){const p=S.providers.find(x=>x.id===id);return p?p.name:id;}
function provUsable(id){const p=S.providers.find(x=>x.id===id);if(!p||!p.enabled)return false;return p.type==="workers-ai"||p.keys.some(k=>k.enabled);}
function list(t){return DRAFT[t]||(S.routes[t]||[]);}
function setList(t,l){DRAFT[t]=l;renderRoutes();}
function fits(t,m){const need=(S.task_info[t]||{}).needs;if(!need||need==="fast")return true;return m.caps.indexOf(need)>=0;}
function slot(t,c,i,n){const m=MI.get(c.provider+"|"+c.model);const need=(S.task_info[t]||{}).needs;const flags=[];
 if(!m&&CAT.discovery.some(d=>d.provider_id===c.provider&&d.ok))flags.push(el("span",{class:"tag w"},"not in the latest list"));
 if(m&&need&&m.caps.indexOf(need)<0)flags.push(el("span",{class:need==="fast"?"tag w":"tag x"},CAPWORD[need]));
 if(!provUsable(c.provider))flags.push(el("span",{class:"tag w"},"no key yet · skipped"));
 if(c.provider==="openrouter"&&!/:free$/.test(c.model))flags.push(el("span",{class:"tag x"},"paid · won't be used"));
 const up=el("button",{class:"b i",title:"move up",onclick:()=>{const l=list(t).slice();if(i>0){[l[i-1],l[i]]=[l[i],l[i-1]];setList(t,l);}}},"↑");
 const dn=el("button",{class:"b i",title:"move down",onclick:()=>{const l=list(t).slice();if(i<l.length-1){[l[i+1],l[i]]=[l[i],l[i+1]];setList(t,l);}}},"↓");
 const rm=el("button",{class:"b i d",title:"remove",onclick:()=>{const l=list(t).slice();l.splice(i,1);setList(t,l);}},"✕");
 if(i===0)up.disabled=true;if(i===n-1)dn.disabled=true;
 const det=el("details",{},el("summary",{},"id"),el("code",{}," "+c.provider+" · "+c.model));
 return el("div",{class:"slot"+(c.provider==="workers-ai"&&i===n-1?" last":"")},el("span",{class:"n"},String(i+1)),
  el("div",{class:"nm"},el("span",{},m?m.name:c.model),det,el("div",{},el("span",{class:"tag pv"},provName(c.provider)),...flags)),up,dn,rm);}
function picker(t){const st=PICK[t]||(PICK[t]={q:"",fit:true,open:false});const need=(S.task_info[t]||{}).needs;
 const addC=c=>{const l=list(t).slice();if(l.some(x=>x.provider===c.provider&&x.model===c.model)){toast("already in the list");return;}
  const last=l.length&&l[l.length-1].provider==="workers-ai"&&c.provider!=="workers-ai"?l.length-1:l.length;l.splice(last,0,c);st.open=true;setList(t,l);};
 if(!st.open)return el("div",{class:"row",style:"margin-top:6px"},el("button",{class:"b a",onclick:()=>{st.open=true;renderRoutes();const i=document.querySelector("#pick-"+t+" input");if(i)i.focus();}},"+ Add a model"));
 const q=el("input",{placeholder:"search models (e.g. flash, llama, coder)",class:"grow"});q.value=st.q;
 const fit=el("input",{type:"checkbox"});fit.checked=st.fit;const res=el("div",{class:"res",role:"listbox"});
 const fill=()=>{st.q=q.value;st.fit=fit.checked;const words=q.value.toLowerCase().split(" ").filter(Boolean);const by={};
  for(const m of CAT.models){if(st.fit&&!fits(t,m))continue;const hay=(m.name+" "+m.id+" "+m.provider+" "+provName(m.provider)).toLowerCase();if(words.some(w=>hay.indexOf(w)<0))continue;(by[m.provider]=by[m.provider]||[]).push(m);}
  const groups=Object.keys(by).sort((a,b)=>(provUsable(b)?1:0)-(provUsable(a)?1:0)||a.localeCompare(b));const kids=[];
  for(const p of groups){kids.push(el("div",{class:"g"},provName(p)+(provUsable(p)?"":" · no key yet")));
   for(const m of by[p]){const inL=list(t).some(x=>x.provider===m.provider&&x.model===m.id);
    kids.push(el("button",{type:"button",role:"option",title:m.provider+" · "+m.id,onclick:()=>addC({provider:m.provider,model:m.id})},el("span",{},(inL?"✓ ":"")+m.name+(fits(t,m)?"":" ⚠ "+CAPWORD[need])),el("span",{class:"id"},m.context?(m.context>=1e6?(Math.round(m.context/1e5)/10)+"M":Math.round(m.context/1000)+"k"):"")));}}
  if(!kids.length)kids.push(el("div",{class:"empty"},CAT.models.length?"No match":"No models yet: press Find models"));res.replaceChildren(...kids);};
 q.oninput=fill;fit.onchange=fill;fill();
 return el("div",{class:"pick",id:"pick-"+t},el("div",{class:"row"},q,el("label",{class:"muted",style:"font-size:12px"},fit," only models that fit"+(need&&need!=="fast"?" ("+({vision:"see images",tools:"use tools",long:"long inputs"})[need]+")":"")),el("button",{class:"b",onclick:()=>{st.open=false;renderRoutes();}},"Done")),res);}
async function saveTask(t){const models=list(t);try{await api("PUT","routes/"+t,{models});delete DRAFT[t];note("route-"+t,"Saved: "+models.length+" models","ok");await load(true);}catch(e){note("route-"+t,e.message,"bad");renderRoutes();}}
function renderRoutes(){if(!S)return;const info=S.task_info||{};$("#routes-note").replaceChildren(...[noteEl("routes")].filter(Boolean));
 $("#routes").replaceChildren(...S.tasks.map(t=>{const l=list(t);const ti=info[t]||{title:t,text:""};
  const save=el("button",{class:"b p",onclick:()=>saveTask(t)},"Save");const undo=DRAFT[t]?el("button",{class:"b",onclick:()=>{delete DRAFT[t];renderRoutes();}},"Undo"):null;
  const ta=el("textarea",{spellcheck:"false"});ta.value=l.map(c=>c.provider+" "+c.model).join("\\n");
  const apply=el("button",{class:"b",onclick:()=>{setList(t,ta.value.split("\\n").map(x=>x.trim()).filter(Boolean).map(x=>{const i=x.search(/\\s/);return i<0?{provider:x,model:""}:{provider:x.slice(0,i),model:x.slice(i).trim()};}));}},"Use this text");
  return el("div",{class:"card task"},el("div",{class:"row"},el("div",{class:"grow"},el("h3",{},ti.title),el("div",{class:"desc"},ti.text)),DRAFT[t]?el("span",{class:"dirty"},"unsaved"):null,undo,save),
   ...(l.length?l.map((c,i)=>slot(t,c,i,l.length)):[el("div",{class:"muted"},"No models: add some below or press Suggest.")]),
   picker(t),noteEl("route-"+t),el("details",{class:"adv"},el("summary",{},"advanced: edit as text (provider model, one per line)"),ta,el("div",{class:"row",style:"margin-top:6px"},apply)));}));}
$("#resetroutes").onclick=async()=>{if(!confirm("Put every task's model list back to the defaults?"))return;await api("POST","routes/reset");for(const k in DRAFT)delete DRAFT[k];note("routes","Routes reset to defaults","ok");load(true);};
$("#suggest").onclick=async e=>{const b=e.target;b.disabled=true;try{const r=await api("POST","routes/suggest",{});let n=0;for(const t of S.tasks)if(r.routes[t]&&r.routes[t].length){DRAFT[t]=r.routes[t];n++;}
 note("routes",n?("Suggested a lineup for "+n+" tasks from "+r.models+" discovered models. Review, then Save all."+(r.empty.length?" Nothing fits yet: "+r.empty.join(", ")+".":"")):"No discovered models yet: press Find models first.",n?"":"warn");renderRoutes();}catch(x){note("routes",x.message,"bad");renderRoutes();}b.disabled=false;};
$("#saveall").onclick=async()=>{const ts=Object.keys(DRAFT);if(!ts.length){note("routes","Nothing to save","");renderRoutes();return;}let bad=[];for(const t of ts){try{await api("PUT","routes/"+t,{models:DRAFT[t]});delete DRAFT[t];}catch(e){bad.push(t+": "+e.message);}}
 note("routes",bad.length?bad.join(" · "):("Saved "+ts.join(", ")),bad.length?"bad":"ok");load(true);};
$("#discall").onclick=async e=>{const b=e.target;b.disabled=true;b.textContent="Finding…";try{const r=await api("POST","discover");note("routes",r.results.map(x=>x.provider+": "+(x.ok?x.count+" models":(x.error||"failed"))).join(" · ")||"No provider can list models yet (add keys)",r.results.some(x=>!x.ok)?"warn":"ok");await loadModels();await load(true);}catch(x){note("routes",x.message,"bad");renderRoutes();}b.disabled=false;b.textContent="Find models (all providers)";};

/* ---------- providers ---------- */
function discFor(id){return (S.discovery||[]).find(d=>d.provider_id===id);}
function renderProviders(){$("#providers").replaceChildren(...S.providers.map(p=>{
 const name=el("input",{value:p.name,size:"26"});const url=el("input",{value:p.base_url,class:"grow"});if(p.type==="workers-ai")url.disabled=true;
 const en=el("input",{type:"checkbox"});en.checked=p.enabled;const notes=el("input",{value:p.notes,class:"grow"});
 const save=el("button",{class:"b p",onclick:async()=>{try{await api("PUT","providers/"+p.id,{name:name.value,base_url:url.value,type:p.type,enabled:en.checked,notes:notes.value});note(p.id,"Saved","ok");}catch(e){note(p.id,e.message,"bad");}load(true);}},"Save");
 const del=p.type==="workers-ai"?null:el("button",{class:"b d",onclick:async()=>{if(!confirm("Delete "+p.id+" and its keys?"))return;await api("DELETE","providers/"+p.id);load(true);}},"Delete");
 const d=discFor(p.id);const dtxt=d?(d.ok?(d.count+" models · "+(d.source==="curated"?"built-in list · ":"")+ago(d.ran_at)):("last try failed "+ago(d.ran_at)+": "+d.error)):"models not found yet";
 const find=el("button",{class:"b a",onclick:async e=>{const b=e.target;b.disabled=true;b.textContent="Finding…";try{const r=await api("POST","providers/"+p.id+"/discover");note(p.id,r.ok?("Found "+r.count+" free models"+(r.source==="curated"?" (built-in Workers AI list)":"")):("Couldn't list models: "+(r.error||"failed")),r.ok?"ok":"bad");if(r.ok)await loadModels().catch(()=>{});}catch(x){note(p.id,x.message,"bad");}await load(true);}},"Find models");
 const keys=el("table",{},el("tbody",{},...p.keys.map(k=>{const ke=el("input",{type:"checkbox"});ke.checked=k.enabled;ke.onchange=async()=>{await api("PATCH","keys/"+k.id,{enabled:ke.checked});load(true);};
  const st=k.cooling_until?"warn":(k.last_fail_at>k.last_ok_at?"bad":(k.last_ok_at?"ok":""));
  return el("tr",{},el("td",{class:"m"},el("span",{class:"dot "+st}),k.masked),el("td",{},k.label||""),el("td",{class:"m"},k.cooling_until?("cooling "+inS(k.cooling_until)):(k.last_error||(k.last_ok_at?"ok "+ago(k.last_ok_at):"untested"))),
  el("td",{},el("label",{class:"muted"},ke," on")),el("td",{},el("button",{class:"b",onclick:async e=>{e.target.textContent="…";try{const r=await api("POST","keys/"+k.id+"/test");note(p.id,r.ok?("Key "+k.masked+" works · "+r.ms+" ms"+(r.models!=null?" · "+r.models+" models":"")):("Key "+k.masked+" failed "+r.status+" "+(r.error||"")).slice(0,200),r.ok?"ok":"bad");}catch(x){note(p.id,x.message,"bad");}load(true);}},"test key")," ",
  el("button",{class:"b d",onclick:async()=>{if(!confirm("Delete key "+k.masked+"?"))return;await api("DELETE","keys/"+k.id);note(p.id,"Key "+k.masked+" deleted","");load(true);}},"delete")));})));
 const nk=el("input",{type:"password",placeholder:"paste a new key",class:"grow",autocomplete:"off"});const nl=el("input",{placeholder:"label (optional)",size:"14"});
 const add=el("button",{class:"b",onclick:async()=>{if(!nk.value.trim()){note(p.id,"Paste a key first","warn");renderProviders();return;}add.disabled=true;add.textContent="Adding…";
  try{const r=await api("POST","providers/"+p.id+"/keys",{key:nk.value,label:nl.value});nk.value="";
   const dsc=r.discovery?(r.discovery.ok?" · found "+r.discovery.count+" models":" · couldn't list models yet: "+(r.discovery.error||"")):"";
   note(p.id,r.duplicate?("That key is already saved ("+r.masked+"), nothing added"):("Key added "+r.masked+dsc),r.duplicate?"warn":"ok");if(r.discovery&&r.discovery.ok)loadModels().catch(()=>{});}
  catch(e){note(p.id,e.message,"bad");}document.activeElement&&document.activeElement.blur&&document.activeElement.blur();await load(true);}},"Add key");
 return el("div",{class:"card"},el("div",{class:"row",style:"margin-bottom:8px"},el("span",{class:"lbl"},p.id+" · "+p.type),el("span",{class:"tag pv"},dtxt),el("span",{class:"grow"}),el("label",{class:"muted"},en," enabled"),find,save,del),
  el("div",{class:"row",style:"margin-bottom:6px"},name,url),el("div",{class:"row",style:"margin-bottom:8px"},notes),
  p.type==="workers-ai"?el("div",{class:"muted",style:"font-size:12px"},"Uses the Worker's AI binding, no key."):el("div",{},keys,el("div",{class:"row",style:"margin-top:8px"},nk,nl,add)),noteEl(p.id));}));}
$("#np-add").onclick=async()=>{try{await api("POST","providers",{id:$("#np-id").value,name:$("#np-name").value,base_url:$("#np-url").value,type:$("#np-type").value});toast("provider added");load(true);}catch(e){toast(e.message);}};
function renderPlans(){$("#defplan").textContent=S.default_plan;$("#plans").replaceChildren(...S.plans.map(p=>{const pr=el("input",{type:"number",min:"0",step:"1",value:p.price_usd,size:"6"});
 const r=el("input",{type:"number",min:"0",value:p.daily_requests,size:"8"});const t=el("input",{type:"number",min:"0",value:p.daily_tokens,size:"12"});
 return el("tr",{},el("td",{class:"m"},p.id+(p.id===S.default_plan?" (default)":"")),el("td",{},pr),el("td",{},r),el("td",{},t),el("td",{},el("button",{class:"b p",onclick:async()=>{try{await api("PUT","plans/"+p.id,{price_usd:+pr.value,daily_requests:+r.value,daily_tokens:+t.value});toast(p.id+" saved");load(true);}catch(e){toast(e.message);}}},"Save")));}));
 /* 0.3.0: the dropdown is rebuilt on every load (0.2.x filled it once, so plans added later never showed), keeping the choice. */
 const sel=$("#as-plan");const keep=sel.value;sel.replaceChildren(...S.plans.map(p=>el("option",{value:p.id},p.id+(p.id===S.default_plan?" (default)":""))));
 sel.value=S.plans.some(p=>p.id===keep)?keep:S.default_plan;
 const st=accessOf();const known=S.plans.map(p=>p.id);const groups=known.concat([...new Set(S.assignments.map(a=>a.plan))].filter(x=>known.indexOf(x)<0));const rows=[];
 for(const g of groups){const as=S.assignments.filter(a=>a.plan===g);if(!as.length)continue;
  rows.push(el("tr",{},el("td",{colspan:"3",class:"grp"},g+(known.indexOf(g)<0?" (no such plan: uses "+S.default_plan+")":"")+" · "+as.length)));
  for(const a of as){const ps=planSelect(a.plan);ps.onchange=()=>assign(a.email,ps.value,"as-note");const s=st[a.email];
   rows.push(el("tr",{},el("td",{class:"m"},a.email,s&&s!=="approved"?el("span",{class:"tag "+(s==="rejected"?"x":"w"),style:"margin-left:8px"},s==="rejected"?"rejected":"not approved"):null),el("td",{},ps),
    el("td",{},el("button",{class:"b d",onclick:async()=>{if(!confirm("Remove "+a.email+"'s plan? They fall back to "+S.default_plan+"."))return;try{await api("PUT","assignments",{email:a.email,plan:""});note("as-note",a.email+" removed (now on "+S.default_plan+")","ok");toast(a.email+" removed");}catch(e){note("as-note",e.message,"bad");toast(e.message);}load(true);}},"remove"))));}}
 $("#assign").replaceChildren(...(rows.length?rows:[el("tr",{},el("td",{class:"muted"},"No accounts assigned yet: everyone is on "+S.default_plan+"."))]));
 const n=noteEl("as-note");$("#as-note").replaceChildren(...(n?[n]:[]));}
function planSelect(v){const s=el("select",{});for(const p of S.plans)s.append(el("option",{value:p.id},p.id));s.value=S.plans.some(p=>p.id===v)?v:S.default_plan;return s;}
async function assign(email,plan,nk){try{const r=await api("PUT","assignments",{email:email,plan:plan});note(nk,r.email+" is on "+r.plan+(r.approved?" and approved":""),"ok");toast(r.email+" → "+r.plan);await load(true);return true;}catch(e){note(nk,e.message,"bad");toast(e.message);renderPlans();renderAccess();return false;}}
$("#as-save").onclick=async()=>{const em=$("#as-email").value.trim();if(!em){note("as-note","Enter an account email first","warn");renderPlans();return;}const pl=$("#as-plan").value;if(!pl){note("as-note","Pick a plan","warn");renderPlans();return;}if(await assign(em,pl,"as-note"))$("#as-email").value="";};
$("#as-email").onkeydown=e=>{if(e.key==="Enter")$("#as-save").click();};

/* ---------- waiting list ---------- */
function accessOf(){const m={};const A=S.access;if(!A||!A.available)return m;for(const k of ["pending","approved","rejected"])for(const r of A[k])m[r.email]=r.status;return m;}
function renderBadge(){const A=S.access;const n=A&&A.available?A.pending.length:0;$("#wl-badge").replaceChildren(...(n?[el("span",{class:"badge"},String(n))]:[]));}
async function setAccess(email,status,plan){try{const r=await api("POST","access",{email:email,status:status,plan:plan});
 note("wl-note",status==="approved"?(email+" approved on "+r.plan):status==="rejected"?(email+" rejected"):(email+" moved back to pending"),status==="rejected"?"warn":"ok");toast(email+": "+status);await load(true);}
 catch(e){note("wl-note",e.message,"bad");toast(e.message);renderAccess();}}
function who(r){return r.name?el("div",{class:"muted",style:"font-size:12px"},r.name):null;}
function renderAccess(){const A=S.access;const n=noteEl("wl-note");$("#wl-note").replaceChildren(...(n?[n]:[]));
 const req=$("#wl-req");
 if(!A||!A.available){req.disabled=true;$("#wl-np").textContent=$("#wl-na").textContent=$("#wl-nr").textContent="–";for(const id of ["#wl-pending","#wl-approved","#wl-rejected"])$(id).replaceChildren(el("tr",{},el("td",{class:"muted"},"Apply D1 migration 0003_waitlist.sql to turn the waiting list on.")));return;}
 req.disabled=false;req.checked=!!A.require_approval;
 const plan=r=>{const a=S.assignments.find(x=>x.email===r.email);return a?a.plan:S.default_plan;};
 $("#wl-np").textContent=A.pending.length;$("#wl-na").textContent=A.approved.length;$("#wl-nr").textContent=A.rejected.length;
 $("#wl-pending").replaceChildren(...(A.pending.length?[el("tr",{},el("th",{},"Account"),el("th",{},"First asked"),el("th",{},"Last asked"),el("th",{},"Tries"),el("th",{},"Plan"),el("th",{},""))].concat(A.pending.map(r=>{const ps=planSelect(S.default_plan);
  return el("tr",{},el("td",{class:"m"},r.email,who(r)),el("td",{class:"m"},ago(r.first_seen)),el("td",{class:"m"},ago(r.last_seen)),el("td",{class:"m"},String(r.count)),el("td",{},ps),
   el("td",{},el("button",{class:"b p",onclick:()=>setAccess(r.email,"approved",ps.value)},"Approve")," ",el("button",{class:"b d",onclick:()=>setAccess(r.email,"rejected")},"Reject")));})):[el("tr",{},el("td",{class:"muted"},"Nobody is waiting."))]));
 $("#wl-approved").replaceChildren(...(A.approved.length?A.approved.map(r=>el("tr",{},el("td",{class:"m"},r.email,who(r)),el("td",{class:"m"},plan(r)),el("td",{class:"m"},(r.decided_by?"by "+r.decided_by+" · ":"")+ago(r.decided_at)),
  el("td",{},el("button",{class:"b",title:"back to pending: their requests are refused again",onclick:()=>setAccess(r.email,"pending")},"Undo")))):[el("tr",{},el("td",{class:"muted"},"No approved accounts yet."))]));
 $("#wl-legacy").textContent=A.legacy_pending_email?(A.legacy_pending_email+" earlier account(s) with usage history are allowed and will be listed here on their next request."):"";
 $("#wl-rejected").replaceChildren(...(A.rejected.length?A.rejected.map(r=>el("tr",{},el("td",{class:"m"},r.email,who(r)),el("td",{class:"m"},r.count+" tries · last "+ago(r.last_seen)),el("td",{class:"m"},(r.decided_by?"by "+r.decided_by+" · ":"")+ago(r.decided_at)),
  el("td",{},el("button",{class:"b",title:"back to pending",onclick:()=>setAccess(r.email,"pending")},"Undo")))):[el("tr",{},el("td",{class:"muted"},"Nobody rejected."))]));}
$("#wl-req").onchange=async e=>{const v=e.target.checked;try{await api("PUT","settings",{require_approval:v});note("wl-note",v?"New accounts now wait for approval":"Approval is off: any Blaze account can use the gateway (rejected ones stay blocked)",v?"ok":"warn");toast(v?"approval on":"approval off");}catch(x){note("wl-note",x.message,"bad");toast(x.message);}load(true);};
async function start(){$("#login").classList.add("hidden");$("#app").classList.remove("hidden");try{const me=await api("GET","me");$("#who").textContent=me.admin.email;await load(true);loadModels().catch(()=>{});}catch(e){toast(e.message);}}
setInterval(()=>{if(TOKEN&&S&&(tab==="health"||tab==="access")&&!document.hidden)load(false).catch(()=>{});},15000);
if(TOKEN)start();else $("#login").classList.remove("hidden");
})();
</script></body></html>`;
}
