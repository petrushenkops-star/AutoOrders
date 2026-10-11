/*
 * Read-only structure and geometry probe for https://lxcompintb.com.
 * No clicks, input edits, network requests, secrets, or raw page contents in result.
 */
(function () {
  const permitted = location.protocol === 'https:' &&
    (location.hostname === 'lxcompintb.com' || location.hostname.endsWith('.lxcompintb.com'));
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
      popupChain: chain(popup), blockerChain: chain(blocker)
    });
  } catch (_) { return JSON.stringify({ status: 'error' }); }
})()