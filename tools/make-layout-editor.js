// Builds docs/layout-editor.html: the layout editor with the current app page and its texts inside.
//   node tools/make-layout-editor.js
const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..');
const app = fs.readFileSync(path.join(root, 'ui/index.html'), 'utf8');
const i = app.indexOf('var L = {'); let d = 0, j = i + 8;
for (; j < app.length; j++) { if (app[j] === '{') d++; else if (app[j] === '}') { d--; if (d === 0) break; } }
const L = eval('(' + app.slice(i + 8, j + 1) + ')');
const js = v => JSON.stringify(v).replace(/<\/script/gi, '<\\/script').replace(/<!--/g, '<\\!--');
const out = fs.readFileSync(path.join(__dirname, 'layout-editor.template.html'), 'utf8')
  .replace('__APP_HTML__', () => js(app)).replace('__APP_STRINGS__', () => js(L));
fs.mkdirSync(path.join(root, 'docs'), { recursive: true });
fs.writeFileSync(path.join(root, 'docs/layout-editor.html'), out);
console.log('docs/layout-editor.html', out.length);
