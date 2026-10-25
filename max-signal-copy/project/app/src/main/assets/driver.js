(function () {
  "use strict";
  const stage = __STAGE__;
  const code = __CODE__;
  const expectedAmount = __AMOUNT__;
  const stageTicks = __STAGE_TICKS__;
  const labels = {
    trade: ["Торговля"],
    fast: ["Быстрое копирование"],
    submit: ["Отправлять", "Отправить"],
    confirm: ["Подтв.", "Подтвердить", "Подтверждать"]
  };
  function reply(status, next = "", detail = "", amount = "") {
    return JSON.stringify({status, next, detail, amount});
  }
  function rect(el) { return el && typeof el.getBoundingClientRect === "function" ? el.getBoundingClientRect() : null; }
  function visible(el) {
    const r = rect(el);
    if (!r) return false;
    const s = window.getComputedStyle(el);
    return r.width > 0 && r.height > 0 && s.visibility !== "hidden" && s.display !== "none";
  }
  function text(el) {
    return String((el && (el.innerText || el.textContent || el.value)) || "").replace(/\s+/g, " ").trim();
  }
  function buttons(words, scope = document) {
    const selectors = "button, a, [role='button'], input[type='button'], input[type='submit'], .van-button, .nut-button, .ant-btn, .btn";
    return [...scope.querySelectorAll(selectors)]
      .filter(e => visible(e) && !e.disabled && words.some(w => text(e) === w));
  }
  function inputForCode() {
    const inputs = [...document.querySelectorAll("input:not([type='hidden']):not([type='checkbox'])")]
      .filter(visible).filter(e => !e.readOnly && !e.disabled);
    const tagged = inputs.filter(el => {
      const nearby = text(el.parentElement) + " " + (el.getAttribute("placeholder") || "");
      return /пароль|код|password|code/i.test(nearby);
    });
    if (tagged.length === 1) return tagged[0];
    if (inputs.length === 1 && /Динамический пароль/.test(document.body.innerText)) return inputs[0];
    return null;
  }
  function succeeded() { return /Заказ выполнен успешно/i.test(document.body.innerText); }
  function hasDialog() { return /Следовать/.test(document.body.innerText) && actionButtons(labels.confirm).length === 1; }

  // Only advertisement labelled LUMEN X may be dismissed automatically. Never
  // use generic modal-dismiss logic near a financial confirmation dialog.
  function promoCandidates() {
    const candidates = [...document.querySelectorAll("[role='dialog'], .van-popup, .modal, [class*='popup'], [class*='modal'], div")]
      .filter(el => visible(el) && /LUMEN\s*X/i.test(text(el)));
    const acceptable = candidates.filter(el => {
      const r = rect(el);
      return r && r.width >= 150 && r.height >= 150 &&
        r.width <= (window.innerWidth || 400) * 0.99 &&
        r.height <= (window.innerHeight || 700) * 0.99;
    });
    acceptable.sort((a, b) => {
      const x = rect(a), y = rect(b);
      return (x.width * x.height) - (y.width * y.height);
    });
    return acceptable;
  }
  function popupCrosses(dialog) {
    const r = rect(dialog);
    const elements = [...dialog.querySelectorAll("button, [role='button'], a, [class*='close'], [class*='cross'], svg, i, span")]
      .filter(visible).filter(e => {
        const b = rect(e);
        if (!b || b.width > 65 || b.height > 65) return false;
        if (b.top > r.top + Math.max(65, r.height * 0.22)) return false;
        if (b.left < r.left + r.width * 0.66) return false;
        const t = text(e);
        const hint = (e.getAttribute("aria-label") || "") + " " +
          (typeof e.className === "string" ? e.className : "");
        return /^[×✕✖xX]$/.test(t) || /close|cross|закрыть|van-icon-cross/i.test(hint);
      });
    elements.sort((a, b) => (rect(a).width * rect(a).height) - (rect(b).width * rect(b).height));
    return elements;
  }
  function promoDialog() {
    // Prefer the smallest popup that actually includes an X. The inner poster
    // also carries LUMEN X text but may not contain its close button.
    const candidates = promoCandidates();
    return candidates.find(d => popupCrosses(d).length > 0) || candidates[0] || null;
  }
  function closePromo() {
    const dialog = promoDialog();
    if (!dialog) return "absent";
    const elements = popupCrosses(dialog);
    if (!elements.length) return "unrecognized";
    elements[0].click();
    return "clicked";
  }

  // A bottom tab is often represented by 2-5 nested DIV/SPAN nodes whose
  // innerText is *identical*. In v0.3.3 each node became a separate candidate,
  // so one real tab incorrectly raised 'Неоднозначная вкладка «Торговля»'.
  // Canonicalize descendants to exactly one clickable tabbar root.
  function tabbarRoot(label, target) {
    const w = window.innerWidth || 400;
    let topmostCompact = label;
    let namedRoot = null;
    for (let a = label, depth = 0; a && depth < 7; a = a.parentElement, depth++) {
      const r = rect(a);
      if (!r || !visible(a) || r.width > w * 0.34 || r.height > 105 ||
          text(a) !== target) break;
      topmostCompact = a;
      const classes = typeof a.className === "string" ? a.className : "";
      const isRealTabRoot = /(?:^|\s)[\w-]*(?:tabbar-item|tab-bar-item|bottom-nav-item|bottom-menu-item)(?=\s|$)/i.test(classes);
      if (isRealTabRoot || a.getAttribute("role") === "tab") namedRoot = a;
    }
    // Named outer tab roots win over captions; otherwise click the highest
    // compact ancestor whose only label is 'Торговля' (events bubble).
    return namedRoot || topmostCompact;
  }
  function bottomTab(target) {
    const h = window.innerHeight || 700;
    const labelled = [...document.querySelectorAll("span, div, a, button, [role='tab'], [role='button']")]
      .filter(e => visible(e) && text(e) === target);
    const roots = new Set();
    for (const e of labelled) {
      const r = rect(e);
      if (!r || r.top < h * 0.54 || r.bottom > h + 20) continue;
      roots.add(tabbarRoot(e, target));
    }
    // If nested fallback roots survived, discard descendants of any other root.
    // Avoid merging unrelated neighbouring tabs: only exact ancestor chains.
    const items = [...roots];
    return items.filter(e => !items.some(other => other !== e &&
      (typeof other.contains === "function" ? other.contains(e) : isParentOf(other, e))));
  }
  function tradeTab() { return bottomTab("Торговля"); }
  function ordersTab() { return bottomTab("Заказы"); }
  function ordersHeading() {
    const h = window.innerHeight || 700;
    return [...document.querySelectorAll("div, span, h1, h2, header, [role='heading']")]
      .some(e => visible(e) && text(e) === "Заказы" && rect(e).top < h * 0.52);
  }
  // The mobile site's Orders page can render its heading as a graphical or
  // nonstandard component. Recognize its distinctive Future/Position/History
  // controls as a fallback; never infer that an order itself was confirmed here.
  function ordersScreen() {
    if (ordersHeading()) return true;
    const page = String(document.body && document.body.innerText || "");
    return /Фьючерсы/i.test(page) && /Детали\s+позиции/i.test(page) &&
      /Подробности\s+истории/i.test(page);
  }
  function orderTabSelection() {
    const tabs = ordersTab();
    if (tabs.length !== 1) return tabs;
    return tabs;
  }
  // The sum is shown on the order-copy screen (before financial confirmation).
  // Capture it before clicking, and later match exactly this order in the list.
  function normalizeAmount(value) {
    const v = String(value || "").replace(/\s/g, "").replace(/,/g, ".");
    return /^\d+(?:\.\d{1,12})?$/.test(v) ? v : "";
  }
  function orderAmountIn(source) {
    const found = [...String(source || "").matchAll(/Сумма\s+заказа\s*[:：]?\s*([0-9][0-9\s]*(?:[.,][0-9]{1,12})?)/gi)]
      .map(m => normalizeAmount(m[1])).filter(Boolean);
    const unique = [...new Set(found)];
    return unique.length === 1 ? unique[0] : "";
  }
  function pendingOrderCards() {
    const w = window.innerWidth || 400, h = window.innerHeight || 700;
    const titles = [...document.querySelectorAll("div, span, p, a, strong")]
      .filter(e => visible(e) && text(e) === "BTC/USDT");
    const cards = new Set();
    for (const title of titles) {
      for (let e = title, depth = 0; e && depth < 9; e = e.parentElement, depth++) {
        const r = rect(e), content = text(e);
        if (!r || !visible(e) || r.height > h * 0.85 || r.width > w * 1.02) break;
        if (/В\s+ожидании/i.test(content) && /Сумма\s+заказа/i.test(content)) {
          cards.add(e); break; // smallest card containing both the pair and status
        }
      }
    }
    const all = [...cards];
    return all.filter(e => !all.some(other => other !== e && isParentOf(e, other)))
      .map(e => ({element: e, amount: orderAmountIn(text(e))}));
  }
  function isParentOf(parent, node) {
    for (let n = node.parentElement; n; n = n.parentElement) if (n === parent) return true;
    return false;
  }

  // 'Быстрое копирование' and 'Отправлять' may be Vant DIV controls, not
  // actual <button> elements. Use exact visible labels in compact controls;
  // do not search loosely for financial confirmation text.
  function enabledWithAncestors(element) {
    for (let el = element, depth = 0; el && depth < 5; el = el.parentElement, depth++) {
      const cls = typeof el.className === "string" ? el.className : "";
      if (el.disabled || ((typeof el.getAttribute === "function") && el.getAttribute("aria-disabled") === "true") ||
          /(?:^|[\s_-])disabled(?:[\s_-]|$)/i.test(cls)) return false;
    }
    return true;
  }
  function actionButtons(words) {
    const semantic = buttons(words).filter(enabledWithAncestors);
    if (semantic.length) return [...new Set(semantic)];
    const w = window.innerWidth || 400;
    const candidates = [...document.querySelectorAll("div, span, a, button, [role='button']")]
      .filter(e => visible(e) && enabledWithAncestors(e) && words.includes(text(e)))
      .filter(e => {
        const r = rect(e);
        return r && r.width <= w * 0.96 && r.height <= 85 && r.height >= 12;
      });
    const roots = candidates.map(e => {
      let root = e;
      for (let a = e.parentElement, depth = 0; a && depth < 4; a = a.parentElement, depth++) {
        const r = rect(a);
        if (!r || !visible(a) || r.width > w * 0.96 || r.height > 85 || !words.includes(text(a))) break;
        root = a;
      }
      return root;
    });
    const unique = [...new Set(roots)].filter(enabledWithAncestors);
    return unique.filter(e => !unique.some(other => other !== e && isParentOf(other, e)));
  }

  // The site can leave its own "Быстрый / Загрузка..." popup floating over
  // the Orders page even after the newly created order has been verified.
  // We must NOT hide the WebView, change popup CSS, or interfere with the
  // financial confirmation stages. Only dismiss this specifically identified
  // *non-financial* popup AFTER the matching pending order has been found.
  function staleQuickDialogs() {
    const w = window.innerWidth || 400, h = window.innerHeight || 700;
    const headings = [...document.querySelectorAll("div, span, strong, h1, h2, h3, [class*='header']")]
      .filter(e => visible(e) && text(e) === "Быстрый");
    const dialogs = new Set();
    for (const heading of headings) {
      for (let el = heading.parentElement, depth = 0; el && depth < 9; el = el.parentElement, depth++) {
        const r = rect(el), content = text(el);
        if (!r || !visible(el) || r.width > w * 0.98 || r.height > h * 0.89) break;
        if (r.width < 140 || r.height < 120) continue;
        // Use an exact heading plus an unmistakable acknowledgment button.
        // Do NOT match financial "Подтв." and never use an unrelated popup.
        if (!/Понятно/.test(content) || /Подтв\.|Следовать/.test(content)) continue;
        const innerHeadings = [...el.querySelectorAll("div, span, strong, h1, h2, h3, [class*='header']")]
          .filter(e => visible(e) && text(e) === "Быстрый");
        if (innerHeadings.length === 0) continue;
        dialogs.add(el);
        break;
      }
    }
    const all = [...dialogs];
    return all.filter(e => !all.some(other => other !== e && isParentOf(e, other)));
  }
  function dismissVerifiedQuickPopup() {
    const dialogs = staleQuickDialogs();
    if (!dialogs.length) return "absent";
    if (dialogs.length !== 1) return "ambiguous";
    const dialog = dialogs[0], w = window.innerWidth || 400;
    const actions = [...dialog.querySelectorAll("button, a, div, span, [role='button'], .van-button")]
      .filter(e => visible(e) && enabledWithAncestors(e) && text(e) === "Понятно")
      .filter(e => { const r = rect(e); return r && r.height >= 12 && r.height <= 90 && r.width <= w * 0.96; });
    // Nested Vant labels are a single action; prefer the outer clickable root.
    const unique = [...new Set(actions)].filter(e => !actions.some(other => other !== e && isParentOf(other, e)));
    if (unique.length === 1) {
      unique[0].click();
      return "clicked";
    }
    // Fall back to the *same* dialog's upper-right cross, never to generic
    // page-level X, which may have a destructive meaning in Orders.
    const crosses = popupCrosses(dialog);
    if (!unique.length && crosses.length === 1) {
      crosses[0].click();
      return "clicked";
    }
    return "unrecognized";
  }

  // Re-evaluated on each tick: a new page may appear between click and callback.
  if (succeeded()) {
    // Never treat the previous order's leftover success dialog as success for a
    // new code. A new order is successful only after THIS run clicked confirm.
    const ack = buttons(["Подтв.", "Понятно", "OK", "ОК"]);
    if (stage === "WAIT_RESULT") {
      if (ack.length === 1) ack[0].click(); // Only dismiss this run's message.
      return reply("advance", "NAVIGATE_ORDERS", "Заказ создан. Проверяю статус в разделе «Заказы»");
    }
    if (!["NAVIGATE_ORDERS", "WAIT_ORDERS", "CHECK_ORDER_STATUS", "WAIT_QUICK_POPUP_CLOSE"].includes(stage)) {
      return reply("error", "", "На странице осталось сообщение об успехе предыдущего заказа. Закройте его перед новым запуском.");
    }
  }
  if (["NAVIGATE_TRADE", "WAIT_PROMO_DISMISS", "WAIT_PROMO_DISMISS_ORDERS", "OPEN_FAST_COPY", "WAIT_TRADE", "NAVIGATE_ORDERS"].includes(stage)) {
    if (promoDialog()) {
      if (stage === "WAIT_PROMO_DISMISS" || stage === "WAIT_PROMO_DISMISS_ORDERS")
        return reply("wait", "", "Ожидание закрытия рекламного окна LUMEN X");
      const close = closePromo();
      if (close === "clicked") return reply("advance", stage === "NAVIGATE_ORDERS" ? "WAIT_PROMO_DISMISS_ORDERS" : "WAIT_PROMO_DISMISS", "Закрываю рекламное окно LUMEN X");
      return reply("error", "", "Окно LUMEN X найдено, но его крестик закрытия не распознан. Заказ не отправлялся.");
    }
    if (stage === "WAIT_PROMO_DISMISS") return reply("advance", "NAVIGATE_TRADE", "Реклама закрыта");
    if (stage === "WAIT_PROMO_DISMISS_ORDERS") return reply("advance", "NAVIGATE_ORDERS", "Реклама закрыта, ищу список заказов");
  }

  switch (stage) {
    case "NAVIGATE_TRADE": {
      if (inputForCode()) return reply("advance", "ENTER_CODE");
      if (actionButtons(labels.fast).length === 1) return reply("advance", "OPEN_FAST_COPY");
      const tab = tradeTab();
      if (tab.length > 1) return reply("error", "", "Неоднозначная вкладка «Торговля»");
      if (tab.length === 1) {
        tab[0].click();
        return reply("advance", "WAIT_TRADE", "Открываю нижнюю вкладку «Торговля»");
      }
      const found = buttons(labels.trade);
      if (found.length > 1) return reply("error", "", "Несколько кнопок «Торговля»");
      if (found.length === 1) {
        found[0].click();
        return reply("advance", "WAIT_TRADE", "Открываю раздел «Торговля»");
      }
      return reply("wait", "", "Ищу вкладку «Торговля»");
    }
    case "WAIT_TRADE": {
      if (inputForCode()) return reply("advance", "ENTER_CODE");
      if (actionButtons(labels.fast).length === 1) return reply("advance", "OPEN_FAST_COPY");
      return reply("wait", "", "Ожидаю появления «Быстрого копирования»");
    }
    case "OPEN_FAST_COPY": {
      if (inputForCode()) return reply("advance", "ENTER_CODE");
      const found = actionButtons(labels.fast);
      if (found.length > 1) return reply("error", "", "Неоднозначная кнопка быстрого копирования");
      if (!found.length) return reply("wait", "", "Ожидаю кнопку «Быстрое копирование»");
      found[0].click();
      return reply("advance", "ENTER_CODE");
    }
    case "ENTER_CODE": {
      const input = inputForCode();
      if (!input) return reply("wait", "", "Ожидаю поле динамического пароля");
      if (!/^\d{4,10}$/.test(code)) return reply("error", "", "Неверный цифровой код");
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set;
      setter.call(input, code);
      input.dispatchEvent(new Event("input", {bubbles: true}));
      input.dispatchEvent(new Event("change", {bubbles: true}));
      return reply("advance", "SUBMIT_CODE");
    }
    case "SUBMIT_CODE": {
      const b = actionButtons(labels.submit);
      if (b.length > 1) return reply("error", "", "Несколько кнопок отправки кода");
      if (!b.length) return reply("wait", "", "Ожидаю доступную кнопку отправки");
      b[0].click();
      return reply("advance", "WAIT_ORDER");
    }
    case "WAIT_ORDER": {
      if (actionButtons(labels.fast).length === 1) return reply("advance", "OPEN_ORDER_COPY");
      if (hasDialog()) return reply("advance", "CONFIRM");
      return reply("wait");
    }
    case "OPEN_ORDER_COPY": {
      if (hasDialog()) return reply("advance", "CONFIRM");
      const b = actionButtons(labels.fast);
      if (b.length > 1) return reply("error", "", "Несколько подходящих заказов. Ручная проверка обязательна.");
      if (!b.length) return reply("wait", "", "Ожидаю кнопку заявки для копирования");
      const amount = orderAmountIn(document.body.innerText);
      b[0].click();
      return reply("advance", "WAIT_MODAL", "", amount);
    }
    case "WAIT_MODAL": return hasDialog() ? reply("advance", "CONFIRM") : reply("wait");
    case "CONFIRM": {
      if (!hasDialog()) return reply("error", "", "Диалог подтверждения не распознан");
      const b = actionButtons(labels.confirm);
      const amount = orderAmountIn(document.body.innerText);
      b[0].click();
      return reply("advance", "WAIT_RESULT", "", amount);
    }
    case "WAIT_RESULT": return reply("wait");
    case "NAVIGATE_ORDERS": {
      if (ordersScreen()) return reply("advance", "CHECK_ORDER_STATUS");
      const tabs = orderTabSelection();
      if (tabs.length > 1) return reply("error", "", "Несколько вкладок «Заказы», статус нужно проверить вручную");
      if (tabs.length === 1) {
        tabs[0].click();
        return reply("advance", "WAIT_ORDERS", "Открываю раздел «Заказы»");
      }
      return reply("wait", "", "Ищу вкладку «Заказы»");
    }
    case "WAIT_ORDERS": {
      if (ordersScreen()) return reply("advance", "CHECK_ORDER_STATUS");
      // A first click on this single-page app can be lost during a rerender.
      // Retry the *navigation tab*, not any code-submission or confirm button.
      if (stageTicks === 9) {
        const tabs = orderTabSelection();
        if (tabs.length > 1) return reply("error", "", "Неоднозначная вкладка «Заказы»; повторный заказ запрещён");
        if (tabs.length === 1) {
          tabs[0].click();
          return reply("wait", "", "Повторно нажимаю только вкладку «Заказы»");
        }
      }
      return reply("wait", "", "Ожидаю экран «Заказы» — сайт остаётся видимым");
    }
    case "CHECK_ORDER_STATUS": {
      if (!ordersScreen()) return reply("wait", "", "Ожидаю список заказов");
      if (!expectedAmount) return reply("error", "", "Не удалось сохранить сумму текущего заказа перед подтверждением. Проверьте статус вручную.");
      const found = pendingOrderCards().filter(card => card.amount === expectedAmount);
      if (found.length > 1) return reply("error", "", "Несколько заказов BTC/USDT с той же суммой в ожидании. Требуется ручная проверка.");
      if (found.length === 1) {
        const close = dismissVerifiedQuickPopup();
        if (close === "clicked") return reply("advance", "WAIT_QUICK_POPUP_CLOSE", "Закрываю окно «Быстрый» после подтверждения заказа");
        // The order is verified; inability to find a safe close control must
        // not misrepresent it as a failed order or cause a second order.
        if (close !== "absent") return reply("pending", "", "Заказ в ожидании; окно «Быстрый» нужно закрыть вручную");
        return reply("pending", "", "Заказ BTC/USDT в ожидании");
      }
      return reply("wait", "", "Проверяю статус «В ожидании» для суммы " + expectedAmount);
    }
    case "WAIT_QUICK_POPUP_CLOSE": {
      // We only enter this stage after confirming exactly one matching order.
      // A short wait allows Vant transitions to complete. Retry closing once
      // if the site re-rendered before processing the first click.
      if (!staleQuickDialogs().length) return reply("pending", "", "Заказ в ожидании, окно «Быстрый» закрыто");
      if (stageTicks === 4) {
        const close = dismissVerifiedQuickPopup();
        if (close === "clicked") return reply("wait", "", "Закрываю оставшееся окно «Быстрый»");
      }
      if (stageTicks >= 8) return reply("pending", "", "Заказ в ожидании; окно сайта не удалось закрыть автоматически");
      return reply("wait", "", "Ожидаю закрытия окна «Быстрый»");
    }
    default: return reply("error", "", "Недопустимое состояние");
  }
})()
