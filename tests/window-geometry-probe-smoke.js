// Offline contract: geometry reads the real page without touching forms or DOM.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const code=fs.readFileSync('app/src/main/assets/window-geometry-probe.js','utf8');
function mock(host='lxcompintb.com'){
 let clicked=0,mutations=0;
 const body={tagName:'BODY',children:[],offsetHeight:703,clientHeight:703,scrollHeight:703,
   getBoundingClientRect:()=>({left:0,top:0,right:390,bottom:703,width:390,height:703})};
 const dialog={className:'van-dialog',parentElement:body,getAttribute:()=>null,
   getClientRects:()=>[1],contains:e=>e===button,children:[],
   offsetHeight:0,clientHeight:0,scrollHeight:0,
   getBoundingClientRect:()=>({left:80,top:100,right:300,bottom:100,width:220,height:0})};
 const overlay={className:'van-overlay',tagName:'DIV',getAttribute:()=>null,
   getBoundingClientRect:()=>({left:0,top:0,right:390,bottom:700,width:390,height:700})};
 const button={tagName:'BUTTON',innerText:'Подтв.',disabled:false,offsetParent:dialog,
   getAttribute:()=>null,getClientRects:()=>[1],closest:()=>dialog,
   contains:e=>e===button,
   getBoundingClientRect:()=>({left:130,top:550,right:230,bottom:585,width:100,height:35}),
   click(){clicked++}};
 const getComputedStyle=e=>({visibility:'visible',display:'block',height:e===dialog?'0px':'703px',
   minHeight:'0px',maxHeight:'none',overflowY:'visible',
   zIndex:'2003',position:e===button?'relative':'fixed',transform:e===dialog?'translate(-50%,-50%)':'none',
   transitionDuration:e===dialog?'0.3s':'0s',pointerEvents:'auto'});
 const document={body,querySelectorAll:()=>[button],
   elementFromPoint:()=>overlay,elementsFromPoint:()=>[overlay,body],
   createElement(){mutations++;}};
 const window={innerWidth:390,innerHeight:703,visualViewport:{width:390,height:703,offsetTop:0,offsetLeft:0,scale:1},scrollX:0,scrollY:0};
 const location={protocol:'https:',hostname:host};
 const ctx={window,document,getComputedStyle,location};vm.createContext(ctx);
 const run=()=>JSON.parse(vm.runInContext(code,ctx));
 return {run,stats:()=>({clicked,mutations})};
}
const good=mock();
for(let i=0;i<4;i++){
 const r=good.run();
 assert.equal(r.status,'ok');
 assert.equal(r.confirmState,'covered');
 assert.equal(r.buttonWithinDialogRect,false);
 assert.equal(r.buttonInsideDialogDOM,true);
 assert.equal(r.buttonRect.y,550);
 assert.equal(r.dialogRect.y,100);
 assert.equal(r.dialogRect.h,0);
 assert.equal(r.ancestorBoxes[0].height,'0px');
 assert.equal(r.ancestorBoxes[0].offsetHeight,0);
 assert.equal(r.ancestorBoxes[1].kind,'page');
 assert.equal(r.offsetBox.height,'0px');
 assert.equal(r.buttonStack,'overlay > page');
 assert.equal(r.dialogCenterStack,'overlay > page');
 assert.equal(r.dialogStyle.transform,true);
 assert.equal(r.dialogStyle.transition,true);
}
assert.deepEqual(good.stats(),{clicked:0,mutations:0});
assert.equal(mock('example.com').run().status,'invalid_page');
const activity=fs.readFileSync('app/src/main/java/com/example/autoorders/MainActivity.kt','utf8');
const start=activity.indexOf('private fun testSiteWindow()'),end=activity.indexOf('private var activeOrderId:',start);
assert.ok(start>=0&&end>start);
const body=activity.slice(start,end);
assert.match(body,/window-geometry-probe\.js/);
assert.match(body,/readSnapshot first@/);
assert.match(body,/readSnapshot last@/);
assert.doesNotMatch(body,/deferred-window-probe|reveal-confirmation|insertBefore|siteRunner\.start|\.click\(/);
assert.match(activity,/Копировать отчёт/);
console.log('Read-only live window geometry smoke tests passed');
