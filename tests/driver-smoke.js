// Offline contract checks for the DOM adapter; no network and no real orders.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const driver = fs.readFileSync('app/src/main/assets/driver.js', 'utf8');
function test(stage, code, bodyText, buttonNames, inputNeeded, expected, clicked, covered=false) {
  const clicks = [];
  const buttons = buttonNames.map(name => ({
    innerText: name, disabled: false, getClientRects() { return [1] }, click() { clicks.push(name) }, getAttribute() { return '' },
    getBoundingClientRect() {return {left:40,top:200,width:120,height:40}},
    closest() {return {getClientRects(){return [1]}, contains:e=>e===this}},
    contains(e){return e===this},
  }));
  const input = {placeholder: 'Пожалуйста, введите динамический пароль', parentElement: {innerText: 'Динамический пароль'},
    value: '', getClientRects() {return [1]}, dispatchEvent() {}};
  const sandbox = {
    location: {host: 'lxcompintb.com', protocol: 'https:'},
    document: {
      body: {innerText: bodyText},
      querySelectorAll(selector) {return selector.startsWith('input:') ? (inputNeeded ? [input] : []) : selector.startsWith('button, a,') ? buttons : []},
      elementFromPoint(){return covered?{className:'van-overlay'}:buttons.find(b=>b.innerText==='Подтв.') || buttons[0]}
    },
    window: {innerWidth:390,innerHeight:703},
    getComputedStyle: () => ({visibility:'visible',display:'block'}),
    HTMLInputElement: {prototype: Object.defineProperty({}, 'value', {set(v){this._ignored = v}, configurable:true})},
    Event: function(){},
  };
  const vm = require('node:vm');
  const codeJS = driver.replace('__STAGE__', JSON.stringify(stage)).replace('__CODE__', JSON.stringify(code));
  const result = JSON.parse(vm.runInNewContext(codeJS, sandbox));
  assert.equal(result.status, expected);
  assert.deepEqual(clicks, clicked);
}
test('OPEN_COPY', '123', 'Меню Быстрое копирование', ['Быстрое копирование'], false, 'advance', ['Быстрое копирование']);
test('ENTER_CODE', '123', 'Динамический пароль', ['Отправлять'], true, 'advance', ['Отправлять']);
test('WAIT_DETAILS', '', 'Сумма заказа 30.9 Быстрое копирование', ['Быстрое копирование'], false, 'advance', ['Быстрое копирование']);
test('WAIT_CONFIRM', '', 'Следовать Вы уверены, что хотите скопировать 30.9 USDT?', ['Подтв.'], false, 'advance', []);
test('WAIT_CONFIRM_SEND', '', 'Следовать Вы уверены, что хотите скопировать 30.9 USDT?', ['Подтв.'], false, 'advance', ['Подтв.']);
test('WAIT_RESULT', '', 'Быстрый Заказ выполнен успешно!', ['Подтв.'], false, 'success', ['Подтв.']);
test('WAIT_CONFIRM', '', 'Техническая ошибка', ['Подтв.'], false, 'wait', []);
test('WAIT_CONFIRM', '', 'Вы уверены, что хотите скопировать?', ['Подтв.'], false, 'repair_needed', [], true);
test('WAIT_CONFIRM_SEND', '', 'Вы уверены, что хотите скопировать?', ['Подтв.'], false, 'error', [], true);
test('OPEN_COPY', '123', 'Меню', [], false, 'wait', []);
console.log('DOM adapter: 10 smoke tests passed, including guarded confirmation and repair request');
