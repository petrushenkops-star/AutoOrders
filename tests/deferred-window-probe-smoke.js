// Staged overlay probing: no click or submit. Repeated evaluations share WebView context.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const js=fs.readFileSync('app/src/main/assets/deferred-window-probe.js','utf8');
function scenario(host='lxcompintb.com'){
 let painted=false,clicks=0,moves=0,restores=0;
 const parent={insertBefore(node,next){
   assert.equal(node,popup);
   assert.ok(next===null||next===overlay);
   if(next===null){overlay.nextSibling=popup;popup.nextSibling=null;moves++;}
   else{popup.nextSibling=overlay;overlay.nextSibling=null;restores++;}
 }};
 const popup={parentElement:parent,nextSibling:null, className:'van-dialog',
   getClientRects:()=>[1],contains:e=>e===button,
   getAttribute:n=>n==='role'?'dialog':'',getBoundingClientRect:()=>({top:480,left:20,width:260,height:180})};
 const overlay={parentElement:parent,nextSibling:null,className:'van-overlay',
   getClientRects:()=>[1],contains:()=>false};
 popup.nextSibling=overlay;
 const button={innerText:'Подтв.',disabled:false,tagName:'BUTTON',
   getClientRects:()=>[1],contains:e=>e===button,closest:()=>popup,
   getAttribute:()=>'',getBoundingClientRect:()=>({top:555,bottom:593,left:40,right:140,width:100,height:38}),
   click(){clicks++}};
 const body={};
 const document={body,querySelectorAll:()=>[button],
   elementFromPoint:()=>painted?button:overlay,
   elementsFromPoint:()=>painted?[button,popup,overlay,body]:[overlay,popup,body]};
 const window={innerWidth:390,innerHeight:703};
 const location={protocol:'https:',hostname:host,href:'https://'+host+'/ru'};
 const getComputedStyle=e=>({
   display:'block',visibility:'visible',position:'fixed',zIndex:'2003',
   pointerEvents:'auto',transform:'none',transitionDuration:'0s'
 });
 const context={location,document,window,getComputedStyle};
 vm.createContext(context);
 const run=phase=>JSON.parse(vm.runInContext(js.replace('__PROBE_PHASE__',phase),context));
 return {run,setPaint:v=>painted=v,counts:()=>({clicks,moves,restores})};
}
const success=scenario();
assert.equal(success.run('start').status,'reordered');
assert.equal(success.counts().moves,1);
const hidden=success.run('check');
assert.equal(hidden.topKind,'overlay');
assert.equal(hidden.topIsOverlay,true);
assert.equal(hidden.dialogContainsPoint,true);
assert.equal(hidden.popupZ,'2003');
assert.equal(hidden.blockerZ,'2003');
assert.equal(hidden.hitStack,'overlay > dialog > other');
assert.equal(hidden.afterOverlay,true);
assert.equal(success.run('check').state,'covered');
success.setPaint(true);
const checked=success.run('check');
assert.equal(checked.status,'checked');assert.equal(checked.afterOverlay,true);
assert.equal(checked.state,'visible');
assert.equal(checked.topKind,'button');
assert.equal(checked.topIsOverlay,false);
assert.equal(checked.topInsideDialog,true);
assert.equal(success.run('commit').status,'confirmed_visible');
assert.equal(success.run('check').status,'no_active_probe');
assert.deepEqual(success.counts(),{clicks:0,moves:1,restores:0});
const failure=scenario();
assert.equal(failure.run('start').status,'reordered');
assert.equal(failure.run('commit').status,'not_verified');
assert.equal(failure.run('rollback').status,'restored');
assert.deepEqual(failure.counts(),{clicks:0,moves:1,restores:1});
assert.equal(failure.run('rollback').status,'no_active_probe');
assert.equal(scenario('example.com').run('start').status,'invalid_page');
const main=fs.readFileSync('app/src/main/java/com/example/autoorders/MainActivity.kt','utf8');
const start=main.indexOf('private fun testSiteWindow()');
const end=main.indexOf('private var activeOrderId:',start);
assert.ok(start>=0&&end>start);
const test=main.slice(start,end);
assert.match(test,/deferred-window-probe\.js/);
assert.match(test,/evalProbe\("rollback"\)/);
assert.match(test,/evalProbe\("commit"\)/);
assert.match(test,/postDelayed\(checkOne@/);
assert.match(test,/postDelayed\(checkTwo@/);
assert.match(test,/fun layerInfo\(data: JSONObject\?\)/);
assert.match(test,/Через 0,5 секунды/);
assert.match(test,/Через 2 секунды/);
assert.doesNotMatch(test,/siteRunner\.start|\.click\(|\.submit\(/);
console.log('Deferred live overlay probe: staged checks and rollback passed');
