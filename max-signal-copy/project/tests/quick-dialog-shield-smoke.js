const assert = require('assert');
const vm = require('vm');
const fs = require('fs');
const source = fs.readFileSync('app/src/main/assets/quick-dialog-shield.js', 'utf8');

function element(content, matchers = [], children = []) {
  const styles = new Map(), priority = new Map();
  const obj = {
    textContent: content, children, parentElement:null, isConnected:true,
    matches(selector) {return matchers.some(s => selector.includes(s));},
    getBoundingClientRect() {return {width:260,height:360};},
    style: {
      getPropertyValue(k){return styles.get(k) || '';},
      getPropertyPriority(k){return priority.get(k) || '';},
      setProperty(k,v,p){styles.set(k,v);priority.set(k,p);},
      removeProperty(k){styles.delete(k);priority.delete(k);}
    }
  };
  for(const child of children) child.parentElement=obj;
  return obj;
}
function simulate(popupTitle, popupContents, quick=true, otherDialog=false) {
  const heading=element(popupTitle);
  const ack=element('Понятно');
  const popup=element(popupContents,['.van-dialog'],[heading,ack]);
  const overlay=element('', ['.van-overlay']);
  const home=element('Главная / Торговля / Заказы');
  const children=[overlay,popup,home];
  if(otherDialog) children.push(element('Подтв. Следовать',['.van-dialog']));
  const body=element('',[],children);
  let observer;
  const document={documentElement:body,querySelectorAll(){return [heading];}};
  class FakeObserver {constructor(callback){this.callback=callback;observer=this;}observe(doc,options){assert.strictEqual(doc,document); assert(options.subtree && options.characterData);}}
  const context={document,MutationObserver:FakeObserver,window:{innerWidth:400,innerHeight:800}};
  vm.runInNewContext(source,context);
  assert(context.window.__maxQuickDialogShieldInstalled);
  assert.strictEqual(home.style.getPropertyValue('opacity'),'');
  if (quick) {
    assert.strictEqual(popup.style.getPropertyValue('opacity'),'0','quick popup visually concealed');
    assert.strictEqual(popup.style.getPropertyPriority('opacity'),'important');
    assert.strictEqual(popup.style.getPropertyValue('pointer-events'),'none');
    assert.strictEqual(overlay.style.getPropertyValue('opacity'),'0','only associated backdrop concealed');
    assert.strictEqual(popup.textContent,popupContents,'popup content preserved for site and driver');
  } else {
    assert.strictEqual(popup.style.getPropertyValue('opacity'),'','other dialog remains visible');
    assert.strictEqual(overlay.style.getPropertyValue('opacity'),'','unrelated backdrop visible');
  }
  if (otherDialog) assert.strictEqual(body.children[3].style.getPropertyValue('opacity'),'','confirmation dialog remains visible');
  // Another site event must not remove the DOM or accumulate altered styles.
  observer.callback();
  assert.strictEqual(popup.isConnected,true);
  // Once the quick popup disappears, restore any backdrop that we changed.
  heading.textContent='Завершено';
  observer.callback();
  assert.strictEqual(overlay.style.getPropertyValue('opacity'),'','mask restored after popup closes');
  assert.strictEqual(popup.style.getPropertyValue('opacity'),'','popup original style restored');
}
simulate('Быстрый','Быстрый Загрузка... Понятно',true);
simulate('Быстрый','× Быстрый Заказ выполнен успешно! Понятно',true);
simulate('Подтв.','Подтв. Следовать',false);
simulate('Быстрый','Быстрый Подтв. Следовать Понятно',false,true);
console.log('Quick-dialog document-start visual suppression tests passed');
