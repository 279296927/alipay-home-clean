/* PWA 自测：抽出 index.html 内联脚本，在 Node 中跑真实数据，校验算法与 Android 版一致性 */
const fs = require('fs');
const path = '/sdcard/Download/solab/XinAomen/ios/index.html';
const html = fs.readFileSync(path, 'utf8');
const m = html.match(/<script>([\s\S]*)<\/script>/);
if (!m) { console.log('FAIL 未找到内联脚本'); process.exit(1); }
let code = m[1];

/* ---------- 浏览器环境桩 ---------- */
const storeMem = {};
globalThis.localStorage = {
  getItem: k => (k in storeMem ? storeMem[k] : null),
  setItem: (k, v) => { storeMem[k] = String(v); },
  removeItem: k => { delete storeMem[k]; }
};
const els = {};
function mkEl(id) {
  return els[id] || (els[id] = {
    id, innerHTML: '', textContent: '', clientWidth: 320,
    style: {},
    getContext: () => ({
      setTransform() {}, clearRect() {}, beginPath() {}, moveTo() {}, lineTo() {},
      stroke() {}, fillRect() {}, fillText() {}, arc() {}, fill() {}, save() {}, restore() {},
      set fillStyle(v) {}, set strokeStyle(v) {}, set lineWidth(v) {}, set font(v) {}, set textAlign(v) {}, set textBaseline(v) {}
    }),
    appendChild() {}, setAttribute() {}, addEventListener() {}
  });
}
globalThis.document = {
  head: { appendChild() {} },
  body: { appendChild() {} },
  getElementById: mkEl,
  createElement: () => ({ style: {}, getContext: mkEl('cv').getContext, toDataURL: () => 'data:,', appendChild() {}, setAttribute() {} }),
  addEventListener() {},
  hidden: false
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
  (0, eval)(code + '\n;globalThis.__T={store,zodiacOfNum,ZODIAC,pad,render,homeHtml,coldHtml,killHtml,patternHtml,trendHtml,hotHtml,syncNow,C,COLD_T1,COLD_T2,WINDOW,pic18,loadPic18,picOpen,picToggle,pic18Html,pic18CardHtml,loadPic18Soon};');
} catch (e) {
  console.log('FAIL  脚本执行异常: ' + e.message);
  process.exit(1);
}
console.log('PASS  内联脚本加载 + 启动无异常');

const T = globalThis.__T;
const store = T.store;

/* 1. 生肖算法（2026 马年表：1=马 2=蛇 3=龙…） */
ok(T.zodiacOfNum(1) === '马', 'zodiacOfNum(1)=马 (实得 ' + T.zodiacOfNum(1) + ')');
ok(T.zodiacOfNum(2) === '蛇', 'zodiacOfNum(2)=蛇');
ok(T.zodiacOfNum(3) === '龙', 'zodiacOfNum(3)=龙');
ok(T.zodiacOfNum(13) === '马' && T.zodiacOfNum(49) === '马', 'zodiacOfNum(13)=马 / (49)=马（马年马号 01/13/25/37/49）');
ok(T.zodiacOfNum(7) === '鼠' && T.zodiacOfNum(8) === '猪', 'zodiacOfNum(7)=鼠 / (8)=猪（与实测开奖 08→猪 一致）');
ok(T.zodiacOfNum(0) === '' && T.zodiacOfNum(50) === '', '越界返回空串');

/* 2. 等待模块自带的启动同步完成（避免与节流互斥互相打断） */
function waitDraws(ms) {
  const t0 = Date.now();
  return new Promise(res => {
    (function loop() {
      if (store.draws.length > 0 || Date.now() - t0 > ms) return res();
      setTimeout(loop, 500);
    })();
  });
}
console.log('... 等待启动同步（live2 + history 2026 + 预测）');
waitDraws(90000).then(async () => {
  if (store.draws.length < 100) { await T.syncNow('测试补拉', true); await waitDraws(60000); }
  /* 预热历史（一次 5 年窗口，与 Android 首次打开口径一致） */
  let warm = 0;
  while (store.draws.length < 100 && warm < 6) {
    warm++;
    const before = store.draws.length;
    await store.fetchHistory();
    if (store.draws.length === before) break;
  }
  const n = store.draws.length;
  ok(n > 0, '预热后抓到数据 ' + n + ' 期');
  ok(n >= 100, '历史累积达 ≥100 期（实得 ' + n + '）');
  const issues = store.draws.map(d => d.issue);
  ok(issues.every((v, i) => i === 0 || issues[i - 1] > v), '期号严格降序（最新在前）');
  const d0 = store.draws[0];
  ok(d0.nums.every(x => x >= 1 && x <= 49), '最新期 7 个号码均在 1..49 → ' + d0.nums.join(','));
  ok(d0.zodiac.filter(z => z.length > 0).length === 7, '最新期 7 个生肖全部有值（繁→简已转换）');
  ok(!d0.zodiac.join('').match(/[龍馬雞豬]/), '生肖已全部简体化');

  /* 3. 号码↔生肖一致性（Android 声称 150 期 0 冲突） */
  const nz = store.numberZodiac();
  let conflict = 0, miss = 0, checked = 0;
  for (let k = 1; k <= 49; k++) {
    if (!nz[k]) { miss++; continue; }
    checked++;
    if (nz[k] !== T.zodiacOfNum(k)) conflict++;
  }
  ok(conflict === 0, '号码↔生肖映射与公式表 0 冲突（校验 ' + checked + '/49 号，缺失 ' + miss + '）');
  const zn = store.zodiacNumbers();
  let znTotal = 0;
  for (const z of T.ZODIAC) znTotal += (zn[z] || []).length;
  ok(znTotal === 49, '生肖→号码全集覆盖 1..49（实得 ' + znTotal + '）');

  /* 4. 冷门（只看特码） */
  const om = store.allOmissions();
  let omOk = true;
  for (let k = 1; k <= 49; k++) {
    const idx = store.lastSeenIndex(k);
    const want = idx < 0 ? store.draws.length : idx;
    if (om[k] !== want) omOk = false;
  }
  ok(omOk, 'allOmissions 与逐号 lastSeenIndex 一致');
  const cc = store.coldCounts();
  ok(cc.length === 2 && cc[0] + cc[1] <= 49, 'coldCounts = [' + cc + '] 合理（阈值 ' + T.COLD_T1 + '/' + T.COLD_T2 + '）');
  const before = store.coldLevelsBefore();
  ok(before.length === n, 'coldLevelsBefore 长度=期数（' + before.length + '）');
  ok(before[0] === store.coldLevel(store.omissionBefore(0)), '最新期 开出前冷度 与 omissionBefore 口径一致');

  /* 5. 杀生肖 / 推荐 */
  const kf = store.killZodiacs(false), ks = store.killZodiacs(true);
  ok(kf.length === 3 && ks.length === 3 && kf.every(z => T.ZODIAC.indexOf(z) >= 0), '杀生肖取 3 个且在 12 生肖内 → 平' + kf.join('') + ' 特' + ks.join(''));
  const flat = store.recommendFlat(3);
  ok(flat.length === 3 && flat.every(a => a.length === 6), '平码推荐 3 组×6 号');
  let noLatest = true;
  const latest7 = store.draws[0].nums;
  for (const arr of flat) for (const v of arr) if (latest7.indexOf(v) >= 0) noLatest = false;
  ok(noLatest, '平码推荐已排除最新一期 7 个已开号');
  const sp = store.recommendSpecial(6);
  ok(sp.length === 6, '特码推荐 6 个 → ' + sp.map(T.pad).join(' '));
  let spNoLatest = true;
  for (const v of sp) if (latest7.indexOf(v) >= 0) spNoLatest = false;
  ok(spNoLatest, '特码推荐已排除最新一期 7 个已开号');

  /* 6. 期号推进 */
  const target = store.nextIssueOf(store.draws[0].issue);
  ok(/^\d{7}$/.test(target), 'nextIssueOf(' + store.draws[0].issue + ') = ' + target);
  ok(store.nextIssueOf('2026365') === '2027001', '年末进位 2026365 → 2027001 (实得 ' + store.nextIssueOf('2026365') + ')');

  /* 5b. 在线预测：显式拉一次（幂等），避免与启动同步的时序竞态 */
  await store.fetchPrediction();
  const onlineKill = store.onlineKillZodiacs(), rec6 = store.onlineRecommendZodiacs();
  if (!store.prediction) {
    /* 接口只保留「未结算(result=0)」条目；开奖刚结束到下一期预测发布之间会出现空档，属数据时点现象 */
    console.log('SKIP  在线预测接口当前无「未结算」条目（开奖间歇期），跳过在线八肖 / 精选码断言');
  } else {
    ok(onlineKill.length === 4 && rec6.length === 6 && store.prediction.zodiac8.length === 8,
      '在线八肖中特：推荐六肖 ' + rec6.join('') + ' / 八肖 ' + store.prediction.zodiac8.join('') + ' / 杀 ' + onlineKill.join('') + '（12-8=4）');
    ok(store.prediction.issue === target, '在线预测期号 = 目标期 ' + target + '（实得 ' + store.prediction.issue + '）');
    ok(store.prediction.codes1.length === 1 && store.prediction.codes5.length === 5 && store.prediction.codes10.length === 10,
      '在线精选码 1/5/10 码数量正确 → ' + store.prediction.codes10.map(T.pad).join(' '));
  }

  /* 5c. 18码中特图：真实接口 → alias=18ma 筛选 / 期号最大 / 图片可渲染 */
  try {
    const got = await T.loadPic18(true);
    ok(!!got && !!T.pic18.url, '18码中特图接口取到图 → ' + T.pic18.url);
    ok(/\/col\/\d+\/18ma\.jpg$/.test(T.pic18.url) && !!T.pic18.issue,
      '图地址形如 /col/<期>/18ma.jpg（期号 ' + T.pic18.issue + '）');
    const picH = T.pic18Html();
    ok(picH.indexOf('<img class="pic"') >= 0 && picH.indexOf('18码中特图') >= 0,
      '18码中特图卡片渲染出 <img> 标签（' + picH.length + ' 字节）');
    const cardH = T.pic18CardHtml();
    ok(cardH.indexOf('pic18wrap') >= 0 && cardH.indexOf('picToggle()') >= 0, '18码中特图卡片可折叠（pic18wrap + 切换按钮）');
  } catch (e) {
    ok(false, '18码中特图测试异常: ' + (e && e.stack || e));
  }

  /* 7. 推荐历史存档 / 判定 */
  const fl = [].concat.apply([], flat);
  store.saveRecoSnapshot(target, fl, sp);
  const pickRec = () => store.recoHistory.filter(h => h.issue === target)[0];
  const rec = pickRec();
  ok(!!rec && rec.flats.length === fl.length && rec.specials.length === sp.length,
    '推荐存档写入成功（目标第' + target + '期，平码 ' + fl.length + ' 个 + 特码 ' + sp.length + ' 个，当前留档 ' + store.recoHistory.length + ' 期）');
  const judgedBefore = rec ? rec.judged : -1;
  store.judgeRecoHistory();
  const rec2 = pickRec();
  const drewTarget = store.draws.some(d => d.issue === target);
  if (drewTarget) {
    /* 测试执行期间后台同步恰好开出目标期（开奖时段跨零点常见）：此时回写判定才是正确行为 */
    ok(!!rec2 && rec2.judged === 1,
      '目标期 ' + target + ' 已被后台同步开出 → 正确回写为已判定（数据时点现象，judged=' + (rec2 && rec2.judged) + '）');
  } else {
    ok(judgedBefore === 0 && !!rec2 && rec2.judged === 0,
      '目标期未开奖 → 保持“待开奖”，不误判（judged ' + judgedBefore + ' → ' + (rec2 && rec2.judged) + '）');
  }


  /* 7. 走势接口（POST + CORS 预检）；Node 侧偶发 fetch failed，重试 2 次以排除网络抖动 */
  const trendPost = (path) => {
    const one = (left) => fetch('https://trend.macaumarksix.com/' + path + '?time=' + Date.now(), {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ type: 2, count: 10 })
    }).then(r => r.json()).catch(e => {
      if (left <= 0) throw e;
      return new Promise(res => setTimeout(res, 800)).then(() => one(left - 1));
    });
    return one(2);
  };
  return Promise.all([trendPost('countCode'), trendPost('countTail'), trendPost('countZodiac'), trendPost('countWave')]).then(rs => {
    ok(rs[0].code === 200 && Object.keys(rs[0].data).length > 0, '走势 /countCode 返回 ' + Object.keys(rs[0].data).length + ' 个号码频次');
    ok(Object.keys(rs[1].data).length === 10, '走势 /countTail 返回 0-9 全部尾数');
    const zd = rs[2].data;
    const zok = T.ZODIAC.some(z => zd[z] !== undefined) || zd['馬'] !== undefined;
    ok(zok, '走势 /countZodiac 键可用（简/繁任一命中）→ 样本键 ' + Object.keys(zd).slice(0, 4).join(','));
    ok(rs[3].data.red !== undefined && rs[3].data.green !== undefined && rs[3].data.blue !== undefined, '走势 /countWave 含 red/green/blue');

    /* 8. 各页面渲染无异常且含关键内容 */
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
      const html2 = fn();
      const miss2 = need.filter(s => html2.indexOf(s) < 0);
      ok(html2.length > 200 && miss2.length === 0, name + ' 页面渲染 ' + html2.length + ' 字节' + (miss2.length ? '，缺失: ' + miss2.join('/') : ''));
    }
    /* 主页表格行中有号码球 */
    const h = T.homeHtml();
    ok((h.match(/class="ball"/g) || []).length > 200, '主页号码球渲染 ' + (h.match(/class="ball"/g) || []).length + ' 个（150期×7球）');
    ok(h.indexOf('★ 爆冷开出') >= 0, '主页历史爆冷标记存在（v2.7 口径）');

    console.log('\n==== 结果：' + PASS + ' 通过 / ' + FAIL + ' 失败 ====');
    process.exit(FAIL === 0 ? 0 : 2);
  });
}).catch(e => { console.log('FAIL  测试异常: ' + (e && e.stack || e)); process.exit(3); });