// Missing Guard 화면 (해시 라우팅 SPA)
// #/login → #/home → #/scan (검사하러 가기) / #/logs (검사 로그) → #/logs/<실행ID>
'use strict';

const $ = (sel, el = document) => el.querySelector(sel);
const $$ = (sel, el = document) => [...el.querySelectorAll(sel)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const ICON = {
  shield: '<svg viewBox="0 0 64 64" fill="none"><path d="M32 9l19 7.5v13.2C51 42.6 42.8 52 32 55 21.2 52 13 42.6 13 29.7V16.5z" stroke="#3ddc97" stroke-width="3.5" stroke-linejoin="round"/><path d="M23 33l6.5 6.5L42 26" stroke="#8af2c6" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  scan: '<svg viewBox="0 0 24 24" fill="none" stroke="#8af2c6" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7V5a1 1 0 0 1 1-1h2M17 4h2a1 1 0 0 1 1 1v2M20 17v2a1 1 0 0 1-1 1h-2M7 20H5a1 1 0 0 1-1-1v-2"/><path d="M8 9h8M8 12h5M8 15h7"/></svg>',
  log: '<svg viewBox="0 0 24 24" fill="none" stroke="#8af2c6" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="3.5" width="16" height="17" rx="2"/><path d="M8 8h8M8 12h8M8 16h5"/></svg>',
  logout: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 17l-5-5 5-5M5 12h11"/></svg>',
  rain: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M6 4v6M12 3v9M18 5v5M6 14v2M12 16v5M18 14v4"/></svg>',
  arrow: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M13 6l6 6-6 6"/></svg>',
};

const STATUS = {
  missing: ['c-missing', '누락 의심'],
  unsure: ['c-unsure', '불확실'],
  ok: ['c-ok', '검사 있음'],
  nomatch: ['c-nomatch', '짝 없는 API'],
  nopair: ['c-nopair', 'API 없는 정책'],
};
const ORDER = { missing: 0, unsure: 1, ok: 2, nomatch: 3, nopair: 4 };
const JUDGE = { present: ['c-ok', '있음'], absent: ['c-missing', '없음'], uncertain: ['c-unsure', '불확실'] };

const S = {
  session: null,
  status: null,
  runs: [],
  scan: { path: '', data: null, selected: new Set(), policyFile: '', policyText: '', error: '' },
  run: { id: null, active: false, lines: [], fileStates: {}, pct: 0, done: null, error: '' },
  detail: { id: null, snap: null, tab: 'result', codeFile: null, hl: null, extra: null, openGroups: null, openCards: null },
};

// ───────── 공통 ─────────
class AuthError extends Error {}

async function api(method, path, body) {
  const opt = { method, headers: {}, credentials: 'same-origin' };
  if (method !== 'GET') {
    opt.headers['Content-Type'] = 'application/json';
    opt.headers['X-MG'] = '1';
    opt.body = JSON.stringify(body || {});
  }
  const res = await fetch(path, opt);
  const type = res.headers.get('content-type') || '';
  const data = type.includes('json') ? await res.json() : await res.text();
  if (res.status === 401 && path !== '/api/login') {
    S.session = null;
    if (location.hash !== '#/login') location.hash = '#/login';
    throw new AuthError('로그인이 필요합니다.');
  }
  if (!res.ok) throw new Error((data && data.error) || `요청 실패 (${res.status})`);
  return data;
}

function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toast.t);
  toast.t = setTimeout(() => t.classList.remove('show'), 1800);
}

function fmtTime(iso) {
  if (!iso) return '';
  return String(iso).replace('T', ' ').slice(0, 16);
}

function idTime(id) {
  const m = String(id).match(/^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})/);
  return m ? `${m[1]}-${m[2]}-${m[3]} ${m[4]}:${m[5]}` : id;
}

function baseName(p) {
  const parts = String(p || '').split(/[\\/]/).filter(Boolean);
  return parts[parts.length - 1] || p;
}

function now() {
  return new Date().toTimeString().slice(0, 8);
}

async function copy(text) {
  try {
    await navigator.clipboard.writeText(text);
    toast('복사했습니다');
  } catch (e) {
    toast('복사가 막혔습니다. 직접 선택해서 복사하세요.');
  }
}

// ───────── 배경 (옅은 매트릭스) ─────────
const Rain = {
  on: true,
  timer: null,
  init() {
    let saved = null;
    try { saved = localStorage.getItem('mg-rain'); } catch (e) { saved = null; }
    const reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    this.on = saved ? saved === 'on' : !reduce;
    this.canvas = $('#rain');
    this.ctx = this.canvas.getContext('2d');
    window.addEventListener('resize', () => this.resize());
    document.addEventListener('visibilitychange', () => this.apply());
    this.resize();
    this.apply();
  },
  resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    this.w = window.innerWidth;
    this.h = window.innerHeight;
    this.canvas.width = this.w * dpr;
    this.canvas.height = this.h * dpr;
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.size = 15;
    const cols = Math.ceil(this.w / (this.size * 1.25));
    this.drops = Array.from({ length: cols }, () => Math.random() * -60);
    this.speed = Array.from({ length: cols }, () => 0.35 + Math.random() * 0.55);
    this.ctx.fillStyle = '#040a07';
    this.ctx.fillRect(0, 0, this.w, this.h);
  },
  chars: '0123456789ABCDEF{}[]<>=/;:#アイウエオカキクケコサシスセソタチツテトナニヌネノ',
  step() {
    const c = this.ctx;
    c.fillStyle = 'rgba(4, 10, 7, 0.10)';
    c.fillRect(0, 0, this.w, this.h);
    c.font = `${this.size}px Consolas, monospace`;
    for (let i = 0; i < this.drops.length; i++) {
      const y = this.drops[i] * this.size;
      if (y > 0) {
        const ch = this.chars[(Math.random() * this.chars.length) | 0];
        const head = Math.random() < 0.06;
        c.fillStyle = head ? 'rgba(170, 255, 215, 0.85)' : `rgba(61, 220, 151, ${0.25 + Math.random() * 0.35})`;
        c.fillText(ch, i * this.size * 1.25, y);
      }
      this.drops[i] += this.speed[i];
      if (y > this.h && Math.random() > 0.975) this.drops[i] = Math.random() * -20;
    }
  },
  apply() {
    clearInterval(this.timer);
    this.timer = null;
    if (this.on && !document.hidden) {
      this.canvas.style.display = '';
      this.timer = setInterval(() => this.step(), 75);
    } else if (!this.on) {
      this.canvas.style.display = 'none';
    }
  },
  toggle() {
    this.on = !this.on;
    try { localStorage.setItem('mg-rain', this.on ? 'on' : 'off'); } catch (e) { /* 저장 불가 환경 */ }
    this.apply();
    toast(this.on ? '배경 효과 켬' : '배경 효과 끔');
  },
};

// ───────── 라우터 ─────────
function route() {
  const h = location.hash || '#/home';
  const m = h.match(/^#\/logs\/([0-9A-Za-z-]+)$/);
  if (m) return { name: 'detail', id: m[1] };
  if (h === '#/login') return { name: 'login' };
  if (h === '#/scan') return { name: 'scan' };
  if (h === '#/logs') return { name: 'logs' };
  return { name: 'home' };
}

async function render() {
  const r = route();
  if (!S.session) {
    try {
      const s = await api('GET', '/api/session');
      S.session = s.loggedIn ? s : null;
    } catch (e) {
      S.session = null;
    }
  }
  if (!S.session && r.name !== 'login') {
    location.hash = '#/login';
    return;
  }
  if (S.session && r.name === 'login') {
    location.hash = '#/home';
    return;
  }
  if (S.session && !S.status) {
    try { S.status = await api('GET', '/api/status'); } catch (e) { /* 아래에서 다시 시도 */ }
  }
  window.scrollTo(0, 0);
  if (r.name === 'login') return viewLogin();
  if (r.name === 'home') return viewHome();
  if (r.name === 'scan') return viewScan();
  if (r.name === 'logs') return viewLogs();
  if (r.name === 'detail') return viewDetail(r.id);
}

function shell(active, content) {
  const st = S.status || {};
  const dot = st.provider === 'mock' ? 'mock' : st.keyConfigured ? 'on' : 'off';
  $('#app').innerHTML = `
    <header class="topbar">
      <a class="brand" href="#/home">${ICON.shield}<span>Missing Guard</span><span class="sub">v1.0</span></a>
      <nav class="nav">
        <a data-k="1" href="#/home" class="${active === 'home' ? 'active' : ''}">홈</a>
        <a data-k="2" href="#/scan" class="${active === 'scan' ? 'active' : ''}">검사하기</a>
        <a data-k="3" href="#/logs" class="${active === 'logs' ? 'active' : ''}">검사 로그</a>
      </nav>
      <div class="top-right">
        <span class="model-chip" title="${esc(st.model)}"><span class="dot ${dot}"></span>${esc(st.model || '모델 확인 중')}</span>
        <span class="user">${esc(S.session?.user)}</span>
        <button class="icon-btn" data-act="rain" title="배경 효과 켜기/끄기">${ICON.rain}</button>
        <button class="icon-btn" data-act="logout" title="로그아웃">${ICON.logout}</button>
      </div>
    </header>
    ${st.keyConfigured === false ? '<div class="page" style="padding-bottom:0"><div class="box warn">API 키가 없습니다. mg-checker 폴더의 <span class="mono">mg.properties</span>에 <span class="mono">api.key=</span>를 넣고 MG를 다시 실행하세요. 키 없이 화면만 보려면 <span class="mono">provider=mock</span>.</div></div>' : ''}
    ${st.provider === 'mock' ? '<div class="page" style="padding-bottom:0"><div class="box warn">mock 모드입니다. 실제 LLM이 아니라 키워드로 흉내 낸 결과라서 화면 확인용으로만 쓰세요.</div></div>' : ''}
    <main class="page">${content}</main>`;
}

// ───────── 로그인 ─────────
function viewLogin() {
  $('#app').innerHTML = `
    <div class="login-wrap">
      <form class="login-card panel" id="loginForm" autocomplete="off">
        <div class="login-logo">${ICON.shield}</div>
        <h1>Missing Guard</h1>
        <p class="tag">배포 전 권한 검사 누락 점검기</p>
        <div class="term">$ mg auth --local <span class="cursor"></span></div>
        <div class="field"><label for="user">아이디</label><input id="user" value="admin" spellcheck="false"></div>
        <div class="field"><label for="pw">비밀번호</label><input id="pw" type="password" autofocus></div>
        <div id="loginErr"></div>
        <button class="btn primary" type="submit">로그인</button>
      </form>
      <div class="login-foot">127.0.0.1 · local only · session cookie</div>
    </div>`;
  $('#pw').focus();
  $('#loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    $('#loginErr').innerHTML = '';
    try {
      const r = await api('POST', '/api/login', { user: $('#user').value.trim(), password: $('#pw').value });
      S.session = r;
      S.status = null;
      location.hash = '#/home';
    } catch (err) {
      $('#loginErr').innerHTML = `<div class="box err" style="margin-bottom:10px">${esc(err.message)}</div>`;
    }
  });
}

// ───────── 메인 ─────────
async function loadRuns() {
  try {
    S.runs = (await api('GET', '/api/runs')).runs || [];
  } catch (e) {
    if (e instanceof AuthError) throw e;
    S.runs = [];
  }
}

function asciiBar(r, n = 16) {
  const k = Math.max(0, Math.min(n, Math.round(r * n)));
  return '[' + '█'.repeat(k) + `<span class="off">${'░'.repeat(n - k)}</span>` + ']';
}

// 개발자 확인 진행률은 "가장 최근 검사" 기준 (예전 검사의 미확인 카드는 섞지 않음)
function reviewOf(run) {
  if (!run) return { need: 0, done: 0 };
  return { need: run.reviewNeed || 0, done: (run.reviewOk || 0) + (run.reviewFp || 0) };
}

async function viewHome() {
  await loadRuns();
  const last = S.runs[0];
  const rv = reviewOf(last);
  const recent = S.runs.slice(0, 3);
  shell('home', `
    <section class="hero">
      <div>
        <div class="eyebrow">대시보드</div>
        <h1><span class="who">${esc(S.session.user)}@mg</span><span class="dim">:~$</span> <span class="cmd">status</span><span class="cursor-inline">_</span></h1>
        <p>권한 정책 문서와 컨트롤러 코드를 대조해, 서버가 빠뜨린 검사를 배포 전에 찾습니다.</p>
      </div>
    </section>

    <section class="actions">
      <div class="action panel">
        <h2>검사하러 가기 <span class="key">mg check</span></h2>
        <p>프로젝트 폴더를 고르면 컨트롤러와 권한 정책 문서를 찾아, LLM이 정책마다 서버 측 검사가 있는지 근거 줄과 함께 판단합니다.</p>
        <div class="slot">
          <div class="slot-k">마지막으로 검사한 폴더</div>
          ${last ? `<div class="slot-v"><b>${esc(baseName(last.root))}</b><span class="mono dim">${esc(last.root)}</span></div>
            <div class="slot-v small dim">컨트롤러 ${(last.files || []).length}개 · API ${last.endpointCount ?? '-'}개 · ${esc(idTime(last.id))}</div>`
          : '<div class="slot-v dim">아직 검사한 폴더가 없습니다.</div>'}
        </div>
        <div class="row cta">
          <a class="btn primary" href="#/scan">새 검사</a>
          ${last ? `<button class="btn" data-act="rescan" data-root="${esc(last.root)}">같은 폴더 다시 검사</button>` : ''}
        </div>
      </div>
      <div class="action panel">
        <h2>검사 로그 보기 <span class="key">mg log</span></h2>
        <div class="mini-stats">
          <div><span class="k">검사 횟수</span><span class="v">${S.runs.length}<small> 회</small></span></div>
          <div><span class="k">최근 검사 확인</span><span class="v">${rv.done}<small> / ${rv.need}</small></span><span class="bar-ascii small">${asciiBar(rv.need ? rv.done / rv.need : 0, 14)}</span></div>
        </div>
        <div class="slot">
          <div class="slot-k">최근 검사</div>
          ${recent.length ? `<div class="mini-runs">${recent.map((r) => {
            const c = r.counts || {};
            const v = reviewOf(r);
            return `<a class="mini-run" href="#/logs/${esc(r.id)}"><span class="mono dim">${esc(idTime(r.id).slice(5))}</span><b title="${esc(r.root)}">${esc(baseName(r.root))}</b>
              <span class="chips"><span class="chip c-missing">누락 ${c.missing ?? 0}</span><span class="chip c-unsure">불확실 ${c.unsure ?? 0}</span><span class="chip c-ok">있음 ${c.ok ?? 0}</span></span>
              <span class="rv" title="개발자 확인">${v.done}/${v.need}</span></a>`;
          }).join('')}</div>` : '<div class="slot-v dim">아직 기록이 없습니다.</div>'}
        </div>
        <div class="row cta"><a class="btn" href="#/logs">전체 로그 열기</a></div>
      </div>
    </section>

    <section class="howto panel" id="howto">
      <button class="howto-bar" data-act="howto" aria-expanded="false">
        <span class="tw">▸</span><b>동작 방식</b>
        <span class="howto-sum"><span class="chip c-rule">규칙</span> → <span class="chip c-llm">LLM</span> → <span class="chip c-rule">규칙</span> → <span class="chip c-human">사람</span></span>
        <span class="howto-btn">[ 펼치기 ]</span>
      </button>
      <div class="howto-body"><div class="pipeline">
        <div class="pipe"><span class="n">01</span><b>대상 선별</b><span class="d"><span class="chip c-rule">규칙</span> 컨트롤러·정책 문서만, API 목록과 줄 범위</span></div>
        <div class="pipe"><span class="n">02</span><b>의미 판단</b><span class="d"><span class="chip c-llm">LLM</span> 조건 분해, API 짝짓기, 있음/없음/불확실 + 근거 줄</span></div>
        <div class="pipe"><span class="n">03</span><b>결과 검증</b><span class="d"><span class="chip c-rule">규칙</span> 형식 검사, 근거 없는 "있음" 강등, 지어낸 API 제거</span></div>
        <div class="pipe"><span class="n">04</span><b>최종 확인</b><span class="d"><span class="chip c-human">사람</span> "검사 있음"까지 근거 줄로 확인하고 수정</span></div>
      </div></div>
    </section>`);
}

// ───────── 검사하기 ─────────
function viewScan() {
  const sc = S.scan;
  if (!sc.path && S.status?.suggestedPath) sc.path = S.status.suggestedPath;
  const d = sc.data;
  const epTotal = d ? d.controllers.reduce((n, c) => n + c.endpoints.length, 0) : 0;
  const policyCount = countPolicies(sc.policyText);
  shell('scan', `
    <div class="page-head">
      <div class="crumb"><a href="#/home">홈</a> / 검사하기</div>
      <div class="eyebrow">새 검사</div>
      <h1>새 검사</h1>
      <p>컨트롤러는 파일마다 따로 LLM에 보내고, 서비스·설정·테스트 코드는 보내지 않습니다.</p>
    </div>
    <div class="steps">
      <section class="step panel">
        <div class="step-head"><span class="step-num">01</span><h2>프로젝트 폴더</h2><span class="muted">Spring 프로젝트의 최상위 폴더</span></div>
        <div class="row">
          <div class="prompt-input grow"><span>~$</span><input id="path" value="${esc(sc.path)}" placeholder="C:\\Users\\me\\Desktop\\MG\\demo-ticket" spellcheck="false"></div>
          <button class="btn primary" data-act="scan">스캔</button>
        </div>
        ${sc.error ? `<div class="box err" style="margin-top:12px">${esc(sc.error)}</div>` : ''}
        ${d ? `<p class="muted small" style="margin:12px 0 0"><span class="chip c-rule">규칙</span> 파일 ${d.visitedFiles}개 중 컨트롤러 ${d.controllers.length}개, API ${epTotal}개, 정책 문서 ${d.policyFiles.length}개를 찾았습니다.${d.truncated ? ' 파일이 너무 많아 일부만 봤습니다.' : ''}</p>` : ''}
      </section>

      <div class="two ${d ? '' : 'hidden'}">
        <section class="step panel">
          <div class="step-head"><span class="step-num">02</span><h2>점검할 컨트롤러</h2><span class="muted">${sc.selected.size}개 선택</span></div>
          <div class="row" style="margin-bottom:10px"><button class="btn sm" data-act="selAll">전체 선택</button><button class="btn sm" data-act="selNone">선택 해제</button></div>
          <div class="ctrl-list">${d ? (d.controllers.length ? d.controllers.map((c) => `
            <div class="ctrl">
              <label><input type="checkbox" data-ctrl="${esc(c.path)}" ${sc.selected.has(c.path) ? 'checked' : ''}>
                <span><span class="path">${esc(c.path)}</span><br><span class="dim small">API ${c.endpoints.length}개 · ${c.lineCount}줄</span></span></label>
              <details><summary>API 목록</summary><div class="eps">${c.endpoints.map((e) => `${esc(e.key)} <span class="dim">L${e.start}-${e.end}</span>`).join('<br>') || '없음'}</div></details>
            </div>`).join('') : '<div class="box info">컨트롤러를 찾지 못했습니다. 프로젝트 최상위(또는 src) 폴더인지 확인하세요.</div>') : ''}</div>
        </section>
        <section class="step panel">
          <div class="step-head"><span class="step-num">03</span><h2>권한 정책</h2><span class="muted">${policyCount ? `정책 ${policyCount}개` : '비어 있음'}</span></div>
          <select id="policyFile" style="width:100%;margin-bottom:10px">
            ${d ? d.policyFiles.map((p) => `<option value="${esc(p)}" ${p === sc.policyFile ? 'selected' : ''}>${esc(p)}</option>`).join('') : ''}
            <option value="" ${sc.policyFile ? '' : 'selected'}>직접 입력</option>
          </select>
          <textarea id="policyText" rows="11" spellcheck="false" placeholder="한 줄에 정책 하나&#10;P1. 예매 내역 상세는 예매한 본인만 조회할 수 있다.">${esc(sc.policyText)}</textarea>
        </section>
      </div>

      <section class="step panel ${d ? '' : 'hidden'}">
        <div class="step-head"><span class="step-num">04</span><h2>실행</h2>
          <span class="legend" style="margin-left:auto"><span class="chip c-rule">규칙</span><span class="chip c-llm">LLM</span><span class="chip c-human">사람</span></span></div>
        <div class="row">
          <button class="btn primary" data-act="run" ${canRun() ? '' : 'disabled'}>검사 시작</button>
          <span class="muted small">${S.run.active ? '검사 중입니다…' : `컨트롤러 ${sc.selected.size}개 · 정책 ${policyCount}개`}</span>
          <span class="grow"></span>
          ${S.run.done ? `<a class="btn" href="#/logs/${esc(S.run.id)}">결과 자세히 보기 ${ICON.arrow}</a>` : ''}
        </div>
        <div class="bar"><div id="runBar" style="width:${S.run.pct}%"></div></div>
        <div class="console" id="console">${consoleHtml()}</div>
        ${S.run.error ? `<div class="box err" style="margin-top:12px">${esc(S.run.error)}</div>` : ''}
      </section>
    </div>`);
  const c = $('#console');
  if (c) c.scrollTop = c.scrollHeight;
  if (sc.autoScan) {
    sc.autoScan = false;
    doScan();
  }
}

function countPolicies(t) {
  return String(t || '').split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#') && !l.startsWith('>')).length;
}

function canRun() {
  return S.scan.data && S.scan.selected.size > 0 && countPolicies(S.scan.policyText) > 0 && !S.run.active;
}

function consoleHtml() {
  if (!S.run.lines.length) return '<span class="dim">$ mg check — 대기 중</span>';
  return S.run.lines.map((l) => `<div><span class="t">${l.t}</span><span class="tag ${l.k}">${l.tag}</span>${esc(l.msg)}</div>`).join('');
}

function log(k, tag, msg) {
  S.run.lines.push({ t: now(), k, tag, msg });
  const c = $('#console');
  if (c) {
    c.innerHTML = consoleHtml();
    c.scrollTop = c.scrollHeight;
  }
}

async function doScan() {
  const sc = S.scan;
  sc.path = $('#path').value.trim().replace(/^"(.*)"$/, '$1'); // 탐색기 '경로로 복사'의 따옴표 제거
  sc.error = '';
  try {
    const d = await api('POST', '/api/scan', { path: sc.path });
    sc.data = d;
    sc.path = d.root;
    sc.selected = new Set(d.controllers.map((c) => c.path));
    sc.policyFile = d.policyFiles[0] || '';
    sc.policyText = '';
    if (sc.policyFile) {
      const p = await api('POST', '/api/policy', { path: d.root, file: sc.policyFile });
      sc.policyText = p.text;
    }
  } catch (e) {
    if (e instanceof AuthError) return;
    sc.data = null;
    sc.error = e.message;
  }
  viewScan();
}

async function doRun() {
  const sc = S.scan;
  sc.policyText = $('#policyText').value;
  const files = [...sc.selected];
  const epTotal = sc.data.controllers.filter((c) => sc.selected.has(c.path)).reduce((n, c) => n + c.endpoints.length, 0);
  S.run = { id: null, active: true, lines: [], fileStates: {}, pct: 0, done: null, error: '' };
  viewScan();
  log('sys', 'sys', `검사 시작 · ${S.status?.model || ''}`);
  log('rule', 'rule', `대상 컨트롤러 ${files.length}개, API ${epTotal}개, 정책 ${countPolicies(sc.policyText)}개`);
  try {
    const r = await api('POST', '/api/check', { path: sc.path, files, policyText: sc.policyText });
    S.run.id = r.id;
    log('sys', 'sys', `실행 ID ${r.id} · 기록은 mg-checker/runs/${r.id}/`);
    poll();
  } catch (e) {
    S.run.active = false;
    if (e instanceof AuthError) return;
    S.run.error = e.message;
    log('err', 'error', e.message);
    viewScan();
  }
}

function poll() {
  setTimeout(async () => {
    let s;
    try {
      s = await api('GET', `/api/runs/${S.run.id}`);
    } catch (e) {
      S.run.active = false;
      if (!(e instanceof AuthError)) {
        S.run.error = e.message;
        if (route().name === 'scan') viewScan();
      }
      return;
    }
    for (const f of s.files) {
      const prev = S.run.fileStates[f.path];
      if (prev === f.state) continue;
      S.run.fileStates[f.path] = f.state;
      const short = f.path.split('/').slice(-2).join('/');
      if (f.state === 'running') log('llm', 'llm', `${short} 판단 중…`);
      if (f.state === 'done') log('rule', 'rule', `${short} 검증 완료 · ${f.message}`);
      if (f.state === 'error') log('err', 'error', `${short} · ${f.message}`);
      if (f.state === 'skipped') log('sys', 'skip', `${short} · ${f.message}`);
    }
    S.run.pct = s.total ? Math.round((s.done / s.total) * 100) : 0;
    const bar = $('#runBar');
    if (bar) bar.style.width = S.run.pct + '%';
    if (s.status === 'done' || s.status === 'error') {
      S.run.active = false;
      if (s.result) {
        const c = s.result.counts;
        S.run.done = s.id;
        if (s.result.downgrades) log('rule', 'rule', `근거 줄 없는 "있음" ${s.result.downgrades}건을 "불확실"로 강등`);
        log('ok', 'done', `누락 의심 ${c.missing} · 불확실 ${c.unsure} · 검사 있음 ${c.ok} · 짝 없는 API ${c.nomatch}${c.nopair ? ` · API 없는 정책 ${c.nopair}` : ''}`);
      }
      if (s.error) {
        S.run.error = s.error;
        log('err', 'error', s.error);
      }
      S.runs = [];
      if (route().name === 'scan') viewScan();
      return;
    }
    poll();
  }, 700);
}

// ───────── 검사 로그 ─────────
async function viewLogs() {
  await loadRuns();
  const last = S.runs[0];
  const rv = reviewOf(last);
  shell('logs', `
    <div class="page-head">
      <div class="crumb"><a href="#/home">홈</a> / 검사 로그</div>
      <div class="eyebrow">검사 기록</div>
      <h1>검사 로그</h1>
      <p>실행마다 지시문, LLM 응답 원문, 결과, 개발자 확인이 <span class="mono">mg-checker/runs/</span>에 저장됩니다.</p>
    </div>
    <section class="stats">
      <div class="stat panel"><div class="k">검사 횟수</div><div class="v">${S.runs.length}<span class="muted small"> 회</span></div></div>
      <div class="stat panel"><div class="k">최근 검사 · 누락 의심</div><div class="v">${last ? (last.counts?.missing ?? 0) : '-'}<span class="muted small"> ${last ? esc(idTime(last.id)) : ''}</span></div></div>
      <div class="stat panel"><div class="k">최근 검사 확인</div><div class="v">${rv.done}<span class="muted small"> / ${rv.need}</span> <span class="bar-ascii small">${asciiBar(rv.need ? rv.done / rv.need : 0, 14)}</span></div></div>
    </section>
    <section class="panel">
      ${S.runs.length ? `<table class="table">
        <thead><tr><th>시각</th><th>프로젝트</th><th>대상</th><th>모델</th><th>결과</th><th>개발자 확인</th></tr></thead>
        <tbody>${S.runs.map((r) => {
          const c = r.counts || {};
          const done = (r.reviewOk || 0) + (r.reviewFp || 0);
          const pct = r.reviewNeed ? Math.round((done / r.reviewNeed) * 100) : 0;
          return `<tr data-run="${esc(r.id)}">
            <td class="mono small">${esc(idTime(r.id))}</td>
            <td><b>${esc(baseName(r.root))}</b><br><span class="dim small mono">${esc(r.root)}</span></td>
            <td class="small" title="${esc((r.files || []).join('\n'))}">컨트롤러 ${(r.files || []).length}개<br><span class="dim">API ${r.endpointCount ?? '-'}개</span></td>
            <td class="mono small muted">${esc(r.model)}</td>
            <td><div class="row" style="gap:6px"><span class="chip c-missing">누락 ${c.missing ?? 0}</span><span class="chip c-unsure">불확실 ${c.unsure ?? 0}</span><span class="chip c-ok">있음 ${c.ok ?? 0}</span></div></td>
            <td><div class="review"><div class="track"><div style="width:${pct}%"></div></div><span class="mono small">${done}/${r.reviewNeed ?? 0}</span></div></td>
          </tr>`;
        }).join('')}</tbody></table>`
        : `<div class="empty"><div class="big">$ ls runs/ — 비어 있음</div>아직 검사 기록이 없습니다. <a href="#/scan">첫 검사를 시작하세요.</a></div>`}
    </section>`);
}

// ───────── 로그 상세 ─────────
async function viewDetail(id) {
  const D = S.detail;
  if (D.id !== id || !D.snap) {
    try {
      D.snap = await api('GET', `/api/runs/${id}`);
    } catch (e) {
      if (e instanceof AuthError) return;
      shell('logs', `<div class="box err">${esc(e.message)}</div><p><a href="#/logs">← 검사 로그로</a></p>`);
      return;
    }
    D.id = id;
    D.openGroups = new Set(['missing', 'unsure']); // 처음엔 봐야 할 그룹만 펼침
    D.openCards = new Set(); // 카드는 전부 한 줄로 접힘
    D.tab = 'result';
    D.extra = null;
    D.hl = null;
    const files = (D.snap.result?.files || []).filter((f) => f.lines);
    D.codeFile = files.length ? files[0].path : null;
  }
  const r = D.snap.result;
  if (!r) {
    shell('logs', `<div class="box info">아직 진행 중인 검사입니다. 잠시 뒤 다시 열어 주세요.</div><p><a href="#/logs">← 검사 로그로</a></p>`);
    D.snap = null;
    return;
  }
  const c = r.counts;
  shell('logs', `
    <div class="crumb"><a href="#/home">홈</a> / <a href="#/logs">검사 로그</a> / ${esc(id)}</div>
    <div class="detail-head">
      <div><div class="eyebrow">검사 결과</div><h1 style="margin:6px 0 0;font-size:26px">검사 결과</h1></div>
      <div class="row"><a class="btn" href="#/scan">새 검사</a></div>
    </div>
    <section class="meta-grid">
      <div class="m panel"><div class="k">시각</div><div class="v mono">${esc(fmtTime(r.startedAt))}</div></div>
      <div class="m panel"><div class="k">프로젝트</div><div class="v" title="${esc(r.root)}">${esc(baseName(r.root))}</div></div>
      <div class="m panel"><div class="k">모델</div><div class="v mono small">${esc(r.model)}</div></div>
      <div class="m panel"><div class="k">대상</div><div class="v">컨트롤러 ${(r.files || []).length}개 · API ${r.endpointCount}개 · 정책 ${r.policies.length}개</div></div>
    </section>
    <section class="summary panel">
      <span class="chip c-missing">누락 의심 ${c.missing}</span><span class="chip c-unsure">불확실 ${c.unsure}</span><span class="chip c-ok">검사 있음 ${c.ok}</span>
      <span class="chip c-nomatch">짝 없는 API ${c.nomatch}</span>${c.nopair ? `<span class="chip c-nopair">API 없는 정책 ${c.nopair}</span>` : ''}
      ${r.downgrades ? `<span class="chip c-rule">강등 ${r.downgrades}</span>` : ''}${r.formatRetries ? `<span class="chip c-rule">형식 재요청 ${r.formatRetries}</span>` : ''}
      <span class="grow"></span><span class="mono small dim">${esc(id)}</span>
    </section>
    <div class="tabs">
      <button data-tab="result" class="${D.tab === 'result' ? 'on' : ''}">결과</button>
      <button data-tab="report" class="${D.tab === 'report' ? 'on' : ''}">보고서</button>
      <button data-tab="raw" class="${D.tab === 'raw' ? 'on' : ''}">LLM 원문 · 지시문</button>
    </div>
    <div id="tabBody"></div>`);
  renderTab();
}

function renderTab() {
  const D = S.detail;
  const body = $('#tabBody');
  if (!body) return;
  if (D.tab === 'result') {
    body.innerHTML = `<div class="split"><div id="cardsArea"></div><aside class="panel code-panel"><div class="code-tabs" id="codeTabs"></div><div class="code" id="code"></div></aside></div>`;
    renderCards();
    renderCode();
  } else if (D.tab === 'report') {
    body.innerHTML = '<div class="panel panel-pad"><span class="muted">불러오는 중…</span></div>';
    api('GET', `/api/runs/${D.id}/report`).then((text) => {
      D.extra = { report: text };
      body.innerHTML = `<div class="panel panel-pad"><div class="row"><b>report.md</b><span class="grow"></span><button class="btn sm" data-act="copyReport">복사</button></div><pre class="raw">${esc(text)}</pre></div>`;
    }).catch((e) => { body.innerHTML = `<div class="box err">${esc(e.message)}</div>`; });
  } else {
    body.innerHTML = '<div class="panel panel-pad"><span class="muted">불러오는 중…</span></div>';
    api('GET', `/api/runs/${D.id}/artifacts`).then((a) => {
      D.extra = { raw: a.raw, prompts: a.prompts };
      const part = (title, kind, list) => `<h3 style="margin:18px 0 8px;font-size:14px">${title}</h3>` + (list.length ? list.map((f, i) => `
        <details class="rawfile"><summary>${esc(f.name)}</summary><div class="row" style="margin-top:8px"><span class="grow"></span><button class="btn sm" data-copy="${kind}:${i}">복사</button></div><pre class="raw">${esc(kind === 'raw' ? prettyJson(f.text) : f.text)}</pre></details>`).join('') : '<p class="muted">없음</p>');
      body.innerHTML = `<div class="panel panel-pad"><p class="muted small" style="margin:0">LLM에 실제로 보낸 지시문과, 규칙 검증 전에 LLM이 돌려준 응답 그대로입니다. 결과 탭과 다른 부분은 규칙이 고친 것입니다(근거 없는 "있음" 강등, 없는 API 제거).<br>저장 위치 <span class="mono">${esc(a.dir)}</span></p>
        ${part('LLM 응답 원문 (raw)', 'raw', a.raw)}${part('보낸 지시문 (prompts)', 'prompts', a.prompts)}</div>`;
    }).catch((e) => { body.innerHTML = `<div class="box err">${esc(e.message)}</div>`; });
  }
}

// 응답이 JSON 한 덩어리면 보기 좋게 줄바꿈 (복사는 원문 그대로)
function prettyJson(text) {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch (e) {
    return text;
  }
}

function cardKey(c) {
  return `${c.policyId}|${c.file}|${c.endpoint}`;
}

function fileEntry(path) {
  return (S.detail.snap?.result?.files || []).find((f) => f.path === path);
}

function lineText(path, n) {
  const f = fileEntry(path);
  return f && f.lines ? (f.lines[n - 1] || '') : '';
}

const GROUPS = [
  ['missing', '누락 의심'],
  ['unsure', '불확실'],
  ['ok', '검사 있음'],
  ['nomatch', '짝 없는 API'],
  ['nopair', 'API 없는 정책'],
];
const REVIEW = ['missing', 'unsure', 'ok'];

function openKey(c) {
  return `${c.status}|${cardKey(c)}`;
}

function renderCards() {
  const D = S.detail;
  const r = D.snap.result;
  const v = D.snap.verdicts || {};
  const area = $('#cardsArea');
  const need = r.cards.filter((c) => REVIEW.includes(c.status));
  const ok = need.filter((c) => v[cardKey(c)] === 'ok').length;
  const fp = need.filter((c) => v[cardKey(c)] === 'fp').length;
  let h = `<div class="cards-tools"><button class="btn sm" data-act="expandAll">모두 펼치기</button><button class="btn sm" data-act="collapseAll">모두 접기</button>
    <span class="grow"></span><span class="small dim">그룹·카드 줄을 누르면 접고 펼칩니다</span></div>`;
  if (r.notes && r.notes.length) h += `<div class="box note" style="margin-bottom:12px"><b>규칙 처리 메모</b><br>${r.notes.map(esc).join('<br>')}</div>`;
  for (const [st, label] of GROUPS) {
    const list = r.cards.filter((c) => c.status === st)
      .sort((x, y) => String(x.file).localeCompare(String(y.file)) || (x.start || 0) - (y.start || 0));
    if (!list.length && st === 'nopair') continue;
    const open = D.openGroups.has(st);
    const left = REVIEW.includes(st) ? list.filter((c) => !v[cardKey(c)]).length : null;
    const [cls] = STATUS[st];
    h += `<section class="grp grp-${st} ${open ? 'open' : ''}">
      <button class="grp-head" data-group="${st}" aria-expanded="${open}">
        <span class="tw">${open ? '▾' : '▸'}</span><span class="chip ${cls}">${label}</span><b>${list.length}</b>
        <span class="grow"></span>
        ${left === null ? '<span class="dim small">확인 대상 아님</span>'
          : list.length === 0 ? '' : left ? `<span class="left">미확인 ${left}</span>` : '<span class="done">확인 완료</span>'}
      </button>
      <div class="grp-body">${list.length ? list.map((c) => cardHtml(c, v[cardKey(c)], D.openCards.has(openKey(c)))).join('') : '<p class="dim small" style="margin:6px 4px">없음</p>'}</div>
    </section>`;
  }
  h += `<p class="tally"><span class="chip c-human">사람</span> 확인 대상 ${need.length}건 (검사 있음 포함) · 맞음 ${ok} · 오탐/틀림 ${fp} · 미확인 ${need.length - ok - fp}</p>`;
  area.innerHTML = h;
}

function condSummary(c) {
  if (c.status === 'nomatch') return '적용 정책 없음';
  if (c.status === 'nopair') return '짝지은 API 없음';
  const n = c.conditions.length;
  const absent = c.conditions.filter((k) => k.final === 'absent').length;
  const unsure = c.conditions.filter((k) => k.final !== 'absent' && k.final !== 'present').length;
  if (absent) return `조건 ${n}개 중 ${absent}개 없음`;
  if (unsure) return `조건 ${n}개 중 ${unsure}개 불확실`;
  return `조건 ${n}개 모두 있음`;
}

function cardHtml(c, verdict, open) {
  const review = REVIEW.includes(c.status);
  const vb = !review ? '' : verdict === 'ok' ? '<span class="vb ok">맞음</span>' : verdict === 'fp' ? '<span class="vb fp">오탐</span>' : '<span class="vb todo">미확인</span>';
  const shortFile = c.file ? c.file.split('/').slice(-2).join('/').replace(/\.java$/, '') : '';
  let h = `<div class="card st-${esc(c.status)} ${open ? 'open' : ''}">
    <button class="top" data-card="${esc(openKey(c))}" aria-expanded="${open ? 'true' : 'false'}">
      <span class="tw">${open ? '▾' : '▸'}</span>
      <span class="ep">${esc(c.endpoint || c.policyId || '')}</span>
      ${c.policyId && c.endpoint ? `<span class="pid">↔ ${esc(c.policyId)}</span>` : ''}
      <span class="sum">${esc(condSummary(c))}</span>
      <span class="grow"></span>
      ${shortFile ? `<span class="file">${esc(shortFile)}</span>` : ''}
      ${vb}
    </button>
    <div class="card-body">`;
  if (c.policyId) h += `<div class="pol-line"><span class="chip c-rule">정책</span> ${esc(c.policyId)}. ${esc(c.policyText)}</div>`;
  h += '<div class="in">';
  if (c.status === 'nomatch') {
    h += `<div class="small muted">이 API에 적용되는 정책이 없습니다. 공개 API라면 정상이고, 아니라면 정책 문서에 조건이 빠진 것일 수 있습니다. <span class="chip c-rule">규칙</span>
      <button class="ev" data-file="${esc(c.file)}" data-line="${c.start}" data-s="${c.start}" data-e="${c.end}" data-bad="0">L${c.start}-${c.end} 보기</button></div>`;
  } else if (c.status === 'nopair') {
    h += '<div class="small muted">선택한 컨트롤러에서 이 정책이 적용되는 API를 찾지 못했습니다. 다른 파일에 있거나, 경로가 바뀌었거나, 아직 구현되지 않았을 수 있습니다. <span class="chip c-rule">규칙</span></div>';
  } else {
    for (const k of c.conditions) {
      const [jc, jl] = JUDGE[k.final] || JUDGE.uncertain;
      const ev = k.line
        ? `<button class="ev" data-file="${esc(c.file)}" data-line="${k.line}" data-s="${c.start}" data-e="${c.end}" data-bad="${k.final === 'absent' ? 1 : 0}">L${k.line}  ${esc(lineText(c.file, k.line).trim().slice(0, 80))}</button>`
        : '<span class="ev none">근거 줄 없음</span>';
      h += `<div class="cond"><span class="name">${esc(k.condition)} <span class="chip c-llm">LLM</span></span><span class="chip ${jc}">${jl}</span>
        <span class="ev">${ev}</span><span class="reason">${esc(k.reason)}</span>${k.flag ? `<span class="flag"><span class="chip c-rule">규칙</span> ${esc(k.flag)}</span>` : ''}</div>`;
    }
    h += '<div class="why">';
    if (c.status !== 'ok' && c.explanation) h += `<p><span class="chip c-llm">설명</span>${esc(c.explanation)}</p>`;
    if (c.status === 'missing' && c.fix) h += `<p><span class="chip c-llm">고칠 방향</span>${esc(c.fix)}</p>`;
    if (c.status !== 'ok' && c.check) h += `<p><span class="chip c-llm">확인할 점</span>${esc(c.check)}</p>`;
    if (c.status === 'ok') h += '<p class="muted small">조건이 모두 코드에서 확인됐다고 판단했습니다. 근거 줄이 정말 그 검사를 하는지 직접 확인하세요.</p>';
    h += '</div>';
  }
  h += '</div>';
  if (['missing', 'unsure', 'ok'].includes(c.status)) {
    const key = cardKey(c);
    h += `<div class="human"><span class="chip c-human">개발자 확인</span>
      <button class="btn sm ${verdict === 'ok' ? 'sel-ok' : ''}" data-key="${esc(key)}" data-verdict="${verdict === 'ok' ? '' : 'ok'}">${c.status === 'ok' ? '맞음 (검사 확인)' : '맞음, 고칠게'}</button>
      <button class="btn sm ${verdict === 'fp' ? 'sel-fp' : ''}" data-key="${esc(key)}" data-verdict="${verdict === 'fp' ? '' : 'fp'}">${c.status === 'ok' ? '틀림 (실제로는 없음)' : '오탐'}</button>
      <span class="dim">${verdict === 'ok' ? '확인함' : verdict === 'fp' ? 'AI 판단이 틀림으로 기록' : '미확인'}</span></div>`;
  }
  return h + '</div></div>';
}

function renderCode() {
  const D = S.detail;
  const files = (D.snap.result.files || []).filter((f) => f.lines);
  const tabs = $('#codeTabs');
  const code = $('#code');
  if (!tabs || !code) return;
  tabs.innerHTML = files.map((f) => `<button class="btn sm ${f.path === D.codeFile ? 'on' : ''}" data-codefile="${esc(f.path)}">${esc(f.path.split('/').slice(-2).join('/'))}</button>`).join('') || '<span class="dim small">코드 없음</span>';
  const f = fileEntry(D.codeFile);
  if (!f) {
    code.innerHTML = '';
    return;
  }
  const hl = D.hl && D.hl.file === D.codeFile ? D.hl : null;
  code.innerHTML = f.lines.map((l, i) => {
    const n = i + 1;
    let cls = '';
    if (hl && n >= hl.s && n <= hl.e) cls = 'range';
    if (hl && n === hl.line) cls = hl.bad ? 'hlbad' : 'hl';
    return `<div data-n="${n}" class="${cls}"><span class="ln">${n}</span><span>${esc(l)}</span></div>`;
  }).join('');
  if (hl) {
    const row = code.querySelector(`div[data-n="${hl.line}"]`);
    if (row) code.scrollTop = Math.max(0, row.offsetTop - code.clientHeight / 3);
  }
}

async function setVerdict(key, value) {
  const D = S.detail;
  try {
    await api('POST', `/api/runs/${D.id}/verdict`, { key, value });
    D.snap.verdicts = D.snap.verdicts || {};
    if (value) D.snap.verdicts[key] = value; else delete D.snap.verdicts[key];
    S.runs = [];
    renderCards();
    toast(value === 'ok' ? '맞음으로 기록' : value === 'fp' ? '틀림으로 기록' : '확인 취소');
  } catch (e) {
    if (!(e instanceof AuthError)) toast(e.message);
  }
}

// ───────── 이벤트 (위임) ─────────
document.addEventListener('click', async (e) => {
  const t = e.target.closest('[data-act],[data-ctrl],[data-group],[data-card],[data-verdict],[data-tab],[data-codefile],[data-copy],.ev[data-line],tr[data-run]');
  if (!t) return;
  if (t.matches('[data-ctrl]')) {
    const p = t.dataset.ctrl;
    if (t.checked) S.scan.selected.add(p); else S.scan.selected.delete(p);
    const btn = $('[data-act="run"]');
    if (btn) btn.disabled = !canRun();
    const cnt = t.closest('.step').querySelector('.step-head .muted');
    if (cnt) cnt.textContent = `${S.scan.selected.size}개 선택`;
    return;
  }
  if (t.matches('tr[data-run]')) { location.hash = `#/logs/${t.dataset.run}`; return; }
  if (t.matches('[data-group]')) {
    const g = S.detail.openGroups;
    if (g.has(t.dataset.group)) g.delete(t.dataset.group); else g.add(t.dataset.group);
    renderCards();
    return;
  }
  if (t.matches('[data-card]')) {
    const key = t.dataset.card;
    const oc = S.detail.openCards;
    if (oc.has(key)) {
      oc.delete(key);
    } else {
      oc.add(key);
      // 펼치면 오른쪽 코드가 그 API 범위로 이동
      const c = S.detail.snap.result.cards.find((x) => openKey(x) === key);
      if (c && c.file && c.start) {
        S.detail.codeFile = c.file;
        S.detail.hl = { file: c.file, line: c.start, s: c.start, e: c.end, bad: false };
        renderCode();
      }
    }
    renderCards();
    return;
  }
  if (t.matches('[data-verdict]')) { setVerdict(t.dataset.key, t.dataset.verdict); return; }
  if (t.matches('[data-tab]')) {
    S.detail.tab = t.dataset.tab;
    $$('.tabs button').forEach((b) => b.classList.toggle('on', b === t));
    renderTab();
    return;
  }
  if (t.matches('[data-codefile]')) { S.detail.codeFile = t.dataset.codefile; renderCode(); return; }
  if (t.matches('.ev[data-line]')) {
    S.detail.codeFile = t.dataset.file;
    S.detail.hl = { file: t.dataset.file, line: +t.dataset.line, s: +t.dataset.s, e: +t.dataset.e, bad: t.dataset.bad === '1' };
    renderCode();
    return;
  }
  if (t.matches('[data-copy]')) {
    const [kind, i] = t.dataset.copy.split(':');
    copy(S.detail.extra[kind][+i].text);
    return;
  }
  const act = t.dataset.act;
  if (act === 'howto') {
    const box = $('#howto');
    const open = !box.classList.contains('open');
    box.classList.toggle('open', open);
    t.setAttribute('aria-expanded', String(open));
    t.querySelector('.tw').textContent = open ? '▾' : '▸';
    t.querySelector('.howto-btn').textContent = open ? '[ 접기 ]' : '[ 펼치기 ]';
  }
  if (act === 'rescan') {
    S.scan = { path: t.dataset.root, data: null, selected: new Set(), policyFile: '', policyText: '', error: '' };
    S.scan.autoScan = true;
    location.hash = '#/scan';
  }
  if (act === 'rain') Rain.toggle();
  if (act === 'logout') {
    try { await api('POST', '/api/logout'); } catch (err) { /* 무시 */ }
    S.session = null;
    S.status = null;
    S.detail = { id: null, snap: null, tab: 'result', codeFile: null, hl: null, extra: null, openGroups: null, openCards: null };
    location.hash = '#/login';
  }
  if (act === 'scan') doScan();
  if (act === 'run') doRun();
  if (act === 'selAll' && S.scan.data) { S.scan.selected = new Set(S.scan.data.controllers.map((c) => c.path)); viewScan(); }
  if (act === 'selNone') { S.scan.selected = new Set(); viewScan(); }
  if (act === 'copyReport') copy(S.detail.extra?.report || '');
  if (act === 'expandAll' || act === 'collapseAll') {
    const D = S.detail;
    const all = act === 'expandAll';
    D.openGroups = new Set(all ? GROUPS.map(([k]) => k) : []);
    D.openCards = new Set(all ? D.snap.result.cards.map(openKey) : []);
    renderCards();
  }
});

document.addEventListener('change', async (e) => {
  if (e.target.id === 'policyFile') {
    const f = e.target.value;
    S.scan.policyFile = f;
    if (f) {
      try {
        S.scan.policyText = (await api('POST', '/api/policy', { path: S.scan.path, file: f })).text;
      } catch (err) {
        if (!(err instanceof AuthError)) toast(err.message);
      }
    } else {
      S.scan.policyText = $('#policyText').value;
    }
    viewScan();
  }
});

document.addEventListener('input', (e) => {
  if (e.target.id === 'policyText') {
    S.scan.policyText = e.target.value;
    const btn = $('[data-act="run"]');
    if (btn) btn.disabled = !canRun();
  }
  if (e.target.id === 'path') S.scan.path = e.target.value;
});

document.addEventListener('keydown', (e) => {
  if (e.target.id === 'path' && e.key === 'Enter') doScan();
});

window.addEventListener('hashchange', render);
Rain.init();
render();
