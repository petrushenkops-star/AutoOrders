// 0.4.4 regression: show real site, buttons and pages throughout every order.
const assert=require('assert'); const fs=require('fs');
const src=fs.readFileSync('app/src/main/java/com/example/maxsignalcopy/MainActivity.kt','utf8');
const driver=fs.readFileSync('app/src/main/assets/driver.js','utf8');
const machine=fs.readFileSync('app/src/main/java/com/example/maxsignalcopy/BrowserAutomation.kt','utf8');
assert(src.includes('siteView.addView(browser, LinearLayout.LayoutParams('),'WebView visible');
assert(!src.includes('executionOverlay'),'No fullscreen native cover');
assert(!src.includes('startCoveredAutomation'),'No masked automation startup');
assert(src.includes('startVisibleAutomation(claim.code)'),'Both manual and automatic code entry keep site visible');
assert(machine.includes('reloadHome("NAVIGATE_ORDERS"'),'After success navigate to Orders automatically');
assert(machine.includes('if (orderListRecoveryUsed)'),'Stop after bounded Orders recovery');
assert(machine.includes('if (stage == "SUBMIT_CODE" || stage == "CONFIRM") uncertain = true'),'Preserve no-resubmission guard');
assert(driver.includes('if (stageTicks === 9)'),'Navigation-only retry');
assert(driver.includes('ordersScreen()'),'Orders screen fallback');
console.log('0.4.4 visible-site and Orders-navigation tests passed');
