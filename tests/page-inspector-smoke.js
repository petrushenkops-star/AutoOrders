// Read-only inspection smoke tests. No real website or financial actions.
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync('app/src/main/assets/page-inspector.js', 'utf8');
function inspect({label, bodyText, buttonNames, codeField, codeValue, host = 'lxcompintb.com'}) {
  const clicked = [];
  const buttons = buttonNames.map(name => ({
    innerText: name, getClientRects() { return [1]; },
    getAttribute() { return ''; },
    click() { clicked.push(name); }
  }));
  const secret = {
    placeholder: 'Динамический пароль', value: codeValue,
    parentElement: {innerText: 'Динамический пароль'},
    getAttribute() { return ''; }, getClientRects() { return [1]; }
  };
  const doc = {
    body: {innerText: bodyText},
    querySelectorAll(selector) { return selector.startsWith('input:') ? (codeField ? [secret] : []) : buttons; }
  };
  const result = JSON.parse(vm.runInNewContext(script, {
    location: {protocol:'https:', hostname:host},
    document: doc, getComputedStyle: () => ({visibility:'visible', display:'block'})
  }));
  assert.equal(clicked.length, 0);
  assert.ok(!JSON.stringify(result).includes(codeValue));
  assert.ok(!JSON.stringify(result).includes('7274747499'));
  return result;
}
const input = inspect({bodyText:'Быстрое копирование Динамический пароль UID 7274747499',
  buttonNames:['Отправлять'], codeField:true, codeValue:'123456'});
assert.equal(input.codeInput,true);
assert.equal(input.sendButtons,1);
assert.equal(input.pageStage,'Ввод динамического кода');
const details = inspect({bodyText:'Сумма заказа 37.4 USDT',
  buttonNames:['Быстрое копирование'], codeField:false, codeValue:'987654'});
assert.equal(details.amountLabel,true);
assert.equal(details.copyButtons,1);
assert.equal(details.pageStage,'Параметры заказа');
const confirm = inspect({bodyText:'Вы уверены, что хотите скопировать?',
  buttonNames:['Подтв.'], codeField:false, codeValue:'987654'});
assert.equal(confirm.confirmButtons,1);
assert.equal(confirm.pageStage,'Подтверждение заказа');
const wrong = inspect({bodyText:'Динамический пароль',
  buttonNames:['Отправлять'], codeField:true, codeValue:'987654', host:'example.com'});
assert.equal(wrong.status, 'error');
console.log('Page inspector: 4 read-only smoke tests passed');