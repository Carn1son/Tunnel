// Draws every Tunnel logo from one shape: a chip with five pins on each side, the tunnel cut out of it,
// the passage inside and a T in the corner like the pin-1 mark of a real chip.
//   node tools/make-logos.js
const fs = require('fs'), path = require('path');
const { chromium } = require('playwright');
const root = path.join(__dirname, '..');
const W = p => path.join(root, p);
const BLUE = '#3b82f6';

const PINS = [29, 39.5, 50, 60.5, 71];
const pinRects = () => PINS.map(p =>
  `<rect x="${p - 2.2}" y="5" width="4.4" height="10" rx="1"/><rect x="${p - 2.2}" y="85" width="4.4" height="10" rx="1"/>` +
  `<rect x="5" y="${p - 2.2}" width="10" height="4.4" rx="1"/><rect x="85" y="${p - 2.2}" width="10" height="4.4" rx="1"/>`).join('');
const BODY = 'M28 18h44a10 10 0 0 1 10 10v44a10 10 0 0 1-10 10h-6V52a16 16 0 0 0-32 0v30h-6a10 10 0 0 1-10-10V28a10 10 0 0 1 10-10z';
const DOOR = 'M42 82V57a8 8 0 0 1 16 0v25z';
const T = 'M23.5 23.5h12v3.7h-4.15v11h-3.7v-11h-4.15z';

// chip in `ink`; the arch is cut out (transparent); passage and T in `acc`
const chip = (ink, acc) => `<g fill="${ink}">${pinRects()}<path fill-rule="evenodd" d="${BODY}"/></g><path fill="${acc}" d="${DOOR}"/><path fill="${acc}" d="${T}"/>`;
const svg = (vb, inner, size) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${vb}"${size ? ` width="${size[0]}" height="${size[1]}"` : ''}>${inner}</svg>`;

async function png(browser, svgText, w, h, file) {
  const p = await browser.newPage({ viewport: { width: w, height: h } });
  await p.setContent(`<html><body style="margin:0;background:transparent">${svgText.replace('<svg ', `<svg width="${w}" height="${h}" `)}</body></html>`);
  await p.screenshot({ path: file, omitBackground: true });
  await p.close();
}

(async () => {
  const b = await chromium.launch();
  const logoW = svg('0 0 100 100', chip('#fff', BLUE)), logoB = svg('0 0 100 100', chip('#111', BLUE));
  fs.writeFileSync(W('assets/logo/tunnel_logo_white.svg'), logoW);
  fs.writeFileSync(W('assets/logo/tunnel_logo_black.svg'), logoB);
  await png(b, logoW, 512, 512, W('assets/logo/tunnel_logo_white_512.png'));
  await png(b, logoB, 512, 512, W('assets/logo/tunnel_logo_black_512.png'));

  // app icon on a black rounded square (Android launcher)
  const icon = svg('0 0 100 100', `<rect width="100" height="100" rx="22" fill="#000"/><g transform="translate(9 9) scale(.82)">${chip('#fff', BLUE)}</g>`);
  fs.writeFileSync(W('assets/logo/tunnel_icon.svg'), icon);
  await png(b, icon, 1024, 1024, W('assets/logo/tunnel_icon_1024.png'));
  for (const [d, s] of [['mdpi', 48], ['hdpi', 72], ['xhdpi', 96], ['xxhdpi', 144], ['xxxhdpi', 192]])
    await png(b, icon, s, s, W(`android/app/src/main/res/mipmap-${d}/ic_launcher.png`));

  // README logos: chip, name, tagline and the blue underline
  const readme = (ink, text, sub) => svg('0 0 266 100', chip(ink, BLUE) +
    `<text x="117" y="46" font-family="DejaVu Sans,Segoe UI,Arial,sans-serif" font-weight="700" font-size="29" fill="${text}">Tunnel</text>` +
    `<text x="118" y="69" font-family="DejaVu Sans,Segoe UI,Arial,sans-serif" font-size="11.2" fill="${sub}">for our own moles only</text>` +
    `<rect x="119" y="79" width="49" height="1.6" fill="${BLUE}"/>`);
  const rd = readme('#fff', '#e6ebf0', '#8b929c'), rl = readme('#111', '#1b1f23', '#6b7280');
  fs.writeFileSync(W('assets/logo/readme_logo_dark.svg'), rd);
  fs.writeFileSync(W('assets/logo/readme_logo_light.svg'), rl);
  await png(b, rd, 950, 357, W('docs/logo-dark.png'));
  await png(b, rl, 950, 357, W('docs/logo-light.png'));
  fs.copyFileSync(W('docs/logo-dark.png'), W('assets/logo/readme_logo_dark.png'));
  fs.copyFileSync(W('docs/logo-light.png'), W('assets/logo/readme_logo_light.png'));
  await b.close();

  // Android vectors: the widget logo in colour, the quick settings tile as a one-colour mask (T cut out)
  const vPins = PINS.map(p => { const a = (p - 2.2).toFixed(1); return `M${a},5h4.4v10h-4.4zM${a},85h4.4v10h-4.4zM5,${a}h10v4.4h-10zM85,${a}h10v4.4h-10z`; }).join('');
  const vBody = 'M28,18h44a10,10 0,0 1,10 10v44a10,10 0,0 1,-10 10h-6v-30a16,16 0,0 0,-32 0v30h-6a10,10 0,0 1,-10 -10v-44a10,10 0,0 1,10 -10z';
  const vDoor = 'M42,82v-25a8,8 0,0 1,16 0v25z', vT = 'M23.5,23.5h12v3.7h-4.15v11h-3.7v-11h-4.15z';
  const vec = (size, body) => `<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="${size}dp" android:height="${size}dp" android:viewportWidth="100" android:viewportHeight="100">\n${body}</vector>\n`;
  fs.writeFileSync(W('android/app/src/main/res/drawable/logo.xml'), vec(32,
    `    <path android:fillColor="#ffffff" android:pathData="${vPins}" />\n    <path android:fillColor="#ffffff" android:fillType="evenOdd" android:pathData="${vBody}" />\n` +
    `    <path android:fillColor="${BLUE}" android:pathData="${vDoor}" />\n    <path android:fillColor="${BLUE}" android:pathData="${vT}" />\n`));
  fs.writeFileSync(W('android/app/src/main/res/drawable/ic_tile.xml'), vec(24,
    `    <path android:fillColor="#ffffff" android:pathData="${vPins}" />\n    <path android:fillColor="#ffffff" android:fillType="evenOdd" android:pathData="${vBody}${vT}" />\n` +
    `    <path android:fillColor="#ffffff" android:pathData="${vDoor}" />\n`));
  console.log('logos written');
})();
