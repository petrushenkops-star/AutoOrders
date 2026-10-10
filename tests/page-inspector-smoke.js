// Read-only inspection contract tests; never sends an order or records secrets.
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync('app/src/main/assets/page-inspector.js', 'utf8');
function inspect({ bodyText='', names=[], codeField=false, secret='546789', confirmTop=320,
                   topElementIsConfirm=true, host='lxcompintb.com' }) {
  const clicks = [];
  const elements = names.map(name => ({
    innerText:name, disabled:false,
    getClientRects(){return [1]},
    getBoundingClientRect(){return {top:confirmTop,bottom:confirmTop+44,left:10,right:150,width:140,height:44}},
    contains(e){return e===this}, getAttribute(){return ''},
    click(){clicks.push(name)}
  }));
  const input = { placeholder:'Динамический пароль', value:secret,
    parentElement:{innerText:'Динамический пароль'},
    getClientRects(){return [1]}, getAttribute(){return ''} };
  const mockDocument = {
    body:{innerText:bodyText},
    querySelectorAll(selector){
      if (selector.startsWith('input:')) return codeField ? [input] : [];
      if (selector.startsWith('[role="dialog"]')) return [];
      return elements;
    },
    elementFromPoint(){return topElementIsConfirm?elements.find(e=>e.innerText==='Подтв.'):{}}
  };
  const result = JSON.parse(vm.runInNewContext(script, {
    location:{protocol:'https:',hostname:host},document:mockDocument,
    window:{innerHeight:850,innerWidth:390},
    getComputedStyle:()=>({visibility:'visible',display:'block'})
  }));
  assert.equal(clicks.length,0);
  assert.equal(JSON.stringify(result).includes(secret),false);
  assert.equal(JSON.stringify(result).includes('7226071297'),false);
  return result;
}
const code = inspect({bodyText:'Динамический пароль', names:['Отправлять'],codeField:true});
assert.equal(code.codeInput,true); assert.equal(code.sendButtons,1);
const details = inspect({bodyText:'Сумма заказа 31.45',names:['Быстрое копирование']});
assert.equal(details.amountLabel,true); assert.equal(details.copyButtons,1);
const visible = inspect({bodyText:'Вы уверены, что хотите скопировать?',names:['Подтв.']});
assert.equal(visible.confirmState,'visible');assert.equal(visible.pageStage,'Подтверждение заказа');
const outside = inspect({names:['Подтв.'],confirmTop:1200});
assert.equal(outside.confirmState,'offscreen');
const covered = inspect({names:['Подтв.'],topElementIsConfirm:false});
assert.equal(covered.confirmState,'covered');
const blocked = inspect({names:['Подтв.'],host:'example.com'});
assert.equal(blocked.status,'error');
console.log('Page inspector: 6 safe geometry smoke tests passed');