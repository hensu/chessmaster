// App Store screenshot framer (fintwit style): dark brand background,
// bold headline with accent word, glowing device frame.
// Usage: NODE_PATH=<dir with puppeteer-core> node render.js 6.9
const puppeteer = require('puppeteer-core');
const fs = require('fs');
const path = require('path');

const ACCENT = '#7FB84A';           // Chess AI green (Game Review CTA)
const CREAM = '#F0D9B5';

const shots = [
  { file: '5-review.png', out: '01_review.png', cap: 'See exactly where', accent: 'the game turned' },
  { file: '4-game.png',   out: '02_play.png',   cap: 'An opponent that',  accent: 'adapts to you' },
  { file: '1-home.png',   out: '03_home.png',   cap: 'Bullet to classical,', accent: 'rated' },
  { file: '3-puzzle.png', out: '04_puzzle.png', cap: 'Train the patterns', accent: 'you miss' },
  { file: '2-learn.png',  out: '05_learn.png',  cap: 'Puzzles & lessons,', accent: 'built in' },
];

function html(shot, W, H) {
  const b64 = fs.readFileSync(path.join(__dirname, 'raw', shot.file)).toString('base64');
  const capTop   = Math.round(H * 0.0412);
  const capPad   = Math.round(W * 0.0682);
  const capFont  = Math.round(W * 0.0940);
  const capLS    = (W * -0.00227).toFixed(2);
  const phoneTop = Math.round(H * 0.2622);
  const phoneW   = Math.round(W * 0.7833);
  const phonePad = Math.round(W * 0.0114);
  const innerW   = phoneW - 2 * phonePad;
  const radOut   = Math.round(W * 0.0591);
  const radIn    = Math.round(W * 0.0470);
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    * { margin:0; padding:0; box-sizing:border-box; }
    html,body { width:${W}px; height:${H}px; overflow:hidden; }
    .bg {
      position:relative; width:${W}px; height:${H}px;
      background:
        radial-gradient(120% 70% at 50% 88%, rgba(127,184,74,0.30) 0%, rgba(62,90,38,0.16) 32%, rgba(10,8,6,0) 62%),
        radial-gradient(90% 60% at 50% 100%, rgba(240,217,181,0.14) 0%, rgba(10,8,6,0) 55%),
        linear-gradient(180deg, #120e0a 0%, #1a1410 50%, #0b0806 100%);
      font-family:-apple-system,'SF Pro Display','Helvetica Neue',Arial,sans-serif;
    }
    .cap {
      position:absolute; top:${capTop}px; left:0; right:0;
      text-align:center; padding:0 ${capPad}px;
      font-weight:800; font-size:${capFont}px; line-height:1.04;
      letter-spacing:${capLS}px; color:#ffffff;
      text-shadow:0 2px 30px rgba(0,0,0,0.4);
    }
    .accent { color:${ACCENT}; }
    .phone {
      position:absolute; left:50%; top:${phoneTop}px; transform:translateX(-50%);
      width:${phoneW}px; padding:${phonePad}px; background:#0c0a08;
      border-radius:${radOut}px;
      box-shadow:
        0 0 0 2px rgba(255,255,255,0.06) inset,
        0 40px 90px rgba(0,0,0,0.6),
        0 0 120px rgba(127,184,74,0.22);
    }
    .phone img { display:block; width:${innerW}px; height:auto; border-radius:${radIn}px; }
  </style></head><body>
    <div class="bg">
      <div class="cap"><span class="w">${shot.cap}</span> <span class="accent">${shot.accent}</span></div>
      <div class="phone"><img src="data:image/png;base64,${b64}"></div>
    </div>
  </body></html>`;
}

(async () => {
  const presets = { '6.9': [1320, 2868], '6.5': [1242, 2688] };
  const which = process.argv[2] || '6.9';
  const [W, H] = presets[which];
  const outDir = path.join(__dirname, 'out', which);
  fs.mkdirSync(outDir, { recursive: true });
  const browser = await puppeteer.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: 'new',
  });
  const page = await browser.newPage();
  await page.setViewport({ width: W, height: H, deviceScaleFactor: 1 });
  for (const shot of shots) {
    await page.setContent(html(shot, W, H), { waitUntil: 'load' });
    const out = path.join(outDir, shot.out);
    await page.screenshot({ path: out, clip: { x: 0, y: 0, width: W, height: H } });
    console.log('rendered', out, `${W}x${H}`);
  }
  await browser.close();
})();
