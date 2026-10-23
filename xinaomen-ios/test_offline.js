/* ============================================================
   离线自测：模拟 iPhone 用 file:// 打开本地 HTML 的最恶劣情况
     · localStorage 抛异常（iOS 对 file:// 来源常见）
     · fetch 全部失败（飞行模式 / 无网 / file:// 跨域被拦）
   期望：打开即有完整内容、6 个页面全部可渲染、算法可算、走势用本地降级。
   ============================================================ */
const fs = require('fs');
const path = '/sdcard/Download/solab/XinAomen/ios/index.html';
const html = fs.readFileSync(path, 'utf8');
const m = html.match(/<script>([\s\S]*)<\/script>/);
if (!m) { console.log('FAIL 未找到内联脚本'); process.exit(1); }
const code = m[1];

/* ---------- 桩：localStorage 被禁（每次访问都抛） ---------- */
let lsCalls = 0;
globalThis.localStorage = {
  getItem() { lsCalls++; throw new Error('SecurityError: localStorage is disabled'); },
  setItem() { lsCalls++; throw new Error('SecurityError: localStorage is disabled'); },
  removeItem() { lsCalls++; throw new Error('SecurityError: localStorage is disabled'); }
};

/* ---------- 桩：网络全断 ---------- */
let fetchCalls = 0;
globalThis.fetch = () => { fetchCalls++; return Promise.reject(new Error('offline')); };

/* ---------- DOM 桩 ---------- */
const els = {};
function mkEl(id) {
  return els[id] || (els[id] = {
    id, innerHTML: '', textContent: '', clientWidth: 320, style: {},
    getContext: () => ({
      setTransform() {}, clearRect() {}, beginPath() {}, moveTo() {}, lineTo() {},
      stroke() {}, fillRect() {}, fillText() {}, arc() {}, fill() {}, save() {}, restore() {},
      set fillStyle(v) {}, set strokeStyle(v) {}, set lineWidth(v) {}, set font(v) {}, set textAlign(v) {}, set textBaseline(v) {}
    }),
    appendChild() {}, setAttribute() {}, addEventListener() {}
  });
}
globalThis.document = {
  head: { appendChild() {} }, body: { appendChild() {} },
  getElementById: mkEl,
  createElement: () => ({ style: {}, getContext: mkEl('cv').getContext, toDataURL: () => 'data:,', appendChild() {}, setAttribute() {} }),
  addEventListener() {}, hidden: false
};
globalThis.window = globalThis;
globalThis.devicePixelRatio = 2;
globalThis.addEventListener = () => {};
globalThis.scrollTo = () => {};
globalThis.setInterval = () => 0;
globalThis.URL = globalThis.URL || {};
globalThis.Blob = globalThis.Blob || function () {};

/* ---------- 执行 ---------- */
let FAIL = 0, PASS = 0;
function ok(cond, msg) { console.log((cond ? 'PASS  ' : 'FAIL  ') + msg); if (cond) PASS++; else FAIL++; }

try {
  (0, eval)(code + '\n;globalThis.__T={store,LS,zodiacOfNum,ZODIAC,pad,render,homeHtml,coldHtml,killHtml,patternHtml,trendHtml,hotHtml,trendLoad,trendLocal,trend,COLD_T1,COLD_T2,WINDOW};'
    );
} catch (e) {
  console.log('FAIL  脚本执行异常: ' + e.message);
  process.exit(1);
}
ok(true, '内联脚本在「存储被禁 + 无网络」下加载并启动，无异常');
console.log('... localStorage 访问次数=' + lsCalls + '（说明探测已发生但异常被吞掉）');

const T = globalThis.__T;
const store = T.store;

/* 1. 内嵌数据生效 */
ok(store.draws.length === 150, '内嵌数据提供 150 期（实得 ' + store.draws.length + '）');
ok(store.draws[0] && store.draws[0].issue === '2026288', '最新期为 2026288（实得 ' + (store.draws[0] || {}).issue + '）');
ok(store.draws[149] && store.draws[149].issue === '2026139', '最旧期为 2026139（实得 ' + (store.draws[149] || {}).issue + '）');
const issues = store.draws.map(d => d.issue);
ok(issues.every((v, i) => i === 0 || issues[i - 1] > v), '期号严格降序');
let gap = 0;
for (let i = 1; i < issues.length; i++) if (parseInt(issues[i - 1], 10) - parseInt(issues[i], 10) !== 1) gap++;
ok(gap === 0, '期号连续无缺口（断裂 ' + gap + ' 处）');

const d0 = store.draws[0];
ok(d0.nums.map(n => (n < 10 ? '0' + n : '' + n)).join(',') === '14,48,05,17,46,21,25',
  '最新期开奖号为 14,48,05,17,46,21,25（实得 ' + d0.nums.map(n => (n < 10 ? '0' + n : '' + n)).join(',') + '）');
ok(d0.zodiac.join(',') === '蛇,羊,虎,虎,鸡,狗,马', '最新期生肖已简体化（实得 ' + d0.zodiac.join(',') + '）');
ok(d0.wave.split(',').join(',') === 'blue,blue,green,green,red,green,blue', '最新期波色还原正确（实得 ' + d0.wave + '）');
ok(!store.draws.some(d => /[龍馬雞豬]/.test(d.zodiac.join(''))), '全部 150 期生肖均为简体');
ok(store.draws.every(d => d.nums.every(n => n >= 1 && n <= 49)), '全部 150 期号码均在 1..49');
ok(store.draws.every(d => d.wave.split(',').length === 7), '全部 150 期波色为 7 段');

/* 2. 算法在纯离线可算 */
const nz = store.numberZodiac();
let conflict = 0, checked = 0;
for (let k = 1; k <= 49; k++) {
  if (!nz[k]) continue;
  checked++;
  if (nz[k] !== T.zodiacOfNum(k)) conflict++;
}
ok(conflict === 0, '号码↔生肖 0 冲突（校验 ' + checked + '/49）');
const om = store.allOmissions();
ok(om.length === 50, 'allOmissions 可用');
const cc = store.coldCounts();
ok(cc[0] + cc[1] <= 49, 'coolCounts 合理 [' + cc + ']');
ok(store.coldLevelsBefore().length === 150, 'coldLevelsBefore 长度 150');
const kf = store.killZodiacs(false);
ok(kf.length === 3, '离线也能算杀生肖 → 平' + kf.join(''));
const flat = store.recommendFlat(3), sp = store.recommendSpecial(6);
ok(flat.length === 3 && flat.every(a => a.length === 6) && sp.length === 6, '离线也能算推荐（平3组×6 / 特6）');
ok(store.nextIssueOf('2026288') === '2026289' && store.nextIssueOf('2026365') === '2027001', '期号推进正确 2026288→2026289 / 2026365→2027001');

/* 3. 存储降级为内存态，仍可读写 */
ok(T.LS.ok === false, '存储探测识别出 localStorage 不可用（LS.ok=false）');
T.LS.set('k', 'v');
ok(T.LS.get('k', '') === 'v', '内存态读写正常（本次会话内存档可用）');
T.LS.set('xam_draws', JSON.stringify([{ issue: '2099001', time: '', wave: '', nums: '1,2,3,4,5,6,7', zodiac: '马,蛇,龙,兔,虎,牛,鼠' }]));
ok(T.LS.get('xam_draws', '') !== '', '内存态可存开奖缓存（关闭页面即丢，属预期）');

/* 4. 走势离线降级：本地自算，且与内嵌数据吻合 */
const lc = T.trendLocal(2, 10);
ok(lc.length === 4, 'trendLocal 返回 4 段（号码/生肖/波色/尾数）');
const codeResp = JSON.parse(lc[0]).data;
const zodResp = JSON.parse(lc[1]).data;
const waveResp = JSON.parse(lc[2]).data;
const tailResp = JSON.parse(lc[3]).data;
let sumCode = 0;
for (const k in codeResp) sumCode += codeResp[k];
ok(sumCode === 60, '本地走势「最近 10 期前 6 码」合计 60（10×6）（实得 ' + sumCode + '）');
let sumZod = 0;
for (const k in zodResp) sumZod += zodResp[k];
ok(sumZod === 60, '本地走势生肖合计 60（实得 ' + sumZod + '）');
ok(waveResp.red + waveResp.green + waveResp.blue === 60, '本地走势波色合计 60（实得 ' + (waveResp.red + waveResp.green + waveResp.blue) + '）');
let sumTail = 0;
for (const k in tailResp) sumTail += tailResp[k];
ok(sumTail === 60 && Object.keys(tailResp).length === 10, '本地走势尾数 0-9 齐全且合计 60（实得 ' + sumTail + '）');

/* 5. 六页面离线渲染 */
T.render();
const sections = [
  ['home', T.homeHtml, ['最新开奖', '本期推荐', '18码中特图', '平码留档', '冷门号预览', '平码生肖统计', '开奖记录']],
  ['cold', T.coldHtml, ['冷门号一览', '冷门 ' + T.COLD_T1, '极冷 ' + T.COLD_T2]],
  ['kill', T.killHtml, ['杀生肖分析', '平码生肖（不含特码）', '特码生肖', '本期杀生肖建议', '生肖遗漏']],
  ['pattern', T.patternHtml, ['冷号规律', '核心结论', '遗漏排行榜']],
  ['trend', T.trendHtml, ['正码统计', '特码统计', '号码出现次数', '生肖出现次数', '波色出现次数', '尾数出现次数']],
  ['hot', T.hotHtml, ['特码投注', '平特一肖', '波色', '热买榜', '上一期', '下一期']]
];
for (const [name, fn, need] of sections) {
  const h = fn();
  const miss = need.filter(s => h.indexOf(s) < 0);
  ok(h.length > 200 && miss.length === 0, name + ' 页面离线渲染 ' + h.length + ' 字节' + (miss.length ? '，缺失: ' + miss.join('/') : ''));
}
const home = T.homeHtml();
ok((home.match(/class="ball"/g) || []).length > 900, '主页号码球 ' + (home.match(/class="ball"/g) || []).length + ' 个');
ok(home.indexOf('★ 爆冷开出') >= 0, '主页爆冷标记存在');
ok(home.indexOf('2026139') >= 0, '主页历史已渲染到最旧期 2026139');

/* 6. 趋势页真实走一遍离线分支（fetch 全失败 → 用本地降级，页面不留白） */
T.trendLoad();
setTimeout(() => {
  const box = els['tCode'];
  const txt = box ? box.innerHTML : '';
  ok(txt.indexOf('ball') >= 0, '走势页在无网络时用本地统计渲染，未留白（' + txt.length + ' 字节）');
  const zodBox = els['tZodiac'], waveBox = els['tWave'], tailBox = els['tTail'];
  ok(zodBox && zodBox.innerHTML.length > 50, '走势-生肖区已渲染');
  ok(waveBox && waveBox.innerHTML.indexOf('%') >= 0, '走势-波色区已渲染');
  ok(tailBox && tailBox.innerHTML.indexOf('次') >= 0, '走势-尾数区已渲染');

  /* 7. 同步全部失败，也不影响已有数据与状态提示 */
  setTimeout(() => {
    ok(store.draws.length === 150, '同步失败后仍保有 150 期内嵌数据');
    ok(fetchCalls > 0, '确实尝试过联网（fetch 调用 ' + fetchCalls + ' 次，全部失败）');
    console.log('\n==== 离线结果：' + PASS + ' 通过 / ' + FAIL + ' 失败 ====');
    process.exit(FAIL === 0 ? 0 : 2);
  }, 2500);
}, 2500);