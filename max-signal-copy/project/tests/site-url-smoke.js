const assert = require('node:assert/strict');
const fs = require('node:fs');
const root = 'app/src/main/java/com/example/maxsignalcopy/';
const site = fs.readFileSync(root + 'SiteConfig.kt', 'utf8');
const activity = fs.readFileSync(root + 'MainActivity.kt', 'utf8');
const session = fs.readFileSync(root + 'TradeSession.kt', 'utf8');
const engine = fs.readFileSync(root + 'BrowserAutomation.kt', 'utf8');
const expected = 'https://pbeaocuec.com/ru/futures?name=BTC/USDT&tabName=FUTURES';
assert(site.includes('const val HOME_URL = "'+expected+'"'), 'Exact requested futures URL');
assert(site.includes('private const val HOST = "pbeaocuec.com"'));
assert(site.includes('return host == HOST'), 'Only exact new host');
assert(!site.includes('host.endsWith'), 'Do not allow lookalike subdomains');
assert(site.includes('address.scheme != "https"'));
assert(site.includes('setOf("https://pbeaocuec.com")'));
assert(site.includes('uri.getQueryParameter("name") == "BTC/USDT"'));
assert(site.includes('uri.getQueryParameter("tabName") == "FUTURES"'));
assert(activity.includes('browser.loadUrl(SiteConfig.HOME_URL)'));
assert(activity.includes('!SiteConfig.accepts(request?.url)'));
assert(activity.includes('!SiteConfig.accepts(browser.url.orEmpty())'));
assert(engine.includes('private val homeUrl = SiteConfig.HOME_URL'));
assert(engine.includes('SiteConfig.isHomePage(url)'));
// Financial trading disabled due to independent phishing and newly-registered domain flags.
assert(site.includes('const val AUTO_TRADING_ENABLED = false'));
assert(activity.includes('if (!SiteConfig.AUTO_TRADING_ENABLED)'));
assert(session.includes('if (!SiteConfig.AUTO_TRADING_ENABLED)'));
assert(engine.includes('if (!SiteConfig.AUTO_TRADING_ENABLED)'));
assert(activity.includes('isEnabled = SiteConfig.AUTO_TRADING_ENABLED'));
assert(activity.includes('manualCode.isEnabled = SiteConfig.AUTO_TRADING_ENABLED'));
// Even loading the page requires the user to acknowledge a warning.
assert(activity.includes('AlertDialog.Builder(this)'));
assert(activity.includes('if (!siteAcknowledged)'));
assert(!activity.includes('if (!TradeSession.isActive()) openSite()'));
for (const [name, src] of [['site',site],['activity',activity],['engine',engine]]) {
  assert(!src.includes('lumecoe.com'), 'Stale trading host in '+name);
  assert(!src.includes('lxcompintb.com'), 'Legacy trading host in '+name);
}
console.log('Futures URL, exact-host navigation and unverified-domain safety checks passed.');
