/*
 * Experimental DOM adapter based on the supplied screen recording, NOT on verified HTML.
 * Run only inside https://lxcompintb.com; stop on any ambiguous UI.
 * All steps are idempotent at app level: no automatic replay of unknown financial results.
 */
(function () {
  const visible = e => !!(e && e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden' && getComputedStyle(e).display !== 'none');
  const norm = s => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();
  const text = norm(document.body?.innerText || '');
  const elements = () => [...document.querySelectorAll('button, a, [role="button"], input[type="submit"], .van-button')].filter(visible);
  const buttons = match => elements().filter(e => norm(e.innerText || e.value || e.getAttribute('aria-label')) === norm(match));
  const safeClick = e => { if (!e || e.disabled) return false; e.click(); return true; };
  // JS e.click() bypasses hit testing: require a real, unobscured onscreen button
  // before submitting a financial confirmation.
  const userReachable = e => {
    if (!e || !visible(e) || e.disabled) return false;
    const r = e.getBoundingClientRect?.();
    const w = window.visualViewport?.width || window.innerWidth;
    const h = window.visualViewport?.height || window.innerHeight;
    if (!r || !Number.isFinite(w) || !Number.isFinite(h) || w <= 0 || h <= 0) return false;
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    if (x < 1 || x >= w - 1 || y < 1 || y >= h - 1) return false;
    const top = document.elementFromPoint?.(x,y);
    return !!top && (top === e || e.contains?.(top));
  };
  const report = (status, detail) => JSON.stringify({ status, detail: detail || '' });
  const stage = __STAGE__;
  const code = __CODE__;
  const simulated = typeof __SIMULATOR__ !== 'undefined' && __SIMULATOR__ === true;
  const isolated = location.protocol === 'https:' &&
    location.hostname === 'appassets.androidplatform.net' &&
    document.getElementById?.('autoorders-simulator-root')?.getAttribute('data-autoorders-simulator') === 'v1';
  const realSite = location.host === 'lxcompintb.com' && location.protocol === 'https:';
  if (simulated ? !isolated : !realSite) return report('error', 'Неверный адрес сайта');
  if (!document.body) return report('wait', 'Загрузка страницы');
  if (/неверный ввод динамического пароля|неправильный динамический пароль/.test(text)) return report('error', 'Сайт отклонил цифровой код');
  if (stage === 'OPEN_COPY' || stage === 'WAIT_MENU') {
    // Only dismiss an image announcement, never a code/order/result dialog.
    const popups = [...document.querySelectorAll('[role="dialog"], .van-dialog, .van-popup')].filter(visible);
    const announcements = popups.filter(p => p.querySelector?.('img') &&
      !p.querySelector('input, textarea, form') &&
      !/динамический пароль|сумма заказа|вы уверены|подтв\.|заказ выполнен|недостаточно средств|быстрое копирование|центр безопасности|балансы/.test(norm(p.innerText)));
    if (announcements.length > 1) return report('error', 'Найдено несколько рекламных окон; закройте их вручную');
    if (announcements.length === 1) {
      const close = [...announcements[0].querySelectorAll(
        '.van-popup__close-icon, .van-icon-cross, button[aria-label], [role="button"][aria-label], button, [role="button"]'
      )].filter(e => visible(e) && (e.matches?.('.van-popup__close-icon, .van-icon-cross') ||
        /^(закрыть|close|×|✕|✖|x)$/.test(norm(e.getAttribute('aria-label') || e.innerText))));
      if (close.length !== 1 || !userReachable(close[0])) return report('wait', 'Не удалось однозначно найти доступную кнопку закрытия рекламы');
      if (!safeClick(close[0])) return report('wait', 'Кнопка закрытия рекламы недоступна');
      return report('wait', 'Закрытие рекламного окна');
    }
    if (text.includes('динамический пароль')) return report('advance', 'ENTER_CODE');
    const menuButton = buttons('Быстрое копирование');
    if (menuButton.length === 1 && userReachable(menuButton[0]) && safeClick(menuButton[0])) return report('advance', 'ENTER_CODE');
    if (menuButton.length > 1) return report('error', 'Найдено несколько кнопок «Быстрое копирование»');
    if (popups.length) return report('wait', 'Закройте открытое окно перед открытием меню');
    // Never toggle the menu again while waiting for its contents to render.
    if (stage === 'WAIT_MENU') return report('wait', 'Ожидание меню личного кабинета');
    const menu = [...document.querySelectorAll(
      '.van-icon-bars, .van-icon-wap-nav, button[aria-label], [role="button"][aria-label], [aria-haspopup="menu"]'
    )].filter(e => visible(e) && (e.matches?.('.van-icon-bars, .van-icon-wap-nav') ||
      /^(меню|открыть меню|menu|open menu)$/.test(norm(e.getAttribute('aria-label')))));
    if (menu.length !== 1) return report('wait', 'Кнопка меню не найдена однозначно; откройте меню вручную');
    if (!userReachable(menu[0])) return report('wait', 'Кнопка меню перекрыта или недоступна');
    if (!safeClick(menu[0])) return report('wait', 'Кнопка меню недоступна');
    return report('advance', 'WAIT_MENU');
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
  if (stage === 'WAIT_CONFIRM' || stage === 'WAIT_CONFIRM_SEND') {
    if (!/вы уверены.*хотите скопировать/.test(text)) {
      return stage === 'WAIT_CONFIRM_SEND' ?
        report('error', 'Окно подтверждения исчезло перед отправкой. Результат проверьте на сайте.') :
        report('wait', 'Ожидание окна подтверждения');
    }
    const confirmations = buttons('Подтв.');
    if (confirmations.length !== 1)
      return report('error', 'Не найдено однозначное подтверждение финансового заказа');
    const button = confirmations[0];
    const dialog = button.closest?.('[role="dialog"], .van-dialog, .van-popup');
    if (!dialog || !dialog.contains?.(button) || !visible(dialog) || button.disabled)
      return report('error', 'Подтверждение отключено или находится вне диалогового окна');
    if (!userReachable(button)) {
      // Only the native driver may decide whether to attempt a one-shot visual fix.
      // Never repair and click in the same JS execution.
      return stage === 'WAIT_CONFIRM' && !simulated ?
        report('repair_needed', 'Кнопка подтверждения перекрыта') :
        report('error', 'Подтверждение недоступно. Автоматическое нажатие не выполнялось.');
    }
    if (stage === 'WAIT_CONFIRM') return report('advance', 'WAIT_CONFIRM_SEND');
    // This is the ONLY financial confirmation step; the native runner marks
    // the outcome uncertain BEFORE evaluating this JS.
    if (!safeClick(button)) return report('error', 'Кнопка подтверждения отключена');
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
