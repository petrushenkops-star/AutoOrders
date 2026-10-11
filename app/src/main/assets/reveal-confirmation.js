/*
 * Safe geometry and stacking-order repair for an obscured confirmation popup.
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
    // Fix geometry first, then the visual stacking order. Do not touch the button.
    const style = popup.style;
    const savedTranslate = style.getPropertyValue('translate');
    const savedTranslatePriority = style.getPropertyPriority('translate');
    const savedZ = style.getPropertyValue('z-index');
    const savedZPriority = style.getPropertyPriority('z-index');
    const restore = () => {
      if (savedTranslate) style.setProperty('translate', savedTranslate, savedTranslatePriority);
      else style.removeProperty('translate');
      if (savedZ) style.setProperty('z-index', savedZ, savedZPriority);
      else style.removeProperty('z-index');
    };
    const rectBefore = popup.getBoundingClientRect();
    let moved = false;
    if (!inside) {
      const margin = 12;
      const targetTop = rectBefore.height <= height - 2 * margin ?
        Math.round((height - rectBefore.height) / 2) : margin;
      const shift = Math.round(targetTop - rectBefore.top);
      if (!Number.isFinite(shift) || Math.abs(shift) > height * 4) {
        return result('failed');
      }
      // CSS translate is additive to library transforms, without mutating DOM structure.
      style.setProperty('translate', '0px ' + shift + 'px', 'important');
      moved = true;
      if (isReachable()) return result('shifted');
    }
    // A dark overlay can sit above the dialog, blocking touches even with correct geometry.
    // Only elevate the existing dialog container, never hide the overlay or trigger a click.
    const b = button.getBoundingClientRect();
    if (b.top < 0 || b.bottom > height || b.left < 0 || b.right > width) {
      restore();
      return result('failed');
    }
    const atCenter = document.elementFromPoint?.(
      b.left + b.width / 2, b.top + b.height / 2
    );
    if (!atCenter || popup.contains?.(atCenter)) {
      restore();
      return result('blocked_inside_dialog');
    }
    let highestLayer = 100000;
    const overlayCandidates = Array.from(document.querySelectorAll(
      '.van-overlay, [class*="overlay"], [class*="mask"], [class*="backdrop"]'
    ));
    overlayCandidates.push(atCenter);
    for (const layer of overlayCandidates) {
      if (!layer || !visible(layer)) continue;
      const z = Number.parseInt(getComputedStyle(layer).zIndex, 10);
      if (Number.isFinite(z)) highestLayer = Math.max(highestLayer, z);
    }
    const popupZ = Number.parseInt(getComputedStyle(popup).zIndex, 10);
    if (Number.isFinite(popupZ)) highestLayer = Math.max(highestLayer, popupZ);
    const fixedZ = Math.min(2147483000, highestLayer + 100);
    style.setProperty('z-index', String(fixedZ), 'important');
    if (isReachable()) return result(moved ? 'shifted_and_layer_fixed' : 'layer_fixed');
    // For nested stacking contexts we must not change ancestor layout blindly.
    restore();
    return result('layer_still_blocked');
  } catch (_) {
    return result('failed');
  }
})()