// Offline DOM tests. A mocked confirmation button must never be clicked.
const assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const js=fs.readFileSync('app/src/main/assets/reveal-confirmation.js','utf8');
function scenario({buttonTop=555,viewport=703,overlayZ=2005,dialogZ=2005,
  blocked=true,host='lxcompintb.com',popupExists=true,buttonExists=true,
  blockerClass='van-overlay',buttonDisabled=false,dialogRaiseWorks=true,overlayLowerWorks=true,
  siteRaisesOverlay=false,reorderWorks=true}={}) {
  const initialOverlayZ = overlayZ, initialDialogZ = dialogZ;
  const data={clicked:0,shift:0,changedOverlay:false,changedDialog:false,reordered:false,restored:false};
  function style(onChange) {
    const values={},priorities={};
    return {
      getPropertyValue(k){return values[k]||''},
      getPropertyPriority(k){return priorities[k]||''},
      setProperty(k,v,p=''){values[k]=v;priorities[k]=p;onChange(k,v);},
      removeProperty(k){delete values[k];delete priorities[k];onChange(k,null)}
    };
  }
  const parent={
    insertBefore(node,next){if(node!==popup)throw Error('unexpected node');
      data.reordered=next===null;
      if(!data.reordered)data.restored=true;
    }
  };
  const overlay={parentElement:parent,nextSibling:null,className:blockerClass,getClientRects(){return [1]},contains(){return false}};
  const popup={parentElement:parent,nextSibling:overlay,getClientRects(){return [1]},
    getBoundingClientRect(){return {top:buttonTop-80+data.shift,height:190}},
    contains(x){return x===button || x===popup}};
  popup.style=style((k,v)=>{
    if(k==='translate')data.shift=v?Number(v.split(' ')[1].replace('px','')):0;
    if(k==='z-index'){
      dialogZ=v===null?initialDialogZ:Number(v);data.changedDialog=true;
      if(siteRaisesOverlay)overlayZ=v===null?initialOverlayZ:initialOverlayZ+1;
    }
  });
  overlay.style=style((k,v)=>{
    if(k==='z-index'){overlayZ=v===null?initialOverlayZ:Number(v);data.changedOverlay=true}
  });
  const button={innerText:'Подтв.',disabled:buttonDisabled,
    getClientRects(){return [1]},getAttribute(){return null},
    getBoundingClientRect(){return {top:buttonTop+data.shift,
      bottom:buttonTop+data.shift+38,left:35,right:145,width:110,height:38}},
    contains(x){return x===this},
    closest(){return popupExists?popup:null},
    click(){data.clicked++}};
  const document={
    body:{innerText:'Вы уверены, что хотите скопировать?'},
    querySelectorAll(selector) {
      if(selector.includes('input[type="submit"]'))return buttonExists?[button]:[];
      return popupExists?[popup]:[];
    },
    elementFromPoint(x,y) {
      const r=button.getBoundingClientRect();
      if(y<r.top||y>r.bottom)return null;
      const raised=dialogRaiseWorks && dialogZ>initialDialogZ && dialogZ>overlayZ;
      const lowered=overlayLowerWorks && overlayZ<initialOverlayZ && overlayZ<dialogZ;
      const moved=dialogRaiseWorks===false && overlayLowerWorks===false &&
        data.reordered && reorderWorks;
      return blocked && !(raised||lowered||moved)?overlay:button;
    }
  };
  function getComputedStyle(e) {
    return {display:'block',visibility:'visible',position:'fixed',
      zIndex:String(e===overlay?overlayZ:dialogZ)};
  }
  const result=JSON.parse(vm.runInNewContext(js,{location:{hostname:host,protocol:'https:'},
    document,window:{innerWidth:390,innerHeight:viewport},getComputedStyle}));
  assert.equal(data.clicked,0);
  return {...data,result,overlayZ,dialogZ};
}

const raised=scenario();
assert.equal(raised.result.status,'dialog_raised');
assert.equal(raised.dialogZ,2008);
const delayedLayerRise=scenario({siteRaisesOverlay:true});
assert.equal(delayedLayerRise.result.status,'dialog_raised');
assert.equal(delayedLayerRise.overlayZ,2006);
assert.equal(delayedLayerRise.dialogZ,2008);
assert.equal(raised.clicked,0);
const fallback=scenario({dialogRaiseWorks:false});
assert.equal(fallback.result.status,'overlay_fixed');
assert.equal(fallback.dialogZ,2005);
assert.equal(fallback.overlayZ,2004);
const reordered=scenario({dialogRaiseWorks:false,overlayLowerWorks:false});
assert.equal(reordered.result.status,'dom_reordered');
assert.equal(reordered.reordered,true);
assert.equal(reordered.clicked,0);
const noChange=scenario({dialogRaiseWorks:false,overlayLowerWorks:false,reorderWorks:false});
assert.equal(noChange.result.status,'overlay_still_blocked');
assert.equal(noChange.restored,true);
assert.equal(noChange.dialogZ,2005);
assert.equal(noChange.overlayZ,2005);
assert.equal(scenario({blocked:false}).result.status,'already_visible');
assert.equal(scenario({buttonTop:150,viewport:290,blocked:false}).result.status,'already_visible');
assert.equal(scenario({buttonTop:490,viewport:290,blocked:false}).result.status,'shifted');
assert.equal(scenario({popupExists:false}).result.status,'no_dialog');
assert.equal(scenario({buttonExists:false}).result.status,'not_found');
assert.equal(scenario({host:'example.org'}).result.status,'invalid_page');
const unsafe=scenario({blockerClass:'unknown-cover'});
assert.equal(unsafe.result.status,'unsafe_layer');
assert.equal(unsafe.changedOverlay,false);
assert.equal(unsafe.changedDialog,false);
const unequal=scenario({overlayZ:2400});
assert.equal(unequal.result.status,'unsafe_layer');
assert.equal(unequal.changedOverlay,false);
assert.equal(unequal.changedDialog,false);
assert.equal(scenario({buttonDisabled:true}).result.status,'disabled');
console.log('Confirmation overlay: click-free smoke tests passed including DOM reorder and rollback');
