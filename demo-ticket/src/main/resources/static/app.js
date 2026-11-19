// 티켓온 데모 화면: 로그인 상태, 내 예매, 좌석 결제, 공연 목록 (정상 사용 흐름만)
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const won = (n) => Number(n).toLocaleString('ko-KR') + '원';
let seats = [];

async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
    credentials: 'same-origin',
  });
  let data = null;
  try { data = await res.json(); } catch (e) { data = null; }
  return { status: res.status, data };
}

async function init() {
  const me = await api('GET', '/api/me');
  if (me.status === 401) {
    $('who').textContent = '로그인 안 됨';
    $('loginBox').hidden = false;
    return;
  }
  const u = me.data;
  $('who').innerHTML = `${esc(u.displayName)} (${esc(u.username)}, ${esc(u.role)}) <form method="post" action="/logout" class="inline"><button class="btn small">로그아웃</button></form>`;
  $('mode').textContent = u.mode === 'fixed' ? '서버: 고친 버전 (fixed 프로필)' : '서버: 취약 버전 (기본 실행)';
  $('mode').className = 'mode ' + (u.mode === 'fixed' ? 'fixed' : 'vuln');
  $('app').hidden = false;
  await Promise.all([loadBookings(), loadSeats(), loadConcerts()]);
}

async function loadBookings() {
  const r = await api('GET', '/api/bookings');
  const list = r.data || [];
  $('bookings').innerHTML = list.length ? list.map((b) => `
    <div class="item">
      <div><b>예매 ${esc(b.bookingId)}</b> · ${esc(b.concert)} · 좌석 ${esc(b.seat)}</div>
      <div class="muted">${won(b.paid)} · ${esc(b.status)}</div>
      ${b.status === '예매완료' ? `<button class="btn small" data-cancel="${esc(b.bookingId)}">취소</button>` : ''}
    </div>`).join('') : '<p class="muted">예매 내역이 없습니다.</p>';
  document.querySelectorAll('[data-cancel]').forEach((btn) => {
    btn.onclick = async () => {
      const res = await api('POST', `/api/bookings/${btn.dataset.cancel}/cancel`);
      if (res.status !== 200) {
        btn.insertAdjacentHTML('afterend', `<span class="err"> ${esc(res.data?.error || res.status)}</span>`);
        return;
      }
      loadBookings();
    };
  });
}

async function loadSeats() {
  const r = await api('GET', '/api/seats');
  seats = r.data || [];
  $('seat').innerHTML = seats.map((s) => `<option value="${esc(s.seatId)}">${esc(s.seatId)} · ${esc(s.concert)}</option>`).join('');
  showPrice();
}

function showPrice() {
  const s = seats.find((x) => x.seatId === $('seat').value);
  $('price').value = s ? won(s.price) : '';
}

async function loadConcerts() {
  const r = await api('GET', '/api/concerts');
  $('concerts').innerHTML = (r.data || []).map((c) => `
    <div class="item"><b>${esc(c.title)}</b><div class="muted">${esc(c.date)} · ${esc(c.place)}</div></div>`).join('');
}

$('seat').onchange = showPrice;
$('pay').onclick = async () => {
  const s = seats.find((x) => x.seatId === $('seat').value);
  if (!s) return;
  const r = await api('POST', '/api/payments', { seatId: s.seatId, price: s.price });
  $('payResult').innerHTML = r.status === 200
    ? `결제 완료 · 결제번호 ${esc(r.data.paymentId)} · ${won(r.data.amount)}`
    : `<span class="err">결제 실패: ${esc(r.data?.error || r.status)}</span>`;
};

init();
