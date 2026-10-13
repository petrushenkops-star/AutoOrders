const assert=require("node:assert/strict"),fs=require("node:fs"),vm=require("node:vm");
const page=fs.readFileSync("app/src/main/assets/simulator.html","utf8");
const js=fs.readFileSync("app/src/main/assets/simulator.js","utf8");
assert.match(page,/connect-src 'none'/);assert.match(page,/data-autoorders-simulator="v1"/);
assert.doesNotMatch(js,/fetch\s*\(|XMLHttpRequest|localStorage|sendBeacon|document\.cookie/);
let html="",actions={};
const root={getAttribute(){return "v1"},
 set innerHTML(s){html=s;actions={}},get innerHTML(){return html},
 insertAdjacentHTML(_,s){html+=s},
 querySelector(){return {value:"123456"}},
 querySelectorAll(){return [...html.matchAll(/data-action="([^"]+)"/g)].map(m=>({
 getAttribute(){return m[1]},addEventListener(_,fn){actions[m[1]]=fn}
 }))}};
const window={},document={getElementById(){return root}};
vm.runInNewContext(js,{document,window});
const sim=window.autoordersSimulator;
const click=a=>{assert.ok(actions[a],a);actions[a]();};
click("open");assert.equal(sim.state(),"code");
click("send");assert.equal(sim.state(),"details");
click("copy");assert.equal(sim.state(),"confirm");
assert.ok(html.indexOf("van-overlay")<html.indexOf("van-dialog"));
click("confirm");assert.equal(sim.state(),"success");
assert.equal(sim.reset("error","blocked"),true);
click("open");click("send");click("copy");
assert.ok(html.indexOf("van-dialog")<html.indexOf("van-overlay"));
click("confirm");assert.equal(sim.state(),"error");
assert.equal(sim.reset("hang","normal"),true);
click("open");click("send");click("copy");click("confirm");
assert.equal(sim.state(),"hang");
assert.equal(sim.reset("unexpected","normal"),false);
// Driver may enter the simulator ONLY with explicit flag, local origin and marker.
const driver=fs.readFileSync("app/src/main/assets/driver.js","utf8");
function probe(host,flag,marker){
 const sandbox={
   document:{body:{innerText:"Меню"},getElementById:()=>marker?root:null,querySelectorAll:()=>[]},
   location:{host,hostname:host,protocol:"https:"},
   window:{innerWidth:390,innerHeight:700},
   getComputedStyle:()=>({visibility:"visible",display:"block"})
 };
 const source=(flag?"const __SIMULATOR__=true;\n":"")+
    driver.replace("__STAGE__",'"OPEN_COPY"').replace("__CODE__",'"123456"');
 return JSON.parse(vm.runInNewContext(source,sandbox));
}
assert.equal(probe("appassets.androidplatform.net",true,true).status,"wait");
assert.equal(probe("appassets.androidplatform.net",false,true).status,"error");
assert.equal(probe("appassets.androidplatform.net",true,false).status,"error");
assert.equal(probe("lxcompintb.com",true,true).status,"error");
assert.equal(probe("lxcompintb.com",false,false).status,"wait");
const kotlin=fs.readFileSync("app/src/main/java/com/example/autoorders/SimulatorActivity.kt","utf8");
assert.match(kotlin,/blockNetworkLoads = true/);
assert.match(kotlin,/allowFileAccess = false/);
assert.match(kotlin,/allowContentAccess = false/);
assert.match(kotlin,/simulation = true/);
console.log("Simulator scenarios, origin isolation and offline checks passed");
