// Mock-only checks of the viewport repair. Never triggers an actual order.
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const js = fs.readFileSync('app/src/main/assets/reveal-confirmation.js','utf8');
function scenario({buttonTop=489,viewport=290,hasPopup=true,hasButton=true,
                   blockedByOverlay=false,host='lxcompintb.com'}) {
  let clicks=0, shift=0, styles={};
  const style = {
    getPropertyValue(n){return styles[n]??'';},
    getPropertyPriority(){return '';},
    setProperty(n,value){styles[n]=value;if(n==='translate')shift=Number(value.split(' ')[1].replace('px',''));},
    removeProperty(n){delete styles[n];if(n==='translate')shift=0;}
  };
  const popup = {
    style,dataset:{},getClientRects(){return [1];},
    contains(e){return e===button},
    getBoundingClientRect(){return {top:buttonTop-70+shift,height:160,bottom:buttonTop+90+shift}}
  };
  const button = {
    innerText:'Подтв.',disabled:false,getClientRects(){return [1];},
    getAttribute(){return ''},click(){clicks++},
    contains(e){return e===this},
    closest(){return hasPopup?popup:null},
    getBoundingClientRect(){return {top:buttonTop+shift,bottom:buttonTop+38+shift,left:35,right:145,width:110,height:38}}
  };
  const overlay = {getClientRects(){return [1]}};
  const page = {
    body:{innerText:'Вы уверены, что хотите скопировать?'},
    querySelectorAll(sel) {
      if(sel.startsWith('[role="dialog"]'))return hasPopup?[popup]:[];
      return hasButton?[button]:[];
    },
    elementFromPoint(x,y){
      let r=button.getBoundingClientRect();
      if (y < r.top || y > r.bottom) return null;
      return blockedByOverlay && !styles['z-index'] ? overlay : button;
    }
  };
  const response=JSON.parse(vm.runInNewContext(js, {
    location:{protocol:'https:',hostname:host},
    document:page,window:{innerWidth:400,innerHeight:viewport},
    getComputedStyle:()=>({visibility:'visible',display:'block',zIndex:'1001'})
  }));
  assert.equal(clicks,0,'a simulated financial button was clicked');
  return {response,shift};
}
assert.equal(scenario({buttonTop:489,viewport:290}).response.status,'shifted');
assert.ok(scenario({buttonTop:489,viewport:290}).shift<0);
assert.equal(scenario({buttonTop:180,viewport:290}).response.status,'already_visible');
assert.equal(scenario({hasPopup:false}).response.status,'no_dialog');
assert.equal(scenario({hasButton:false}).response.status,'not_found');
assert.equal(scenario({host:'example.com'}).response.status,'invalid_page');
assert.equal(scenario({buttonTop:200,viewport:700,blockedByOverlay:true}).response.status,'layer_fixed');
console.log('Popup reposition: 6 click-free smoke tests passed');