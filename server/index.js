'use strict';
/**
 * Servidor do AutoClick (VPS / EasyPanel). Sem dependências: só Node.
 *
 *  GET  /                      painel de status (quais celulares, versão, estado)
 *  GET  /api/devices           JSON pro painel
 *  POST /api/sync              o celular manda o relatório e recebe o manifesto
 *  GET  /api/manifest          manifesto genérico (debug)
 *  GET  /apk/AutoClick.apk     o APK publicado (gerado no build do Docker)
 *  GET  /health                ok
 *
 * Fontes de verdade (tudo vem do repositório, copiado pra imagem no build):
 *  apk/AutoClick.apk + apk/version.json   -> versão do app
 *  macros/*.json                          -> macros (formato exportado pelo app)
 *  config.json                            -> configurações
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.resolve(__dirname, '..');
const PORT = Number(process.env.PORT) || 3000;
const APK_PATH = process.env.APK_PATH || path.join(ROOT, 'apk', 'AutoClick.apk');
const VERSION_FILE = process.env.VERSION_FILE || path.join(path.dirname(APK_PATH), 'version.json');
const MACROS_DIR = process.env.MACROS_DIR || path.join(ROOT, 'macros');
const CONFIG_FILE = process.env.CONFIG_FILE || path.join(ROOT, 'config.json');
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data');
const GRADLE_PROPS = path.join(ROOT, 'gradle.properties');
const PUBLIC_DIR = path.join(__dirname, 'public');

function gradleProp(name) {
  try {
    const txt = fs.readFileSync(GRADLE_PROPS, 'utf8');
    const m = txt.match(new RegExp('^\\s*' + name.replace(/\./g, '\\.') + '\\s*=\\s*(.*)$', 'm'));
    return m ? m[1].trim() : '';
  } catch (_) {
    return '';
  }
}

// Token: o mesmo que foi gravado no APK (gradle.properties). Vazio = sem token.
const TOKEN = (process.env.SYNC_TOKEN || gradleProp('autoclick.syncToken') || '').trim();

const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

// ---------------------------------------------------------------------------
// APK publicado
// ---------------------------------------------------------------------------
let app = { available: false, versionCode: 0, versionName: '', sha256: '', size: 0, url: '/apk/AutoClick.apk' };

function loadApk() {
  try {
    const buf = fs.readFileSync(APK_PATH);
    let v = {};
    try { v = JSON.parse(fs.readFileSync(VERSION_FILE, 'utf8')); } catch (_) { /* sem version.json */ }
    app = {
      available: true,
      versionCode: Number(v.versionCode) || 0,
      versionName: String(v.versionName || ''),
      sha256: sha256(buf),
      size: buf.length,
      builtAt: v.builtAt || null,
      url: '/apk/AutoClick.apk',
    };
    console.log(`[apk] ${app.versionName} (build ${app.versionCode}) ${app.size} bytes sha256=${app.sha256.slice(0, 12)}…`);
  } catch (e) {
    app = { available: false, versionCode: 0, versionName: '', sha256: '', size: 0, url: '/apk/AutoClick.apk' };
    console.log(`[apk] nenhum APK em ${APK_PATH} (${e.message}); só macros/config serão sincronizados`);
  }
}

// ---------------------------------------------------------------------------
// Macros e config (relidos a cada pedido: arquivos pequenos, e assim uma
// edição local aparece sem reiniciar)
// ---------------------------------------------------------------------------
function loadMacros() {
  const out = [];
  let files = [];
  try { files = fs.readdirSync(MACROS_DIR).filter((f) => f.toLowerCase().endsWith('.json')).sort(); } catch (_) { return out; }
  for (const f of files) {
    const key = f.replace(/\.json$/i, '');
    try {
      const raw = fs.readFileSync(path.join(MACROS_DIR, f), 'utf8');
      const envelope = JSON.parse(raw);
      if (envelope.app !== 'autoclick' || !envelope.macro || !Array.isArray(envelope.macro.steps)) {
        console.log(`[macros] ${f}: não é um macro exportado pelo AutoClick, ignorado`);
        continue;
      }
      if (envelope.macro.steps.length === 0) {
        console.log(`[macros] ${f}: macro vazio, ignorado`);
        continue;
      }
      // sha do conteúdo canônico: mudou o arquivo, muda o sha, o celular troca
      const sha = sha256(JSON.stringify(envelope));
      out.push({ key, name: envelope.macro.name || key, steps: envelope.macro.steps.length, sha, envelope });
    } catch (e) {
      console.log(`[macros] ${f}: JSON inválido (${e.message}), ignorado`);
    }
  }
  return out;
}

function loadConfig() {
  try {
    const cfg = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'));
    return cfg && typeof cfg === 'object' ? cfg : {};
  } catch (e) {
    if (e.code !== 'ENOENT') console.log(`[config] ${CONFIG_FILE}: ${e.message}`);
    return {};
  }
}

/** Manifesto pra UM celular: config base + o bloco devices[id] por cima. */
function manifestFor(deviceId) {
  const cfg = loadConfig();
  const perDevice = (cfg.devices && deviceId && cfg.devices[deviceId]) || {};
  const merged = { ...cfg };
  delete merged.devices;
  for (const [k, v] of Object.entries(perDevice)) {
    // name/chipPhone sao identidade do aparelho, nao config: fora do merge, senao
    // o configRev desse celular nunca bate com o geral e o painel diz
    // "ainda vai aplicar" pra sempre.
    if (k === 'name' || k === 'macros' || k === 'chipPhone') continue;
    merged[k] = v;
  }
  const allow = Array.isArray(perDevice.macros) ? perDevice.macros : null;
  const macros = loadMacros().filter((m) => !allow || allow.includes(m.key));
  return {
    ok: true,
    now: Date.now(),
    app: app.available ? { versionCode: app.versionCode, versionName: app.versionName, sha256: app.sha256, size: app.size, url: app.url } : null,
    config: merged,
    configRev: sha256(JSON.stringify(merged)).slice(0, 16),
    macros: macros.map((m) => ({ key: m.key, sha: m.sha, envelope: m.envelope })),
    device: { name: perDevice.name || '', chipPhone: digits(perDevice.chipPhone) },
  };
}

// ---------------------------------------------------------------------------
// Celulares que se apresentaram
// ---------------------------------------------------------------------------
const DEVICES_FILE = path.join(DATA_DIR, 'devices.json');
let devices = {};
try { devices = JSON.parse(fs.readFileSync(DEVICES_FILE, 'utf8')) || {}; } catch (_) { devices = {}; }

let saveTimer = null;
function saveDevicesSoon() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try {
      fs.mkdirSync(DATA_DIR, { recursive: true });
      fs.writeFileSync(DEVICES_FILE, JSON.stringify(devices, null, 2));
    } catch (e) {
      // sem volume gravável: fica só em memória (o celular se apresenta de novo em minutos)
    }
  }, 2000);
}

const str = (v, max = 120) => (v == null ? '' : String(v)).slice(0, max);
// Numero do chip: so digitos (o celular manda limpo, mas o config.json pode vir formatado).
const digits = (v) => String(v == null ? '' : v).replace(/\D/g, '').slice(0, 20);

function recordDevice(report, ip) {
  const id = str(report.id, 64);
  if (!id) return null;
  const cfg = loadConfig();
  const named = cfg.devices && cfg.devices[id] && cfg.devices[id].name;
  // Chip que este celular opera: override do config.json > o que o celular leu na
  // tela > o valor anterior (um relatorio sem o campo, ex. app antigo, nao apaga).
  // `devices[id]` aqui ainda e o registro ANTERIOR (so e trocado no fim), igual ao
  // truque do firstSeen.
  const prev = devices[id] || {};
  const chipCfg = digits(cfg.devices && cfg.devices[id] && cfg.devices[id].chipPhone);
  const chipRep = digits(report.chipPhone);
  const d = {
    id,
    name: str(named || report.name, 60),
    chipPhone: chipCfg || chipRep || prev.chipPhone || '',
    chipPhoneAt: chipRep ? (Number(report.chipPhoneAt) || Date.now()) : (prev.chipPhoneAt || 0),
    chipPhoneSource: chipCfg ? 'config' : chipRep ? 'report' : (prev.chipPhoneSource || ''),
    model: str(report.model, 40),
    brand: str(report.brand, 40),
    android: str(report.android, 20),
    sdk: Number(report.sdk) || 0,
    versionCode: Number(report.versionCode) || 0,
    versionName: str(report.versionName, 20),
    screen: report.screen && typeof report.screen === 'object' ? { w: Number(report.screen.w) || 0, h: Number(report.screen.h) || 0 } : null,
    service: !!report.service,
    state: report.state && typeof report.state === 'object' ? report.state : {},
    battery: Number.isFinite(Number(report.battery)) ? Number(report.battery) : -1,
    canInstall: report.canInstall !== false,
    update: report.update && typeof report.update === 'object' ? report.update : {},
    macros: Array.isArray(report.macros) ? report.macros.slice(0, 50) : [],
    localMacros: Array.isArray(report.localMacros) ? report.localMacros.slice(0, 50) : [],
    selected: str(report.selected, 40),
    configRev: str(report.configRev, 32),
    autorunFp: str(report.autorunFp, 120),
    commandId: str(report.commandId, 60),
    pollSeconds: Number(report.pollSeconds) || 300,
    uptimeS: Number(report.uptimeS) || 0,
    ip: str(ip, 60),
    lastSeen: Date.now(),
    firstSeen: (devices[id] && devices[id].firstSeen) || Date.now(),
  };
  devices[id] = d;
  saveDevicesSoon();
  return d;
}

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------
function send(res, code, body, headers = {}) {
  const isObj = body !== null && typeof body === 'object' && !Buffer.isBuffer(body);
  const data = isObj ? JSON.stringify(body) : body;
  res.writeHead(code, {
    'Content-Type': isObj ? 'application/json; charset=utf-8' : (headers['Content-Type'] || 'text/plain; charset=utf-8'),
    'Cache-Control': 'no-store',
    ...headers,
    ...(Buffer.isBuffer(data) || typeof data === 'string' ? { 'Content-Length': Buffer.byteLength(data) } : {}),
  });
  res.end(data);
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) {
        reject(new Error('corpo grande demais'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function authorized(req) {
  if (!TOKEN) return true;
  const given = req.headers['x-token'] || '';
  return given === TOKEN;
}

function clientIp(req) {
  const fwd = req.headers['x-forwarded-for'];
  if (fwd) return String(fwd).split(',')[0].trim();
  return req.socket.remoteAddress || '';
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;
  try {
    if (req.method === 'GET' && (p === '/' || p === '/index.html')) {
      const html = fs.readFileSync(path.join(PUBLIC_DIR, 'index.html'));
      return send(res, 200, html, { 'Content-Type': 'text/html; charset=utf-8' });
    }
    if (req.method === 'GET' && p === '/health') return send(res, 200, { ok: true });

    if (req.method === 'GET' && p === '/api/devices') {
      const macros = loadMacros().map((m) => ({ key: m.key, name: m.name, steps: m.steps, sha: m.sha }));
      const cfg = loadConfig();
      const merged = { ...cfg };
      delete merged.devices;
      return send(res, 200, {
        ok: true,
        now: Date.now(),
        app,
        macros,
        config: cfg,
        configRev: sha256(JSON.stringify(merged)).slice(0, 16),
        tokenRequired: !!TOKEN,
        devices: Object.values(devices).sort((a, b) => b.lastSeen - a.lastSeen),
      });
    }

    if (req.method === 'GET' && p === '/apk/AutoClick.apk') {
      if (!app.available) return send(res, 404, { ok: false, error: 'nenhum APK publicado' });
      const st = fs.statSync(APK_PATH);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': st.size,
        'Content-Disposition': `attachment; filename="AutoClick-${app.versionName || 'app'}-${app.versionCode}.apk"`,
        'X-Version-Code': String(app.versionCode),
        'X-Sha256': app.sha256,
        'Cache-Control': 'no-store',
      });
      fs.createReadStream(APK_PATH).pipe(res);
      return;
    }

    if (req.method === 'GET' && p === '/api/manifest') {
      if (!authorized(req)) return send(res, 401, { ok: false, error: 'token inválido' });
      return send(res, 200, manifestFor(url.searchParams.get('id') || ''));
    }

    if (req.method === 'POST' && p === '/api/sync') {
      if (!authorized(req)) return send(res, 401, { ok: false, error: 'token inválido' });
      let report;
      try {
        report = JSON.parse((await readBody(req, 512 * 1024)) || '{}');
      } catch (e) {
        return send(res, 400, { ok: false, error: 'JSON inválido' });
      }
      const d = recordDevice(report, clientIp(req));
      if (!d) return send(res, 400, { ok: false, error: 'relatório sem id' });
      const st = d.state || {};
      console.log(`[sync] ${d.name || d.model} (${d.id.slice(0, 8)}) chip=${d.chipPhone || '-'} v${d.versionName}/${d.versionCode} serviço=${d.service ? 'on' : 'OFF'} ${st.playing ? 'rodando ' + (st.macro || '') : 'parado'} bat=${d.battery}%`);
      return send(res, 200, manifestFor(d.id));
    }

    return send(res, 404, { ok: false, error: 'não encontrado' });
  } catch (e) {
    console.error('[erro]', e);
    return send(res, 500, { ok: false, error: e.message });
  }
});

loadApk();
server.listen(PORT, '0.0.0.0', () => {
  console.log(`AutoClick server na porta ${PORT} | macros: ${MACROS_DIR} | config: ${CONFIG_FILE} | token: ${TOKEN ? 'sim' : 'não'}`);
  console.log(`macros publicados: ${loadMacros().map((m) => m.key).join(', ') || '(nenhum)'}`);
});
