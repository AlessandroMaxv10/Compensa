// Compensa — servidor (login, contas, painel admin e IA coletiva)
// Node.js 18+ sem nenhuma dependência externa.
'use strict';

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8080);
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const DB_FILE = path.join(DATA_DIR, 'db.json');
const TOKEN_DAYS = 30;
const INVITE_DAYS = 7;
const TRIAL_DAYS = 3; // teste grátis automático ao liberar // prazo para o motorista fazer o primeiro acesso
const MAX_SAMPLES = 100000;
const N = 12;
// mesmo "conhecimento de fábrica" do app
const PRIOR = [-4.0, 2.0, 2.0, -0.5, 0.0, 0.0, 0.0, 0.0, 0.0, -1.0, -0.5, 0.3];

fs.mkdirSync(DATA_DIR, { recursive: true });

// ---------- auto-atualização do APK (baixa do GitHub sozinho) ----------
const APK_FILE = path.join(DATA_DIR, 'compensa.apk');
const APK_URL = 'https://github.com/AlessandroMaxv10/Compensa/releases/download/latest/Compensa.apk';
let lastApkPull = 0;
let apkPulling = null;
function httpsGetFollow(url, cb, redirects) {
  redirects = redirects || 0;
  if (redirects > 6) return cb(new Error('muitos redirecionamentos'));
  https.get(url, { headers: { 'User-Agent': 'Compensa-Server' } }, (r) => {
    if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location) {
      r.resume();
      return httpsGetFollow(r.headers.location, cb, redirects + 1);
    }
    if (r.statusCode !== 200) { r.resume(); return cb(new Error('HTTP ' + r.statusCode)); }
    cb(null, r);
  }).on('error', cb);
}
function fetchLatestApk() {
  if (apkPulling) return apkPulling;
  apkPulling = new Promise((resolve, reject) => {
    const tmp = APK_FILE + '.tmp';
    httpsGetFollow(APK_URL, (err, r) => {
      if (err) return reject(err);
      const ws = fs.createWriteStream(tmp);
      r.pipe(ws);
      ws.on('finish', () => ws.close(() => {
        try {
          if (fs.statSync(tmp).size > 100000) {
            fs.renameSync(tmp, APK_FILE);
            lastApkPull = Date.now();
            resolve(true);
          } else { try { fs.unlinkSync(tmp); } catch (e) {} reject(new Error('arquivo pequeno')); }
        } catch (e) { reject(e); }
      }));
      ws.on('error', reject);
    });
  }).then(v => { apkPulling = null; return v; }, e => { apkPulling = null; throw e; });
  return apkPulling;
}
// baixa uma vez quando o servidor sobe
fetchLatestApk().then(() => console.log('APK atualizado do GitHub')).catch(e => console.log('APK: usando o que houver (' + e.message + ')'));

// ---------- banco de dados (arquivo JSON) ----------
let db = { users: [], samples: [], model: { w: PRIOR, n: 0, updatedAt: null }, secret: null };
if (fs.existsSync(DB_FILE)) db = Object.assign(db, JSON.parse(fs.readFileSync(DB_FILE, 'utf8')));
if (!db.secret) db.secret = crypto.randomBytes(32).toString('hex');

let saveTimer = null;
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    const tmp = DB_FILE + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(db));
    fs.renameSync(tmp, DB_FILE);
  }, 300);
}

function hashPass(pass, salt = crypto.randomBytes(16).toString('hex')) {
  return { salt, hash: crypto.scryptSync(String(pass), salt, 64).toString('hex') };
}
function checkPass(u, pass) {
  const h = crypto.scryptSync(String(pass), u.salt, 64);
  const ref = Buffer.from(u.hash, 'hex');
  return ref.length === h.length && crypto.timingSafeEqual(ref, h);
}

// primeiro administrador
if (!db.users.some(u => u.role === 'admin')) {
  const user = process.env.ADMIN_USER || 'admin';
  const pass = process.env.ADMIN_PASS || crypto.randomBytes(5).toString('hex');
  db.users.push({ id: crypto.randomUUID(), user, name: 'Administrador', role: 'admin', active: true,
    validUntil: null, createdAt: Date.now(), lastLogin: null, ...hashPass(pass) });
  console.log(`\n>>> Admin criado: usuário "${user}" senha "${pass}" (troque no painel)\n`);
}
save();

// ---------- CPF e nome (primeiro acesso) ----------
// O CPF não é guardado em texto: só uma "impressão digital" (HMAC) e os 2 últimos dígitos.
function cpfDigits(c) { return String(c || '').replace(/\D/g, ''); }
function cpfValid(c) {
  const d = cpfDigits(c);
  if (d.length !== 11 || /^(\d)\1{10}$/.test(d)) return false;
  for (const n of [9, 10]) {
    let sum = 0;
    for (let i = 0; i < n; i++) sum += Number(d[i]) * (n + 1 - i);
    const dv = (sum * 10) % 11 % 10;
    if (dv !== Number(d[n])) return false;
  }
  return true;
}
function cpfKey(c) { return crypto.createHmac('sha256', db.secret).update('cpf:' + cpfDigits(c)).digest('hex'); }
function cpfMask(c) { const d = cpfDigits(c); return `***.***.**${d[8]}-${d.slice(9)}`; }
function normName(n) {
  return String(n || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
    .replace(/[^a-z ]/g, ' ').split(/\s+/).filter(w => w.length > 1 && !['da', 'de', 'do', 'das', 'dos', 'e'].includes(w));
}
// confere primeiro e último nome (aceita "João Silva" para "João Pedro da Silva")
function nameMatches(registered, typed) {
  const a = normName(registered), b = normName(typed);
  if (!a.length || !b.length) return false;
  return a[0] === b[0] && a[a.length - 1] === b[b.length - 1];
}
function inviteOk(u) { return u.pending && Date.now() - (u.inviteAt || 0) < INVITE_DAYS * 864e5; }

// ---------- tokens ----------
function sign(data) { return crypto.createHmac('sha256', db.secret).update(data).digest('base64url'); }
function devHash(device) {
  return crypto.createHash('sha256').update('dev:' + String(device || '')).digest('hex').slice(0, 16);
}
function makeToken(u, device) {
  const exp = Date.now() + TOKEN_DAYS * 864e5;
  const dh = device ? devHash(device) : '0';
  const body = `${u.id}.${exp}.${dh}`;
  return { token: `${body}.${sign(body)}`, exp };
}
function readToken(req) {
  const m = /^Bearer (.+)$/.exec(req.headers.authorization || '');
  if (!m) return null;
  const parts = m[1].split('.');
  if (parts.length !== 4) return null;
  const body = `${parts[0]}.${parts[1]}.${parts[2]}`;
  const good = Buffer.from(sign(body));
  const got = Buffer.from(parts[3]);
  if (good.length !== got.length || !crypto.timingSafeEqual(good, got)) return null;
  if (Number(parts[1]) < Date.now()) return null;
  const u = db.users.find(x => x.id === parts[0]);
  if (!u || !accountOk(u)) return null;
  // o aparelho do token precisa estar entre os aparelhos liberados da conta
  // token COM aparelho (app) precisa bater; token SEM aparelho (painel web) é liberado
  const devs = deviceList(u);
  if (parts[2] !== '0' && !devs.map(devHash).includes(parts[2])) return null;
  return u;
}
function accountOk(u) { return !u.pending && u.active && (!u.validUntil || u.validUntil > Date.now()); }

function deviceList(u) {
  if (Array.isArray(u.devices)) return u.devices;
  if (u.device) { u.devices = [u.device]; delete u.device; return u.devices; } // migra formato antigo
  u.devices = [];
  return u.devices;
}
// Libera este aparelho para a conta, respeitando o limite (padrão 1; o admin pode subir para 2).
function bindDevice(u, device) {
  const d = device ? String(device).slice(0, 80) : null;
  if (!d) return null; // app antigo sem id de aparelho: não trava
  const devs = deviceList(u);
  if (devs.includes(d)) return null; // aparelho já liberado
  const max = u.maxDevices || 1;
  if (devs.length >= max) {
    return max === 1
      ? 'Esta conta já está em uso em outro celular. Fale com o suporte pelo WhatsApp para liberar este aparelho.'
      : `Esta conta já está nos ${max} aparelhos permitidos. Fale com o suporte pelo WhatsApp para liberar este.`;
  }
  devs.push(d);
  return null;
}

function publicUser(u) {
  const samples = db.samples.filter(s => s.u === u.id).length;
  return { id: u.id, user: u.user, name: u.name, role: u.role, active: u.active, cpf: u.cpfMask || null,
    pending: !!u.pending, inviteExpired: !!u.pending && !inviteOk(u), inviteAt: u.inviteAt || null,
    devices: deviceList(u).length, maxDevices: u.maxDevices || 1,
    requested: !!u.requested, resetRequested: !!u.resetRequested,
    trial: !!u.trial, trialEnded: !!(u.trial && u.validUntil && u.validUntil < Date.now()),
    lastSeen: u.lastSeen || null, online: !!(u.lastSeen && Date.now() - u.lastSeen < 5 * 60e3),
    validUntil: u.validUntil, createdAt: u.createdAt, lastLogin: u.lastLogin, samples };
}

// ---------- IA coletiva ----------
let pendingSinceTrain = 0;
function train() {
  const data = db.samples.slice(-20000).filter(s => Array.isArray(s.f) && s.f.length === N);
  const w = PRIOR.slice();
  const lr = 0.05, lambda = 0.01;
  for (let ep = 0; ep < 30; ep++) {
    for (const s of data) {
      let z = 0;
      for (let i = 0; i < N; i++) z += w[i] * s.f[i];
      const err = s.y - 1 / (1 + Math.exp(-z));
      for (let i = 0; i < N; i++) w[i] += lr * (err * s.f[i] - lambda * (w[i] - PRIOR[i]));
    }
  }
  db.model = { w: w.map(x => Math.round(x * 1e4) / 1e4), n: data.length, updatedAt: Date.now() };
  pendingSinceTrain = 0;
  save();
}
setInterval(() => { if (pendingSinceTrain > 0) train(); }, 10 * 60 * 1000);

// ---------- proteção simples contra força bruta ----------
const attempts = new Map();
function tooMany(ip) {
  const now = Date.now();
  const a = (attempts.get(ip) || []).filter(t => now - t < 60_000);
  a.push(now);
  attempts.set(ip, a);
  return a.length > 10;
}

// ---------- HTTP ----------
const SEC_HEADERS = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Strict-Transport-Security': 'max-age=31536000; includeSubDomains',
  'Content-Security-Policy': "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'self'",
};
function send(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...SEC_HEADERS });
  res.end(body);
}
function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', c => { size += c.length; if (size > 2e6) { reject(new Error('grande')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => { try { resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {}); } catch (e) { reject(e); } });
  });
}
const PANEL = fs.readFileSync(path.join(__dirname, 'admin.html'));
const LANDING = fs.readFileSync(path.join(__dirname, 'landing.html'));
const APP_PAGE = `<!DOCTYPE html><html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Compensa - Baixar o app</title>
<style>body{margin:0;font-family:system-ui,sans-serif;background:#0A1A12;color:#fff;text-align:center;padding:24px}
.card{max-width:460px;margin:0 auto;background:#12241b;border-radius:18px;padding:24px}
h1{font-size:26px;margin:8px 0}.car{font-size:48px}
.btn{display:block;background:#12A150;color:#fff;text-decoration:none;font-weight:800;font-size:18px;padding:16px;border-radius:14px;margin:18px 0}
.muted{color:#9fb3a8;font-size:14px;text-align:left}ol{text-align:left;line-height:1.7}.wa{color:#25D366;font-weight:700}</style></head>
<body><div class="card"><div class="car">🚗💲</div><h1>Compensa</h1>
<p>Sua corrida compensa? A resposta na hora, direto na tela da Uber e da 99.</p>
<a class="btn" href="/download">⬇️ Baixar o aplicativo (Android)</a>
<p class="muted"><b>Como instalar:</b></p>
<ol class="muted"><li>Toque em Baixar acima.</li><li>Abra o arquivo <b>Compensa.apk</b> na pasta Downloads.</li>
<li>Se pedir, permita "instalar apps desconhecidos".</li><li>Abra o app, toque em <b>Fazer meu cadastro</b> e envie nome + CPF.</li>
<li>Fale com o suporte para liberarem seu acesso.</li></ol>
<p class="muted">Só para Android. Suporte no WhatsApp: <span class="wa">(11) 93937-0020</span></p></div></body></html>`;

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;
  const ip = req.headers['x-forwarded-for']?.split(',')[0].trim() || req.socket.remoteAddress;
  try {
    if (req.method === 'GET' && p === '/admin') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', ...SEC_HEADERS });
      return res.end(PANEL);
    }
    if (req.method === 'GET' && (p === '/' || p === '/app' || p === '/baixar')) {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', ...SEC_HEADERS });
      return res.end(LANDING);
    }
    if (req.method === 'GET' && p === '/api/health') return send(res, 200, { ok: true });

    // Download do APK (arquivo em DATA_DIR/compensa.apk)
    if (req.method === 'GET' && p === '/download') {
      const apk = APK_FILE;
      // Auto-atualiza do GitHub: se a cache tem mais de 40s (ou não existe), busca a última.
      if (!fs.existsSync(apk) || Date.now() - lastApkPull > 40 * 1000) {
        try { await fetchLatestApk(); } catch (e) { /* mantém o APK atual se falhar */ }
      }
      if (!fs.existsSync(apk)) {
        res.writeHead(404, { 'Content-Type': 'text/html; charset=utf-8', ...SEC_HEADERS });
        return res.end('<meta charset="utf-8"><body style="font-family:sans-serif;padding:24px">O aplicativo ainda não foi publicado. Fale com o suporte.</body>');
      }
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': 'attachment; filename="Compensa.apk"',
        'Content-Length': fs.statSync(apk).size,
      });
      return fs.createReadStream(apk).pipe(res);
    }

    if (req.method === 'POST' && p === '/api/login') {
      if (tooMany(ip)) return send(res, 429, { error: 'Muitas tentativas. Espere 1 minuto.' });
      const b = await readBody(req);
      const u = db.users.find(x => !x.pending && x.user && x.user.toLowerCase() === String(b.user || '').trim().toLowerCase());
      if (!u || !checkPass(u, b.pass || '')) return send(res, 401, { error: 'Usuário ou senha incorretos' });
      if (!u.active) return send(res, 403, { error: 'Conta bloqueada. Fale com o administrador.' });
      if (u.validUntil && u.validUntil < Date.now()) return send(res, 403, { error: u.trial ? 'Seu teste grátis de 3 dias acabou! Fale com o suporte no WhatsApp para continuar usando o Compensa.' : 'Seu acesso venceu. Fale com o suporte no WhatsApp para renovar.' });
      const dev = bindDevice(u, b.device);
      if (dev) return send(res, 409, { error: dev });
      u.lastLogin = Date.now(); save();
      const t = makeToken(u, b.device);
      return send(res, 200, { token: t.token, tokenExp: t.exp, name: u.name, role: u.role, validUntil: u.validUntil });
    }

    // Cadastro (auto-cadastro): a pessoa pede acesso com nome + CPF; fica aguardando o admin liberar
    if (req.method === 'POST' && p === '/api/request-access') {
      if (tooMany(ip)) return send(res, 429, { error: 'Muitas tentativas. Espere 1 minuto.' });
      const b = await readBody(req);
      const name = String(b.name || '').trim().replace(/\s+/g, ' ');
      if (normName(name).length < 2) return send(res, 400, { error: 'Coloque seu nome completo (nome e sobrenome)' });
      if (!cpfValid(b.cpf)) return send(res, 400, { error: 'CPF inválido' });
      const key = cpfKey(b.cpf);
      const existe = db.users.find(x => x.cpfKey === key);
      if (existe) {
        if (existe.requested) return send(res, 400, { error: 'Seu cadastro já foi enviado. Aguarde a liberação ou fale com o suporte.' });
        if (existe.pending) return send(res, 400, { error: 'Seu acesso já foi liberado! Toque em "Já tenho acesso" e faça o primeiro acesso.' });
        return send(res, 400, { error: 'Já existe conta com esse CPF. Toque em "Já tenho acesso" para entrar.' });
      }
      const u = { id: crypto.randomUUID(), user: null, name: name.slice(0, 80), cpfKey: key, cpfMask: cpfMask(b.cpf),
        role: 'driver', active: false, pending: true, requested: true, inviteAt: Date.now(),
        validUntil: null, createdAt: Date.now(), lastLogin: null, salt: null, hash: null, maxDevices: 1, devices: [] };
      db.users.push(u); save();
      return send(res, 200, { ok: true });
    }

    // Esqueci a senha: a pessoa pede a troca com nome + CPF; o admin libera e ela refaz o primeiro acesso
    if (req.method === 'POST' && p === '/api/forgot') {
      if (tooMany(ip)) return send(res, 429, { error: 'Muitas tentativas. Espere 1 minuto.' });
      const b = await readBody(req);
      const GEN = 'Se os dados estiverem certos, seu pedido foi enviado. Fale com o suporte para concluir.';
      if (!cpfValid(b.cpf)) return send(res, 400, { error: 'CPF inválido' });
      const u = db.users.find(x => x.cpfKey === cpfKey(b.cpf) && !x.requested);
      if (u && nameMatches(u.name, b.name)) { u.resetRequested = true; save(); }
      return send(res, 200, { ok: true, message: GEN }); // resposta igual mesmo se não achar (não revela se o CPF existe)
    }

    // Primeiro acesso: nome + CPF liberados pelo administrador → a pessoa cria usuário e senha
    if (req.method === 'POST' && p === '/api/first-access') {
      if (tooMany(ip)) return send(res, 429, { error: 'Muitas tentativas. Espere 1 minuto.' });
      const b = await readBody(req);
      const NAO = 'Nome ou CPF não liberados. Confira os dados ou fale com o administrador.';
      if (!cpfValid(b.cpf)) return send(res, 400, { error: 'CPF inválido' });
      const u = db.users.find(x => x.cpfKey === cpfKey(b.cpf));
      if (!u || !nameMatches(u.name, b.name)) return send(res, 403, { error: NAO });
      if (u.requested) return send(res, 403, { error: 'Seu cadastro ainda não foi liberado. Fale com o suporte pelo WhatsApp.' });
      if (!u.pending) return send(res, 400, { error: 'Este CPF já fez o primeiro acesso. Entre com seu usuário e senha.' });
      if (!inviteOk(u)) return send(res, 403, { error: 'Prazo do primeiro acesso venceu. Peça ao administrador para liberar de novo.' });
      if (!u.active) return send(res, 403, { error: 'Conta bloqueada. Fale com o administrador.' });
      const user = String(b.user || '').trim();
      if (!/^[a-zA-Z0-9._@-]{3,40}$/.test(user)) return send(res, 400, { error: 'Usuário: de 3 a 40 letras ou números, sem espaço' });
      if (db.users.some(x => x.user && x.user.toLowerCase() === user.toLowerCase())) return send(res, 400, { error: 'Esse usuário já existe, escolha outro' });
      if (!b.pass || String(b.pass).length < 6) return send(res, 400, { error: 'Senha com no mínimo 6 caracteres' });
      if (cpfDigits(b.pass).length >= 11 && cpfDigits(b.pass).includes(cpfDigits(b.cpf))) return send(res, 400, { error: 'Não use o CPF como senha' });
      Object.assign(u, { user, pending: false, resetRequested: false, lastLogin: Date.now() }, hashPass(b.pass));
      u.devices = []; bindDevice(u, b.device); // libera este primeiro aparelho
      save();
      const t = makeToken(u, b.device);
      return send(res, 200, { token: t.token, tokenExp: t.exp, user, name: u.name, role: u.role, validUntil: u.validUntil });
    }

    const me = readToken(req);
    if (p.startsWith('/api/') && !me) return send(res, 401, { error: 'Faça login novamente' });

    me.lastSeen = Date.now(); // marca presença em qualquer ação autenticada
    if (req.method === 'POST' && p === '/api/ping') { save(); return send(res, 200, { ok: true }); }
    if (req.method === 'GET' && p === '/api/me')
      return send(res, 200, { ...publicUser(me), model: db.model });

    if (req.method === 'POST' && p === '/api/password') {
      const { old, pass } = await readBody(req);
      if (!checkPass(me, old || '')) return send(res, 400, { error: 'Senha atual errada' });
      if (!pass || String(pass).length < 6) return send(res, 400, { error: 'Senha nova com no mínimo 6 caracteres' });
      Object.assign(me, hashPass(pass)); save();
      return send(res, 200, { ok: true });
    }

    if (req.method === 'POST' && p === '/api/samples') {
      const { samples } = await readBody(req);
      let added = 0;
      for (const s of Array.isArray(samples) ? samples.slice(0, 500) : []) {
        if (!Array.isArray(s.f) || s.f.length !== N || !s.f.every(Number.isFinite)) continue;
        db.samples.push({ u: me.id, f: s.f.map(Number), y: s.y ? 1 : 0, t: Date.now() });
        added++;
      }
      if (db.samples.length > MAX_SAMPLES) db.samples.splice(0, db.samples.length - MAX_SAMPLES);
      pendingSinceTrain += added;
      if (pendingSinceTrain >= 25) train(); else save();
      return send(res, 200, { added });
    }

    if (req.method === 'GET' && p === '/api/model') return send(res, 200, db.model);

    // ----- administração -----
    if (p.startsWith('/api/admin/')) {
      if (me.role !== 'admin') return send(res, 403, { error: 'Somente administrador' });

      if (req.method === 'GET' && p === '/api/admin/users')
        return send(res, 200, db.users.map(publicUser));

      if (req.method === 'GET' && p === '/api/admin/stats') {
        const good = db.samples.filter(s => s.y === 1).length;
        const now = Date.now(), DAY = 864e5;
        const using7d = db.users.filter(u => u.lastLogin && now - u.lastLogin < 7 * DAY).length;
        const using24h = db.users.filter(u => u.lastLogin && now - u.lastLogin < DAY).length;
        return send(res, 200, {
          users: db.users.length,
          drivers: db.users.filter(u => u.role === 'driver').length,
          active: db.users.filter(accountOk).length,
          requests: db.users.filter(u => u.requested).length,
          resetRequests: db.users.filter(u => u.resetRequested).length,
          withLogin: db.users.filter(u => u.user && !u.pending).length,
          installed: db.users.filter(u => deviceList(u).length > 0).length,
          onlineNow: db.users.filter(u => u.lastSeen && Date.now() - u.lastSeen < 5 * 60e3).length,
          trials: db.users.filter(u => u.trial && u.validUntil && u.validUntil > Date.now()).length,
          trialsEnded: db.users.filter(u => u.trial && u.validUntil && u.validUntil < Date.now()).length,
          using7d, using24h,
          samples: db.samples.length, good, model: db.model });
      }

      if (req.method === 'POST' && p === '/api/admin/train') { train(); return send(res, 200, db.model); }

      if (req.method === 'POST' && p === '/api/admin/users') {
        const b = await readBody(req);
        const name = String(b.name || '').trim().replace(/\s+/g, ' ');
        if (normName(name).length < 2) return send(res, 400, { error: 'Coloque o nome completo (nome e sobrenome)' });
        if (!cpfValid(b.cpf)) return send(res, 400, { error: 'CPF inválido' });
        const key = cpfKey(b.cpf);
        if (db.users.some(x => x.cpfKey === key)) return send(res, 400, { error: 'Já existe uma conta com esse CPF' });
        const u = { id: crypto.randomUUID(), user: null, name: name.slice(0, 80), cpfKey: key, cpfMask: cpfMask(b.cpf),
          role: b.role === 'admin' ? 'admin' : 'driver', active: true, pending: true, inviteAt: Date.now(),
          validUntil: b.validUntil ? Number(b.validUntil) : null, createdAt: Date.now(), lastLogin: null, salt: null, hash: null };
        db.users.push(u); save();
        return send(res, 200, publicUser(u));
      }

      const m = /^\/api\/admin\/users\/([\w-]+)$/.exec(p);
      if (m) {
        const u = db.users.find(x => x.id === m[1]);
        if (!u) return send(res, 404, { error: 'Não encontrado' });
        if (req.method === 'PATCH') {
          const b = await readBody(req);
          if (u.id === me.id && (b.active === false || b.role === 'driver')) return send(res, 400, { error: 'Você não pode bloquear a si mesmo' });
          if (typeof b.active === 'boolean') u.active = b.active;
          if ('validUntil' in b) u.validUntil = b.validUntil ? Number(b.validUntil) : null;
          if ('days' in b) { // define quantos dias a partir de agora (0 = sem validade)
            const n = Math.max(0, Math.min(3650, Number(b.days) || 0));
            u.validUntil = n > 0 ? Date.now() + n * 864e5 : null; u.trial = false;
          }
          if ('addDays' in b) { // soma dias ao prazo atual
            const n = Math.max(0, Math.min(3650, Number(b.addDays) || 0));
            const base = Math.max(Date.now(), u.validUntil || 0);
            u.validUntil = base + n * 864e5; u.trial = false;
          }
          if (b.name) u.name = String(b.name).slice(0, 80);
          if (b.role === 'admin' || b.role === 'driver') u.role = b.role;
          if (b.reset) {
            if (!u.cpfKey) return send(res, 400, { error: 'Esta conta não tem CPF cadastrado' });
            if (u.id === me.id) return send(res, 400, { error: 'Você não pode resetar a si mesmo' });
            Object.assign(u, { pending: true, inviteAt: Date.now(), user: null, salt: null, hash: null, devices: [], resetRequested: false });
          }
          if (b.unbind) u.devices = []; // desvincula os aparelhos: pode entrar num novo celular
          if ('maxDevices' in b) u.maxDevices = Number(b.maxDevices) === 2 ? 2 : 1;
          if (b.approve) { // libera o auto-cadastro já com teste grátis de 3 dias
            u.requested = false; u.active = true; u.pending = true; u.inviteAt = Date.now();
            u.validUntil = Date.now() + TRIAL_DAYS * 864e5; u.trial = true;
          }
          if (b.pass && !u.pending) {
            if (String(b.pass).length < 6) return send(res, 400, { error: 'Senha com no mínimo 6 caracteres' });
            Object.assign(u, hashPass(b.pass));
          }
          save();
          return send(res, 200, publicUser(u));
        }
        if (req.method === 'DELETE') {
          if (u.id === me.id) return send(res, 400, { error: 'Você não pode excluir a si mesmo' });
          db.users = db.users.filter(x => x.id !== u.id);
          db.samples = db.samples.filter(s => s.u !== u.id);
          save();
          return send(res, 200, { ok: true });
        }
      }
    }
    send(res, 404, { error: 'Rota não encontrada' });
  } catch (e) {
    console.error(e);
    send(res, 400, { error: 'Requisição inválida' });
  }
});

server.listen(PORT, () => console.log(`Compensa servidor rodando na porta ${PORT}`));
