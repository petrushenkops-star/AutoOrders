/*
 * Opt-in, temporary visual repair experiment for a zero-height order dialog.
 * Never clicks elements, dispatches events, submits forms, or sends requests.
 * phase=start: set dialog height and stacking; check: read hit-test;
 * restore: ALWAYS restore original inline styles, even after success.
 */
(function() {
  const phase='__PHASE__', slot='__autoordersHeightTrialV1';
  const reply=(status,extra={})=>JSON.stringify({status,...extra});
  const permitted=location.protocol==='https:' &&
    (location.hostname==='lxcompintb.com' || location.hostname.endsWith('.lxcompintb.com'));
  if(!permitted||!document.body)return reply('invalid_page');
  const visible=e=>!!e&&!!e.getClientRects().length&&
    getComputedStyle(e).display!=='none'&&getComputedStyle(e).visibility!=='hidden';
  const norm=s=>String(s||'').replace(/\s+/g,' ').trim().toLowerCase();
  const hit=button=>{
    const r=button.getBoundingClientRect(),w=window.innerWidth||0,h=window.innerHeight||0;
    if(r.width<1||r.height<1||r.left<0||r.right>w||r.top<0||r.bottom>h)return 'offscreen';
    const e=document.elementFromPoint?.(r.left+r.width/2,r.top+r.height/2);
    return !e?'unknown':e===button||button.contains(e)?'visible':'covered';
  };
  const record=(el,prop)=>({
    value:el.style.getPropertyValue(prop),priority:el.style.getPropertyPriority(prop)
  });
  const restoreStyle=(el,prop,old)=>{
    if(old.value)el.style.setProperty(prop,old.value,old.priority);
    else el.style.removeProperty(prop);
  };
  const restore=trial=>{
    for(const p of ['height','min-height','z-index']){
      restoreStyle(trial.dialog,p,trial.original[p]);
    }
  };
  try {
    if(phase==='start'){
      if(window[slot])return reply('already_running');
      const buttons=[...document.querySelectorAll(
        'button, a, [role="button"], input[type="submit"], .van-button'
      )].filter(e=>visible(e)&&
        norm(e.innerText||e.value||e.getAttribute('aria-label'))==='подтв.');
      if(buttons.length!==1)return reply('not_unique');
      const button=buttons[0],dialog=button.closest?.('[role="dialog"], .van-dialog, .van-popup');
      if(!dialog||!visible(dialog)||button.disabled||hit(button)!=='covered')return reply('not_covered');
      const r=button.getBoundingClientRect(),top=document.elementFromPoint?.(
        r.left+r.width/2,r.top+r.height/2);
      const cn=typeof top?.className==='string'?top.className:'';
      if(!/(^|\s)(van-overlay|overlay|van-mask|modal-mask)(\s|$)/i.test(cn))return reply('not_overlay');
      const cs=getComputedStyle(dialog),os=getComputedStyle(top),rect=dialog.getBoundingClientRect();
      const dz=Number(cs.zIndex),oz=Number(os.zIndex),height=dialog.scrollHeight;
      const maxH=(window.innerHeight||0)-32;
      if(!Number.isInteger(dz)||dz!==oz||dz<1||dz>2147483000||
         cs.position!=='fixed'||os.position!=='fixed' ||
         !dialog.parentElement||dialog.parentElement!==top.parentElement ||
         rect.width<100||rect.height>1||Math.abs(Number.parseFloat(cs.height))>1 ||
         !Number.isFinite(height)||height<100||height>maxH ||
         !dialog.contains(button)||top.contains(dialog)||dialog.contains(top))return reply('unsafe_layout');
      const original={};
      for(const p of ['height','min-height','z-index'])original[p]=record(dialog,p);
      const trial={dialog,button,overlay:top,original,url:location.href};
      window[slot]=trial;
      try{
        dialog.style.setProperty('height',Math.ceil(height)+'px','important');
        dialog.style.setProperty('min-height',Math.ceil(height)+'px','important');
        dialog.style.setProperty('z-index',String(dz+2),'important');
        return reply('started',{targetHeight:Math.ceil(height),
          actualHeight:Math.round(dialog.getBoundingClientRect().height),
          state:hit(button)});
      }catch(_){restore(trial);delete window[slot];return reply('apply_failed');}
    }
    const trial=window[slot];
    if(!trial)return reply('not_running');
    if(phase==='restore'){
      try{
        restore(trial);
        delete window[slot];
        return reply('restored',{state:hit(trial.button),
          actualHeight:Math.round(trial.dialog.getBoundingClientRect().height)});
      }catch(_){delete window[slot];return reply('restore_failed');}
    }
    if(phase!=='check')return reply('invalid_phase');
    if(location.href!==trial.url||!trial.dialog.isConnected||!trial.dialog.contains(trial.button)||
       !trial.overlay.isConnected)return reply('page_changed');
    return reply('checked',{state:hit(trial.button),
      actualHeight:Math.round(trial.dialog.getBoundingClientRect().height),
      dialogZ:String(getComputedStyle(trial.dialog).zIndex).slice(0,12)});
  }catch(_){
    const pending=window[slot];
    if(pending){try{restore(pending);}catch(_){}delete window[slot];}
    return reply('error');
  }
})()