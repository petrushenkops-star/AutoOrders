const assert = require('assert');
const fs = require('fs');
const root = 'app/src/main/java/com/example/maxsignalcopy/';
const site = fs.readFileSync(root + 'SiteConfig.kt', 'utf8');
const ui = fs.readFileSync(root + 'MainActivity.kt', 'utf8');
const automation = fs.readFileSync(root + 'BrowserAutomation.kt', 'utf8');
const readme = fs.readFileSync('README.md', 'utf8');

assert(site.includes('const val HOME_URL = "https://lumecoe.com"'), 'canonical website address');
assert(site.includes('host == HOST || host.endsWith(".$HOST")'), 'only intended domain or subdomains');
assert(site.includes('address.scheme != "https"'), 'https required');
assert(ui.includes('browser.loadUrl(SiteConfig.HOME_URL)'), 'home button uses canonical website');
assert(ui.includes('SiteConfig.DOCUMENT_START_ORIGINS'), 'WebView start script origins match website');
assert(ui.includes('!SiteConfig.accepts(request?.url)'), 'WebView navigation is restricted');
assert(ui.includes('!SiteConfig.accepts(browser.url.orEmpty())'), 'manual order is restricted');
assert(automation.includes('private val homeUrl = SiteConfig.HOME_URL'), 'automation starts at correct URL');
assert(automation.includes('private fun safeUrl(url: String): Boolean = SiteConfig.accepts(url)'), 'automation shares allowlist');
assert(site.includes('fun isHomePage(url: String): Boolean'), 'home completion supports root and locales');
assert(site.includes("path.isEmpty()"), 'root landing is accepted');
assert(site.includes('Regex("^/[a-zA-Z]{2}(?:-[a-zA-Z]{2})?$")'), 'language redirect allowed');
assert(automation.includes('SiteConfig.isHomePage(url)'), 'browser completion checks landing route');
assert(!automation.includes('trimEnd(\'/\') == "/ru"'), 'no hardcoded /ru wait');

for (const [name, source] of [['SiteConfig',site],['MainActivity',ui],['BrowserAutomation',automation],['README',readme]]) {
  assert(!source.includes('lxcompintb.com'), 'legacy website reference found in ' + name);
}
console.log('Canonical LumeCo website and URL allowlist checks passed');
