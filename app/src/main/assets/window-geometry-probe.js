/*
 * Read-only geometry snapshot of the real site confirmation.
 * No DOM mutation, event dispatch, form submission, network, storage or raw user data.
 * Only whitelisted numerical CSS coordinates and element categories are returned.
 */
(function () {
  const result = (status, extra={}) => JSON.stringify({status,...extra});
  if (location.protocol !== 'https:' ||
      !(location.hostname === 'lxcompintb.com' ||
        location.hostname.endsWith('.lxcompintb.com')) || !document.body) {
    return result('invalid_page');
  }
  try {
    const visible = e => e && e.getClientRects().length &&
      getComputedStyle(e).display !== 'none' &&
      getComputedStyle(e).visibility !== 'hidden';
    const norm = s => String(s||'').replace(/\s+/g,' ').trim().toLowerCase();
    const buttons=[...document.querySelectorAll(
      'button, a, [role="button"], input[type="submit"], .van-button'
    )].filter(e => visible(e) &&
       norm(e.innerText||e.value||e.getAttribute('aria-label'))==='подтв.');
    if (buttons.length!==1) return result('not_unique',{confirmCount:buttons.length>9?10:buttons.length});
    const button=buttons[0], popup=button.closest?.('[role="dialog"], .van-dialog, .van-popup');
    if (!popup || !visible(popup)) return result('no_dialog');
    const typ = e => {
      if (!e) return 'none';
      if (e===document.body) return 'page';
      const cls=typeof e.className==='string'?e.className.toLowerCase():'';
      if (/van-overlay|overlay|van-mask|modal-mask/.test(cls)) return 'overlay';
      if (e.getAttribute?.('role')==='dialog' || /dialog/.test(cls)) return 'dialog';
      if (/popup/.test(cls)) return 'popup';
      if (e.tagName==='BUTTON') return 'button';
      return 'other';
    };
    const rounded = n => Number.isFinite(Number(n)) ?
      Math.max(-10000,Math.min(10000,Math.round(Number(n)))) : null;
    const rect = e => {
      if(!e) return null;
      const r=e.getBoundingClientRect();
      return {x:rounded(r.left),y:rounded(r.top),w:rounded(r.width),
        h:rounded(r.height),right:rounded(r.right),bottom:rounded(r.bottom)};
    };
    const style = e => {
      if (!e) return null;
      const s=getComputedStyle(e), z=String(s.zIndex||'auto');
      return {z:/^-?\d{1,9}$/.test(z)?z:'auto',
        position:['fixed','absolute','relative','sticky','static'].includes(s.position)?
          s.position:'unknown',
        transform:!!s.transform&&s.transform!=='none',
        transition:String(s.transitionDuration||'0s').split(',')
          .some(x=>Number.parseFloat(x)>0),
        pointerEvents:s.pointerEvents==='none'?'none':
          s.pointerEvents==='auto'?'auto':'other'};
    };
    const br=button.getBoundingClientRect(),pr=popup.getBoundingClientRect();
    const bx=br.left+br.width/2,by=br.top+br.height/2;
    const px=pr.left+pr.width/2,py=pr.top+pr.height/2;
    const stack = (x,y) => {
      if(!Number.isFinite(x)||!Number.isFinite(y))return 'unknown';
      return typeof document.elementsFromPoint==='function' ?
         document.elementsFromPoint(x,y).slice(0,6).map(typ).join(' > '):
         typ(document.elementFromPoint?.(x,y));
    };
    const top=document.elementFromPoint?.(bx,by);
    const isInside=br.width>0&&br.height>0&&bx>=pr.left&&bx<=pr.right&&
      by>=pr.top&&by<=pr.bottom;
    // Read-only box model diagnostics for a zero-height dialog.
    const boxMetrics=e=>{
      if(!e)return null;
      const style=getComputedStyle(e);
      const number=p=>Number.isFinite(Number(e[p]))?rounded(e[p]):null;
      const value=p=>String(style[p]||'unknown').slice(0,32);
      return {kind:typ(e),rect:rect(e),height:value('height'),
        minHeight:value('minHeight'),maxHeight:value('maxHeight'),
        offsetHeight:number('offsetHeight'),clientHeight:number('clientHeight'),
        scrollHeight:number('scrollHeight'),
        overflowY:value('overflowY'),position:value('position'),
        display:value('display'),children:Math.min(e.children?.length||0,99)};
    };
    const ancestorBoxes=[];
    for(let e=popup,i=0;e&&i<4;e=e.parentElement,i++)ancestorBoxes.push(boxMetrics(e));
    const childBoxes=[...popup.children].slice(0,5).map(boxMetrics);
    const offsetBox=boxMetrics(button.offsetParent);
    const viewport=window.visualViewport||null;
    const contentW=window.innerWidth||0,contentH=window.innerHeight||0;
    const inView=br.width>0&&br.height>0&&bx>=0&&by>=0&&
      bx<contentW&&by<contentH;
    const blocker=typ(top)==='overlay'?top:null;
    const state=!inView?'offscreen':
      top===button||button.contains(top)?'visible':top?'covered':'unknown';
    return result('ok',{
      confirmState:state,
      buttonRect:rect(button), dialogRect:rect(popup), blockerRect:rect(blocker),
      buttonWithinDialogRect:isInside,
      buttonInsideDialogDOM:popup.contains(button),
      buttonCenter:{x:rounded(bx),y:rounded(by)},
      dialogCenter:{x:rounded(px),y:rounded(py)},
      buttonCenterDelta:{x:rounded(bx-px),y:rounded(by-py)},
      buttonStack:stack(bx,by),dialogCenterStack:stack(px,py),
      topKind:typ(top),
      buttonStyle:style(button),dialogStyle:style(popup),blockerStyle:style(blocker),
      dialogParentType:typ(popup.parentElement),
      ancestorBoxes,childBoxes,offsetBox,
      viewport:{innerW:rounded(contentW),innerH:rounded(contentH),
        visualW:rounded(viewport?.width??contentW),
        visualH:rounded(viewport?.height??contentH),
        offsetX:rounded(viewport?.offsetLeft??0),
        offsetY:rounded(viewport?.offsetTop??0),
        scale:rounded((viewport?.scale??1)*100)/100},
      scroll:{x:rounded(window.scrollX||0),y:rounded(window.scrollY||0)}
    });
  } catch (_) { return result('error'); }
})()