/* ============================================================
   make_boot.js —— 抓取最近 150 期真实开奖，注入 index.html 的内嵌数据槽
   用法：node make_boot.js            （就地更新 index.html）
        node make_boot.js --dry      （只打印，不写文件）
   幂等：重复执行只替换 var BOOT_DATA='...'; 这一行，其余内容不动。
   ============================================================ */
const fs = require('fs');
const DIR = '/sdcard/Download/solab/XinAomen/ios';
const HTML = DIR + '/index.html';
const DRY = process.argv.indexOf('--dry') >= 0;

const UA = 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36';
const simp = z => ({ '龍': '龙', '馬': '马', '雞': '鸡', '豬': '猪' }[z] || z);

function get(url) {
  return fetch(url, { headers: { 'Accept': 'application/json', 'User-Agent': UA } })
    .then(r => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); });
}

(async () => {
  let arr = [];
  const year = new Date().getFullYear();
  // 历史按年接口（含最新期，降序）；当前年不够 150 期时再取「当前年-30」
  for (const y of [year, year - 30]) {
    try {
      const j = await get('https://history.macaumarksix.com/history/macaujc2/y/' + y);
      if (j && j.data && j.data.length) arr = arr.concat(j.data);
    } catch (e) { console.log('年份', y, '失败', e.message); }
    if (arr.length >= 150) break;
  }
  // live 兜底最新一期（历史接口偶发延迟时补齐）
  try {
    const l = await get('https://macaumarksix.com/api/live2');
    if (Array.isArray(l) && l.length) arr = [l[0]].concat(arr);
  } catch (e) { console.log('live2 失败', e.message); }

  // 去重 + 按期号降序 + 取 150
  const seen = new Set(), list = [];
  for (const o of arr) {
    if (!o || !o.expect || seen.has(o.expect)) continue;
    seen.add(o.expect); list.push(o);
  }
  list.sort((a, b) => (a.expect < b.expect ? 1 : a.expect > b.expect ? -1 : 0));
  const keep = list.slice(0, 150);

  // 紧凑格式：期号|开奖时间(到分)|波色(r/g/b)|号码(补零2位)|生肖（简体） 用 ; 分隔
  const recs = keep.map(o => {
    const nums = String(o.openCode || '').split(',')
      .map(s => String(parseInt(s, 10) || 0).padStart(2, '0')).join(',');
    const zs = String(o.zodiac || '').split(',').map(s => simp(s.trim())).join(',');
    const wave = String(o.wave || '').split(',')
      .map(s => { const t = s.trim().toLowerCase(); return t[0] || '-'; }).join('');
    return [o.expect, String(o.openTime || '').replace(/:\d\d$/, ''), wave, nums, zs].join('|');
  });
  const boot = recs.join(';');

  console.log('期数:', keep.length);
  console.log('最新:', keep[0].expect, keep[0].openCode, keep[0].zodiac);
  console.log('最旧:', keep[keep.length - 1].expect);
  console.log('数据长度:', boot.length, '字符');

  // 连续性自检
  let gap = 0;
  for (let i = 1; i < keep.length; i++) {
    if (parseInt(keep[i - 1].expect, 10) - parseInt(keep[i].expect, 10) !== 1) gap++;
  }
  console.log('期号不连续处:', gap);

  if (keep.length < 100) { console.log('✗ 数据不足 100 期，放弃写入'); process.exit(1); }

  // 注入 index.html
  const html = fs.readFileSync(HTML, 'utf8');
  const re = /var BOOT_DATA='[^']*';/;
  if (!re.test(html)) { console.log('✗ index.html 中未找到 BOOT_DATA 槽位'); process.exit(1); }
  const out = html.replace(re, "var BOOT_DATA='" + boot + "';");
  if (DRY) {
    console.log('[dry] 将替换', html.length, '->', out.length, '字节');
  } else {
    fs.writeFileSync(HTML, out);
    console.log('已注入', HTML, '（' + out.length + ' 字节）');
  }
})();
