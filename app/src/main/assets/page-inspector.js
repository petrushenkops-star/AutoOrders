/*
 * Read-only structure probe for https://lxcompintb.com.
 * No clicks, input edits, network requests, secrets or raw content in the result.
 */
(function () {
  const permitted = location.protocol === 'https:' &&
    (location.hostname === 'lxcompintb.com' || location.hostname.endsWith('.lxcompintb.com'));
  if (!permitted || !document.body) return JSON.stringify({ status: 'error' });
  try {
    const norm = s => String(s || '').replace(/\s+/g, ' ').trim().toLowerCase();
    const visible = e => !!(e && e.getClientRects().length &&
      getComputedStyle(e).visibility !== 'hidden' && getComputedStyle(e).display !== 'none');
    const actions = Array.from(document.querySelectorAll(
      'button, a, [role="button"], input[type="submit"], .van-button'
    )).filter(visible);
    const exact = name => actions.filter(e =>
      norm(e.innerText || e.value || e.getAttribute('aria-label')) === norm(name)).length;
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
    return JSON.stringify({
      status: 'ok', pageStage: pageStage,
      copyButtons: exact('Быстрое копирование'),
      codeInput: codeInput,
      sendButtons: exact('Отправлять'),
      amountLabel: amountLabel,
      confirmButtons: exact('Подтв.'),
      successLabel: successLabel
    });
  } catch (_) { return JSON.stringify({ status: 'error' }); }
})()