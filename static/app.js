'use strict';
// Works two ways: served by the Java server itself, or opened with VS Code Live Server (API found on ports 8080-8099).
let BASE = null, token = '', openGroups = new Set(), menuOpen = false, lastTab = '', tabHistory = [], suppressTabHistory = false, editingProfile = false;
async function findApi() {
  const host = location.hostname || 'localhost';
  const ping = async b => {
    try {
      const ctl = new AbortController(); setTimeout(() => ctl.abort(), 700);
      const j = await (await fetch(b + '/api/ping', { signal: ctl.signal })).json();
      return j.portal === 'palm' ? { b, started: j.started || 0 } : null;
    } catch (e) { return null; }
  };
  const same = await ping('');
  if (same) return same.b;                                   // this page was served by the Java server itself
  const found = (await Promise.all(Array.from({ length: 20 }, (_, i) => ping(`http://${host}:${8080 + i}`)))).filter(Boolean);
  if (!found.length) return null;
  found.sort((x, y) => y.started - x.started);               // several copies running? use the newest one
  return found[0].b;
}
const F = (path, o = {}) => fetch(BASE + path, { ...o, headers: { ...(o.headers || {}), ...(token ? { Authorization: 'Bearer ' + token } : {}) } });
findApi().then(b => { BASE = b; });
let csrf = '', D = { role: '', me: {}, students: [], companies: [], placements: [], applications: [], posts: [], resets: [], attendance: [], reports: [], notes: [], logs: [], evals: [], audit: [], staff: [], studentList: [], accessRequests: [], settings: {} }, tab = '', cred = (() => { try { return JSON.parse(sessionStorage.getItem('palmCred') || 'null'); } catch (e) { return null; } })();
// Navigation: a left sidebar. A plain string is a page; [label, [pages]] is a group that opens like a drop-down.
const MENU = {
  ADMIN: ['Overview', ['Placement process', ['Applications', 'Approvals', 'Placements']], ['Students', ['Students', 'Attendance', 'Logbook']], 'Student List', 'Companies', ['Noticeboard', ['Opportunities', 'News']], ['Administration', ['Reports', 'Resets', 'Settings', 'Audit']], 'Notifications'],
  STUDENT: ['Overview', ['My internship', ['My Application', 'Attendance', 'Logbook']], ['Explore', ['Discover', 'Companies']], ['Noticeboard', ['Opportunities', 'News']], 'Notifications', 'Account'],
  COMPANY: ['Overview', ['Recruitment', ['Applications', 'Interns', 'Attendance', 'Logbook']], ['Company page', ['Profile', 'Post']], 'Notifications', 'Account']
};
const NAV = Object.fromEntries(Object.entries(MENU).map(([r, m]) => [r, m.flatMap(x => typeof x === 'string' ? [x] : x[1])]));
const groupOf = (t, role) => { const g = MENU[role].find(x => typeof x !== 'string' && x[1].includes(t)); return g ? g[0] : null; };
const LAB = { Resets: 'Password resets', Post: 'Post updates', Interns: 'Interns and reports', 'My Application': 'My application', Audit: 'Audit log' };
const lab = t => t === 'Notifications' && D.me && D.me.unread ? 'Notifications · ' + D.me.unread : (LAB[t] || t);
const ICON = { Overview:'home', 'Placement process':'workflow', Applications:'file', Approvals:'approval', Placements:'placement', Students:'student', Attendance:'attendance', Logbook:'logbook', 'Student List':'list', Companies:'building', Noticeboard:'bell', Opportunities:'opportunity', News:'campaign', Administration:'settings', Reports:'report', Resets:'lock', Settings:'settings', Audit:'history', Notifications:'bell', 'My internship':'briefcase', 'My Application':'file', Explore:'search', Discover:'opportunity', Account:'user', Recruitment:'users', Interns:'users', 'Company page':'building', Profile:'person', Post:'campaign' };
const MATERIAL_ICON = {
  // Navigation
  grid:'apps', home:'home', menu:'menu', arrowLeft:'arrow_back', arrowRight:'arrow_forward', chevronLeft:'chevron_left', chevronRight:'chevron_right',
  search:'search', filter:'filter_list', more:'more_horiz', refresh:'refresh', dashboard:'dashboard', workflow:'account_tree',
  // People / roles
  user:'person', profile:'account_circle', users:'groups', student:'school', userPlus:'person_add', admin:'admin_panel_settings',
  // Placement / applications
  briefcase:'work', briefcaseCheck:'business_center', file:'description', folder:'folder', placement:'assignment_turned_in', approval:'approval',
  opportunity:'travel_explore', report:'summarize', evaluation:'fact_check', application:'description', letter:'draft',
  // Company / academic functions
  building:'business', company:'apartment', attendance:'event_available', logbook:'menu_book', calendar:'calendar_month', calendarCheck:'event_available',
  // Communication / alerts
  bell:'notifications', notification:'notifications', megaphone:'campaign', mail:'mail', campaign:'campaign', alert:'warning', info:'info', help:'help',
  // Status
  check:'check', checkCircle:'check_circle', xCircle:'cancel', close:'close', clock:'schedule', status:'track_changes', shieldCheck:'verified_user',
  // Account / administration
  settings:'settings', lock:'lock', history:'history', list:'format_list_bulleted', fileSpreadsheet:'table_chart', trash:'delete',
  logout:'logout', send:'send', link:'link', edit:'edit', eye:'visibility', print:'print', download:'download', upload:'upload', save:'save',
  plus:'add', star:'star', moreVert:'more_vert', visibility:'visibility'
};
const icon = (name, cls='') => h('span', { class:'material-symbols-outlined ui-icon '+cls, 'aria-hidden':'true' }, MATERIAL_ICON[name] || name || 'apps');
const iconForLabel = t => { const x = String(t).toLowerCase(); if (x.includes('back') || x.includes('previous')) return 'arrowLeft'; if (x.includes('next')) return 'arrowRight'; if (x.includes('sign out') || x.includes('logout')) return 'logout'; if (x.includes('download') || x.includes('export')) return 'download'; if (x.includes('upload')) return 'upload'; if (x.includes('print')) return 'print'; if (x.includes('save') || x.includes('update')) return 'save'; if (x.includes('edit') || x.includes('manage')) return 'edit'; if (x.includes('view') || x.includes('open') || x.includes('review')) return 'eye'; if (x.includes('approve') || x.includes('approved') || x.includes('accept')) return 'approval'; if (x.includes('complete')) return 'checkCircle'; if (x.includes('decline') || x.includes('reject') || x.includes('return')) return 'close'; if (x.includes('letter')) return 'letter'; if (x.includes('attendance')) return 'attendance'; if (x.includes('logbook')) return 'logbook'; if (x.includes('report')) return 'report'; if (x.includes('evaluation')) return 'evaluation'; if (x.includes('add') || x.includes('create') || x.includes('register')) return 'plus'; if (x.includes('search') || x.includes('find')) return 'search'; if (x.includes('notification')) return 'bell'; return null; };
const iconButtonChildren = (t, extraIcon) => [icon(extraIcon || iconForLabel(t) || 'grid'), h('span', { class: 'button-label' }, String(t).replace(/^[↗→←↓↑]+\s*/, ''))];
const $ = s => document.querySelector(s);
const v = id => $('#' + id).value;
// DOM is built from text nodes only: user data can never become markup (XSS-proof).
const h = (t, a = {}, ...k) => {
  const e = document.createElement(t);
  for (const [n, val] of Object.entries(a)) n.startsWith('on') ? e.addEventListener(n.slice(2), val) : e.setAttribute(n, val);
  k.flat(Infinity).filter(c => c !== null && c !== false && c !== undefined).forEach(c => e.append(c));
  return e;
};
const logo = () => { const i = h('img', { class: 'logo', src: 'puc.png', alt: 'Palm University College' }); i.addEventListener('error', () => i.replaceWith(h('span', { class: 'mark' }, '✦'))); return i; };
$('#lp').append(logo());
const toast = m => { const t = $('#toast'); t.textContent = m; t.className = 'show'; setTimeout(() => t.className = '', 3000); };
$('#toast').setAttribute('role', 'status'); $('#toast').setAttribute('aria-live', 'polite');
const openPortalLogin = role => {
  showLogin();
  const u = $('#un');
  if (u) { u.focus(); if (role === 'student') u.placeholder = 'Enter your student ID'; else if (role === 'coordinator') u.placeholder = 'Enter your coordinator username'; else if (role === 'partner') u.placeholder = 'Enter your company username'; }
};
['landing-login','landing-start','process-start','landing-final-login'].forEach(id => { const el = document.getElementById(id); if (el) el.addEventListener('click', () => openPortalLogin()); });
document.querySelectorAll('[data-login-role]').forEach(el => el.addEventListener('click', () => openPortalLogin(el.dataset.loginRole)));


async function api(path, body) {
  const r = await F(path, body ? { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-CSRF': csrf }, body: new URLSearchParams(body) } : {});
  const j = await r.json().catch(() => ({}));
  if (r.status === 401) { showLogin(); throw 0; }
  if (!r.ok) { toast(j.error || 'Something went wrong'); throw 0; }
  return j;
}
const act = async (path, body, msg) => { try { const j = await api(path, body); toast(msg); if (j.password) { cred = j; sessionStorage.setItem('palmCred', JSON.stringify(j)); } await refresh(); if (j.password) window.scrollTo({ top: 0, behavior: 'smooth' }); } catch (e) {} };
const refresh = async () => { document.body.classList.add('is-loading'); try { D = await api('/api/data'); render(); } finally { document.body.classList.remove('is-loading'); } };
const showLanding = () => { $('#app').hidden = true; $('#login').hidden = true; $('#landing').hidden = false; window.scrollTo({ top: 0, behavior: 'smooth' }); };
const showLogin = () => { sessionStorage.removeItem('palm'); sessionStorage.removeItem('palmCred'); cred = null; token = ''; lastTab = ''; tabHistory = []; suppressTabHistory = false; openGroups = new Set(); menuOpen = false; editingProfile = false; $('#app').hidden = true; $('#landing').hidden = true; $('#login').hidden = false; window.scrollTo({ top: 0, behavior: 'smooth' }); };

$('#lf').addEventListener('submit', async e => {
  e.preventDefault();
  if (BASE === null) BASE = await findApi();
  if (BASE === null) { $('#lerr').textContent = 'Cannot reach the Java server. Start Server.java first, then try again.'; return; }
  const r = await F('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ username: $('#un').value, password: $('#pw').value }) });
  const j = await r.json().catch(() => ({ error: 'Server unreachable' }));
  if (!r.ok) { $('#lerr').textContent = j.error; return; }
  token = j.token; csrf = j.csrf; tab = NAV[j.role][0]; tabHistory = []; suppressTabHistory = false; sessionStorage.setItem('palm', JSON.stringify({ csrf, token, role: j.role })); cred = null; $('#pw').value = ''; $('#lerr').textContent = '';
  $('#landing').hidden = true; $('#login').hidden = true; $('#app').hidden = false; refresh();
});

const STATUS_ICON = { PENDING:'clock', ACTIVE:'briefcaseCheck', COMPLETED:'checkCircle', CANCELLED:'xCircle', SUBMITTED:'file', FORWARDED:'arrowRight', ACCEPTED:'checkCircle', DECLINED:'xCircle', REJECTED:'xCircle', WITHDRAWN:'arrowLeft', PLACED:'checkCircle', OPEN:'briefcase', APPROVED:'checkCircle', RETURNED:'arrowLeft' };
const STATUS_LABEL = { PENDING:'Pending', ACTIVE:'Active', COMPLETED:'Completed', CANCELLED:'Cancelled', SUBMITTED:'Submitted', FORWARDED:'Forwarded', ACCEPTED:'Approved', DECLINED:'Declined', REJECTED:'Rejected', WITHDRAWN:'Withdrawn', PLACED:'Placed', OPEN:'Open', APPROVED:'Approved', RETURNED:'Returned' };
const pill = s => { const key = String(s || '').toUpperCase(); return h('span', { class: 'pill ' + key, title: STATUS_LABEL[key] || key }, icon(STATUS_ICON[key] || 'info'), h('span', {}, STATUS_LABEL[key] || key)); };
const btn = (t, f) => h('button', { class: 'link icon-button', onclick: f }, ...iconButtonChildren(t));
const field = (id, label, type = 'text', ph = '', val = '') => h('label', {}, label, h('input', { id, type, placeholder: ph, maxlength: 120, value: val || '' }));
const sel = (id, items) => h('select', { id }, items.map(([val, t]) => h('option', { value: val }, t)));
const table = (head, rows) => {   // long tables get a search box and pages of 20 rows
  if (!rows.length) return h('p', { class: 'empty' }, 'Nothing here yet.');
  const txt = c => c === null || c === undefined || c === false ? '' : Array.isArray(c) ? c.map(txt).join(' ') : (c.textContent !== undefined ? c.textContent : String(c));
  const PAGE = 20; let page = 0, q = '';
  const body = h('tbody'), info = h('span', { class: 'sm' });
  const prev = h('button', { class: 'link icon-button', type: 'button' }, ...iconButtonChildren('Previous', 'arrowLeft')), next = h('button', { class: 'link icon-button', type: 'button' }, ...iconButtonChildren('Next', 'arrowRight'));
  const draw = () => {
    const list = q ? rows.filter(r => txt(r).toLowerCase().includes(q)) : rows, pages = Math.max(1, Math.ceil(list.length / PAGE));
    page = Math.min(page, pages - 1);
    body.replaceChildren(...list.slice(page * PAGE, page * PAGE + PAGE).map(r => h('tr', {}, r.map(c => h('td', {}, c)))));
    info.textContent = list.length + (list.length === 1 ? ' row' : ' rows') + (pages > 1 ? ' · page ' + (page + 1) + ' of ' + pages : '');
    prev.disabled = page === 0; next.disabled = page >= pages - 1;
  };
  prev.addEventListener('click', () => { page--; draw(); }); next.addEventListener('click', () => { page++; draw(); });
  const search = rows.length > 8 ? h('div', { class: 'tools' }, h('input', { class: 'tsearch', type: 'search', placeholder: 'Search this table…', 'aria-label': 'Search this table', oninput: e => { q = e.target.value.trim().toLowerCase(); page = 0; draw(); } })) : null;
  draw();
  return h('div', { class: 'card wrap' }, search, h('table', {}, h('thead', {}, h('tr', {}, head.map(x => h('th', {}, x)))), body), h('div', { class: 'tools' }, info, h('span', {}, prev, next)));
};
const stSel = r => h('select', { onchange: e => act('/api/status', { sid: r.sid, status: e.target.value }, 'Status updated') },
  ['PENDING', 'ACTIVE', 'COMPLETED', 'CANCELLED'].map(s => h('option', { value: s, ...(s === r.status ? { selected: '' } : {}) }, s)));
const chain = ['SUBMITTED', 'FORWARDED', 'ACCEPTED'], labels = [['Submitted to school','file'], ['With company','building'], ['Approved','checkCircle']];
const track = st => chain.includes(st) ? h('div', { class: 'track' }, chain.map((s, i) => h('span', { class: i <= chain.indexOf(st) ? 'done' : '' }, icon(labels[i][1]), h('span', {}, labels[i][0])))) : pill(st);
const cvBtn = a => a.cv ? btn('View CV', () => review(a, null)) : h('span', { class: 'sm' }, 'No CV on file');
const letterBtns = a => [cvBtn(a), ...(a.status === 'ACCEPTED' ? [btn('School letter', () => showLetter(a, 'school')), btn('Company letter', () => showLetter(a, 'company'))] : [])];
const noteBox = ph => h('input', { class: 'note', placeholder: ph, maxlength: 300, 'aria-label': ph });
const notes = a => [a.schoolNote ? h('span', { class: 'sm' }, 'To student: ' + a.schoolNote) : null, a.companyNote ? h('span', { class: 'sm' }, 'Company: ' + a.companyNote) : null];
const decideBtns = a => { const n = noteBox('Feedback to the school'); return [cvBtn(a), n, pbtn('Accept', () => act('/api/decide', { app: a.id, action: 'accept', note: n.value }, 'Student accepted')), btn('Decline', () => act('/api/decide', { app: a.id, action: 'decline', note: n.value }, 'Application declined'))]; };
const STATUS_MSG = { SUBMITTED: 'Awaiting review by the school', FORWARDED: 'Approved by the school and sent to the company', ACCEPTED: 'Approved by the company. Your letters are ready', REJECTED: 'Not approved by the school', DECLINED: 'The company has declined this application', WITHDRAWN: 'You withdrew this application' };
const fwd = a => { const s = sel('f' + a.id, [['', 'Choose company…'], ...D.companies.map(c => [c.id, c.name])]); s.value = a.cid; const n = noteBox('Message to student (optional)'); return [s, n, pbtn('Forward', () => act('/api/review', { app: a.id, action: 'forward', cid: s.value, note: n.value }, 'Forwarded to company')), btn('Reject', () => act('/api/review', { app: a.id, action: 'reject', note: n.value }, 'Application rejected'))]; };
const coCards = () => h('div', { class: 'grid' }, D.companies.map(c => {
  const bar = h('i'); bar.style.width = (100 * c.filled / c.capacity) + '%';
  return h('div', { class: 'card co' }, h('b', {}, c.name), h('small', {}, c.industry), h('small', {}, c.focus), c.about ? h('p', { class: 'sm' }, c.about) : null, site(c.website), h('div', { class: 'bar' }, bar), h('span', {}, c.filled + ' / ' + c.capacity + ' interns'));
}));

async function showLetter(a, type) {
  let L; try { L = await api(`/api/letter?app=${a.id}&type=${type}`); } catch (e) { return; }
  const link = location.origin + location.pathname + '#verify=' + L.ref + ':' + L.code;
  $('#letter').replaceChildren(h('div', { class: 'card' }, h('h2', {}, L.title), h('pre', { class: 'letter' }, L.body),
    h('p', { class: 'sm' }, 'Reference ' + L.ref + ' · Verification code ' + L.code + '. Anyone can check that this letter is genuine at: ' + link),
    h('button', { class: 'icon-button', onclick: () => window.print() }, ...iconButtonChildren('Print / Save as PDF'))));
  $('#letter').scrollIntoView({ behavior: 'smooth' });
}
async function findMatch(s) {
  let m; try { m = await api('/api/match?student=' + encodeURIComponent(s.id)); } catch (e) { return; }
  $('#match').replaceChildren(h('div', { class: 'card' }, h('h2', {}, icon('star'), h('span', {}, 'Best fits for ' + s.name)),
    m.length ? m.map(c => h('div', { class: 'match' }, h('div', {}, h('b', {}, c.name), h('small', {}, c.why)), h('span', { class: 'score' }, c.score + '%'),
      h('button', { class: 'icon-button', onclick: () => act('/api/assign', { sid: s.id, cid: c.id }, 'Placed at ' + c.name) }, ...iconButtonChildren('Assign')))) : h('p', { class: 'empty' }, 'No companies with open slots.')));
}

async function uploadCv() {
  const f = $('#cvf').files[0];
  if (!f) return toast('Choose a PDF file first');
  if (f.size > 2097152) return toast('CV must be 2 MB or smaller');
  const r = await F('/api/cv', { method: 'POST', headers: { 'X-CSRF': csrf, 'Content-Type': 'application/pdf' }, body: f });
  const j = await r.json().catch(() => ({}));
  toast(r.ok ? 'CV uploaded' : (j.error || 'Upload failed'));
  if (r.ok) { await refresh(); analyse(); }
}
const cvCard = () => h('div', { class: 'card' }, h('b', {}, 'Step 1: Your CV'),
  h('p', {}, D.me.cv ? '✓ CV on file. Now complete Step 2 below and press "Submit application" so the school can review it. (Upload again to replace the CV.)' : 'Upload your CV as a PDF (max 2 MB). The analysis starts as soon as it is uploaded.'),
  h('p', { class: 'sm' }, D.me.ai ? '✦ AI analysis is on.' : 'AI analysis is off: the server has no AI key (a free GEMINI_API_KEY works), so a basic keyword match is used.'),
  field('skl', 'Skills and interests (optional, improves the match)', 'text', 'e.g. Python, accounting, customer service'),
  h('label', { class: 'chk' }, h('input', { id: 'cons', type: 'checkbox', ...(aiConsent ? { checked: '' } : {}), onchange: e => { aiConsent = e.target.checked; } }), 'I agree that my CV may be sent to an AI service to suggest companies (optional). Without this, a private built-in matcher is used.'),
  h('div', { class: 'form' }, h('input', { id: 'cvf', type: 'file', accept: '.pdf,application/pdf' }), h('button', { class: 'icon-button', onclick: uploadCv }, ...iconButtonChildren('Upload CV'))),
  D.me.cv ? h('div', {}, btn('✦ Analyse my CV and suggest companies', analyse), h('div', { id: 'ai' })) : null);

let prefill = '', aiConsent = false;
const site = w => /^https?:\/\//.test(w || '') ? h('a', { href: w, target: '_blank', rel: 'noopener noreferrer', class: 'sm' }, w) : null;
const postCard = p => h('div', { class: 'card' }, h('small', { class: 'tag' }, p.company + ' · ' + p.date), h('h2', {}, p.title), h('p', {}, p.body),
  D.role === 'STUDENT' && p.type === 'AD' && !D.me.placed ? btn('Apply to this company', () => { prefill = p.company; tab = 'My Application'; render(); }) : null);
const feed = (type, title, intro) => { const ps = D.posts.filter(p => p.type === type); return [h('h1', {}, title), h('p', {}, intro), ps.length ? ps.map(postCard) : h('p', { class: 'empty' }, 'Nothing posted yet.')]; };

async function runSearch() {
  const q = v('sq'); if (q.trim().length < 2) return toast('Type at least 2 characters');
  let r; try { r = await api('/api/search?q=' + encodeURIComponent(q)); } catch (e) { return; }
  $('#sr').replaceChildren(h('h2', {}, 'On the Palm portal'),
    r.portal.length ? r.portal.map(c => h('div', { class: 'card' }, h('b', {}, c.name), h('small', {}, ' · ' + c.industry), h('p', {}, c.about), site(c.website), D.role === 'STUDENT' ? btn('Apply', () => { prefill = c.name; tab = 'My Application'; render(); }) : null)) : h('p', { class: 'empty' }, 'No portal company matches.'),
    h('h2', {}, 'From the web'),
    r.webEnabled ? (r.web.length ? r.web.map(w => h('div', { class: 'card' }, /^https?:\/\//.test(w.link) ? h('a', { href: w.link, target: '_blank', rel: 'noopener noreferrer' }, w.title) : h('b', {}, w.title), h('p', { class: 'sm' }, w.snippet))) : h('p', { class: 'empty' }, 'No web results.'))
      : h('div', { class: 'card' }, h('p', {}, 'Live web results are not switched on for this portal yet.'), h('a', { href: r.link, target: '_blank', rel: 'noopener noreferrer' }, 'Search the web for "' + q + '" ↗')));
}

async function analyse() {
  $('#ai').replaceChildren(h('p', { class: 'sm' }, 'Analysing your CV… this can take up to a minute.'));
  let r; try { r = await api('/api/analyse', { skills: ($('#skl') || {}).value || '', consent: aiConsent ? '1' : '' }); } catch (e) { $('#ai').replaceChildren(h('p', { class: 'sm' }, 'The analysis could not be completed. See the message shown above.')); return; }
  $('#ai').replaceChildren(h('p', { class: 'sm' }, (r.engine !== 'local' ? 'AI analysis of your CV and the company profiles (your CV was sent to the AI provider). ' : 'Built-in keyword analysis. ') + r.note),
    r.matches.map(c => h('div', { class: 'match' }, h('div', {}, h('b', {}, c.name), h('small', {}, c.reason)), h('span', { class: 'score' }, c.score + '%'),
      btn('Use', () => { const i = $('#ac'); if (i) { i.value = c.name; toast('Company filled in below'); } }))));
}

const loginText = c => 'Palm University College Internship Portal\nPortal: ' + location.href.split('#')[0] + '\nUsername: ' + c.login + '\nTemporary password: ' + c.password + '\nPlease sign in and change your password under Account.';
const credCard = () => !cred ? null : h('div', { class: 'card cred' }, h('b', {}, 'Login details for ' + (cred.name || cred.login) + ' (shown once)'),
  h('pre', { class: 'letter' }, loginText(cred)),
  h('p', { class: 'sm' }, 'Send these details to the account holder. The portal address only works for them if the portal is hosted where they can reach it.'),
  h('div', { class: 'form' },
    pbtn('Copy details', () => navigator.clipboard ? navigator.clipboard.writeText(loginText(cred)).then(() => toast('Copied'), () => toast('Could not copy: select the text and copy it')) : toast('Select the text and copy it')),
    cred.email ? h('a', { class: 'btn icon-button', href: 'mailto:' + cred.email + '?subject=' + encodeURIComponent('Your Palm internship portal login') + '&body=' + encodeURIComponent(loginText(cred)) }, ...iconButtonChildren('Email these details', 'mail')) : null,
    btn('Dismiss', () => { cred = null; sessionStorage.removeItem('palmCred'); render(); })));
const accountsTable = () => [h('h2', {}, 'Company accounts'),
  h('p', { class: 'sm' }, 'Passwords are never stored in readable form. If a company loses its password, generate a new one and send it.'),
  table(['ID', 'Company', 'Username', 'Contact email', 'Interns', ''], D.companies.map(c => [c.id, c.name, c.id.toLowerCase(), c.email || '-', c.filled + ' / ' + c.capacity,
    btn('New password', () => act('/api/resetpw', { username: c.id.toLowerCase() }, 'New password generated'))]))];
const pbtn = (t, f) => h('button', { class: 'icon-button', onclick: f }, ...iconButtonChildren(t));
const msgBtns = a => { const n = noteBox('Message to the student'); n.value = a.status === 'ACCEPTED'
  ? 'Congratulations! Your internship application has been approved. Your approval letters are ready in your portal.'
  : 'We are sorry: your application was not approved by the company. Please apply to another company.';
  return [n, btn('Send to student', () => act('/api/message', { app: a.id, note: n.value }, 'Message sent to the student'))]; };

async function review(a, mode) {   // shows the student's CV inside the portal, with the decision controls beside it
  let blob;
  try { const r = await F('/api/cv?app=' + a.id); if (!r.ok) throw 0; blob = new Blob([await r.arrayBuffer()], { type: 'application/pdf' }); } catch (e) { return toast('Could not load the CV'); }
  const url = URL.createObjectURL(blob), box = $('#review');
  if (!box) { window.open(url, '_blank'); return; }
  box.replaceChildren(h('div', { class: 'card' }, h('small', { class: 'tag' }, a.id + ' · ' + a.company), h('h2', {}, a.student + ' · ' + a.program), h('p', {}, a.statement),
    h('iframe', { class: 'cvframe', src: url, title: 'CV of ' + a.student }),
    h('p', { class: 'sm' }, h('a', { href: url, target: '_blank', rel: 'noopener' }, 'Open the CV in a new tab ↗')),
    mode === 'school' ? h('div', { class: 'form' }, fwd(a)) : mode === 'company' ? h('div', { class: 'form' }, decideBtns(a)) : null));
  box.scrollIntoView({ behavior: 'smooth' });
}

async function downloadCsv(t) {
  const from = ($('#rf') || {}).value || '', to = ($('#rt') || {}).value || '';
  const r = await F('/api/export?type=' + t + '&from=' + encodeURIComponent(from) + '&to=' + encodeURIComponent(to));
  if (!r.ok) return toast('Could not download the report');
  const a = h('a', { href: URL.createObjectURL(await r.blob()), download: 'palm-' + t + '.csv' });
  document.body.append(a); a.click(); a.remove();
}

async function studentCv(s) {   // admin: open a student's CV straight from the Students tab
  let blob;
  try { const r = await F('/api/cv?sid=' + s.id); if (!r.ok) throw 0; blob = new Blob([await r.arrayBuffer()], { type: 'application/pdf' }); } catch (e) { return toast('Could not load the CV'); }
  const url = URL.createObjectURL(blob);
  $('#review').replaceChildren(h('div', { class: 'card' }, h('small', { class: 'tag' }, s.id), h('h2', {}, s.name + ' · ' + s.program),
    h('iframe', { class: 'cvframe', src: url, title: 'CV of ' + s.name }),
    h('p', { class: 'sm' }, h('a', { href: url, target: '_blank', rel: 'noopener' }, 'Open the CV in a new tab ↗'))));
  $('#review').scrollIntoView({ behavior: 'smooth' });
}

let appTab = 'current';
const appGroups = list => {
  const cur = a => a.status === 'SUBMITTED' || a.status === 'FORWARDED';
  const act = a => a.status === 'ACCEPTED' && ['PENDING', 'ACTIVE'].includes(a.pstatus);
  return { current: list.filter(cur), active: list.filter(act), past: list.filter(a => !cur(a) && !act(a)) };
};
const seg = (label, on, f) => { const b = h('button', { class: on ? 'btn' : 'link', onclick: f }, label); if (on) b.style.border = '0'; return b; };
const appTabs = g => h('div', { class: 'form' }, [['current', 'Current'], ['active', 'Internships in progress'], ['past', 'Past records']].map(([k, l]) => seg(l + ' (' + g[k].length + ')', appTab === k, () => { appTab = k; render(); })));

const fmt = s => s ? new Date(s).toLocaleString([], { dateStyle: 'medium', timeStyle: 'medium' }) : '';
const hrs = w => w.out ? ((new Date(w.out) - new Date(w.in)) / 3600000).toFixed(2) : '';
const attTable = (list, names) => table(names ? ['Student', 'Company', 'Clock in', 'Clock out', 'Hours'] : ['Clock in', 'Clock out', 'Hours'],
  list.map(w => (names ? [w.student, w.company] : []).concat([fmt(w.in), w.out ? fmt(w.out) : (Date.now() - new Date(w.in) > 14 * 3600000 ? pill('REJECTED') : h('span', { class: 'pill ACTIVE' }, icon(STATUS_ICON.ACTIVE), h('span', {}, 'Clocked in'))), hrs(w)])));

async function uploadReport() {
  const f = $('#rpf').files[0], sid = v('rsid');
  if (!sid) return toast('Choose an intern first');
  if (!f) return toast('Choose the report PDF first');
  if (f.size > 5242880) return toast('The report must be 5 MB or smaller');
  const r = await F('/api/report?sid=' + encodeURIComponent(sid) + '&summary=' + encodeURIComponent(v('rsm')), { method: 'POST', headers: { 'X-CSRF': csrf, 'Content-Type': 'application/pdf' }, body: f });
  const j = await r.json().catch(() => ({}));
  toast(r.ok ? 'Report uploaded' : (j.error || 'Upload failed'));
  if (r.ok) refresh();
}

async function reportAct(k, mode) {   // view, download or print a company's final report
  let blob;
  try { const r = await F('/api/report?sid=' + k.sid + '&cid=' + k.cid); if (!r.ok) throw 0; blob = new Blob([await r.arrayBuffer()], { type: 'application/pdf' }); } catch (e) { return toast('Could not load the report'); }
  const url = URL.createObjectURL(blob);
  if (mode === 'download') { const a = h('a', { href: url, download: 'Intern-report-' + k.student.replace(/\s+/g, '-') + '.pdf' }); document.body.append(a); a.click(); a.remove(); return; }
  if (mode === 'print') { window.open(url, '_blank'); return toast('Report opened in a new tab: use its printer icon or Ctrl + P'); }
  const box = $('#review'); if (!box) { window.open(url, '_blank'); return; }
  box.replaceChildren(h('div', { class: 'card' }, h('small', { class: 'tag' }, k.company + ' · ' + k.date), h('h2', {}, 'Final report: ' + k.student), k.summary ? h('p', {}, k.summary) : null,
    h('iframe', { class: 'cvframe', src: url, title: 'Final report' })));
  box.scrollIntoView({ behavior: 'smooth' });
}

const internshipCard = () => {   // student: shows where they are interning
  const st = D.me.placement; if (!st) return null;
  const text = st === 'ACTIVE' ? 'You are currently interning at ' : st === 'PENDING' ? 'Your internship is confirmed at ' : st === 'COMPLETED' ? 'You completed your internship at ' : 'Internship at ';
  return h('div', { class: 'card cred' }, h('small', { class: 'tag' }, 'My internship'), h('h2', {}, text + D.me.company),
    h('p', {}, pill(st), ' Placed on ' + D.me.since + (D.me.start ? ' · Started ' + D.me.start : '') + (D.me.end ? ' · Ends ' + D.me.end : '') + (st === 'PENDING' ? '. It starts as soon as your company or the school marks it as started.' : '')),
    st === 'ACTIVE' ? h('div', { class: 'form' }, pbtn('Open attendance', () => { tab = 'Attendance'; render(); })) : null);
};

const deadlineNote = () => D.settings && D.settings.deadline ? h('p', { class: 'msg' }, 'Applications close on ' + D.settings.deadline + '.') : null;
const bars = (title, pairs) => h('div', { class: 'card' }, h('h2', {}, title), pairs.length ? pairs.map(([k, n, mx]) => { const b = h('i'); b.style.width = (mx ? 100 * n / mx : 0) + '%'; return h('div', { class: 'row' }, h('span', {}, k), h('div', { class: 'bar' }, b), h('b', {}, n)); }) : h('p', { class: 'empty' }, 'No data yet.'));
const count = (list, f) => { const m = {}; list.forEach(x => { const k = f(x) || 'Other'; m[k] = (m[k] || 0) + 1; }); const e = Object.entries(m).sort((a, b) => b[1] - a[1]).slice(0, 8); const mx = e.length ? e[0][1] : 0; return e.map(([k, n]) => [k, n, mx]); };
const datesForm = mine => h('div', { class: 'card form' }, h('label', {}, 'Intern', sel('dsid', mine.map(p => [p.sid, p.student]))), field('dst', 'Start date', 'date'), field('den', 'End date', 'date'),
  pbtn('Save internship dates', () => act('/api/dates', { sid: v('dsid'), start: v('dst'), end: v('den') }, 'Dates saved')));
const logCard = (l, review) => { const n = review && l.status === 'SUBMITTED' ? noteBox('Comment (optional)') : null;
  return h('div', { class: 'card' }, h('small', { class: 'tag' }, 'Week of ' + l.week + (review ? ' · ' + l.student : '')), h('p', {}, l.body), h('p', {}, pill(l.status)), l.note ? h('p', { class: 'sm' }, 'Supervisor: ' + l.note) : null,
    n ? h('div', { class: 'form' }, n, pbtn('Approve', () => act('/api/logreview', { id: l.id, action: 'approve', note: n.value }, 'Entry approved')), btn('Return', () => act('/api/logreview', { id: l.id, action: 'return', note: n.value }, 'Entry returned'))) : null); };

const views = {
  Overview: () => {
    if (D.role === 'STUDENT') {
      const placement = D.me.placement, submitted = D.applications.filter(a => ['SUBMITTED','FORWARDED'].includes(a.status)).length;
      const completed = D.placements.filter(p => p.status === 'COMPLETED').length;
      const hours = D.attendance.reduce((sum,w)=>sum+(w.out?(new Date(w.out)-new Date(w.in))/3600000:0),0);
      const actions = [];
      if (!D.me.cv) actions.push(['Upload your CV','Complete your profile and unlock company matching.',()=>{tab='My Application';render();}]);
      if (!placement && !submitted) actions.push(['Explore placements','Browse partner companies and find a placement that fits your goals.',()=>{tab='Discover';render();}]);
      if (placement === 'ACTIVE') actions.push(['Update your logbook','Record this week’s work and keep your internship history current.',()=>{tab='Logbook';render();}]);
      return [
        h('section',{class:'welcome-block'},h('div',{},h('span',{class:'eyebrow'},'STUDENT DASHBOARD'),h('h1',{},'Welcome back, '+(D.me.name||'Student').split(' ')[0]+'.'),h('p',{},placement==='ACTIVE'?'Your internship is in progress. Keep your attendance and weekly logbook up to date.':placement?'Your placement journey is underway. Here is your latest status.':'Your internship journey starts here. Complete the next step to move forward.')),
          h('button',{class:'primary-action',onclick:()=>{tab=placement?'My Application':'Discover';render();}}, placement?'View internship':'Find an opportunity')),
        h('div',{class:'metric-grid'},
          h('div',{class:'metric-card feature'},h('span',{class:'metric-icon'},icon('briefcase')),h('span',{class:'metric-label'},'Internship status'),h('strong',{},placement||'Not placed'),h('small',{},D.me.company||'No company selected yet')),
          h('div',{class:'metric-card'},h('span',{class:'metric-label'},'Applications'),h('strong',{},D.applications.length),h('small',{},submitted?'One needs your attention':'No active application')),
          h('div',{class:'metric-card'},h('span',{class:'metric-label'},'Attendance hours'),h('strong',{},hours.toFixed(1)),h('small',{},'Recorded hours')),
          h('div',{class:'metric-card'},h('span',{class:'metric-label'},'Completed internships'),h('strong',{},completed),h('small',{},'Academic history'))),
        actions.length ? h('section',{class:'section-block'},h('div',{class:'section-head'},h('div',{},h('h2',{},'Next steps'),h('p',{},'A few actions can keep your placement journey moving.'))),h('div',{class:'action-grid'},actions.map(a=>h('button',{class:'action-card',onclick:a[2]},h('span',{class:'action-arrow'},icon('arrowRight')),h('strong',{},a[0]),h('span',{},a[1]))))) : null,
        h('section',{class:'section-block'},h('div',{class:'section-head'},h('div',{},h('h2',{},'Recent applications'),h('p',{},'Track your latest placement decisions.')),h('button',{class:'text-action icon-button',onclick:()=>{tab='My Application';render();}},icon('arrowRight'),h('span',{},'View all'))), D.applications.length?D.applications.slice().reverse().slice(0,4).map(a=>h('div',{class:'activity-row'},h('span',{class:'activity-mark'},icon('workflow')),h('div',{class:'activity-main'},h('strong',{},a.company),h('small',{},a.date+' · '+a.statement),track(a.status)))):h('div',{class:'empty-panel'},h('strong',{},'No applications yet'),h('p',{},'Start by exploring partner companies and submitting your first application.')))
      ];
    }
    if (D.role === 'COMPANY') {
      const apps = D.applications.length, interns = D.placements.filter(p=>p.status!=='CANCELLED').length, active = D.placements.filter(p=>p.status==='ACTIVE').length, pending = D.applications.filter(a=>a.status==='FORWARDED').length;
      return [h('section',{class:'welcome-block'},h('div',{},h('span',{class:'eyebrow'},'EMPLOYER DASHBOARD'),h('h1',{},'Welcome back, '+(D.me.name||'Partner')+'.'),h('p',{},'Manage your recruitment pipeline, support your interns and keep your company profile current.')),h('button',{class:'primary-action',onclick:()=>{tab=pending?'Applications':'Profile';render();}},pending?'Review applications':'Manage profile')),
        h('div',{class:'metric-grid'},[['Applications',apps,'Total applications'],['Awaiting decision',pending,'Forwarded by Palm'],['Interns',interns,'Current placements'],['Active interns',active,'Currently on internship']].map(([a,b,c])=>h('div',{class:'metric-card'},h('span',{class:'metric-label'},a),h('strong',{},b),h('small',{},c)))),
        h('section',{class:'section-block'},h('div',{class:'section-head'},h('div',{},h('h2',{},'Recruitment pipeline'),h('p',{},'The applications that need the most attention.')),h('button',{class:'text-action icon-button',onclick:()=>{tab='Applications';render();}},icon('arrowRight'),h('span',{},'Open recruitment'))), D.applications.length?D.applications.slice().reverse().slice(0,5).map(a=>h('div',{class:'activity-row'},h('span',{class:'activity-mark'},icon('users')),h('div',{class:'activity-main'},h('strong',{},a.student),h('small',{},a.program+' · '+a.date),pill(a.status)))):h('div',{class:'empty-panel'},h('strong',{},'Your pipeline is clear'),h('p',{},'Forwarded applications from Palm University will appear here.'))),
        h('section',{class:'section-block'},h('div',{class:'section-head'},h('div',{},h('h2',{},'Company presence'),h('p',{},'A complete profile helps students discover and match with your organisation.')),h('button',{class:'text-action icon-button',onclick:()=>{tab='Profile';render();}},icon('arrowRight'),h('span',{},'View profile'))), D.companies.length?coCards():h('div',{class:'empty-panel'},'Company profile not set up yet.'))
      ];
    }
    const n = D.students.length;
    const placed = D.students.filter(x => x.placed).length;
    const placementRate = n ? Math.round(100 * placed / n) : 0;
    const live = D.placements.filter(x => x.status !== 'CANCELLED');
    const active = live.filter(x => x.status === 'ACTIVE').length;
    const pendingPlacements = live.filter(x => x.status === 'PENDING').length;
    const completed = live.filter(x => x.status === 'COMPLETED').length;
    const submitted = D.applications.filter(a => a.status === 'SUBMITTED').length;
    const forwarded = D.applications.filter(a => a.status === 'FORWARDED').length;
    const accepted = D.applications.filter(a => a.status === 'ACCEPTED').length;
    const declined = D.applications.filter(a => a.status === 'DECLINED').length;
    const rejected = D.applications.filter(a => a.status === 'REJECTED').length;
    const withdrawn = D.applications.filter(a => a.status === 'WITHDRAWN').length;
    const slots = D.companies.reduce((a, c) => a + Math.max(0, (Number(c.capacity) || 0) - (Number(c.filled) || 0)), 0);
    const activeCompanies = D.companies.filter(c => (Number(c.filled) || 0) > 0).length;
    const totalCapacity = D.companies.reduce((a,c)=>a+(Number(c.capacity)||0),0);
    const avg = D.evals.length ? (D.evals.reduce((a, e) => a + (e.punctuality + e.skills + e.attitude + e.teamwork) / 4, 0) / D.evals.length).toFixed(1) : '–';
    const hours = D.attendance.reduce((a, w) => a + (w.out ? (new Date(w.out) - new Date(w.in)) / 3600000 : 0), 0).toFixed(0);
    const openActions = [
      submitted && ['Applications awaiting review', submitted + ' student application' + (submitted === 1 ? '' : 's') + ' need your decision.', 'Review applications', 'Applications'],
      Math.max(0, n - placed) && ['Students awaiting placement', Math.max(0, n - placed) + ' student' + (n - placed === 1 ? '' : 's') + ' still need a placement.', 'View students', 'Students'],
      D.companies.filter(c => (Number(c.capacity)||0) > (Number(c.filled)||0)).length === 0 && D.companies.length === 0 && ['Build your partner network', 'No industry partners are registered yet.', 'Add a company', 'Companies']
    ].filter(Boolean).slice(0,3);
    const pipeline = [
      ['Applications', D.applications.length],
      ['Reviewed', D.applications.filter(a => ['FORWARDED','ACCEPTED','DECLINED','REJECTED'].includes(a.status)).length],
      ['Approved', D.applications.filter(a => ['ACCEPTED'].includes(a.status)).length],
      ['Placed', placed],
      ['Active', active],
      ['Completed', completed]
    ];
    const maxPipe = Math.max(1, ...pipeline.map(x=>x[1]));
    const outcome = [['Submitted',submitted,'warn'],['Forwarded',forwarded,'info'],['Accepted',accepted,'ok'],['Declined',declined,'bad'],['Rejected',rejected,'bad'],['Withdrawn',withdrawn,'mute']];
    const maxOutcome = Math.max(1,...outcome.map(x=>x[1]));
    const programme = count(live, x => x.program).slice(0,5);
    const companies = count(live, x => x.company).slice(0,5);
    const latest = D.placements.slice().reverse().slice(0,5);
    const rateRing = h('div',{class:'admin-ring'},h('div',{},h('strong',{},placementRate+'%'),h('span',{},'placement rate')));
    rateRing.style.setProperty('--rate', placementRate * 3.6 + 'deg');
    const go = name => { tab = name; render(); };
    const stat = (label,value,sub,iconName,cls='') => h('div',{class:'admin-kpi '+cls},h('div',{class:'admin-kpi-top'},h('span',{class:'admin-kpi-icon'},icon(iconName)),h('span',{class:'admin-kpi-label'},label)),h('strong',{},value),h('small',{},sub));
    const miniBars = (title, items, action) => {
      const rows = items.length ? items.map(([label,val,max]) => {
        const width = max ? Math.max(4,100*val/max) : 0;
        return h('div',{class:'admin-bar-row'},h('div',{class:'admin-bar-meta'},h('span',{},label),h('b',{},val)),h('div',{class:'admin-bar-track'},h('i',{style:'width:'+width+'%'})));
      }) : h('div',{class:'empty-panel'},h('strong',{},'No data yet'),h('p',{},'Activity will appear here as the programme grows.'));
      return h('div',{class:'admin-panel admin-chart-panel'},h('div',{class:'admin-panel-head'},h('div',{},h('h3',{},title),h('p',{},'Live system distribution')),action?h('button',{class:'text-action icon-button',onclick:()=>go(action[0])},icon('arrowRight'),h('span',{},action[1])):null),rows);
    };
    const actionRows = openActions.length ? openActions.map((a,i) => {
      return h('button',{class:'admin-action-row '+(i===0?'urgent':''),onclick:()=>go(a[3])},
        h('span',{class:'admin-action-index'},String(i+1).padStart(2,'0')),
        h('span',{class:'admin-action-copy'},h('strong',{},a[0]),h('small',{},a[1])),
        h('span',{class:'admin-action-cta'},icon('arrowRight'),h('span',{},a[2])));
    }) : h('div',{class:'admin-clear-state'},
      h('span',{class:'admin-clear-icon'},icon('checkCircle')),
      h('div',{},h('strong',{},'You are all caught up'),h('p',{},'There are no high-priority actions requiring attention right now.')));
    const outcomeRows = outcome.map(([label,val,kind]) => {
      const width = Math.max(val ? 5 : 0, 100*val/maxOutcome);
      return h('div',{class:'admin-outcome-row'},h('span',{},label),h('div',{class:'admin-outcome-track'},h('i',{class:kind,style:'width:'+width+'%'})),h('b',{},val));
    });
    const capacityPct = totalCapacity ? Math.round(100*(totalCapacity-slots)/totalCapacity) : 0;
    const capacityBar = h('div',{class:'admin-capacity-track'},h('i',{style:'width:'+capacityPct+'%'}));
    const pipelineNode = (label,val,i) => h('div',{class:'admin-pipeline-node'},h('div',{class:'admin-pipeline-dot '+(val?'has-data':'')},val||'0'),h('strong',{},label),h('small',{},i===0?'Intake':i===5?'Outcome':(i<3?'Review':'Placement')));
    return [
      h('section',{class:'admin-command-hero'},
        h('div',{class:'admin-command-copy'},h('span',{class:'eyebrow'},'ADMIN CONTROL CENTRE'),h('h1',{},'Internship programme at a glance.'),h('p',{},'A live operational view of Palm’s internship ecosystem — what is moving, what needs attention and where students are in the journey.'),h('div',{class:'admin-hero-meta'},h('span',{},'2026 / 27 internship cycle'),h('i',{}),h('span',{},'Live programme data'))),
        h('div',{class:'admin-command-ring'},rateRing,h('div',{},h('small',{},'PLACEMENT HEALTH'),h('strong',{},placementRate+'% of students placed'),h('span',{},Math.max(0,n-placed)+' still seeking placement')))
      ),
      h('section',{class:'admin-kpi-grid'},
        stat('Students',n,'Registered in the portal','student','primary'),
        stat('Active placements',active,'Students currently interning','briefcase','success'),
        stat('Needs review',submitted,'Applications awaiting your decision','file','warning'),
        stat('Open capacity',slots,'Available partner slots','building','gold'),
        stat('Completed',completed,'Internships completed','workflow'),
        stat('Evaluations',D.evals.length,'Company evaluations received','star'),
        stat('Hours logged',hours,'Total recorded hours','clock'),
        stat('Partner companies',D.companies.length,'Registered organisations','building')
      ),
      h('section',{class:'admin-attention-layout'},
        h('div',{class:'admin-panel admin-attention'},h('div',{class:'admin-panel-head'},h('div',{},h('span',{class:'admin-section-kicker'},'ACTION REQUIRED'),h('h3',{},'What needs your attention?'),h('p',{},'Priority items are surfaced here so nothing important gets buried.')),h('span',{class:'admin-live-dot'},'Live')),
          actionRows) ,
        h('div',{class:'admin-panel admin-health'},h('div',{class:'admin-panel-head'},h('div',{},h('span',{class:'admin-section-kicker'},'PROGRAMME HEALTH'),h('h3',{},'Placement performance'),h('p',{},'The current placement picture across the university.'))),h('div',{class:'admin-health-body'},rateRing,h('div',{class:'admin-health-stats'},h('div',{},h('strong',{},placed),h('span',{},'Students placed')),h('div',{},h('strong',{},n-placed),h('span',{},'Awaiting placement')),h('div',{},h('strong',{},slots),h('span',{},'Open company slots')))))
      ),
      h('section',{class:'admin-panel admin-pipeline'},h('div',{class:'admin-panel-head'},h('div',{},h('span',{class:'admin-section-kicker'},'INTERNSHIP LIFECYCLE'),h('h3',{},'From application to completion'),h('p',{},'Follow the volume moving through each major stage.')),h('button',{class:'text-action icon-button',onclick:()=>go('Applications')},icon('arrowRight'),h('span',{},'Open applications'))),
        h('div',{class:'admin-pipeline-track'},pipeline.map((x,i)=>[pipelineNode(x[0],x[1],i),i<pipeline.length-1?h('span',{class:'admin-pipeline-arrow'},icon('arrowRight')):null]).flat()),
        h('div',{class:'admin-pipeline-foot'},h('span',{},'Programme capacity: '+totalCapacity),h('span',{},'Active companies: '+activeCompanies),h('span',{},'Pending placements: '+pendingPlacements))
      ),
      h('section',{class:'admin-analytics-grid'},
        miniBars('Placements by programme',programme.map(x=>[x[0],x[1],programme[0]?.[1]||1]),['Students','View students']),
        miniBars('Top placement partners',companies.map(x=>[x[0],x[1],companies[0]?.[1]||1]),['Companies','View companies']),
        h('div',{class:'admin-panel admin-outcomes'},h('div',{class:'admin-panel-head'},h('div',{},h('h3',{},'Application outcomes'),h('p',{},'How applications are progressing'))),outcomeRows)
      ),
      h('section',{class:'admin-bottom-grid'},
        h('div',{class:'admin-panel admin-recent'},h('div',{class:'admin-panel-head'},h('div',{},h('span',{class:'admin-section-kicker'},'RECENT ACTIVITY'),h('h3',{},'Latest placements'),h('p',{},'The newest students moving into internship.') ),h('button',{class:'text-action icon-button',onclick:()=>go('Placements')},icon('arrowRight'),h('span',{},'View all'))),
          latest.length?latest.map((r,i)=>h('div',{class:'admin-recent-row'},h('span',{class:'admin-recent-number'},String(i+1).padStart(2,'0')),h('div',{},h('strong',{},r.student),h('small',{},r.company+' · '+r.date)),pill(r.status))):h('div',{class:'empty-panel'},h('strong',{},'No placements yet'),h('p',{},'New placements will appear here as students are assigned.'))),
        h('div',{class:'admin-panel admin-snapshot'},h('div',{class:'admin-panel-head'},h('div',{},h('span',{class:'admin-section-kicker'},'EXECUTIVE SNAPSHOT'),h('h3',{},'At a glance'),h('p',{},'The numbers behind the programme.'))),h('div',{class:'admin-snapshot-grid'},h('div',{},h('strong',{},placementRate+'%'),h('span',{},'Placement rate')),h('div',{},h('strong',{},avg),h('span',{},'Average rating')),h('div',{},h('strong',{},hours),h('span',{},'Hours logged')),h('div',{},h('strong',{},forwarded),h('span',{},'Forwarded applications'))),h('div',{class:'admin-capacity'},h('div',{},h('span',{},'Company capacity utilisation'),h('b',{},capacityPct+'%')),capacityBar))
      )
    ];
  },
  Applications: () => {
    const g = appGroups(D.applications), admin = D.role === 'ADMIN';
    const head = admin ? ['Ref', 'Student', 'Programme', 'Company', 'Statement', 'Status', 'Messages', ''] : ['Ref', 'Student', 'Programme', 'Statement', 'Status', ''];
    const rows = g[appTab].slice().reverse().map(a => admin
      ? [a.id, a.student, a.program, a.company, a.statement, pill(a.status), notes(a), a.status === 'SUBMITTED' ? [btn('Review CV and decide', () => review(a, 'school'))] : letterBtns(a)]
      : [a.id, a.student, a.program, a.statement, pill(a.status), a.status === 'FORWARDED' ? btn('Review CV and decide', () => review(a, 'company')) : letterBtns(a)]);
    return [h('h1', {}, admin ? 'Applications' : 'Applications from Palm University'), h('div', { id: 'review' }),
      admin && !D.applications.length ? h('p', { class: 'msg' }, 'No applications yet. An application appears here after a student uploads a CV and presses "Submit application".') : null,
      appTabs(g), table(head, rows), h('div', { id: 'letter' })];
  },
  'My Application': () => {
    const open = D.applications.some(a => ['SUBMITTED', 'FORWARDED'].includes(a.status));
    return [h('h1', {}, 'My internship application'), internshipCard(), deadlineNote(),
      D.me.placed || open ? null : cvCard(),
      D.me.placed || open ? null : h('h2', {}, 'Step 2: Apply to a company'),
      D.me.placed || open ? null : h('div', { class: 'card form' },
        h('label', {}, 'Preferred company (pick from the list or type your own)', h('input', { id: 'ac', list: 'cl', placeholder: 'Click to choose, or start typing', maxlength: 60, autocomplete: 'off', value: (() => { const p = prefill; prefill = ''; return p; })() }),
          h('datalist', { id: 'cl' }, D.companies.map(c => h('option', { value: c.name, label: c.name + ' · ' + (c.capacity - c.filled) + ' open' })))),
        h('label', {}, 'Why do you want this placement?', h('textarea', { id: 'stm', maxlength: 400, rows: 4 })),
        h('button', { onclick: () => act('/api/apply', { company: v('ac'), statement: v('stm') }, 'Application submitted to the school') }, 'Submit application')),
      D.applications.length ? D.applications.slice().reverse().map(a => h('div', { class: 'card' }, h('b', {}, a.company), h('small', {}, ' · updated ' + a.date), h('p', {}, a.statement), track(a.status), h('p', { class: 'msg' }, h('b', {}, STATUS_MSG[a.status] || a.status), a.schoolNote ? h('span', { class: 'sm' }, 'Message from the school: ' + a.schoolNote) : null), h('div', {}, letterBtns(a), ['SUBMITTED', 'FORWARDED'].includes(a.status) ? btn('Withdraw application', () => { if (confirm('Withdraw this application?')) act('/api/withdraw', { app: a.id }, 'Application withdrawn'); }) : null))) : h('p', { class: 'empty' }, 'No application yet.'),
      h('div', { id: 'letter' })];
  },
  Attendance: () => D.role === 'STUDENT'
    ? [h('h1', {}, 'Attendance'),
       D.me.placement === 'ACTIVE'
         ? (() => { const open = D.attendance.find(w => !w.out);
             return h('div', { class: 'card' }, h('b', {}, 'Internship at ' + D.me.company),
               h('p', {}, open ? 'You clocked in at ' + fmt(open.in) + '. Remember to clock out when you finish.' : 'You are not clocked in.'),
               h('div', { class: 'form' }, open ? pbtn('Clock out', () => act('/api/clock', { action: 'out' }, 'Clocked out')) : pbtn('Clock in', () => act('/api/clock', { action: 'in' }, 'Clocked in')))); })()
         : h('p', { class: 'msg' }, D.me.placement === 'PENDING' ? 'Your placement is approved. Attendance opens as soon as your company or the school marks your internship as started.' : 'Attendance is available once your internship has been approved and has started.'),
       h('h2', {}, 'My records'), attTable(D.attendance, false)]
    : [h('h1', {}, 'Attendance'), h('p', {}, D.attendance.filter(w => !w.out).length + ' intern(s) currently clocked in. Times come from the server clock.'), attTable(D.attendance, true)],
  Notifications: () => [h('h1', {}, 'Notifications'),
    D.me.unread ? h('div', { class: 'form' }, pbtn('Mark all as read', () => act('/api/notesread', {}, 'All marked as read'))) : null,
    D.notes.length ? D.notes.map(n => h('div', { class: 'card' + (n.read ? '' : ' cred') }, h('small', { class: 'tag' }, fmt(n.at)), h('p', {}, n.text))) : h('p', { class: 'empty' }, 'Nothing yet. Updates about applications and internships appear here.')],
  Logbook: () => D.role === 'STUDENT'
    ? [h('h1', {}, 'Weekly logbook'),
       D.me.placement === 'ACTIVE' ? h('div', { class: 'card' }, h('p', {}, 'Write a short summary of what you did this week. Your supervisor at ' + D.me.company + ' reviews it.'),
         h('label', {}, 'This week', h('textarea', { id: 'lgb', maxlength: 1500, rows: 5 })), h('div', { class: 'form' }, pbtn('Submit entry', () => act('/api/log', { body: v('lgb') }, 'Logbook entry sent'))))
         : h('p', { class: 'msg' }, 'The logbook opens once your internship is Active.'),
       h('h2', {}, 'My entries'), D.logs.length ? D.logs.map(l => logCard(l, false)) : h('p', { class: 'empty' }, 'No entries yet.')]
    : D.role === 'COMPANY'
    ? [h('h1', {}, 'Intern logbooks'), h('p', {}, 'Approve an entry, or return it with a comment.'), D.logs.length ? D.logs.map(l => logCard(l, true)) : h('p', { class: 'empty' }, 'No entries yet.')]
    : [h('h1', {}, 'Logbooks'), table(['Week', 'Student', 'Company', 'Status', 'Entry', 'Supervisor note'], D.logs.map(l => [l.week, l.student, l.company, pill(l.status), l.body, l.note || '-']))],
  Settings: () => { const s = D.settings; return [h('h1', {}, 'Settings'),
    h('div', { class: 'card' }, h('h2', {}, 'Applications and data'),
      h('div', { class: 'form' }, field('dl', 'Application deadline (empty = none)', 'date', '', s.deadline), field('rd', 'Keep CV files for (days)', 'number', '730', s.retentionDays),
        pbtn('Save settings', () => act('/api/settings', { deadline: v('dl'), retentionDays: v('rd') }, 'Settings saved'))),
      h('p', { class: 'sm' }, 'Server: ' + (s.production ? 'production mode' : 'development mode') + '. Email sending is ' + (s.mail ? 'ON: verification codes, reset codes and notifications are emailed.' : 'OFF: set the SMTP settings so students can verify their email.'))),
    h('div', { class: 'card' }, h('h2', {}, 'Student List'),
      h('p', {}, s.registerCount ? 'Only the ' + s.registerCount + ' students on the approved list can sign up.' : 'No approved student list is loaded. Public student registration is closed until the list is populated.'),
      h('button', { class: 'icon-button', onclick: () => { tab = 'Student List'; render(); } }, ...iconButtonChildren('Manage Student List', 'list'))),
    h('div', { class: 'card' }, h('h2', {}, 'School staff'), h('p', { class: 'sm' }, 'Staff accounts: ' + D.staff.join(', ')),
      h('div', { class: 'form' }, field('stf', 'Staff email', 'email', 'name@palm.edu.gh'), pbtn('Add staff member', () => act('/api/staff', { email: v('stf') }, 'Staff account created'))))]; },
  Audit: () => [h('h1', {}, 'Audit log'), h('p', {}, 'Who did what, newest first (the latest 300 entries). Download the whole log as CSV.'),
    h('div', { class: 'form' }, pbtn('⬇ Audit log (CSV)', () => downloadCsv('audit'))),
    table(['When', 'Who', 'Action', 'Detail'], D.audit.map(a => [fmt(a.at), a.actor, a.action, a.detail]))],
  Opportunities: () => feed('AD', 'Internship opportunities', 'Openings advertised by partner companies.'),
  News: () => feed('UPDATE', 'Company news', 'Updates, progress and milestones shared by partner companies.'),
  Discover: () => [h('h1', {}, 'Discover companies'),
    h('div', { class: 'card form' }, field('sq', 'Search for a company or industry', 'text', 'e.g. software, banking, agriculture'), h('button', { class: 'icon-button', onclick: runSearch }, ...iconButtonChildren('Search'))), h('div', { id: 'sr' })],
  Profile: () => {
    const c = D.companies[0] || {}, filled = !!(c.about || c.website || c.email || c.focus || c.industry);
    if (filled && !editingProfile) return [h('h1', {}, 'Company profile'),
      h('div', { class: 'card' }, h('small', { class: 'tag' }, 'Current profile'), h('h2', {}, c.name),
        c.industry ? h('p', {}, h('b', {}, 'Industry: '), c.industry) : null,
        c.focus ? h('p', {}, h('b', {}, 'Focus areas and skills sought: '), c.focus) : null,
        c.about ? h('p', {}, c.about) : h('p', { class: 'sm' }, 'No business description yet.'),
        site(c.website), c.email ? h('p', { class: 'sm' }, 'Contact: ' + c.email) : null,
        h('p', { class: 'sm' }, c.filled + ' of ' + c.capacity + ' intern places filled'),
        h('div', { class: 'form' }, pbtn('Edit profile', () => { editingProfile = true; render(); }))),
      h('p', { class: 'sm' }, 'Students see this profile under Companies and Discover, and the AI match reads it. You can edit it at any time.')];
    const save = async () => { try { await api('/api/profile', { name: v('pn'), industry: v('pi'), focus: v('pf'), website: v('pw2'), email: v('pe'), about: v('pa') }); toast('Profile saved'); editingProfile = false; await refresh(); } catch (e) {} };
    return [h('h1', {}, filled ? 'Edit company profile' : 'Set up your company profile'),
      h('div', { class: 'card form' }, field('pn', 'Company name', 'text', '', c.name), field('pi', 'Industry', 'text', '', c.industry), field('pf', 'Focus areas and skills you look for', 'text', 'e.g. software, networks, data', c.focus),
        field('pw2', 'Website', 'url', 'https://', c.website), field('pe', 'Contact email', 'email', '', c.email),
        h('label', {}, 'About your business', h('textarea', { id: 'pa', maxlength: 600, rows: 4 }, c.about || '')),
        h('button', { class: 'icon-button', onclick: save }, ...iconButtonChildren('Save profile'))),
      filled ? btn('← Cancel and go back to the profile', () => { editingProfile = false; render(); }) : null,
      h('p', { class: 'sm' }, 'The AI match reads this profile, so a fuller profile means better matches for your business.')];
  },
  Post: () => [h('h1', {}, 'Post to students and the school'),
    h('div', { class: 'card form' }, h('label', {}, 'Type', sel('pt', [['AD', 'Internship opportunity'], ['UPDATE', 'Business update or milestone']])), field('ptl', 'Title'),
      h('label', {}, 'Details', h('textarea', { id: 'pb', maxlength: 600, rows: 4 })),
      h('button', { class: 'icon-button', onclick: () => act('/api/post', { type: v('pt'), title: v('ptl'), body: v('pb') }, 'Posted') }, ...iconButtonChildren('Publish'))),
    h('h2', {}, 'Your posts'), D.posts.length ? D.posts.map(postCard) : h('p', { class: 'empty' }, 'No posts yet.')],
  Account: () => [h('h1', {}, 'Account'),
    h('div', { class: 'card form' }, field('op', 'Current password', 'password'), field('np', 'New password (8+ characters, a letter and a number)', 'password'),
      h('button', { class: 'icon-button', onclick: () => act('/api/password', { old: v('op'), password: v('np') }, 'Password changed') }, ...iconButtonChildren('Change password'))),
    D.role === 'STUDENT' ? h('div', { class: 'card' }, h('h2', {}, 'Delete my account'), h('p', { class: 'sm' }, 'This removes your account, CV, applications, logbook and notifications. If you already have a placement the school must keep your records, and you should ask the school office instead.'),
      h('div', { class: 'form' }, field('dp', 'Your password', 'password'), btn('Delete my account and data', async () => { if (!confirm('Delete your account and all your data? This cannot be undone.')) return; try { await api('/api/deleteme', { password: v('dp') }); toast('Your account was deleted'); showLogin(); } catch (e) {} }))) : null],
  Resets: () => [h('h1', {}, 'Password reset requests'), h('p', {}, 'Requests the portal could not email (mail is off, or the person has no email on file) appear here. Hand the code over after confirming who is asking. Codes expire after 30 minutes.'),
    table(['Account', 'Code', 'Expires in'], D.resets.map(r => [r.username, r.code, r.minutes + ' min']))],
  Approvals: () => [h('h1', {}, 'Company approvals'), h('p', {}, 'Every response from partner companies to applications the school forwarded.'),
    table(['Ref', 'Student', 'Programme', 'Company', 'Decision', 'Company feedback', 'Date', ''], D.applications.filter(a => ['ACCEPTED', 'DECLINED'].includes(a.status)).reverse().map(a => [a.id, a.student, a.program, a.company, pill(a.status), a.companyNote || '-', a.date, [letterBtns(a), msgBtns(a)]])), h('div', { id: 'letter' })],
  Students: () => [h('h1', {}, 'Students'),
    h('p', {}, 'Student accounts are created through the approved Student List. Manage eligibility and access requests from that page.'),
    h('div', { id: 'match' }), h('div', { id: 'review' }),
    table(['ID', 'Name', 'Programme', 'Email', 'CV', 'Status', ''], D.students.map(s => [s.id, s.name, s.program, s.email || '-', s.cv ? btn('📄 View CV', () => studentCv(s)) : h('span', { class: 'sm' }, 'none'), pill(s.placed ? 'PLACED' : 'OPEN'),
      [s.placed ? null : btn('✦ Smart match', () => findMatch(s)), btn('Erase data', () => { if (confirm('Erase ' + s.name + ' and all their data? This cannot be undone.')) act('/api/erase', { sid: s.id }, 'Student data erased'); })]]))],
  'Student List': () => {
    const entries = D.studentList || [], requests = D.accessRequests || [];
    const registered = entries.filter(x => x.registered).length;
    const pendingReq = requests.filter(x => x.status === 'PENDING').length;
    const input = h('textarea', { id: 'student-import', rows: '9', placeholder: 'student@palm.edu.gh, Student Name\nstudent2@palm.edu.gh, Another Student' });
    const file = h('input', { id: 'student-file', type: 'file', accept: '.csv,.txt,.tsv', hidden: '' });
    const preview = h('div', { id: 'student-import-preview' });
    const parse = text => {
      const seen = new Set(), valid = [], invalid = [];
      const lines = String(text || '').replace(/^\uFEFF/, '').split(/\r?\n/).map(line => line.trim()).filter(Boolean);
      if (lines.length && /^email(?:address)?$/i.test(lines[0].split(/[,;\t]/, 1)[0].trim())) lines.shift();
      lines.forEach(line => {
        const [rawEmail = '', ...nameParts] = line.split(/[,;\t]/);
        const email = rawEmail.trim().toLowerCase(), name = nameParts.join(',').trim();
        if (!/^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$/.test(email)) { invalid.push([email, 'Invalid email']); return; }
        if (seen.has(email)) { invalid.push([email, 'Duplicate in import']); return; }
        seen.add(email);
        valid.push({ email, name });
      });
      return { valid, invalid };
    };
    const renderPreview = text => {
      const result = parse(text);
      preview.replaceChildren(
        h('div', { class: 'import-summary' },
          h('span', { class: 'import-good' }, icon('checkCircle'), result.valid.length + ' valid'),
          h('span', { class: 'import-bad' }, icon('xCircle'), result.invalid.length + ' rejected')),
        result.invalid.length ? h('p', { class: 'sm' }, 'Rejected entries are shown below and will not be imported.') : null,
        result.invalid.length ? table(['Value', 'Reason'], result.invalid) : null,
        result.valid.length ? table(['Email', 'Name'], result.valid.slice(0, 100).map(x => [x.email, x.name || '-'])) : h('p', { class: 'empty' }, 'Paste a list or choose a file to preview it.'));
      input.dataset.parsed = JSON.stringify(result.valid);
    };
    input.addEventListener('input', () => renderPreview(input.value));
    file.addEventListener('change', () => {
      const selected = file.files && file.files[0];
      if (!selected) return;
      const reader = new FileReader();
      reader.onload = () => { input.value = String(reader.result || ''); renderPreview(input.value); };
      reader.onerror = () => toast('Could not read the selected file');
      reader.readAsText(selected);
    });
    const rows = () => JSON.parse(input.dataset.parsed || '[]');
    const makeList = items => items.map(x => x.email + (x.name ? ',' + x.name : '')).join('\n');
    const importBtn = pbtn('Import valid students', () => {
      const parsed = rows();
      if (!parsed.length) return toast('Nothing valid to import');
      act('/api/register', { list: makeList(parsed), mode: 'merge' }, 'Student list imported');
    });
    const replaceBtn = btn('Replace entire list', () => {
      const parsed = rows();
      if (!parsed.length) return toast('Nothing valid to import');
      if (!confirm('Replace the entire approved student list with this import? This will remove students not present in the new list from eligibility. Existing accounts are not deleted.')) return;
      act('/api/register', { list: makeList(parsed), mode: 'replace' }, 'Student list replaced');
    });
    const clearBtn = btn('Clear approved list', () => {
      if (!entries.length) return toast('The approved list is already empty');
      if (!confirm('Clear the approved student list? Student registration will close. Existing accounts will not be deleted.')) return;
      act('/api/register', { list: '', mode: 'replace' }, 'Approved list cleared');
    });
    const template = btn('Download template', () => {
      const blob = new Blob(['email,name\nstudent@palm.edu.gh,Student Name\n'], { type: 'text/csv' });
      const link = document.createElement('a');
      link.href = URL.createObjectURL(blob);
      link.download = 'palm-student-list-template.csv';
      link.click();
      setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    });
    const accessRows = requests.map(r => [r.name, r.email, r.program, r.reason || '-', r.date, pill(r.status),
      r.status === 'PENDING'
        ? [pbtn('Approve access', () => act('/api/access', { id: r.id, decision: 'approve' }, 'Student access approved')), btn('Reject access', () => act('/api/access', { id: r.id, decision: 'reject' }, 'Access request rejected'))]
        : h('span', { class: 'sm' }, 'Processed')]);
    const importHistory = D.audit.filter(a => a.action === 'student_list_changed').slice(0, 8);
    return [
      h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, 'Student List'), h('p', { class: 'sm' }, 'Control which students are eligible to create internship portal accounts. This list is the registration authorization source.'))),
      h('div', { class: 'metric-grid compact' }, [
        ['list', 'Eligible students', entries.length],
        ['userPlus', 'Registered accounts', registered],
        ['clock', 'Not yet registered', entries.length - registered],
        ['alert', 'Access requests', pendingReq]
      ].map(([i, label, value]) => h('div', { class: 'metric-card' }, h('span', { class: 'metric-icon' }, icon(i)), h('span', { class: 'metric-label' }, label), h('strong', {}, value)))),
      h('div', { class: 'student-list-security card' }, h('div', { class: 'security-icon' }, icon('shieldCheck')), h('div', {},
        h('strong', {}, 'Registration gate is active'),
        h('p', { class: 'sm' }, 'Only email addresses on this approved list can create student accounts. If the list is empty, public student registration is closed. Email verification is still required when mail is configured.'))),
      h('div', { class: 'split-grid' },
        h('section', { class: 'card' }, h('div', { class: 'section-head' }, h('div', {}, h('h2', {}, 'Add or import students'), h('p', { class: 'sm' }, 'Use one email per line. Optional format: email,name.'))),
          input, h('div', { class: 'form-actions' }, btn('Choose CSV / TXT file', () => file.click()), template, importBtn, replaceBtn, clearBtn), file, preview),
        h('section', { class: 'card' }, h('h2', {}, 'Current approved list'), h('p', { class: 'sm' }, 'Merge imports to add students without removing existing eligibility.'),
          entries.length ? table(['Email', 'Name', 'Status'], entries.map(x => [x.email, x.name || '-', pill(x.registered ? 'REGISTERED' : 'ELIGIBLE')])) : h('div', { class: 'empty-panel' }, h('strong', {}, 'No students on the approved list')))),
      h('section', { class: 'card' }, h('div', { class: 'section-head' }, h('div', {}, h('h2', {}, 'Access requests'), h('p', { class: 'sm' }, 'Students who are missing from the list can request access. Their account is not created until an administrator approves the request.'))),
        requests.length ? table(['Student', 'Email', 'Programme', 'Reason', 'Date', 'Status', 'Action'], accessRows) : h('div', { class: 'empty-panel' }, h('strong', {}, 'No access requests'), h('p', {}, 'Requests from students who are not on the approved list will appear here.'))),
      h('section', { class: 'card' }, h('h2', {}, 'Student List activity'), h('p', { class: 'sm' }, 'Recent changes to the approved list are recorded in the audit log.'),
        importHistory.length ? table(['When', 'Administrator', 'Action', 'Details'], importHistory.map(a => [fmt(a.at), a.actor, icon('history'), a.detail])) : h('div', { class: 'empty-panel' }, h('strong', {}, 'No import activity yet'))),
      h('div', { class: 'card info-panel' }, h('h3', {}, 'Import safeguards'), h('ul', {},
        h('li', {}, 'Invalid emails and duplicates are rejected before import.'),
        h('li', {}, 'Merge is the default so an import cannot accidentally wipe the existing list.'),
        h('li', {}, 'Replacing the list requires an explicit confirmation.'),
        h('li', {}, 'Every list change and access decision is written to the audit log.')))
    ];
  },
  Companies: () => [h('h1', {}, 'Partner companies'),
    D.role === 'ADMIN' ? h('div', { class: 'card form' }, field('cn', 'Company name'), field('ci', 'Industry'), field('cf', 'Focus areas', 'text', 'e.g. software, networks, data'), field('cc', 'Intern capacity', 'number', '5'), field('ce', 'Contact email (to send the login)', 'email', 'hr@company.com'),
      h('button', { class: 'icon-button', onclick: () => act('/api/company', { name: v('cn'), industry: v('ci'), focus: v('cf'), capacity: v('cc'), email: v('ce') }, 'Company registered') }, ...iconButtonChildren('Register company'))) : null,
    D.role === 'ADMIN' ? accountsTable() : null,
    coCards()],
  Placements: () => { const mine = D.placements.filter(p => p.status !== 'CANCELLED'); return [h('h1', {}, 'Placements'),
    h('div', { class: 'card form' },
      h('label', {}, 'Student', sel('as', D.students.filter(s => !s.placed).map(s => [s.id, s.name]))),
      h('label', {}, 'Company', sel('ac', D.companies.filter(c => c.filled < c.capacity).map(c => [c.id, c.name + ' (' + (c.capacity - c.filled) + ' open)']))),
      h('button', { class: 'icon-button', onclick: () => act('/api/assign', { sid: v('as'), cid: v('ac') }, 'Placement created') }, ...iconButtonChildren('Assign directly'))),
    mine.length ? datesForm(mine) : null,
    table(['Student', 'Programme', 'Company', 'Placed', 'Start', 'End', 'Status'], D.placements.slice().reverse().map(r => [r.student, r.program, r.company, r.date, r.start || '-', r.end || '-', r.status === 'CANCELLED' ? pill(r.status) : stSel(r)]))]; },
  Interns: () => { const mine = D.placements.filter(p => p.status !== 'CANCELLED'), rep = r => D.reports.find(k => k.sid === r.sid && k.cid === r.cid), ev = r => D.evals.find(k => k.sid === r.sid && k.cid === r.cid);
    const rate = id => h('label', {}, id[0].toUpperCase() + id.slice(1), sel('ev-' + id, [[5, '5 · Excellent'], [4, '4 · Good'], [3, '3 · Satisfactory'], [2, '2 · Needs work'], [1, '1 · Poor']]));
    return [h('h1', {}, 'Our interns'),
      mine.length ? datesForm(mine) : null,
      mine.length ? h('div', { class: 'card form' }, h('label', {}, 'Intern', sel('rsid', mine.map(p => [p.sid, p.student]))),
        field('rsm', 'Summary of the report (optional)', 'text', 'e.g. Excellent performance, recommended for hire'),
        h('label', {}, 'Final report on the intern (PDF, max 5 MB)', h('input', { id: 'rpf', type: 'file', accept: '.pdf,application/pdf' })),
        h('button', { class: 'icon-button', onclick: uploadReport }, ...iconButtonChildren('Upload final report'))) : null,
      mine.length ? h('div', { class: 'card' }, h('h2', {}, 'End-of-internship evaluation'),
        h('div', { class: 'form' }, h('label', {}, 'Intern', sel('evsid', mine.map(p => [p.sid, p.student]))), ['punctuality', 'skills', 'attitude', 'teamwork'].map(rate),
          h('label', {}, 'Comment', h('textarea', { id: 'evc', rows: 2, maxlength: 500 })),
          pbtn('Save evaluation', () => act('/api/eval', { sid: v('evsid'), punctuality: v('ev-punctuality'), skills: v('ev-skills'), attitude: v('ev-attitude'), teamwork: v('ev-teamwork'), comment: v('evc') }, 'Evaluation saved')))) : null,
      table(['Student', 'Start', 'End', 'Status', 'Evaluation', 'Final report', ''], D.placements.slice().reverse().map(r => [r.student, r.start || '-', r.end || '-', pill(r.status),
        ev(r) ? [icon('checkCircle'), ev(r).date] : 'not yet', rep(r) ? [icon('checkCircle'), rep(r).date, btn('Download', () => reportAct(rep(r), 'download'))] : 'not yet',
        [r.status === 'PENDING' ? btn('Mark as started', () => act('/api/start', { sid: r.sid }, 'Internship marked as started')) : null,
         r.status === 'ACTIVE' ? btn('Complete internship', () => { if (confirm('Mark this internship as completed?')) act('/api/complete', { sid: r.sid }, 'Internship completed'); }) : null]]))]; },
  Reports: () => [h('h1', {}, 'Reports'), h('p', {}, 'Download any report as a CSV file (opens in Excel). The dates narrow the placements, applications, attendance and evaluations reports.'),
    h('div', { class: 'card form' }, field('rf', 'From', 'date'), field('rt', 'To', 'date'), ['students', 'companies', 'placements', 'applications', 'attendance', 'evaluations'].map(t => pbtn('⬇ ' + t[0].toUpperCase() + t.slice(1), () => downloadCsv(t)))),
    h('h2', {}, 'Evaluations by companies'),
    table(['Student', 'Company', 'Punctuality', 'Skills', 'Attitude', 'Teamwork', 'Average', 'Comment'], D.evals.map(e => [e.student, e.company, e.punctuality, e.skills, e.attitude, e.teamwork, ((e.punctuality + e.skills + e.attitude + e.teamwork) / 4).toFixed(1), e.comment || '-'])),
    h('h2', {}, 'Final reports from companies'), h('p', {}, 'Reports on each intern, uploaded by the company. View, download or print them.'),
    h('div', { id: 'review' }),
    table(['Student', 'Company', 'Summary', 'Date', ''], D.reports.map(k => [k.student, k.company, k.summary || '-', k.date,
      [btn('View', () => reportAct(k, 'view')), btn('Download', () => reportAct(k, 'download')), btn('Print', () => reportAct(k, 'print'))]]))]
};

const HERO = {
  ADMIN: ['Palm Internship Management System', 'Coordinate placements with clarity', 'Bring students, university staff and industry partners together through one connected internship workflow.'],
  STUDENT: ['Palm Internship Management System', 'Your professional journey starts here', 'Discover opportunities, apply with confidence and keep every stage of your internship journey organised.'],
  COMPANY: ['Palm Internship Management System', "Connect with Palm's next generation of talent", 'Review candidates, manage interns and contribute to meaningful workplace learning through one connected workspace.']
};
const hero = () => tab !== NAV[D.role][0] || D.role === 'ADMIN' ? null : h('section', { class: 'hero' }, h('small', {}, HERO[D.role][0]), h('h1', {}, HERO[D.role][1]), h('p', {}, HERO[D.role][2]));

function render() {
  if (lastTab !== tab) {
    if (lastTab && !suppressTabHistory) tabHistory.push(lastTab);
    const g0 = groupOf(tab, D.role); if (g0) openGroups.add(g0);
    lastTab = tab;
  }
  const go = t => { if (t === tab) return; tab = t; menuOpen = false; render(); window.scrollTo({ top: 0 }); };
  const goBack = () => {
    if (!tabHistory.length) return;
    tab = tabHistory.pop();
    suppressTabHistory = true;
    menuOpen = false;
    render();
    suppressTabHistory = false;
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };
  const item = t => h('button', { class: 'nv' + (t === tab ? ' on' : ''), 'aria-current': t === tab ? 'page' : 'false', onclick: () => go(t) }, icon(ICON[t] || 'grid'), lab(t));
  const group = ([label, pages]) => {
    const open = openGroups.has(label);
    return h('div', { class: 'grp' + (open ? ' open' : '') },
      h('button', { class: 'nv gh' + (pages.includes(tab) ? ' here' : ''), 'aria-expanded': open ? 'true' : 'false', onclick: () => { open ? openGroups.delete(label) : openGroups.add(label); render(); } },
        icon(ICON[label] || 'grid'), h('span', {class:'nav-label'}, label), icon(open ? 'arrowLeft' : 'arrowRight', 'chev-icon')),
      open ? h('div', { class: 'sub' }, pages.map(p => h('button', { class: 'nv sb' + (p === tab ? ' on' : ''), 'aria-current': p === tab ? 'page' : 'false', onclick: () => go(p) }, lab(p)))) : null);
  };
  const g = groupOf(tab, D.role);
  $('#app').replaceChildren(h('a', { class: 'skip', href: '#main' }, 'Skip to content'), h('div', { class: 'shell' },
    h('aside', { class: 'side' + (menuOpen ? ' open' : ''), 'aria-label': 'Main menu' },
      h('div', { class: 'sb-brand' }, logo(), h('div', {}, h('b', {}, 'Palm University'), h('small', {}, 'Internship Portal'))),
      h('div', { class: 'menu', role: 'navigation', 'aria-label': 'Pages' }, MENU[D.role].map(x => typeof x === 'string' ? item(x) : group(x))),
      h('div', { class: 'sb-foot' },
        h('div', { class: 'sb-user' }, h('b', {}, D.me.name), h('small', {}, D.role + ' · ' + (BASE || location.host).replace('http://', ''))),
        h('button', { class: 'ghost icon-button', onclick: async () => { try { await api('/api/logout', {}); } catch (e) {} showLogin(); } }, ...iconButtonChildren('Sign out', 'logout')))),
    h('div', { class: 'scrim' + (menuOpen ? ' on' : ''), onclick: () => { menuOpen = false; render(); } }),
    h('div', { class: 'content' },
      h('div', { class: 'topbar' },
      tabHistory.length ? h('button', { class: 'topbar-back', type: 'button', title: 'Go back to the previous page', 'aria-label': 'Go back to the previous page', onclick: goBack }, icon('arrowLeft'), h('span', {}, 'Back')) : null,
      h('button', { class: 'burger', 'aria-label': 'Open the menu', 'aria-expanded': menuOpen ? 'true' : 'false', onclick: () => { menuOpen = true; render(); } }, icon('menu')),
        h('div', {class:'topbar-title'}, h('span',{class:'eyebrow'}, D.role === 'ADMIN' ? 'Career Services' : D.role === 'COMPANY' ? 'Employer Workspace' : 'Student Workspace'), h('span', { class: 'crumb' }, g ? g + ' / ' + lab(tab) : lab(tab))),
        h('div',{class:'topbar-actions'},
          D.role === 'STUDENT' ? h('button',{class:'top-action',title:'Find companies','aria-label':'Find companies',onclick:()=>{tab='Discover';render();}},icon('search')) : null,
          h('button',{class:'top-action notification-action',title:'Notifications','aria-label':'Notifications',onclick:()=>{tab='Notifications';render();}},icon('bell'), D.me.unread ? h('span',{class:'notif-dot'},D.me.unread>9?'9+':D.me.unread) : null),
          h('button',{class:'profile-chip','aria-label':'Open account settings',onclick:()=>{tab='Account';render();}}, h('span',{class:'avatar'}, (D.me.name||'P').slice(0,1).toUpperCase()), h('span',{class:'profile-name'},D.me.name||'Account'))
        )),
      h('main', { id: 'main' }, hero(), credCard(), views[tab]()),
      h('nav',{class:'mobile-nav','aria-label':'Mobile navigation'},
        [NAV[D.role][0], NAV[D.role].includes('My Application')?'My Application':NAV[D.role][1], 'Notifications', 'Account'].filter((x,i,a)=>x && a.indexOf(x)===i).slice(0,4).map(t=>h('button',{class:t===tab?'active':'',onclick:()=>go(t)},icon(ICON[t]||'grid'),h('span',{},lab(t)))))
      )));
}

// Stay signed in if the page reloads (Live Server reloads whenever a project file changes)
(async () => {
  const s = JSON.parse(sessionStorage.getItem('palm') || 'null');
  if (!s) return;
  BASE = await findApi();
  if (BASE === null) return;
  csrf = s.csrf; token = s.token || ''; tab = NAV[s.role][0];
  $('#landing').hidden = true; $('#login').hidden = true; $('#app').hidden = false;
  try { await refresh(); } catch (e) { showLogin(); }
})();

// ---- sign in, sign up, email verification, password reset, privacy notice and letter check ----
const PRIVACY = [
  'Who we are: Palm University College runs this portal to organise student internships.',
  'What we keep: your name, email address and programme; your CV; your applications and the school and company messages about them; your attendance, weekly logbook, evaluation and final report during an internship; and a log of important actions on your account.',
  'Who can see it: school staff, and the company you apply to or intern with, only for the applications and internships that involve them. Companies never see other students or other companies.',
  'AI suggestions: if you tick the optional consent box, your CV is sent to an AI service only to suggest companies. If you do not tick it, a private matcher on our own server is used and nothing leaves the portal.',
  'How long: CV files are deleted automatically after the retention period set by the school (730 days unless changed). Placement records are kept as academic records.',
  'Your rights: you can change your password at any time and delete your account and data from the Account page if you have no placement. For anything else, ask the school office to correct or erase your data, in line with Ghana\'s Data Protection Act, 2012 (Act 843).',
  'Security: passwords are stored only as salted hashes, and important actions are recorded in an audit log.'
];
(() => {
  const lf = $('#lf'), box = h('div', { class: 'authbox' });
  lf.replaceWith(box);
  const lk = (t, f) => h('button', { type: 'button', class: 'link', onclick: f }, t);
  const fld = (id, ph, type = 'text') => h('input', { id, placeholder: ph, type, maxlength: 120, 'aria-label': ph, required: '' });
  const out = () => h('div', { class: 'sm', role: 'alert' });
  const forms = { li: lf };
  const mode = m => Object.entries(forms).forEach(([k, f]) => { f.hidden = k !== m; });
  const back = () => lk('← Back to sign in', () => mode('li'));
  const sm = out(), vm = out(), rm = out(), lm = out();
  forms.sg = h('form', { hidden: '' }, h('h1', {}, 'Create your account'), h('p', {}, 'Student registration is controlled by the university-approved Student List. Use Request Access if your email is missing.'),
    fld('sg-n', 'Full name'), fld('sg-p', 'Programme, e.g. BSc Computer Science'), fld('sg-e', 'Email address', 'email'), fld('sg-w', 'Password (8+ characters, a letter and a number)', 'password'),
    h('label', { class: 'chk' }, h('input', { id: 'sg-c', type: 'checkbox' }), 'I have read and accept the ', lk('privacy notice', () => mode('pv'))), sm, h('button', {}, 'Create account'), back());
  forms.vf = h('form', { hidden: '' }, h('h1', {}, 'Verify your email'), h('p', {}, 'Enter the 6-digit code we emailed you.'),
    fld('vf-e', 'Email address', 'email'), fld('vf-c', '6-digit code'), vm, h('button', {}, 'Verify email'), lk('Send me a new code', () => resend()), back());
  forms.rs = h('form', { hidden: '' }, h('h1', {}, 'Reset password'), h('p', {}, 'Request a code (we email it, or the school office gives it to you), then choose a new password.'),
    h('input', { id: 'rs-u', placeholder: 'Email or username', 'aria-label': 'Email or username', required: '' }), h('button', { type: 'button', id: 'rs-req' }, 'Request reset code'),
    h('input', { id: 'rs-c', placeholder: 'Reset code', 'aria-label': 'Reset code', maxlength: 12 }), h('input', { id: 'rs-w', type: 'password', placeholder: 'New password', 'aria-label': 'New password' }), rm,
    h('button', {}, 'Set new password'), back());
  forms.pv = h('form', { hidden: '' }, h('h1', {}, 'Privacy notice'), PRIVACY.map(t => h('p', { class: 'priv' }, t)), back());
  forms.lv = h('form', { hidden: '' }, h('h1', {}, 'Check a letter'), h('p', {}, 'Enter the reference and verification code printed on an approval letter.'),
    fld('lv-r', 'Reference, e.g. A004'), fld('lv-c', 'Verification code, e.g. 1A2B-3C4D-5E6F'), lm, h('button', {}, 'Check the letter'), back());
  const arOut = out();
  forms.ar = h('form', { hidden: '' }, h('h1', {}, 'Request portal access'),
    h('p', {}, 'If your university email is not on the approved student list, request access from the Internship Office. No account is created until the request is approved.'),
    fld('ar-n', 'Full name'), fld('ar-p', 'Programme'), fld('ar-e', 'University email', 'email'),
    h('input', { id: 'ar-r', placeholder: 'Reason (optional)', 'aria-label': 'Reason (optional)', maxlength: 300 }),
    h('div', { class: 'sm' }, 'Your request will be reviewed by an administrator.'), arOut, h('button', {}, 'Submit access request'), back());
  lf.append(h('div', { class: 'alt' }, lk('Create student account', () => mode('sg')), ' · ', lk('Request access', () => mode('ar')), ' · ', lk('Verify email', () => mode('vf')), ' · ', lk('Forgot password?', () => mode('rs')),
    h('br'), lk('Privacy notice', () => mode('pv')), ' · ', lk('Check a letter', () => mode('lv'))));
  box.append(h('button', { type: 'button', class: 'link auth-home', onclick: showLanding }, '← Back to portal home'), ...Object.values(forms));
  const post = async (path, body, o) => {
    if (BASE === null) BASE = await findApi();
    if (BASE === null) { o.textContent = 'Cannot reach the Java server. Start Server.java first.'; return null; }
    const r = await F(path, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(body) });
    const j = await r.json().catch(() => ({ error: 'Unexpected reply' }));
    if (!r.ok) { o.textContent = j.error; return null; }
    return j;
  };
  const resend = async () => { const j = await post('/api/resend', { username: $('#vf-e').value }, vm); if (j) vm.textContent = 'If that account is waiting for verification, a new code is on its way.'; };
  forms.sg.addEventListener('submit', async e => {
    e.preventDefault();
    const em = $('#sg-e').value.trim().toLowerCase();
    if (!/^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$/.test(em)) { sm.textContent = 'Enter a valid email address.'; return; }
    if (!$('#sg-c').checked) { sm.textContent = 'Please accept the privacy notice to continue.'; return; }
    const j = await post('/api/signup', { name: $('#sg-n').value, program: $('#sg-p').value, email: em, password: $('#sg-w').value, consent: '1' }, sm);
    if (!j) return;
    if (j.verify) { mode('vf'); $('#vf-e').value = em; vm.textContent = 'We emailed you a 6-digit code. Enter it below.'; }
    else { mode('li'); $('#un').value = em; $('#lerr').textContent = 'Account created. Sign in with your email.'; }
  });
  forms.ar.addEventListener('submit', async e => {
    e.preventDefault();
    const em = $('#ar-e').value.trim().toLowerCase();
    if (!/^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$/.test(em)) { arOut.textContent = 'Enter a valid email address.'; return; }
    const j = await post('/api/access-request', { name: $('#ar-n').value, program: $('#ar-p').value, email: em, reason: $('#ar-r').value }, arOut);
    if (j) arOut.textContent = 'Your request has been received. If further eligibility review is required, the Internship Office will contact you.';
  });
  forms.vf.addEventListener('submit', async e => {
    e.preventDefault();
    const j = await post('/api/verify', { username: $('#vf-e').value, code: $('#vf-c').value }, vm);
    if (j) { mode('li'); $('#un').value = $('#vf-e').value; $('#lerr').textContent = 'Email verified. You can sign in.'; }
  });
  $('#rs-req').addEventListener('click', async () => {
    const j = await post('/api/forgot', { username: $('#rs-u').value }, rm);
    if (j) rm.textContent = 'If that account exists, a code has been issued (emailed, or held by the school office).';
  });
  forms.rs.addEventListener('submit', async e => {
    e.preventDefault();
    const j = await post('/api/reset', { username: $('#rs-u').value, code: $('#rs-c').value, password: $('#rs-w').value }, rm);
    if (j) { mode('li'); $('#un').value = $('#rs-u').value; $('#lerr').textContent = 'Password changed. Sign in.'; }
  });
  const check = async () => {
    if (BASE === null) BASE = await findApi();
    if (BASE === null) { lm.textContent = 'Cannot reach the Java server.'; return; }
    const r = await F('/api/vl?ref=' + encodeURIComponent($('#lv-r').value) + '&code=' + encodeURIComponent($('#lv-c').value));
    const j = await r.json().catch(() => ({}));
    lm.textContent = j.valid ? '✓ Genuine letter: ' + j.student + ' · ' + j.company + ' · ' + j.date : '✗ This reference and code do not match any approval letter.';
  };
  forms.lv.addEventListener('submit', e => { e.preventDefault(); check(); });
  const m = /^#verify=([A-Za-z0-9]+):([0-9A-Fa-f-]+)$/.exec(location.hash);
  if (m) { mode('lv'); $('#lv-r').value = m[1]; $('#lv-c').value = m[2]; check(); }
})();
