/*
 * Cosmetic suppression for the website's non-transactional "Быстрый" status
 * popup. Installed at document start, BEFORE the site's scripts build it.
 * Never remove nodes, block network calls, change input fields, or click
 * confirmation buttons. The website and the trade driver still see the dialog
 * and its original state, including the success message.
 */
(function () {
  "use strict";
  if (window.__maxQuickDialogShieldInstalled) return;
  window.__maxQuickDialogShieldInstalled = true;

  const DIALOGS = ".van-dialog, .van-popup, [role='dialog'], .nut-dialog, .ant-modal, .el-dialog, .modal";
  const OVERLAYS = ".van-overlay, .nut-overlay, .ant-modal-mask, .modal-backdrop";
  const managedDialogs = new Set();
  const managedBackdrops = new Set();
  const label = (el) => String(el && el.textContent || "").replace(/\s+/g, " ").trim();

  function quickTitle(el) {
    if (label(el) !== "Быстрый") return false;
    // A heading containing a nested control is not an exact title.
    for (const child of el.children || []) {
      if (label(child) !== "Быстрый") return false;
    }
    return true;
  }

  function isSafePopup(node) {
    const value = label(node);
    // Never suppress an authorization / order-confirmation dialog, even if a
    // site's copy accidentally embeds the same word in its other text.
    return value.includes("Быстрый") &&
      !/Подтв\.|Подтвердить|Следовать|Динамический пароль/i.test(value) &&
      (/Понятно|Загрузка|Заказ выполнен успешно/i.test(value));
  }

  function locateQuickPopup(title) {
    // Prefer the site's semantically identifiable popup root. An ordinary
    // DIV, a whole-page container, or a button is never hidden just because
    // it mentions the word "Быстрый".
    for (let p = title.parentElement, depth = 0; p && depth < 9; p = p.parentElement, depth++) {
      if (typeof p.matches === "function" && p.matches(DIALOGS) && isSafePopup(p)) return p;
    }
    // Some builds render the popup without a semantic role/class. Select the
    // smallest compact ancestor containing BOTH exact title and acknowledgement.
    for (let p = title.parentElement, depth = 0; p && depth < 7; p = p.parentElement, depth++) {
      const r = p.getBoundingClientRect && p.getBoundingClientRect();
      if (!r || r.width < 140 || r.height < 120 ||
          r.width > (window.innerWidth || 400) * 0.98 ||
          r.height > (window.innerHeight || 700) * 0.9) continue;
      if (isSafePopup(p) && /Понятно/.test(label(p))) return p;
    }
    return null;
  }

  function conceal(el, registry) {
    if (!el || !el.isConnected) return;
    registry.add(el);
    // Opacity (rather than display:none or visibility:hidden) deliberately
    // preserves DOM geometry, innerText, button handlers and the app's driver.
    // No CSS animation: the popup is never painted visibly once recognized.
    if (el.style.getPropertyValue("opacity") !== "0" ||
        el.style.getPropertyPriority("opacity") !== "important") {
      el.style.setProperty("opacity", "0", "important");
    }
    if (el.style.getPropertyValue("pointer-events") !== "none" ||
        el.style.getPropertyPriority("pointer-events") !== "important") {
      el.style.setProperty("pointer-events", "none", "important");
    }
  }

  function restore(el, registry) {
    if (!registry.has(el)) return;
    registry.delete(el);
    if (!el.isConnected) return;
    // Restore only properties that are still our values. Never overwrite any
    // CSS changes from the website after its popup disappears.
    if (el.style.getPropertyValue("opacity") === "0" &&
        el.style.getPropertyPriority("opacity") === "important") el.style.removeProperty("opacity");
    if (el.style.getPropertyValue("pointer-events") === "none" &&
        el.style.getPropertyPriority("pointer-events") === "important") el.style.removeProperty("pointer-events");
  }

  function associatedBackdrop(popup) {
    // Only a directly associated Vant-like sibling backdrop may be concealed.
    // Never hide all overlays on the website: an unrelated consent or trading
    // confirmation may be open at the same time.
    for (let p = popup.parentElement, depth = 0; p && depth < 3; p = p.parentElement, depth++) {
      const overlays = [...p.children].filter(e => e !== popup &&
        typeof e.matches === "function" && e.matches(OVERLAYS));
      if (overlays.length === 1) return overlays[0];
    }
    return null;
  }

  let scanning = false;
  function scan() {
    if (scanning || !document.documentElement) return;
    scanning = true;
    try {
      const found = new Set(), masks = new Set();
      const titles = document.querySelectorAll("div, span, strong, h1, h2, h3, [class*='title']");
      for (const title of titles) {
        if (!quickTitle(title)) continue;
        const popup = locateQuickPopup(title);
        if (!popup) continue;
        found.add(popup);
        conceal(popup, managedDialogs);
        const mask = associatedBackdrop(popup);
        if (mask) { masks.add(mask); conceal(mask, managedBackdrops); }
      }
      for (const previous of [...managedDialogs]) if (!found.has(previous)) restore(previous, managedDialogs);
      for (const previous of [...managedBackdrops]) if (!masks.has(previous)) restore(previous, managedBackdrops);
    } catch (_) {
      // Cosmetic helper must never break the page or automatic order flow.
    } finally { scanning = false; }
  }

  // MutationObserver callbacks run at microtask checkpoints before browser
  // paint. The listener is registered while the document is being created.
  const observer = new MutationObserver(scan);
  observer.observe(document, {childList:true, subtree:true, characterData:true, attributes:true, attributeFilter:["class", "style"]});
  scan();
})();
