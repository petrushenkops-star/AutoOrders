// Page preparation exercises the shared driver without network or real orders.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const driver = fs.readFileSync('app/src/main/assets/driver.js', 'utf8');
const clicks = [];
function control(name, icon = '') {
  return {
    innerText: name, disabled: false,
    getAttribute: key => key === 'aria-label' ? name : '',
    getClientRects: () => [1],
    getBoundingClientRect: () => ({left: 300, top: 50, width: 30, height: 30}),
    matches: selector => !!icon && selector.includes(icon),
    contains: function (node) { return node === this; },
    click: () => clicks.push(name || icon),
  };
}
const close = control('', '.van-icon-cross');
const menu = control('', '.van-icon-bars');
const copy = control('Быстрое копирование');
const send = control('Отправлять');
function announcement(text = 'LUMEN X', closes = [close], hasInput = false) {
  return {
    innerText: text, getClientRects: () => [1],
    querySelector: selector => selector === 'img' ? {} : hasInput ? {} : null,
    querySelectorAll: () => closes,
  };
}
function run(stage, {popups = [], menus = [], actions = [], text = '', blocked = [], host = 'lxcompintb.com', input = null} = {}) {
  clicks.length = 0;
  const sandbox = {
    location: {host, hostname: host, protocol: 'https:'},
    document: {
      body: {innerText: text},
      querySelectorAll(selector) {
        if (selector.startsWith('[role="dialog"]')) return popups;
        if (selector.startsWith('.van-icon-bars')) return menus;
        if (selector.startsWith('input:')) return input ? [input] : [];
        if (selector.startsWith('button, a,')) return actions;
        return [];
      },
      elementFromPoint: () => [...popups.flatMap(p => p.querySelectorAll()), ...actions, ...menus]
        .find(e => !blocked.includes(e)) || {},
    },
    window: {innerWidth: 390, innerHeight: 700},
    getComputedStyle: () => ({visibility: 'visible', display: 'block'}),
    HTMLInputElement: {prototype: Object.defineProperty({}, 'value', {set(value) {this.value = value;}})},
    Event: function () {},
  };
  return JSON.parse(vm.runInNewContext(driver.replace('__STAGE__', JSON.stringify(stage))
    .replace('__CODE__', '"123456"'), sandbox));
}
// Poll across delayed rendering: dismiss ad, open menu once, then reach code form.
assert.equal(run('OPEN_COPY', {popups: [announcement()], menus: [menu]}).status, 'wait');
assert.deepEqual(clicks, ['.van-icon-cross']);
assert.equal(run('OPEN_COPY', {menus: [menu]}).detail, 'WAIT_MENU');
assert.deepEqual(clicks, ['.van-icon-bars']);
assert.equal(run('WAIT_MENU', {menus: [menu]}).status, 'wait');
assert.deepEqual(clicks, []);
assert.equal(run('WAIT_MENU', {actions: [copy]}).detail, 'ENTER_CODE');
assert.deepEqual(clicks, ['Быстрое копирование']);
// Vant renders its menu drawer as a popup too; it must remain usable.
const drawer = {innerText: 'Балансы Быстрое копирование', getClientRects: () => [1],
  querySelector: selector => selector === 'img' ? {} : null, querySelectorAll: () => []};
assert.equal(run('WAIT_MENU', {popups: [drawer], actions: [copy]}).detail, 'ENTER_CODE');
assert.deepEqual(clicks, ['Быстрое копирование']);
const input = {placeholder: 'Динамический пароль', getClientRects: () => [1], dispatchEvent() {}};
assert.equal(run('ENTER_CODE', {text: 'Динамический пароль', actions: [send], input}).detail, 'WAIT_DETAILS');
assert.equal(input.value, '123456');
assert.deepEqual(clicks, ['Отправлять']);
// Existing menu or form requires no extra toggle.
assert.equal(run('OPEN_COPY', {actions: [copy], menus: [menu]}).detail, 'ENTER_CODE');
assert.deepEqual(clicks, ['Быстрое копирование']);
assert.equal(run('OPEN_COPY', {text: 'Динамический пароль', menus: [menu]}).detail, 'ENTER_CODE');
assert.deepEqual(clicks, []);
// Both accessible labels and Vant icons are supported.
assert.equal(run('OPEN_COPY', {menus: [control('Открыть меню')]}).detail, 'WAIT_MENU');
assert.equal(run('OPEN_COPY', {menus: [control('', '.van-icon-wap-nav')]}).detail, 'WAIT_MENU');
// Ambiguity, occlusion and foreign origins must cause no clicks.
for (const state of [
  {popups: [announcement(), announcement()]},
  {popups: [announcement('LUMEN X', [close, control('Закрыть')])]},
  {popups: [announcement()], blocked: [close]},
  {menus: [menu, control('Меню')]},
  {menus: [menu], blocked: [menu]},
  {menus: [menu], host: 'example.com'},
  {actions: [copy, control('Быстрое копирование')]},
  {actions: [copy], blocked: [copy]},
]) {
  assert.notEqual(run('OPEN_COPY', state).status, 'advance');
  assert.deepEqual(clicks, []);
}
// An image alone must never turn a transaction/code dialog into an announcement.
for (const state of [
  {popups: [announcement('Вы уверены, что хотите скопировать?')], menus: [menu], actions: [copy]},
  {popups: [announcement('Заказ выполнен успешно')], menus: [menu]},
  {popups: [announcement('Сумма заказа')], menus: [menu]},
  {popups: [announcement('Ввод', [close], true)], menus: [menu]},
]) {
  assert.equal(run('OPEN_COPY', state).status, 'wait');
  assert.deepEqual(clicks, []);
}
console.log('Navigation: announcement dismissal, menu opening, delayed rendering and guarded clicks passed');
