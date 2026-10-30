// Pads the real build-18 web captures (780x1564) with an Android status bar and gesture bar to the 780x1688 phone screen.
// node blender/compose_screens.mjs chat-list bill-card ...
import { readFileSync } from 'node:fs';
const { chromium } = await import('/home/kyrylo/phantom_ai/showcase-out/overhaul/lib.mjs');
const SRC = process.env.SRC || '/home/kyrylo/phantom_ai/showcase-out/real-screens/raw/web';
const OUT = process.env.OUT || '/home/kyrylo/phantom_ai/showcase-out/site-v6/screens-b18';
const names = process.argv.slice(2);
const times = { 'chat-list': '11:12', 'bill-card': '11:14', 'assistant-draft': '09:34', 'venue-page': '10:52', 'call-incoming-audio': '11:20', 'meetup-card': '10:52', 'bill-sheet': '11:15', 'payment-in-chat': '09:35', 'call-active-audio': '11:21' };
const b = await chromium.launch();
let p;
for (const n of names) { p = await b.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 }); p.on('pageerror', e => console.log('err:', e.message));
  const data = 'data:image/png;base64,' + readFileSync(`${SRC}/${n}.png`).toString('base64');
  await p.setContent(`<html><body style="margin:0;width:390px;height:844px;overflow:hidden;font-family:Roboto,'Noto Sans',sans-serif">
<canvas id=c width=780 height=1564 style="display:none"></canvas>
<div id=top style="height:31px;display:flex;align-items:center;justify-content:space-between;padding:0 18px 0 22px;font-size:13.5px;font-weight:500;letter-spacing:.2px"></div>
<img id=i src="${data}" style="display:block;width:390px;height:782px">
<div id=bot style="height:31px;display:flex;align-items:center;justify-content:center"><div id=pill style="width:108px;height:4px;border-radius:2px"></div></div>
<script>
const img=document.getElementById('i');
window.ready=new Promise(r=>{if(img.complete)setTimeout(()=>img.onload(),0);img.onload=()=>{const c=document.getElementById('c').getContext('2d');c.drawImage(img,0,0);
const px=(x,y)=>{const d=c.getImageData(x,y,1,1).data;return [d[0],d[1],d[2]]};
const t=px(3,3),bt=px(3,1560);const lum=a=>(a[0]*.3+a[1]*.59+a[2]*.11);
const ink=lum(t)<128?'#F2F2F2':'#1A1A1A', inkB=lum(bt)<128?'rgba(255,255,255,.75)':'rgba(0,0,0,.6)';
document.body.style.background='rgb('+bt+')';
const top=document.getElementById('top');top.style.background='rgb('+t+')';top.style.color=ink;
top.innerHTML='<span>${'${T}'}</span><span style="display:flex;gap:6px;align-items:center">'+
'<svg width="15" height="12" viewBox="0 0 15 12"><path d="M7.5 11.5 0.6 3.9A10.3 10.3 0 0 1 14.4 3.9Z" fill="'+ink+'"/></svg>'+
'<svg width="13" height="13" viewBox="0 0 13 13"><path d="M12.5 0.5V12.5H0.5Z" fill="'+ink+'"/></svg>'+
'<svg width="9" height="15" viewBox="0 0 9 15"><rect x="2.5" y="0" width="4" height="2" rx=".6" fill="'+ink+'"/><rect x=".5" y="1.6" width="8" height="13" rx="1.6" fill="none" stroke="'+ink+'" stroke-width="1"/><rect x="1.8" y="4.6" width="5.4" height="8.8" rx=".7" fill="'+ink+'"/></svg></span>';
document.getElementById('pill').style.background=inkB;r()}});
</script></body></html>`.replace('${T}', times[n] || '11:12'));
  await p.evaluate(() => window.ready);
  await p.screenshot({ path: `${OUT}/${n}.png` });
  console.log(n);
}
await b.close();
