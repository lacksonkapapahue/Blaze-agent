// The /admin page: one static HTML document, no external assets. Blaze look: dark, rounded
// (no square corners), orange + blue accents, mono labels. All dynamic text goes through
// textContent (no innerHTML with data).

export function adminPage(nonce: string): string {
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>Blaze Gateway · admin</title>
<style nonce="${nonce}">
:root{--bg:#1c1c1f;--raised:#26262a;--raised2:#2e2e33;--hair:#3a3a40;--text:#ececea;--muted:#9c9c96;--accent:#7b98ff;--fire:#ff8a3d;--ok:#5fd38d;--warn:#f0b35a;--bad:#ff6b6b;
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
button.b:hover{border-color:var(--accent)}button.b:disabled{opacity:.5;cursor:default}
button.p{background:var(--fire);color:#1c1c1f;border-color:var(--fire);font-weight:600}button.p:hover{border-color:#ffb27d}
button.a{background:transparent;color:var(--accent);border-color:var(--accent)}button.d{color:var(--bad)}
button.i{padding:3px 9px;min-width:30px}
table{width:100%;border-collapse:separate;border-spacing:0;font-size:13px}th{text-align:left;font:11px var(--mono);text-transform:uppercase;letter-spacing:.08em;color:var(--muted);padding:6px 8px;border-bottom:1px solid var(--hair)}
td{padding:7px 8px;border-bottom:1px solid var(--hair);vertical-align:top}td.m{font-family:var(--mono);font-size:12px}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:6px;background:var(--muted)}.dot.ok{background:var(--ok)}.dot.warn{background:var(--warn)}.dot.bad{background:var(--bad)}
.tag{display:inline-block;font:11px var(--mono);color:var(--muted);border:1px solid var(--hair);border-radius:999px;padding:0 7px;margin:0 3px 3px 0}
.tag.pv{color:var(--accent);border-color:rgba(123,152,255,.45)}.tag.w{color:var(--warn);border-color:rgba(240,179,90,.5)}.tag.x{color:var(--bad);border-color:rgba(255,107,107,.5)}
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
.slot details{display:inline-block;margin-left:6px}.slot summary{display:inline;cursor:pointer;font:11px var(--mono);color:var(--muted);list-style:none}
.slot summary::-webkit-details-marker{display:none}.slot code{font:11px var(--mono);color:var(--muted);word-break:break-all}
.slot.last{border-style:dashed}.pick{background:var(--raised2);border-radius:var(--r-s);padding:9px;margin-top:8px}
details.adv>summary{cursor:pointer;font:11px var(--mono);color:var(--muted);margin-top:10px}
.dirty{color:var(--fire);font:11px var(--mono)}
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
  <div class="card"><div class="lbl" style="margin-bottom:8px">Assign a plan to an account</div>
  <div class="row"><input id="as-email" type="email" placeholder="account email" class="grow"><select id="as-plan"></select><button class="b p" id="as-save">Save</button></div>
  <table style="margin-top:10px"><tbody id="assign"></tbody></table></div>
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
function show(id){for(const s of ["health","routes","providers","plans"])$("#t-"+s).classList.toggle("hidden",s!==id);for(const b of document.querySelectorAll("#nav button"))b.classList.toggle("on",b.dataset.t===id);tab=id;if(id==="routes")loadModels().catch(e=>toast(e.message));}
function signOut(silent){if(!silent&&TOKEN)fetch("/admin/api/logout",{method:"POST",headers:{Authorization:"Bearer "+TOKEN}}).catch(()=>{});TOKEN="";sessionStorage.removeItem("bz_admin");$("#app").classList.add("hidden");$("#login").classList.remove("hidden");}
async function signIn(body){$("#lerr").textContent="";try{const r=await fetch("/admin/api/login",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)});const j=await r.json();if(!r.ok)throw new Error((j.error&&j.error.message)||"Sign-in failed");TOKEN=j.token;
 const me=await fetch("/admin/api/me",{headers:{Authorization:"Bearer "+TOKEN}});if(!me.ok)throw new Error("Not an admin");sessionStorage.setItem("bz_admin",TOKEN);$("#pw").value="";$("#tk").value="";start();}catch(e){TOKEN="";$("#lerr").textContent=e.message;}}
$("#go").onclick=()=>signIn({email:$("#em").value,password:$("#pw").value});$("#pw").onkeydown=e=>{if(e.key==="Enter")$("#go").click();};
$("#gotk").onclick=()=>signIn({token:$("#tk").value});$("#out").onclick=()=>signOut(false);
for(const b of document.querySelectorAll("#nav button"))b.onclick=()=>show(b.dataset.t);
/* force=true after the admin's own actions (always re-render); the 15 s refresh passes false so it never wipes what's being typed. */
async function load(force){S=await api("GET","state");render(force!==false);}
async function loadModels(){CAT=await api("GET","models");MI=new Map(CAT.models.map(m=>[m.provider+"|"+m.id,m]));renderRoutes();}
function editing(){const a=document.activeElement;return !!(a&&a.closest&&a.closest("#t-routes,#t-providers,#t-plans")&&(a.tagName==="INPUT"||a.tagName==="TEXTAREA"||a.tagName==="SELECT"));}
function render(force){$("#ver").textContent="v"+S.version;const w=$("#warns");w.replaceChildren(...S.warnings.map(x=>el("div",{class:"card warnbox"},"! "+x)));
 $("#s-req").textContent=S.usage_today.requests;$("#s-users").textContent=S.usage_today.users;$("#s-tok").textContent=Number(S.usage_today.tokens).toLocaleString();
 $("#s-cool").textContent=S.health.filter(h=>h.cooling_until).length;renderHealth();if(force||!editing()){renderRoutes();renderProviders();renderPlans();}}
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
function picker(t){const st=PICK[t]||(PICK[t]={q:"",fit:true});const q=el("input",{placeholder:"search models (e.g. flash, llama, coder)",class:"grow"});q.value=st.q;
 const fit=el("input",{type:"checkbox"});fit.checked=st.fit;const sel=el("select",{class:"grow"});
 const fill=()=>{st.q=q.value;st.fit=fit.checked;const words=q.value.toLowerCase().split(" ").filter(Boolean);const by={};
  for(const m of CAT.models){if(st.fit&&!fits(t,m))continue;const hay=(m.name+" "+m.id+" "+m.provider).toLowerCase();if(words.some(w=>hay.indexOf(w)<0))continue;(by[m.provider]=by[m.provider]||[]).push(m);}
  const groups=Object.keys(by).sort((a,b)=>(provUsable(b)?1:0)-(provUsable(a)?1:0)||a.localeCompare(b));
  sel.replaceChildren(...groups.map(p=>el("optgroup",{label:provName(p)+(provUsable(p)?"":" (no key yet)")},...by[p].map(m=>el("option",{value:m.provider+"|"+m.id},m.name+(fits(t,m)?"":" ⚠ "+CAPWORD[(S.task_info[t]||{}).needs]))))));
  if(!groups.length)sel.append(el("option",{value:""},CAT.models.length?"no match":"no models yet: press Find models"));};
 q.oninput=fill;fit.onchange=fill;fill();
 const add=el("button",{class:"b a",onclick:()=>{const v=sel.value;if(!v)return;const i=v.indexOf("|");const c={provider:v.slice(0,i),model:v.slice(i+1)};const l=list(t).slice();
  if(l.some(x=>x.provider===c.provider&&x.model===c.model)){toast("already in the list");return;}
  const last=l.length&&l[l.length-1].provider==="workers-ai"&&c.provider!=="workers-ai"?l.length-1:l.length;l.splice(last,0,c);setList(t,l);}},"+ Add");
 const need=(S.task_info[t]||{}).needs;
 return el("div",{class:"pick"},el("div",{class:"row"},q,el("label",{class:"muted",style:"font-size:12px"},fit," only models that fit"+(need&&need!=="fast"?" ("+({vision:"see images",tools:"use tools",long:"long inputs"})[need]+")":""))),el("div",{class:"row",style:"margin-top:6px"},sel,add));}
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
 const name=el("input",{value:p.name,size:"18"});const url=el("input",{value:p.base_url,class:"grow"});if(p.type==="workers-ai")url.disabled=true;
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
 const sel=$("#as-plan");if(!sel.options.length)for(const p of S.plans)sel.append(el("option",{value:p.id},p.id));
 $("#assign").replaceChildren(...S.assignments.map(a=>el("tr",{},el("td",{class:"m"},a.email),el("td",{class:"m"},a.plan),el("td",{},el("button",{class:"b",onclick:async()=>{await api("PUT","assignments",{email:a.email,plan:S.default_plan});load(true);}},"back to "+S.default_plan)))));}
$("#as-save").onclick=async()=>{try{await api("PUT","assignments",{email:$("#as-email").value,plan:$("#as-plan").value});$("#as-email").value="";toast("saved");load(true);}catch(e){toast(e.message);}};
async function start(){$("#login").classList.add("hidden");$("#app").classList.remove("hidden");try{const me=await api("GET","me");$("#who").textContent=me.admin.email;await load(true);loadModels().catch(()=>{});}catch(e){toast(e.message);}}
setInterval(()=>{if(TOKEN&&S&&tab==="health"&&!document.hidden)load(false).catch(()=>{});},15000);
if(TOKEN)start();else $("#login").classList.remove("hidden");
})();
</script></body></html>`;
}
