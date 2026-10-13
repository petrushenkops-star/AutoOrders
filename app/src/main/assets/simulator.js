/* Fully local fake website. No network, cookies, account data or financial operations. */
(function(){
"use strict";
const root=document.getElementById("autoorders-simulator-root");
if(!root||root.getAttribute("data-autoorders-simulator")!=="v1")return;
let outcome="success",cover="normal",stage="menu",pz=2004,oz=2004;
const button=(id,label)=>'<button type="button" data-action="'+id+'">'+label+'</button>';
function render(){
 if(stage==="menu")root.innerHTML='<h3>Личный кабинет (демо)</h3>'+button("open","Быстрое копирование");
 if(stage==="code")root.innerHTML='<h3>Быстрое копирование</h3><label>Динамический пароль'+
   '<input placeholder="Динамический пароль" aria-label="Динамический пароль" inputmode="numeric"></label>'+
   button("send","Отправлять");
 if(stage==="details"||stage==="confirm"){
   root.innerHTML='<h3>Доставка и копирование</h3><p>Сумма заказа</p><h2>25.00 TEST</h2>'+
     button("copy","Быстрое копирование");
   if(stage==="confirm"){
     const mask='<div class="van-overlay" style="z-index:'+oz+'"></div>';
     const dialog='<section role="dialog" class="van-dialog" style="z-index:'+pz+'">'+
       '<p>Вы уверены, что хотите скопировать тестовый заказ?</p>'+
       button("cancel","Отмена")+button("confirm","Подтв.")+'</section>';
     root.insertAdjacentHTML("beforeend",cover==="blocked"?dialog+mask:mask+dialog);
   }
 }
 if(stage==="success")root.innerHTML='<h3 class="success">Заказ выполнен успешно!</h3>'+
    '<p>Это только симуляция.</p>'+button("close","Подтв.");
 if(stage==="error")root.innerHTML='<h3 class="error">Ошибка тестового заказа</h3>'+
   button("close","Закрыть");
 if(stage==="hang")root.innerHTML='<h3>Ожидание результата</h3><p>Симуляция отсутствия ответа.</p>';
 root.querySelectorAll("[data-action]").forEach(el=>el.addEventListener("click",()=>{
  const a=el.getAttribute("data-action");
  if(a==="open"&&stage==="menu")stage="code";
  else if(a==="send"&&stage==="code"){
    if(!/^\d+$/.test(root.querySelector("input")?.value||""))return;
    stage="details";
  }else if(a==="copy"&&stage==="details")stage="confirm";
  else if(a==="cancel"&&stage==="confirm")stage="details";
  else if(a==="confirm"&&stage==="confirm")stage=outcome==="success"?"success":outcome==="error"?"error":"hang";
  else if(a==="close")stage="menu";
  else return;
  render();
 }));
}
window.autoordersSimulator=Object.freeze({
 reset(nextOutcome,nextCover,popupZ=2004,overlayZ=2004){
  if(!["success","error","hang"].includes(nextOutcome)||!["normal","blocked"].includes(nextCover))return false;
  outcome=nextOutcome;cover=nextCover;
  pz=Number.isInteger(popupZ)&&popupZ>0&&popupZ<=100000?popupZ:2004;
  oz=Number.isInteger(overlayZ)&&overlayZ>0&&overlayZ<=100000?overlayZ:2004;
  stage="menu";render();return true;
 },
 // A single button opens the test confirmation in the currently selected mode.
 // This does not contact lxcompintb.com and does not submit anything.
 showConfirmation(){stage="confirm";render();return true;},
 state(){return stage;}
});
render();
})();