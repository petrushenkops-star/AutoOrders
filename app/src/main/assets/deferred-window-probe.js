/*
 * One-shot live overlay diagnostic, with a deferred hit test.
 * Modes: start -> check -> commit / rollback. This script never invokes any
 * click(), form submission, network request, or financial operation.
 */
(function () {
  const mode = '__PROBE_PHASE__';
  const slot = '__autoordersDeferredWindowProbeV1';
  const result = (status, extra={}) => JSON.stringify({status,...extra});
  if (location.protocol !== 'https:' ||
      !(location.hostname === 'lxcompintb.com' ||
        location.hostname.endsWith('.lxcompintb.com')) || !document.body) {
    return result('invalid_page');
  }
  const kind = e => {
    if (!e) return 'none';
    const cls = typeof e.className === 'string' ? e.className.toLowerCase() : '';
    if (/(^|\s)(van-overlay|overlay|van-mask|modal-mask)(\s|$)/.test(cls)) return 'overlay';
    if (/dialog/.test(cls) || e.getAttribute?.('role') === 'dialog') return 'dialog';
    if (e.tagName === 'BUTTON') return 'button';
    return 'other';
  };
  const hit = button => {
    const r = button.getBoundingClientRect();
    const w = window.visualViewport?.width || window.innerWidth;
    const h = window.visualViewport?.height || window.innerHeight;
    if (!w || !h || r.width <= 0 || r.height <= 0 ||
        r.left < 0 || r.right > w || r.top < 2 || r.bottom > h - 2) return 'offscreen';
    const e = document.elementFromPoint?.(r.left+r.width/2,r.top+r.height/2);
    if (!e) return 'unknown';
    return e === button || button.contains(e) ? 'visible' : 'covered';
  };
  // Return only bounded structural metadata. Do not expose text, IDs, URLs,
  // code fields, amounts, class names, CSS selectors or account information.
  const snapshot = ({popup,blocker,button}) => {
    const r = button.getBoundingClientRect();
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    const top = document.elementFromPoint?.(x,y);
    const stack = typeof document.elementsFromPoint === 'function' ?
      document.elementsFromPoint(x,y).slice(0,5).map(kind).join(' > ') : 'unavailable';
    const ps = getComputedStyle(popup), bs = getComputedStyle(blocker);
    const safeZ = s => /^-?\d{1,9}$/.test(String(s.zIndex)) ? String(s.zIndex) : 'auto';
    const safePosition = s => ['fixed','absolute','sticky','relative','static']
      .includes(s.position) ? s.position : 'unknown';
    const safePointer = s => s.pointerEvents === 'none' ? 'none' :
      s.pointerEvents === 'auto' ? 'auto' : 'other';
    const hasTransition = s => String(s.transitionDuration||'0s')
      .split(',').some(v => parseFloat(v)>0);
    const rect = popup.getBoundingClientRect();
    return {
      topKind:kind(top),topIsOverlay:top===blocker,topInsideDialog:
        top===popup || !!popup.contains(top),hitStack:stack,
      popupZ:safeZ(ps),blockerZ:safeZ(bs),
      popupPosition:safePosition(ps),blockerPosition:safePosition(bs),
      popupTransform:!!ps.transform && ps.transform!=='none',
      blockerTransform:!!bs.transform && bs.transform!=='none',
      popupTransition:hasTransition(ps),blockerTransition:hasTransition(bs),
      popupPointer:safePointer(ps),blockerPointer:safePointer(bs),
      dialogContainsPoint:rect.top<=y && rect.bottom>=y &&
        rect.left<=x && rect.right>=x,
      afterOverlay:blocker.nextSibling===popup
    };
  };
  const restore = probe => {
    const {parent,popup,originalNext} = probe;
    if (!parent || popup.parentElement !== parent) return false;
    try {
      parent.insertBefore(popup,originalNext && originalNext.parentElement === parent ?
        originalNext : null);
      return true;
    } catch (_) { return false; }
  };
  try {
    if (mode === 'start') {
      if (window[slot]) return result('already_running');
      const normalize = s => String(s||'').replace(/\s+/g,' ').trim().toLowerCase();
      const matches = [...document.querySelectorAll(
        'button, a, [role="button"], input[type="submit"], .van-button'
      )].filter(e => e.getClientRects().length &&
        getComputedStyle(e).display !== 'none' &&
        getComputedStyle(e).visibility !== 'hidden' &&
        normalize(e.innerText||e.value||e.getAttribute('aria-label')) === 'подтв.');
      if (matches.length !== 1) return result('not_unique');
      const button=matches[0];
      if (button.disabled || hit(button) !== 'covered') return result('not_covered');
      const popup = button.closest?.('[role="dialog"], .van-dialog, .van-popup');
      if (!popup || !popup.contains(button) || !popup.getClientRects().length) return result('no_dialog');
      const r=button.getBoundingClientRect();
      const blocker=document.elementFromPoint?.(r.left+r.width/2,r.top+r.height/2);
      const parent=popup.parentElement;
      if (!blocker || kind(blocker)!=='overlay' || !parent ||
          parent!==blocker.parentElement || blocker===popup ||
          popup.contains(blocker) || blocker.contains(popup)) return result('unsafe_layout');
      const pcss=getComputedStyle(popup), bcss=getComputedStyle(blocker);
      if (pcss.position!=='fixed'||bcss.position!=='fixed'||
          !/^\d+$/.test(pcss.zIndex)||pcss.zIndex!==bcss.zIndex) return result('unsafe_layers');
      const originalNext=popup.nextSibling;
      const probe={popup,blocker,button,parent,originalNext,originalUrl:location.href};
      // Store original position before moving; no user interaction is dispatched.
      window[slot]=probe;
      try { parent.insertBefore(popup,blocker.nextSibling); }
      catch (_){delete window[slot];return result('move_failed');}
      return result('reordered', {
        immediate:hit(button), ...snapshot(probe)
      });
    }
    const probe=window[slot];
    if (!probe) return result('no_active_probe');
    if (location.href!==probe.originalUrl || probe.popup.parentElement!==probe.parent ||
        probe.blocker.parentElement!==probe.parent ||
        !probe.popup.contains(probe.button)) {
      delete window[slot];
      return result('page_changed');
    }
    const state=hit(probe.button);
    const details={state,...snapshot(probe)};
    if (mode==='check') return result('checked',details);
    if (mode==='rollback') {
      const restored=restore(probe);
      delete window[slot];
      return result(restored?'restored':'restore_failed',details);
    }
    if (mode==='commit') {
      if (state!=='visible' || !details.afterOverlay) return result('not_verified',details);
      delete window[slot];
      return result('confirmed_visible',details);
    }
    return result('invalid_phase');
  } catch (_) {
    return result('error');
  }
})()