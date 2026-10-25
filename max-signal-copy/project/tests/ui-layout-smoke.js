const assert = require('assert');
const fs = require('fs');
const src = fs.readFileSync('app/src/main/java/com/example/maxsignalcopy/MainActivity.kt','utf8');
const gradle = fs.readFileSync('app/build.gradle.kts','utf8');
assert(src.includes('window.setDecorFitsSystemWindows(false)'), 'Android edge-to-edge enabled');
assert(src.includes('WindowInsets.Type.systemBars()') && src.includes('WindowInsets.Type.ime()'), 'System bars and keyboard insets handled');
assert(src.includes('root.setOnApplyWindowInsetsListener'), 'Root respects window insets');
assert(src.includes('setSiteFullscreen(true)') && src.includes('setSiteFullscreen(false)'), 'Full-screen mode reversible');
assert(src.includes('siteView.addView(browser, LinearLayout.LayoutParams('), 'Browser WebView is directly visible');
assert(!src.includes('browserFrame.addView(executionOverlay'), 'No opaque website overlay');
assert(src.includes('ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f'), 'Browser fills remaining space');
assert(src.includes('manualBar.addView(manualCode'), 'Manual code is in compact row');
assert(src.includes('minWidth = 0'), 'Compact bottom buttons allowed to shrink');
assert(gradle.includes('applicationId = "com.example.maxsignalcopy"'));
assert(gradle.includes('versionCode = 21') && gradle.includes('versionName = "0.4.8"'));
console.log('UI smoke checks passed.');

// API chat history is the only signal source; no notification reading permission.
const manifest = fs.readFileSync('app/src/main/AndroidManifest.xml','utf8');
const api = fs.readFileSync('app/src/main/java/com/example/maxsignalcopy/MaxHistoryClient.kt','utf8');
const mailbox = fs.readFileSync('app/src/main/java/com/example/maxsignalcopy/SignalMailbox.kt','utf8');
assert(!manifest.includes('BIND_NOTIFICATION_LISTENER_SERVICE'), 'No notification listener enabled');
assert(api.includes('platform-api2.max.ru') && api.includes('/messages?chat_id='), 'Official MAX history API used');
assert(api.includes('/chats/$id'), 'Verify chosen chat title with API');
assert(src.includes('Проверить историю MAX (БЕЗ заказа)'), 'Safe inspection mode');
assert(src.includes('MaxApiCredentials.update'), 'Token and chat configuration');
assert(mailbox.includes('offerFromHistory'), 'History code links into existing order flow');
console.log('MAX history integration smoke checks passed.');
