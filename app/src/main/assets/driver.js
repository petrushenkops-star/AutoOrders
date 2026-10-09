/*
 * Experimental DOM adapter based on the supplied screen recording, NOT on verified HTML.
 * Run only inside https://lxcompintb.com; stop on any ambiguous UI.
 * All steps are idempotent at app level: no automatic replay of unknown financial results.
 */
(function () {
  const visible = e => !!(e && e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden');
  const norm = s => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();
  const text = norm(document.body?.innerText || '');
  const elements = () => [...document.querySelectorAll('button, a, [role="button"], input[type="submit"], .van-button')].filter(visible);
  const buttons = match => elements().filter(e => norm(e.innerText || e.value || e.getAttribute('aria-label')) === norm(match));
  const safeClick = e => { if (!e || e.disabled) return false; e.click(); return true; };
  const report = (status, detail) => JSON.stringify({ status, detail: detail || '' });
  const stage = __STAGE__;
  const code = __CODE__;
  if (location.host !== 'lxcompintb.com' || location.protocol !== 'https:') return report('error', 'Неверный адрес сайта');
  if (!document.body) return report('wait', 'Загрузка страницы');
  if (/неверный ввод динамического пароля|неправильный динамический пароль/.test(text)) return report('error', 'Сайт отклонил цифровой код');
  if (stage === 'OPEN_COPY') {
    if (text.includes('динамический пароль')) return report('advance', 'ENTER_CODE');
    const menuButton = buttons('Быстрое копирование');
    if (menuButton.length === 1 && safeClick(menuButton[0])) return report('advance', 'ENTER_CODE');
    return report('wait', 'Откройте меню личного кабинета и убедитесь, что видна кнопка «Быстрое копирование»');
  }
  if (stage === 'ENTER_CODE') {
    if (!text.includes('динамический пароль')) return report('wait', 'Ожидание формы цифрового кода');
    const input = [...document.querySelectorAll('input:not([type="hidden"])')].find(e => visible(e) &&
        /динамическ|парол/i.test((e.placeholder || '') + ' ' + (e.parentElement?.innerText || '')));
    if (!input) return report('wait', 'Поле цифрового кода не найдено');
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(input, code);
    input.dispatchEvent(new Event('input', {bubbles: true}));
    input.dispatchEvent(new Event('change', {bubbles: true}));
    const send = buttons('Отправлять');
    if (send.length !== 1 || !safeClick(send[0])) return report('error', 'Кнопка отправки кода не найдена однозначно');
    return report('advance', 'WAIT_DETAILS');
  }
  if (stage === 'WAIT_DETAILS') {
    if (text.includes('заказ выполнен успешно')) return report('error', 'Неожиданный экран результата');
    if (!text.includes('сумма заказа') || !text.includes('быстрое копирование')) return report('wait', 'Ожидание страницы заказа');
    const copy = buttons('Быстрое копирование');
    if (copy.length !== 1 || !safeClick(copy[0])) return report('error', 'Не удалось однозначно найти кнопку заказа');
    return report('advance', 'WAIT_CONFIRM');
  }
  if (stage === 'WAIT_CONFIRM') {
    if (!/вы уверены.*хотите скопировать/.test(text)) return report('wait', 'Ожидание окна подтверждения');
    const confirmations = buttons('Подтв.');
    if (confirmations.length !== 1 || !safeClick(confirmations[0])) return report('error', 'Не найдено подтверждение финансового заказа');
    return report('advance', 'WAIT_RESULT');
  }
  if (stage === 'WAIT_RESULT') {
    if (text.includes('заказ выполнен успешно')) {
      const close = buttons('Подтв.');
      if (close.length === 1) safeClick(close[0]);
      return report('success', 'Сайт сообщил об успешном выполнении заказа');
    }
    if (/ошибка|не удалось|недостаточно средств/.test(text)) return report('error', 'Сайт сообщил об ошибке заказа');
    return report('wait', 'Ожидание результата операции');
  }
  return report('error', 'Неизвестный этап');
})()
