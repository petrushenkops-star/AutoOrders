/*
 * Conservative repair for an overlapping confirmation dialog and overlay.
 * Adjusts dialog z-index first (or overlay z-index if needed) only when both
 * fixed layers have the same z-index and the overlay covers the confirmation.
 * Reverts changes that do not expose the button. Never clicks, submits, hides or disables
 * any element. Failed attempts restore the original CSS.
 */
(function () {
  const report = status => JSON.stringify({status});
  if (location.protocol !== 'https:' ||
      !(location.hostname === 'lxcompintb.com' ||
        location.hostname.endsWith('.lxcompintb.com')) ||
      !document.body) return report('invalid_page');
  try {
    const normalize = value => String(value || '').replace(/\s+/g,' ').trim().toLowerCase();
    const visible = element => !!element && !!element.getClientRects().length &&
      getComputedStyle(element).display !== 'none' &&
      getComputedStyle(element).visibility !== 'hidden';
    const buttons = [...document.querySelectorAll(
      'button, a, [role="button"], input[type="submit"], .van-button'
    )].filter(visible).filter(element =>
      normalize(element.innerText || element.value || element.getAttribute('aria-label')) === 'подтв.');
    if (buttons.length !== 1) return report('not_found');
    const button = buttons[0];
    if (button.disabled) return report('disabled');
    const popup = button.closest?.('[role="dialog"], .van-dialog, .van-popup');
    if (!popup || !visible(popup) || !popup.contains(button)) return report('no_dialog');
    const height = window.visualViewport?.height || window.innerHeight || 0;
    const width = window.visualViewport?.width || window.innerWidth || 0;
    if (!Number.isFinite(height) || !Number.isFinite(width) ||
        height < 100 || width < 100) return report('failed');
    const reachable = () => {
      const r = button.getBoundingClientRect();
      if (r.top < 2 || r.bottom > height - 2 || r.left < 0 || r.right > width) return false;
      const top = document.elementFromPoint?.(r.left + r.width / 2, r.top + r.height / 2);
      return !!top && (top === button || button.contains(top));
    };
    if (reachable()) return report('already_visible');
    const popupStyle = popup.style;
    const oldTranslate = popupStyle.getPropertyValue('translate');
    const oldTranslatePriority = popupStyle.getPropertyPriority('translate');
    let moved = false;
    const restoreTranslate = () => {
      if (oldTranslate) popupStyle.setProperty('translate',oldTranslate,oldTranslatePriority);
      else popupStyle.removeProperty('translate');
    };
    const initial = button.getBoundingClientRect();
    if (initial.top < 2 || initial.bottom > height - 2) {
      const rect = popup.getBoundingClientRect();
      const targetTop = rect.height <= height - 24 ?
        Math.round((height - rect.height) / 2) : 12;
      const delta = Math.round(targetTop - rect.top);
      if (!Number.isFinite(delta) || Math.abs(delta) > height * 4) return report('failed');
      popupStyle.setProperty('translate','0px ' + delta + 'px','important');
      moved = true;
      if (reachable()) return report('shifted');
    }
    const rect = button.getBoundingClientRect();
    if (rect.top < 2 || rect.bottom > height - 2 ||
        rect.left < 0 || rect.right > width) {
      restoreTranslate();
      return report('failed');
    }
    const blocker = document.elementFromPoint?.(
      rect.left + rect.width / 2, rect.top + rect.height / 2
    );
    if (!blocker || popup.contains(blocker)) {
      restoreTranslate();
      return report('unknown_blocker');
    }
    const cls = typeof blocker.className === 'string' ? blocker.className : '';
    const isOverlay = /(^|\s)(van-overlay|overlay|van-mask|modal-mask)(\s|$)/i.test(cls);
    const popupCSS = getComputedStyle(popup);
    const blockerCSS = getComputedStyle(blocker);
    const popupZ = Number(popupCSS.zIndex);
    const blockerZ = Number(blockerCSS.zIndex);
    if (!isOverlay || popupCSS.position !== 'fixed' ||
        blockerCSS.position !== 'fixed' ||
        !Number.isInteger(popupZ) || !Number.isInteger(blockerZ) ||
        popupZ !== blockerZ || popupZ <= 0) {
      restoreTranslate();
      return report('unsafe_layer');
    }
    // With identical z-indices, WebView can paint a later overlay above the dialog.
    // Prefer lifting only the visible dialog; preserve its existing transform.
    const oldDialogZ = popupStyle.getPropertyValue('z-index');
    const oldDialogPriority = popupStyle.getPropertyPriority('z-index');
    const restoreDialog = () => {
      if (oldDialogZ) popupStyle.setProperty('z-index',oldDialogZ,oldDialogPriority);
      else popupStyle.removeProperty('z-index');
    };
    // A site re-render can raise the overlay by one stacking level. A narrow
    // three-level cushion remains above that shift without altering all dialogs.
    // The caller re-inspects accessibility after a delay before reporting success.
    if (popupZ > 2147483000) {
      restoreTranslate();
      return report('unsafe_layer');
    }
    popupStyle.setProperty('z-index',String(popupZ + 3),'important');
    if (reachable()) return report(moved ? 'shifted_and_dialog_raised' : 'dialog_raised');
    restoreDialog();
    // Conservative fallback if a stacking context defeats the dialog z-index.
    const layerStyle = blocker.style;
    const oldZ = layerStyle.getPropertyValue('z-index');
    const oldZPriority = layerStyle.getPropertyPriority('z-index');
    layerStyle.setProperty('z-index',String(popupZ - 1),'important');
    if (reachable()) return report(moved ? 'shifted_and_overlay_fixed' : 'overlay_fixed');
    if (oldZ) layerStyle.setProperty('z-index',oldZ,oldZPriority);
    else layerStyle.removeProperty('z-index');
    restoreDialog();

    // The device diagnostic showed equal-z siblings under the same parent.
    // When changing their z-index does not work, temporarily place the dialog
    // AFTER the overlay in DOM paint order. Preserve and restore its original
    // position if the button is still covered. No clicks or submissions.
    if (popup.parentElement && popup.parentElement === blocker.parentElement &&
        popup !== blocker && !popup.contains(blocker) && !blocker.contains(popup)) {
      const parent = popup.parentElement;
      const originalNext = popup.nextSibling;
      try {
        parent.insertBefore(popup, blocker.nextSibling);
        if (reachable()) return report(moved ? 'shifted_and_dom_reordered' : 'dom_reordered');
        parent.insertBefore(popup, originalNext);
      } catch (_) {
        try { parent.insertBefore(popup, originalNext); } catch (_) {}
      }
    }
    restoreTranslate();
    return report('overlay_still_blocked');
  } catch (_) {
    return report('failed');
  }
})()