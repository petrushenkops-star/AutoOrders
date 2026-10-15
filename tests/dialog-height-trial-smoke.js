// The live trial must change only CSS box/stacking and restore it after use.
const assert=require('node:assert/strict'), fs=require('node:fs'), vm=require('node:vm');
const source=fs.readFileSync('app/src/main/assets/dialog-height-trial.js','utf8');
function fixture({host='lxcompintb.com',allowVisible=true,height=0}={}){
 let clicks=0,submits=0,requests=0;
 const props=new Map();
 const style={
   getPropertyValue:p=>props.get(p)?.value||'',
   getPropertyPriority:p=>props.get(p)?.priority||'',
   setProperty:(p,v,priority='')=>props.set(p,{value:String(v),priority}),
   removeProperty:p=>props.delete(p)
 };
 const parent={}, body={};
 const overlay={className:'van-overlay',parentElement:parent,isConnected:true,contains:()=>false};
 const dialog={parentElement:parent,isConnected:true,scrollHeight:260,style,
   getClientRects:()=>[1],contains:e=>e===button,
   getBoundingClientRect:()=>({left:28,top:height?352:222,right:367,bottom:height?352+height:222+Number.parseFloat(props.get('height')?.value||0),
     width:339,height:Number.parseFloat(props.get('height')?.value||height)})};
 const button={innerText:'Подтв.',disabled:false,getClientRects:()=>[1],
   closest:()=>dialog,contains:e=>e===button,getAttribute:()=>null,
   getBoundingClientRect:()=>({left:201,right:348,top:555,bottom:593,width:147,height:38}),
   click(){clicks++}};
 const document={body,querySelectorAll:()=>[button],
   elementFromPoint:()=>allowVisible&&props.has('height')&&props.has('z-index')?button:overlay};
 const computed=e=>e===dialog?{
   display:'block',visibility:'visible',position:'fixed',
   height:props.get('height')?.value||height+'px',
   zIndex:props.get('z-index')?.value||'2003'
 }:e===overlay?{position:'fixed',zIndex:'2003'}:{display:'block',visibility:'visible'};
 const window={innerWidth:394,innerHeight:703};
 const location={protocol:'https:',hostname:host,href:'https://'+host+'/ru'};
 const ctx={window,document,location,getComputedStyle:computed};
 vm.createContext(ctx);
 return {
   run:phase=>JSON.parse(vm.runInContext(source.replace('__PHASE__',phase),ctx)),
   style,counts:()=>({clicks,submits,requests}),mutations:()=>props.size
 };
}
const ok=fixture();
let initial=ok.run('start');
assert.equal(initial.status,'started');
assert.equal(initial.targetHeight,260);
assert.equal(initial.actualHeight,260);
assert.equal(initial.state,'visible');
assert.equal(ok.run('check').state,'visible');
assert.equal(ok.run('check').state,'visible');
assert.equal(ok.run('restore').status,'restored');
assert.equal(ok.mutations(),0);
assert.equal(ok.run('check').status,'not_running');
assert.deepEqual(ok.counts(),{clicks:0,submits:0,requests:0});
const blocked=fixture({allowVisible:false});
assert.equal(blocked.run('start').status,'started');
assert.equal(blocked.run('check').state,'covered');
assert.equal(blocked.run('restore').status,'restored');
assert.equal(blocked.mutations(),0);
const unsuitable=fixture({height:260});
assert.equal(unsuitable.run('start').status,'unsafe_layout');
assert.equal(unsuitable.mutations(),0);
assert.equal(fixture({host:'example.org'}).run('start').status,'invalid_page');
const main=fs.readFileSync('app/src/main/java/com/example/autoorders/MainActivity.kt','utf8');
const first=main.indexOf('private fun trialFixDialogHeight()');
const last=main.indexOf('private var activeOrderId:',first);
assert.ok(first>=0&&last>first);
const method=main.slice(first,last);
assert.match(method,/dialog-height-trial\.js/);
assert.match(method,/execute\("start"\)/);
assert.match(method,/execute\("check"\)/);
assert.match(method,/execute\("restore"\)/);
assert.doesNotMatch(method,/siteRunner\.start|\.click\(|\.submit\(|loadUrl\(/);
assert.match(main,/Пробное исправление окна/);
console.log('Reversible dialog-height trial tests passed');
