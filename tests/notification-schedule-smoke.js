const fs=require('node:fs'),assert=require('node:assert/strict');
const scheduler=fs.readFileSync('app/src/main/java/com/example/autoorders/AlarmScheduler.kt','utf8');
const store=fs.readFileSync('app/src/main/java/com/example/autoorders/LocalStore.kt','utf8');
const main=fs.readFileSync('app/src/main/java/com/example/autoorders/MainActivity.kt','utf8');
const manifest=fs.readFileSync('app/src/main/AndroidManifest.xml','utf8');
// Granting exact-alarm permission must replace previously registered inexact alarms.
assert.match(scheduler,/fun rescheduleAfterPermissionChange\(\)/);
assert.match(scheduler,/prefs\.contains\("exact_allowed"\)/);
assert.match(scheduler,/previous == null \|\| previous != allowed/);
assert.match(scheduler,/refresh\(slots, slots\)/);
assert.match(scheduler,/alarm\.setExactAndAllowWhileIdle\(/);
assert.match(scheduler,/alarm\.setAndAllowWhileIdle\(/);
assert.match(main,/override fun onResume\(\)/);
assert.match(main,/alarms\.rescheduleAfterPermissionChange\(\)/);
assert.match(scheduler,/ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED/);
assert.match(manifest,/android\.app\.action\.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED/);
assert.match(manifest,/android\.intent\.action\.MY_PACKAGE_REPLACED/);
// Track the alarm delivery time independently from whether Android displayed a banner.
assert.match(scheduler,/\.putExtra\("scheduled_at", scheduledAt\)/);
assert.match(scheduler,/getLongExtra\("scheduled_at", 0L\)/);
assert.match(scheduler,/store\.recordAlarmEvent\(/);
assert.match(store,/fun lastAlarmEvent\(\): AlarmEvent\?/);
assert.match(store,/fun recordAlarmEvent\(/);
assert.match(main,/Последнее срабатывание расписания/);
assert.match(main,/Получено Android/);
assert.match(main,/Задержка: \$difference сек/);
// Test notification must not create an order or run any financial driver.
const a=scheduler.indexOf('fun testScheduleNotification(');
const b=scheduler.indexOf('private fun postOrderNotification(',a);
assert.ok(a>0&&b>a);
const test=scheduler.slice(a,b);
assert.match(test,/Тест уведомления/);
assert.doesNotMatch(test,/addOrder|OrderEntry|siteRunner|click\(|\.submit\(/);
assert.match(main,/Проверить уведомление без заказа/);
assert.match(scheduler,/areNotificationsEnabled\(\)/);
assert.match(scheduler,/getNotificationChannel\(CHANNEL_ID\)/);
assert.match(scheduler,/Уведомление передано Android/);
console.log('Background alarm rescheduling and notification diagnostics safety checks passed');