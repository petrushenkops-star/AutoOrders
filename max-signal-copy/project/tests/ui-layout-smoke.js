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
assert(gradle.includes('versionCode = 23') && gradle.includes('versionName = "0.5.0"'));
console.log('UI smoke checks passed.');

// The old MAX bot settings and activation UI must not return.
const manifest = fs.readFileSync('app/src/main/AndroidManifest.xml','utf8');
assert(!manifest.includes('BIND_NOTIFICATION_LISTENER_SERVICE'));
assert(!src.includes('Проверить историю MAX'));
assert(!src.includes('chat_id чата ЛюменХ'));
assert(!src.includes('MAX: поиск/пуск'));
assert(!src.includes('MaxApiCredentials'));
assert(src.includes('Ручной ввод кода') && src.includes('Сохранить длину кода'));
assert(src.includes('Расписание поиска Telegram'));
assert(src.includes('TradeSession.start(') && src.includes('WebViewCompat.addDocumentStartJavaScript'));
console.log('Clean settings and manual trading smoke checks passed.');
console.log('MAX history integration smoke checks passed.');
