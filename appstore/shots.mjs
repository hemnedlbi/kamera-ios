// App Store screenshots of camera.4chra.com as the iPhone app shows it (iPhone 6.9": 1320x2868).
// Usage: node appstore/shots.mjs <output-dir>   — Design & Development Hemn A.Muhammad
import { chromium } from 'playwright';
const out = process.argv[2] || 'shots';
const url = 'https://camera.4chra.com/?app=ios';
const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 440, height: 956 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true,
  geolocation: { latitude: 36.191, longitude: 44.009 }, permissions: ['geolocation'],
  userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148',
});
const page = await ctx.newPage();
const idle = async (ms) => { try { await page.waitForLoadState('networkidle', { timeout: 15000 }); } catch {} await page.waitForTimeout(ms); };
const shot = async (name) => { await page.screenshot({ path: `${out}/${name}.png` }); console.log('saved', name); };
const click = async (sel) => { try { await page.click(sel, { timeout: 5000 }); } catch (e) { console.log('skip', sel, e.message.split('\n')[0]); } };

await page.goto(url, { waitUntil: 'domcontentloaded' });
await idle(4000);
await shot('1-map');

await click('#btnMenu'); await page.waitForTimeout(600);
await click('#btnSim');
await idle(12000);
await shot('2-driving');
await idle(14000);
await shot('3-driving');

await click('#btnStart'); await page.waitForTimeout(800);   // stop the test drive
await click('#btnTheme'); await idle(3000);
await shot('4-light');

await click('#btnAdd'); await page.waitForTimeout(1200);
await shot('5-add-camera');
await browser.close();
