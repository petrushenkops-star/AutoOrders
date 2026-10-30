const assert = require('assert');
const fs = require('fs');
const source = name => fs.readFileSync('app/src/main/java/com/example/maxsignalcopy/'+name+'.kt','utf8');
const ui = source('MainActivity');
const service = source('TradeForegroundService');
const session = source('TradeSession');
const result = source('OrderPendingNotifier');
assert(ui.includes('Сохранить длину кода') && ui.includes('Telegram'));
for (const retired of ['Проверить историю MAX','chat_id чата ЛюменХ','MAX: поиск/пуск','MaxApiCredentials','scanChatHistory(']) {
  assert(!ui.includes(retired), 'Retired messenger UI reference: '+retired);
}
assert(service.includes('stopForeground(STOP_FOREGROUND_REMOVE)'));
assert(service.includes('.cancel(NOTIFICATION)'));
assert(session.indexOf('TradeForegroundService.finish(context)') <
       session.indexOf('OrderPendingNotifier.show(context)'), 'Stop progress notification before final');
assert(result.includes('manager.cancel(801)'), 'Remove stale progress before result');
assert(ui.includes('WebViewCompat.addDocumentStartJavaScript'), 'Quick popup fix preserved');
assert(ui.includes('TradeSession.start(this, code)'), 'Manual trading preserved');
assert(ui.includes('WeeklySearchSchedule.activeAt'), 'Weekly Telegram schedule preserved');
console.log('Retired messenger cleanup and single-result notification smoke checks passed.');
