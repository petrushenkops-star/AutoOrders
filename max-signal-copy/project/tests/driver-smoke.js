const assert = require('assert');
const fs = require('fs');
const vm = require('vm');
const source = fs.readFileSync('app/src/main/assets/driver.js','utf8');

function makeElement(name, options = {}) {
  const r = {left:0, top:0, width:100, height:30, ...(options.rect || {})};
  return {
    innerText:name, textContent:name, tagName:options.tagName || 'BUTTON',
    className: options.className || '', disabled: false,
    readOnly:false, isInput:!!options.isInput,
    parentElement:options.parent || {innerText:'Динамический пароль'},
    getAttribute:key=> (options.attributes || {})[key] || null,
    getBoundingClientRect:()=>({ ...r, right:r.left+r.width, bottom:r.top+r.height }),
    click() {this.clicks = (this.clicks || 0) + 1;},
    dispatchEvent:()=>{},
    querySelectorAll:()=> options.children || [],
  };
}
function run(stage, elements=[], bodyText='BTC/USDT', code='8438078', amount='', stageTicks=1) {
  function Input() {}
  Object.defineProperty(Input.prototype,'value',{set(v){this._value=v;}});
  const inputs=elements.filter(e=>e.isInput).map(e=>Object.assign(Object.create(Input.prototype),e));
  const nodes=elements.filter(e=>!e.isInput);
  const doc = {
    body:{innerText:bodyText},
    querySelectorAll:sel=> sel.startsWith('input:not') ? inputs :
      sel.startsWith("button, a, [role='button']") ? nodes.filter(e => ['BUTTON','A'].includes(e.tagName) || /van-button|ant-btn|nut-button|btn/.test(e.className)) : nodes,
  };
  const env = {
    window:{innerWidth:400,innerHeight:800,getComputedStyle:()=>({visibility:'visible',display:'block'})},
    document:doc, HTMLInputElement:Input,
    Event:function(type) {this.type=type;}
  };
  const js=source.replace('__STAGE__',JSON.stringify(stage)).replace('__CODE__',JSON.stringify(code)).replace('__AMOUNT__',JSON.stringify(amount)).replace('__STAGE_TICKS__',String(stageTicks));
  return {result:JSON.parse(vm.runInNewContext(js,env)),elements,inputs};
}
function resultIs(r,status,next){assert.equal(r.result.status,status);if(next)assert.equal(r.result.next,next);}

// Basic navigation using a button: no repeat while waiting for SPA route.
const trade=makeElement('Торговля');
let x=run('NAVIGATE_TRADE',[trade]);
resultIs(x,'advance','WAIT_TRADE');assert.equal(trade.clicks,1);
x=run('WAIT_TRADE',[trade]);resultIs(x,'wait');assert.equal(trade.clicks,1);

// Mobile bottom tab from real video: div/span instead of button.
const tab=makeElement('Торговля',{tagName:'DIV',className:'van-tabbar-item',rect:{top:710,left:170,width:70,height:40}});
const label=makeElement('Торговля',{tagName:'SPAN',parent:tab,rect:{top:726,left:174,width:55,height:20}});
x=run('NAVIGATE_TRADE',[tab,label]);
resultIs(x,'advance','WAIT_TRADE');assert.equal(tab.clicks,1);assert.equal(label.clicks||0,0);

// Promo LUMEN X: close only X at top-right, never trigger a trading button below it.
const cross=makeElement('×',{tagName:'SPAN',rect:{top:130,left:325,width:22,height:22},attributes:{'aria-label':'Close'}});
const promo=makeElement('LUMEN X',{tagName:'DIV',className:'van-popup',rect:{top:100,left:35,width:330,height:540},children:[cross]});
const tradeUnderPromo=makeElement('Торговля',{tagName:'DIV',className:'van-tabbar-item',rect:{top:720,left:170,width:75,height:35}});
x=run('NAVIGATE_TRADE',[promo,tradeUnderPromo]);
resultIs(x,'advance','WAIT_PROMO_DISMISS');assert.equal(cross.clicks,1);assert.equal(tradeUnderPromo.clicks||0,0);
x=run('WAIT_PROMO_DISMISS',[promo,tradeUnderPromo]);resultIs(x,'wait');assert.equal(cross.clicks,1);
x=run('WAIT_PROMO_DISMISS',[promo,tradeUnderPromo],'LUMEN X','8438078','',8);
resultIs(x,'wait');assert.equal(cross.clicks,2,'retry touches only promo X');
x=run('WAIT_PROMO_DISMISS',[promo,tradeUnderPromo],'LUMEN X','8438078','',32);
resultIs(x,'error');assert.equal(tradeUnderPromo.clicks||0,0,'never click trade while promo persists');
x=run('WAIT_PROMO_DISMISS',[tradeUnderPromo]);resultIs(x,'advance','NAVIGATE_TRADE');
// Unknown advertisement close must stop instead of clicking dangerous overlay.
x=run('NAVIGATE_TRADE',[makeElement('LUMEN X',{tagName:'DIV',rect:{top:120,left:25,width:340,height:500}})]);
resultIs(x,'error');

const fast=makeElement('Быстрое копирование');
x=run('WAIT_TRADE',[fast]);resultIs(x,'advance','OPEN_FAST_COPY');
x=run('OPEN_FAST_COPY',[fast]);resultIs(x,'advance','ENTER_CODE');assert.equal(fast.clicks,1);

const inp=makeElement('',{tagName:'INPUT',isInput:true,attributes:{placeholder:'Динамический пароль'}});
x=run('ENTER_CODE',[inp], 'Динамический пароль');
resultIs(x,'advance','SUBMIT_CODE');assert.equal(x.inputs[0]._value,'8438078');

const send=makeElement('Отправлять');
x=run('SUBMIT_CODE',[send]);resultIs(x,'advance','WAIT_ORDER');assert.equal(send.clicks,1);
const confirm=makeElement('Подтв.');
x=run('CONFIRM',[confirm],'Следовать. Подтвердить заявку?');
resultIs(x,'advance','WAIT_RESULT');assert.equal(confirm.clicks,1);
x=run('WAIT_RESULT',[confirm],'Следовать. Подтвердить заявку?');
resultIs(x,'wait');assert.equal(confirm.clicks,1);
const resultAck=makeElement('Подтв.');
x=run('WAIT_RESULT',[resultAck],'Быстрый. Заказ выполнен успешно!');resultIs(x,'advance','NAVIGATE_ORDERS');assert.equal(resultAck.clicks,1);
x=run('NAVIGATE_TRADE',[resultAck],'Быстрый. Заказ выполнен успешно!');resultIs(x,'error');assert.equal(resultAck.clicks,1);

const duplicate=makeElement('Подтв.');
x=run('CONFIRM',[confirm,duplicate],'Следовать. Подтвердить заявку?');
resultIs(x,'error');assert.equal(duplicate.clicks||0,0);
// Regression for the exact bug in the 7758(1).mp4 recording:
// one on-screen tab generates multiple nested DOM elements with the same text.
const rootTab = makeElement('Торговля',{tagName:'DIV',className:'van-tabbar-item',rect:{top:700,left:166,width:78,height:67}});
const tabBody = makeElement('Торговля',{tagName:'DIV',className:'van-tabbar-item__body',parent:rootTab,rect:{top:705,left:169,width:70,height:55}});
const tabCaption = makeElement('Торговля',{tagName:'SPAN',className:'van-tabbar-item__text',parent:tabBody,rect:{top:743,left:172,width:60,height:23}});
x=run('NAVIGATE_TRADE',[rootTab,tabBody,tabCaption]);
resultIs(x,'advance','WAIT_TRADE');
assert.equal(rootTab.clicks,1,'only real tab root should receive click');
assert.equal(tabBody.clicks||0,0);
assert.equal(tabCaption.clicks||0,0);

// Same label descendants without any framework classes also collapse.
const plainOuter=makeElement('Торговля',{tagName:'DIV',rect:{top:690,left:165,width:85,height:75}});
const plainInner=makeElement('Торговля',{tagName:'SPAN',parent:plainOuter,rect:{top:734,left:170,width:75,height:25}});
x=run('NAVIGATE_TRADE',[plainOuter,plainInner]);
resultIs(x,'advance','WAIT_TRADE'); assert.equal(plainOuter.clicks,1);

// Two distinct tabs remain ambiguous and must not be tapped.
const separateTab=makeElement('Торговля',{tagName:'DIV',className:'van-tabbar-item',rect:{top:701,left:264,width:78,height:65}});
x=run('NAVIGATE_TRADE',[rootTab,tabCaption,separateTab]);
resultIs(x,'error');assert.equal(separateTab.clicks||0,0);

// The real site may use a DIV or nested SPAN styled as a rounded button.
const fastDiv=makeElement('Быстрое копирование',{tagName:'DIV',className:'copy-action',rect:{top:480,left:20,width:300,height:50}});
const fastLabel=makeElement('Быстрое копирование',{tagName:'SPAN',parent:fastDiv,rect:{top:494,left:65,width:205,height:20}});
x=run('WAIT_TRADE',[fastDiv,fastLabel]);resultIs(x,'advance','OPEN_FAST_COPY');
x=run('OPEN_FAST_COPY',[fastDiv,fastLabel]);resultIs(x,'advance','ENTER_CODE');
assert.equal(fastDiv.clicks,1);assert.equal(fastLabel.clicks||0,0);
const sendDiv=makeElement('Отправлять',{tagName:'DIV',rect:{top:620,left:20,width:310,height:48}});
x=run('SUBMIT_CODE',[sendDiv]);resultIs(x,'advance','WAIT_ORDER');assert.equal(sendDiv.clicks,1);

console.log('driver-smoke: nested tab deduplication, DIV actions, promo, financial flow passed');

// 0.3.6 regression: a successful order-creation toast alone is NOT a
// notification. It must be followed by Orders -> BTC/USDT -> Waiting.
const ordersRoot = makeElement('Заказы',{tagName:'DIV',className:'van-tabbar-item',rect:{top:720,left:250,width:75,height:45}});
const ordersText = makeElement('Заказы',{tagName:'SPAN',parent:ordersRoot,rect:{top:740,left:258,width:58,height:20}});
x=run('NAVIGATE_ORDERS',[ordersRoot,ordersText], 'Главная', '8438078', '882.403561');
resultIs(x,'advance','WAIT_ORDERS');
assert.equal(ordersRoot.clicks,1);assert.equal(ordersText.clicks||0,0);
x=run('WAIT_ORDERS',[ordersRoot,ordersText], 'Главная', '8438078', '882.403561');
resultIs(x,'wait');
const heading=makeElement('Заказы',{tagName:'DIV',rect:{top:40,left:80,width:220,height:38}});
x=run('WAIT_ORDERS',[ordersRoot,heading], 'Заказы. Фьючерсы');
resultIs(x,'advance','CHECK_ORDER_STATUS');
const row=makeElement('BTC/USDT В ожидании Время расчета 60s Сумма заказа 882.403561',{tagName:'DIV',rect:{top:220,left:13,width:375,height:260}});
const pair=makeElement('BTC/USDT',{tagName:'DIV',parent:row,rect:{top:232,left:25,width:106,height:35}});
x=run('CHECK_ORDER_STATUS',[heading,row,pair], 'Заказы BTC/USDT В ожидании Сумма заказа 882.403561','8438078','882.403561');
resultIs(x,'pending');
x=run('CHECK_ORDER_STATUS',[heading,row,pair], 'Заказы BTC/USDT В ожидании Сумма заказа 882.403561','8438078','123.123456');
resultIs(x,'wait'); // old unrelated BTC/USDT pending order is insufficient
x=run('CHECK_ORDER_STATUS',[heading,row,pair], 'Заказы BTC/USDT В ожидании Сумма заказа 882.403561');
resultIs(x,'error'); // cannot prove which financial order was created
const completedRow=makeElement('BTC/USDT Исполнен Сумма заказа 882.403561',{tagName:'DIV',rect:{top:220,left:13,width:375,height:260}});
const completedPair=makeElement('BTC/USDT',{tagName:'DIV',parent:completedRow,rect:{top:233,left:27,width:110,height:36}});
x=run('CHECK_ORDER_STATUS',[heading,completedRow,completedPair],'Заказы BTC/USDT Исполнен Сумма заказа 882.403561','8438078','882.403561');
resultIs(x,'wait'); // no notification if status != waiting
// Amount in copying form is captured BEFORE clicking the final confirmation.
const copyAction=makeElement('Быстрое копирование');
x=run('OPEN_ORDER_COPY',[copyAction], 'BTC/USDT Сумма заказа 882.403561');
resultIs(x,'advance','WAIT_MODAL'); assert.equal(x.result.amount,'882.403561');
console.log('driver-smoke: verified pending order and exact amount match passed');

// 0.4.1 regression: after returning Home from a nested Copy screen,
// the LUMEN X promotional overlay may reappear while navigating to Orders.
// It must be closed without touching the financial confirmation controls.
const ordersPromoClose=makeElement('×',{tagName:'SPAN',rect:{top:130,left:325,width:22,height:22},attributes:{'aria-label':'Close'}});
const ordersPromo=makeElement('LUMEN X',{tagName:'DIV',className:'van-popup',rect:{top:100,left:35,width:330,height:540},children:[ordersPromoClose]});
const visibleOrdersTab=makeElement('Заказы',{tagName:'DIV',className:'van-tabbar-item',rect:{top:715,left:245,width:76,height:48}});
x=run('NAVIGATE_ORDERS',[ordersPromo,visibleOrdersTab],'LUMEN X');
resultIs(x,'advance','WAIT_PROMO_DISMISS_ORDERS');
assert.equal(ordersPromoClose.clicks,1,'only advertisement close clicked');
assert.equal(visibleOrdersTab.clicks||0,0);
x=run('WAIT_PROMO_DISMISS_ORDERS',[ordersPromo,visibleOrdersTab],'LUMEN X');
resultIs(x,'wait');
x=run('WAIT_PROMO_DISMISS_ORDERS',[visibleOrdersTab],'Главная');
resultIs(x,'advance','NAVIGATE_ORDERS');
x=run('NAVIGATE_ORDERS',[visibleOrdersTab],'Главная');
resultIs(x,'advance','WAIT_ORDERS');
assert.equal(visibleOrdersTab.clicks,1);

// Kotlin controller starts each run from Home, and returns to Home after a
// confirmed order before looking for the Orders tab. No financial retry in
// recovery. These are structural checks; Android builds compile the controller.
const kotlinController=fs.readFileSync('app/src/main/java/com/example/maxsignalcopy/BrowserAutomation.kt','utf8');
assert(kotlinController.includes('reloadHome("NAVIGATE_TRADE"'));
assert(kotlinController.includes('reloadHome("NAVIGATE_ORDERS"'));
assert(kotlinController.includes('orderListRecoveryUsed = true'));
assert(kotlinController.includes('if (next == "NAVIGATE_ORDERS" && stage == "WAIT_RESULT")'));
assert(kotlinController.includes('if (stage == "SUBMIT_CODE" || stage == "CONFIRM") uncertain = true'));
const kotlinActivity=fs.readFileSync('app/src/main/java/com/example/maxsignalcopy/MainActivity.kt','utf8');
assert(kotlinActivity.includes('TradeSession.pageFinished(url.orEmpty())'));
console.log('driver-smoke: auto-home navigation from nested pages and safe Orders retry passed');

// 0.4.3: click on Orders can be lost during Vue/Vant SPA re-render.
// Bounded second click is navigation only, never a confirmation or submit.
const retryOrdersRoot=makeElement('Заказы',{tagName:'DIV',className:'van-tabbar-item',rect:{top:720,left:250,width:75,height:45}});
x=run('WAIT_ORDERS',[retryOrdersRoot], 'Главная', '8438078', '882.403561',9);
resultIs(x,'wait');
assert.equal(retryOrdersRoot.clicks,1,'one bounded retry of Orders tab');
x=run('WAIT_ORDERS',[retryOrdersRoot], 'Главная', '8438078', '882.403561',10);
resultIs(x,'wait');
assert.equal(retryOrdersRoot.clicks,1,'no repeated navigation clicks');
// Some skins don't expose a separate text heading but clearly render Orders controls.
const futures=makeElement('Фьючерсы',{tagName:'DIV',rect:{top:90,left:40,width:300,height:45}});
const position=makeElement('Детали позиции',{tagName:'DIV',rect:{top:170,left:40,width:150,height:40}});
const history=makeElement('Подробности истории',{tagName:'DIV',rect:{top:170,left:210,width:160,height:40}});
x=run('WAIT_ORDERS',[futures,position,history], 'Фьючерсы Детали позиции Подробности истории BTC/USDT В ожидании Сумма заказа 882.403561');
resultIs(x,'advance','CHECK_ORDER_STATUS');
// Distinctive navigation controls are NOT enough to notify: status needs exact amount.
x=run('CHECK_ORDER_STATUS',[futures,position,history], 'Фьючерсы Детали позиции Подробности истории BTC/USDT');
resultIs(x,'error');
console.log('driver-smoke: SPA Orders retry and alternate page recognition passed');

// 0.4.4 regression from screenshot 7926.jpg: a site-owned "Быстрый"
// modal can remain on top of an already VERIFIED pending BTC/USDT order.
// Dismiss ONLY its "Понятно" after exact amount/status match, then notify.
const quickModal = makeElement('Быстрый Загрузка... Понятно', {
  tagName: 'DIV', className: 'van-dialog',
  rect: {top:210,left:24,width:345,height:450}
});
const quickTitle = makeElement('Быстрый', {
  tagName: 'DIV', parent:quickModal,
  rect: {top:240,left:150,width:100,height:35}
});
const quickOk = makeElement('Понятно', {
  tagName: 'BUTTON', parent:quickModal,
  rect: {top:595,left:44,width:310,height:52}
});
quickModal.querySelectorAll = () => [quickTitle, quickOk];
const quickNodes = [heading,row,pair,quickModal,quickTitle,quickOk];
const pendingWithQuick = 'Заказы Фьючерсы BTC/USDT В ожидании Сумма заказа 882.403561 Быстрый Загрузка... Понятно';
x=run('CHECK_ORDER_STATUS',quickNodes,pendingWithQuick,'8438078','882.403561');
resultIs(x,'advance','WAIT_QUICK_POPUP_CLOSE');
assert.equal(quickOk.clicks,1,'only popup acknowledgment is clicked');
x=run('WAIT_QUICK_POPUP_CLOSE',[heading,row,pair],'Заказы BTC/USDT В ожидании Сумма заказа 882.403561','8438078','882.403561');
resultIs(x,'pending');
assert.equal(quickOk.clicks,1,'no double closing');

// An old pending order with a different amount must not close the popup.
quickOk.clicks=0;
x=run('CHECK_ORDER_STATUS',quickNodes,pendingWithQuick,'8438078','999.999');
resultIs(x,'wait');
assert.equal(quickOk.clicks,0);
// Financial confirmation can still be visible: never touch "Понятно" during it.
x=run('WAIT_RESULT',quickNodes,'Быстрый Загрузка... Понятно');
resultIs(x,'wait');
assert.equal(quickOk.clicks,0);
// If the site renders an unrelated popup, never dismiss it using generic page X.
const unrelated = makeElement('Сервис Понятно',{tagName:'DIV',className:'van-dialog',rect:{top:210,left:24,width:345,height:400}});
x=run('CHECK_ORDER_STATUS',[heading,row,pair,unrelated],'Заказы BTC/USDT В ожидании Сумма заказа 882.403561 Сервис Понятно','8438078','882.403561');
resultIs(x,'pending');
console.log('driver-smoke: verified pending -> dismiss only the matching quick popup, no trade retries passed');
