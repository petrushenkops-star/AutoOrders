/*
 * Position-only repair for an offscreen confirmation popup.
 * Never clicks, focuses controls, writes inputs, makes requests, or sends orders.
 */
(function () {
  const allowed = location.protocol === 'https:' &&
    (location.hostname === 'lxcompintb.com' || location.hostname.endsWith('.lxcompintb.com'));
  const result = status => JSON.stringify({status});
  if (!allowed || !document.body) return result('invalid_page');
  try {
    const norm = s => String(s || '').replace(/\s+/g, ' ').trim().toLowerCase();
    const visible = e => !!(e && e.getClientRects().length &&
      getComputedStyle(e).visibility !== 'hidden' &&
      getComputedStyle(e).display !== 'none');
    const controls = Array.from(document.querySelectorAll(
      'button, a, [role="button"], input[type="submit"], .van-button'
    )).filter(visible);
    const confirms = controls.filter(e =>
      norm(e.innerText || e.value || e.getAttribute('aria-label')) === 'подтв.');
    if (confirms.length !== 1) return result('not_found');
    const button = confirms[0];
    const popups = Array.from(document.querySelectorAll(
      '[role="dialog"], .van-dialog, .van-popup'
    )).filter(visible);
    const popup = button.closest?.('[role="dialog"], .van-dialog, .van-popup') ||
      popups.find(e => e.contains(button));
    if (!popup) return result('no_dialog');
    const height = window.visualViewport?.height || window.innerHeight || 0;
    const width = window.visualViewport?.width || window.innerWidth || 0;
    if (!Number.isFinite(height) || height < 100 || width < 100) return result('failed');
    const rect = button.getBoundingClientRect();
    const centerX = rect.left + rect.width / 2;
    const centerY = rect.top + rect.height / 2;
    const inside = rect.top >= 8 && rect.bottom <= height - 8 &&
      centerX >= 0 && centerX <= width;
    const isReachable = () => {
      const b = button.getBoundingClientRect();
      if (b.top < 0 || b.bottom > height || b.left < 0 || b.right > width) return false;
      const top = document.elementFromPoint?.(b.left + b.width / 2, b.top + b.height / 2);
      return !!top && (top === button || button.contains?.(top));
    };
    if (inside && isReachable()) return result('already_visible');
    // Move only the popup container, retaining all original site handlers and button logic.
    const dialogRect = popup.getBoundingClientRect();
    const pad = 12;
    const targetTop = dialogRect.height <= height - 2 * pad ?
      Math.round((height - dialogRect.height) / 2) : pad;
    const delta = Math.round(targetTop - dialogRect.top);
    // CSS individual translate is additive to existing transforms used by popup libraries.
    const style = popup.style;
    const prevValue = style.getPropertyValue('translate');
    const prevPriority = style.getPropertyPriority('translate');
    const existing = Number(popup.dataset?.autoordersRevealShift || '0');
    if (existing) {
      // Restore original position before recalculating on subsequent taps.
      if (prevValue) style.setProperty('translate', prevValue, prevPriority);
      else style.removeProperty('translate');
      if (popup.dataset) delete popup.dataset.autoordersRevealShift;
    }
    // Recompute after reset, avoiding compounded shifts.
    const resetRect = popup.getBoundingClientRect();
    const newDelta = Math.round(targetTop - resetRect.top);
    if (Math.abs(newDelta) > height * 4) return result('failed');
    const before = style.getPropertyValue('translate');
    const beforePriority = style.getPropertyPriority('translate');
    style.setProperty('translate', '0px ' + newDelta + 'px', 'important');
    if (isReachable()) {
      if (popup.dataset) popup.dataset.autoordersRevealShift = String(newDelta);
      return result('shifted');
    }
    // Revert on failure, leaving the website in its original state.
    if (before) style.setProperty('translate', before, beforePriority);
    else style.removeProperty('translate');
    return result('failed');
  } catch (_) {
    return result('failed');
  }
})()