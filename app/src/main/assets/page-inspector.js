/*
 * Read-only structure and geometry probe for https://lxcompintb.com.
 * No clicks, input edits, network requests, secrets, or raw page contents in result.
 */
(function () {
  const simulated = typeof __SIMULATOR__ !== 'undefined' && __SIMULATOR__ === true;
  const isolated = location.protocol === 'https:' &&
    location.hostname === 'appassets.androidplatform.net' &&
    document.getElementById?.('autoorders-simulator-root')?.getAttribute('data-autoorders-simulator') === 'v1';
  const realSite = location.protocol === 'https:' &&
    (location.hostname === 'lxcompintb.com' || location.hostname.endsWith('.lxcompintb.com'));
  const permitted = simulated ? isolated : realSite;
  if (!permitted || !document.body) return JSON.stringify({ status: 'error' });
  try {
    const norm = s => String(s || '').replace(/\s+/g, ' ').trim().toLowerCase();
    const visible = e => !!(e && e.getClientRects().length &&
      getComputedStyle(e).visibility !== 'hidden' &&
      getComputedStyle(e).display !== 'none');
    const actions = Array.from(document.querySelectorAll(
      'button, a, [role="button"], input[type="submit"], .van-button'
    )).filter(visible);
    const matching = name => actions.filter(e =>
      norm(e.innerText || e.value || e.getAttribute('aria-label')) === norm(name));
    const text = norm(document.body.innerText);
    const codeInput = Array.from(document.querySelectorAll('input:not([type="hidden"])'))
      .filter(visible).some(e =>
        /динамическ|парол/i.test((e.placeholder || '') + ' ' +
          (e.getAttribute('aria-label') || '') + ' ' + (e.parentElement?.innerText || '')));
    const amountLabel = text.includes('сумма заказа');
    const successLabel = /заказ выполнен успешно/.test(text);
    const confirmDialog = /вы уверены.*хотите скопировать/.test(text);
    const pageStage = successLabel ? 'Результат заказа' :
      confirmDialog ? 'Подтверждение заказа' :
      amountLabel ? 'Параметры заказа' :
      codeInput ? 'Ввод динамического кода' : 'Меню / другой экран';

    // Determine whether the single confirmation control is in the viewport and unobscured.
    // Numeric geometry is limited to CSS viewport coordinates; account/order data is omitted.
    const viewportWidth = window.visualViewport?.width || window.innerWidth || 0;
    const viewportHeight = window.visualViewport?.height || window.innerHeight || 0;
    const confirms = matching('Подтв.');
    let confirmState = 'not_found';
    let confirmTop = 0;
    let confirmBottom = 0;
    if (confirms.length === 1) {
      const button = confirms[0];
      const rect = button.getBoundingClientRect();
      confirmTop = Math.round(rect.top);
      confirmBottom = Math.round(rect.bottom);
      const outside = rect.bottom <= 0 || rect.top >= viewportHeight ||
        rect.right <= 0 || rect.left >= viewportWidth;
      if (button.disabled) {
        confirmState = 'disabled';
      } else if (outside) {
        confirmState = 'offscreen';
      } else {
        const x = Math.max(1, Math.min(viewportWidth - 1, rect.left + rect.width / 2));
        const y = Math.max(1, Math.min(viewportHeight - 1, rect.top + rect.height / 2));
        const topElement = document.elementFromPoint?.(x, y);
        if (!topElement) {
          confirmState = 'unknown';
        } else {
          confirmState = button === topElement || button.contains?.(topElement)
            ? 'visible' : 'covered';
        }
      }
    } else if (confirms.length > 1) {
      confirmState = 'unknown';
    }
    const dialogElements = Array.from(document.querySelectorAll(
      '[role="dialog"], .van-dialog, .van-popup'
    )).filter(visible).length;
    // Read-only CSS stacking diagnostics; never include raw account text, IDs or class names.
    const category = node => {
      if (!node || node === document.body) return 'страница';
      const cls = typeof node.className === 'string' ? node.className.toLowerCase() : '';
      const role = (node.getAttribute?.('role') || '').toLowerCase();
      if (role === 'dialog' || /dialog/.test(cls)) return 'dialog';
      if (/popup/.test(cls)) return 'popup';
      if (/overlay/.test(cls)) return 'overlay';
      if (/backdrop/.test(cls)) return 'backdrop';
      if (/(^|[\s_-])mask([\s_-]|$)/.test(cls)) return 'mask';
      if (/loading|spinner/.test(cls)) return 'loading';
      if (node.tagName === 'BUTTON') return 'button';
      return 'контейнер';
    };
    const css = node => {
      if (!node) return {z:'?',pos:'?',transform:false,opacity:'?'};
      const s = getComputedStyle(node);
      const z = String(s.zIndex || 'auto');
      const pos = String(s.position || '');
      const opacity = Number.parseFloat(s.opacity ?? '1');
      return {
        z: /^-?\d+$/.test(z) ? z.slice(0,12) : 'auto',
        pos: ['fixed','absolute','relative','sticky','static'].includes(pos) ? pos : 'неизвестно',
        transform: !!s.transform && s.transform !== 'none',
        opacity: Number.isFinite(opacity) ? opacity.toFixed(2) : '?'
      };
    };
    const popup = confirms.length === 1 ?
      confirms[0].closest?.('[role="dialog"], .van-dialog, .van-popup') || null : null;
    let blocker = null;
    if (confirms.length === 1 && confirmState === 'covered') {
      const b = confirms[0].getBoundingClientRect();
      blocker = document.elementFromPoint?.(b.left + b.width / 2, b.top + b.height / 2);
    }
    const chain = node => {
      const items = [];
      for (let e = node, i = 0; e && e !== document.body && i < 7; e = e.parentElement, i++) {
        const layer = css(e);
        if (i === 0 || layer.z !== 'auto' || layer.transform || layer.opacity !== '1.00') {
          items.push(category(e) + '[z=' + layer.z + ', ' + layer.pos +
            (layer.transform ? ', transform' : '') +
            (layer.opacity !== '1.00' ? ', opacity=' + layer.opacity : '') + ']');
        }
      }
      return items.slice(0, 5).join(' > ');
    };
    const blockerSide = popup && blocker ?
      (popup.contains?.(blocker) ? 'внутри окна' : 'вне окна') : 'не определено';

    // Read-only diagnostics for nested stacking contexts. No account data,
    // site text, form fields, IDs or raw class names leave this script.
    const contextFlags = node => {
      if (!node || node === document.body) return 'нет';
      const s = getComputedStyle(node), flags = [];
      if (s.transform && s.transform !== 'none') flags.push('transform');
      if (s.filter && s.filter !== 'none') flags.push('filter');
      if (s.perspective && s.perspective !== 'none') flags.push('perspective');
      if (s.isolation === 'isolate') flags.push('isolation');
      if (/(paint|layout|strict|content)/.test(s.contain || '')) flags.push('contain');
      if (Number.isFinite(Number(s.opacity)) && Number(s.opacity) < 1) flags.push('opacity');
      if (s.zIndex && s.zIndex !== 'auto' &&
          ['fixed','absolute','relative','sticky'].includes(s.position)) flags.push('z-index');
      return flags.join(', ') || 'нет';
    };
    const ancestry = node => {
      const result = [];
      for (let e = node, i = 0; e && i < 16; e = e.parentElement, i++) {
        result.push(e);
        if (e === document.body) break;
      }
      return result;
    };
    const ancestryPopup = popup ? ancestry(popup) : [];
    const ancestryBlocker = blocker ? ancestry(blocker) : [];
    const commonParent = ancestryPopup.find(e => ancestryBlocker.includes(e)) || null;
    const layerRelation = !popup || !blocker ? 'unknown' :
      popup.contains?.(blocker) ? 'blocker_inside_dialog' :
      blocker.contains?.(popup) ? 'dialog_inside_blocker' :
      popup.parentElement && popup.parentElement === blocker.parentElement ? 'siblings' :
      'separate_parents';
    const rectButton = confirms.length === 1 ? confirms[0].getBoundingClientRect() : null;
    const hitStack = rectButton && typeof document.elementsFromPoint === 'function' ?
      document.elementsFromPoint(rectButton.left + rectButton.width / 2,
        rectButton.top + rectButton.height / 2).slice(0, 5).map(category).join(' > ') :
      'недоступно';
    const parentLayer = e => e?.parentElement || null;
    return JSON.stringify({
      status: 'ok', pageStage,
      copyButtons: matching('Быстрое копирование').length,
      codeInput, sendButtons: matching('Отправлять').length,
      amountLabel, confirmButtons: confirms.length, successLabel,
      viewportHeight: Math.round(viewportHeight), dialogElements,
      confirmState, confirmTop, confirmBottom,
      blockerType: category(blocker), blockerZ: css(blocker).z,
      blockerPosition: css(blocker).pos, blockerSide,
      popupType: popup ? category(popup) : 'не определено',
      popupZ: css(popup).z, popupPosition: css(popup).pos,
      popupChain: chain(popup), blockerChain: chain(blocker),
      layerRelation, commonParent: category(commonParent),
      popupParentType: category(parentLayer(popup)),
      blockerParentType: category(parentLayer(blocker)),
      popupParentZ: css(parentLayer(popup)).z,
      blockerParentZ: css(parentLayer(blocker)).z,
      popupParentContext: contextFlags(parentLayer(popup)),
      blockerParentContext: contextFlags(parentLayer(blocker)),
      popupAncestryDepth: ancestryPopup.length,
      blockerAncestryDepth: ancestryBlocker.length,
      hitStack
    });
  } catch (_) { return JSON.stringify({ status: 'error' }); }
})()