// Real phone screencap (1080 wide) -> 780x1688 screen with the status bar redrawn clean (no personal icons).
// node blender/compose_phone_capture.mjs <src.png> <out.png> [time]
import { readFileSync } from 'node:fs';
const { chromium } = await import('/home/kyrylo/phantom_ai/showcase-out/overhaul/lib.mjs');
const [src, out, time = '11:40'] = process.argv.slice(2);
const b = await chromium.launch();
const p = await b.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
const data = 'data:image/png;base64,' + readFileSync(src).toString('base64');
await p.setContent(`<body style="margin:0;overflow:hidden;font-family:Roboto,'Noto Sans',sans-serif">
<img id=i src="${data}" style="display:block;width:390px">
<div id=top style="position:absolute;left:0;top:0;width:390px;height:31px;display:flex;align-items:center;justify-content:space-between;padding:0 18px 0 22px;box-sizing:border-box;font-size:13.5px;font-weight:500"></div>
<canvas id=c width=8 height=8 style="display:none"></canvas></body>`);
await p.evaluate(async (time) => {
  const img = document.getElementById('i');
  await img.decode();
  const c = document.getElementById('c').getContext('2d');
  c.drawImage(img, 3, 40, 1, 1, 0, 0, 1, 1);
  const [r, g, bl] = c.getImageData(0, 0, 1, 1).data;
  const ink = r * 0.3 + g * 0.59 + bl * 0.11 < 128 ? '#F2F2F2' : '#1A1A1A';
  const top = document.getElementById('top');
  top.style.background = `rgb(${r},${g},${bl})`;
  top.style.color = ink;
  top.innerHTML = `<span>${time}</span><span style="display:flex;gap:6px;align-items:center"><svg width="15" height="12" viewBox="0 0 15 12"><path d="M7.5 11.5 0.6 3.9A10.3 10.3 0 0 1 14.4 3.9Z" fill="${ink}"/></svg><svg width="13" height="13" viewBox="0 0 13 13"><path d="M12.5 0.5V12.5H0.5Z" fill="${ink}"/></svg><svg width="9" height="15" viewBox="0 0 9 15"><rect x="2.5" y="0" width="4" height="2" rx=".6" fill="${ink}"/><rect x=".5" y="1.6" width="8" height="13" rx="1.6" fill="none" stroke="${ink}" stroke-width="1"/><rect x="1.8" y="4.6" width="5.4" height="8.8" rx=".7" fill="${ink}"/></svg></span>`;
}, time);
await p.screenshot({ path: out });
await b.close();
