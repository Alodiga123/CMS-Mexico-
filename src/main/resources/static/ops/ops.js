/* Consola de operación: lógica de los módulos nuevos (tarjeta 360, autorizador, conciliación,
   aclaraciones, fraude, gremio, plásticos, reportes). Vanilla JS sobre la API del CMS. */
(function () {
    'use strict';

    // ------------------------------------------------------------------ helpers
    const $ = (id) => document.getElementById(id);
    const esc = (v) => v == null ? '' : String(v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const money = (v, cur) => v == null ? '—' : Number(v).toLocaleString('es-MX', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + (cur ? ' ' + cur : '');
    const dt = (v) => v ? String(v).replace('T', ' ').slice(0, 19) : '—';
    const d = (v) => v ? String(v).slice(0, 10) : '—';
    const today = () => new Date().toISOString().slice(0, 10);

    async function api(method, path, body, opts) {
        const init = { method, headers: { 'Accept': 'application/json' } };
        if (body !== undefined) { init.headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(body); }
        if (opts && opts.headers) Object.assign(init.headers, opts.headers);
        const res = await fetch(path, init);
        const text = await res.text();
        let data = null;
        try { data = text ? JSON.parse(text) : null; } catch (e) { data = text; }
        if (!res.ok) {
            const msg = data && data.message ? data.message : (data && data.error) || ('HTTP ' + res.status);
            const err = new Error(msg); err.status = res.status; err.code = data && data.errorCode; err.data = data;
            throw err;
        }
        return data;
    }

    function toast(msg, kind) {
        const t = document.createElement('div');
        t.className = 'ops-toast ' + (kind || '');
        t.textContent = msg;
        document.body.appendChild(t);
        setTimeout(() => t.remove(), kind === 'err' ? 6000 : 3200);
    }
    const fail = (e) => toast((e.code ? e.code + ': ' : '') + e.message, 'err');

    const STATUS_BADGE = {
        ACTIVE: 'badge-emerald', APPROVED: 'badge-emerald', CAPTURED: 'badge-emerald', ACTIVATED: 'badge-emerald', RESOLVED: 'badge-emerald', SENT: 'badge-emerald', CLOSED: 'badge-emerald', PRODUCED: 'badge-emerald', DELIVERED: 'badge-emerald', RESOLVED_CUSTOMER: 'badge-emerald', RESOLVED_MERCHANT: 'badge-emerald',
        CREATED: 'badge-cyan', HELD: 'badge-cyan', OPEN: 'badge-cyan', OPENED: 'badge-cyan', REQUESTED: 'badge-cyan', IN_BATCH: 'badge-cyan', PENDING_SEND: 'badge-cyan', RECEIVED: 'badge-cyan', SHIPPED: 'badge-cyan', BUILT: 'badge-cyan',
        SUSPENDED: 'badge-amber', RELEASED: 'badge-amber', STEP_UP: 'badge-amber', IN_REVIEW: 'badge-amber', REVIEWED: 'badge-amber', CHARGEBACK_SENT: 'badge-amber', REPRESENTED: 'badge-amber', SENT_TO_MANUFACTURER: 'badge-amber', WITHDRAWN: 'badge-amber', RETURNED: 'badge-amber',
        BLOCKED: 'badge-red', CANCELED: 'badge-red', CLOSED_CARD: 'badge-red', EXPIRED: 'badge-red', DECLINED: 'badge-red', FAILED: 'badge-red', DESTROYED: 'badge-red', ENUMERATION: 'badge-red', DISMISSED: 'badge-purple'
    };
    const badge = (s) => s == null ? '' : `<span class="badge ${STATUS_BADGE[s] || 'badge-purple'}">${esc(s)}</span>`;
    const rc = (code, approved) => `<span class="badge ${approved ? 'badge-emerald' : (code === '1A' ? 'badge-amber' : 'badge-red')} ops-mono">${esc(code)}</span>`;
    const empty = (tbody, cols, text) => { tbody.innerHTML = `<tr><td colspan="${cols}" class="ops-empty">${esc(text || 'Sin registros.')}</td></tr>`; };
    const errRow = (tbody, cols, e) => { tbody.innerHTML = `<tr><td colspan="${cols}" class="ops-error">${esc(e.message)}</td></tr>`; };
    const kpi = (label, value, cls) => `<div class="ops-kpi ${cls || ''}"><div class="l">${esc(label)}</div><div class="v${String(value ?? '').length > 9 ? ' txt' : ''}">${esc(value)}</div></div>`;
    const kv = (pairs) => `<div class="ops-kv">${pairs.map(([k, v]) => `<div class="k">${esc(k)}</div><div>${v == null || v === '' ? '—' : v}</div>`).join('')}</div>`;
    const ask = (label, dflt) => { const v = prompt(label, dflt || ''); return v === null ? null : v; };
    const who = () => (window.auth && auth.session() && auth.session().username) || localStorage.getItem('ops.operator') || 'mesa.control';

    function pane(btn, id) {
        const tabs = btn.parentElement;
        tabs.querySelectorAll('button').forEach(b => b.classList.remove('on'));
        btn.classList.add('on');
        let el = tabs.nextElementSibling;
        while (el) { if (el.classList.contains('ops-pane')) el.classList.toggle('on', el.id === id); el = el.nextElementSibling; }
    }

    let cardsCache = null;
    async function allCards(force) {
        if (!cardsCache || force) cardsCache = await api('GET', '/api/cards');
        return cardsCache;
    }
    const cardLabel = (c) => `#${c.id} · ${c.embossedName} · **** ${c.last4} · ${c.productName} (${c.cardType}/${c.paymentType}) · ${c.status}`;

    // ------------------------------------------------------------------ TARJETA 360
    const card360 = {
        card: null,
        async search() {
            const q = ($('c360Query').value || '').trim();
            if (!q) return;
            try {
                let card = null;
                if (/^\d{1,6}$/.test(q) && q.length !== 4) card = await api('GET', '/api/cards/' + q);
                else {
                    const all = await allCards(true);
                    const hits = all.filter(c => c.last4 === q || String(c.id) === q);
                    if (hits.length === 0) throw new Error('No hay tarjeta con ese id o últimos cuatro');
                    if (hits.length > 1) toast(`${hits.length} tarjetas terminan en ${q}; se muestra la más reciente`, '');
                    card = hits.sort((a, b) => b.id - a.id)[0];
                }
                await this.show(card);
                if (!(history.state && history.state.arg === String(card.id))) history.replaceState({ tab: 'tab-card360', arg: String(card.id) }, '', '#tab-card360/' + card.id);
            } catch (e) { $('c360Summary').innerHTML = `<div class="ops-error">${esc(e.message)}</div>`; $('c360Body').style.display = 'none'; fail(e); }
        },
        async open(id) {
            $('c360Query').value = id;
            window.__navSilent = true; try { switchTab({ target: null }, 'tab-card360'); } finally { window.__navSilent = false; }
            history.pushState({ tab: 'tab-card360', arg: String(id) }, '', '#tab-card360/' + id);
            if (typeof updateNavBack === 'function') updateNavBack();
            await this.search();
        },
        /** Back to the quick list without leaving the screen. */
        async clear() {
            this.card = null; $('c360Query').value = ''; $('c360Body').style.display = 'none';
            history.pushState({ tab: 'tab-card360' }, '', '#tab-card360');
            if (typeof updateNavBack === 'function') updateNavBack();
            await this.recent();
        },
        /** Nothing chosen yet: the newest cards as quick picks, so the screen is never empty. */
        async recent() {
            const box = $('c360Summary');
            try {
                const p = await api('GET', '/api/cards?page=0&size=12');
                if (this.card) return;   // a card got selected meanwhile: keep it
                const rows = p.content || [];
                box.innerHTML = `<div class="ops-muted" style="margin-bottom:0.6rem">Busca por id o últimos cuatro, o elige una de las últimas tarjetas emitidas:</div>
                    <div class="table-container"><table class="ops-compact"><thead><tr><th>ID</th><th>Titular</th><th>Producto</th><th>Últimos 4</th><th>Estado</th><th>Saldo</th><th></th></tr></thead><tbody>${rows.map(c => `<tr><td>#${c.id}</td><td>${esc(c.embossedName)}</td><td>${esc(c.productName)}</td><td class="ops-mono">**** ${esc(c.last4)}</td><td>${badge(c.status)}</td><td>${money(c.balance, c.currency)}</td><td><button class="btn btn-primary" style="padding:0.3rem 0.7rem;font-size:0.72rem" onclick="ops.card360.open(${c.id})">Ver 360</button></td></tr>`).join('')}</tbody></table></div>`;
            } catch (e) { box.innerHTML = `<div class="ops-error">${esc(e.message)}</div>`; }
        },
        async show(card) {
            this.card = card;
            const c = card;
            $('c360Summary').innerHTML = `
                <div class="ops-grid-3">
                    <div>${kv([['Tarjeta', `<strong>#${c.id}</strong> **** ${esc(c.last4)}`], ['Titular', esc(c.embossedName) + ' <span class="ops-muted">' + esc(c.customerName) + '</span>'], ['Producto', `${esc(c.productName)} · ${badge(c.cardType)} ${badge(c.paymentType)} ${badge(c.network)}`], ['Categoría', esc(c.cardCategory)]])}</div>
                    <div>${kv([['Estado', badge(c.status)], ['Vence', esc(c.expiryDate)], ['Moneda / País', `${esc(c.currency)} · ${esc(c.country)}`], ['Saldo ledger', `<strong style="color:var(--accent-emerald)">${money(c.balance, c.currency)}</strong>`]])}</div>
                    <div>${kv([['Límites producto', `día ${money(c.dailyLimit)} · semana ${money(c.weeklyLimit)} · mes ${money(c.monthlyLimit)}`], ['Acciones', `<div class="ops-actions">
                        ${c.status === 'CREATED' || c.status === 'SUSPENDED' ? `<button class="btn btn-emerald" onclick="ops.card360.status('ACTIVE')">Activar</button>` : ''}
                        ${c.status === 'ACTIVE' ? `<button class="btn btn-amber" onclick="ops.card360.status('SUSPENDED')">Suspender</button>` : ''}
                        ${c.status === 'ACTIVE' || c.status === 'SUSPENDED' ? `<button class="btn btn-red" onclick="ops.card360.status('BLOCKED')">Bloquear</button>` : ''}
                        ${c.status === 'BLOCKED' ? `<button class="btn btn-emerald" onclick="ops.card360.status('ACTIVE')">Desbloquear</button>` : ''}
                        <button class="btn btn-primary" onclick="ops.authorizer.preselect(${c.id})">⚡ Autorizar</button>
                        <button class="btn btn-amber" onclick="ops.card360.clear()">← Volver a la lista</button>
                        <button class="btn btn-primary" onclick="ops.guild.verifyCardId(${c.id})">🛡 Verificar en gremio</button></div>`]])}</div>
                </div>`;
            $('c360Body').style.display = '';
            await Promise.all([this.loadControls(), this.loadCore(), this.loadAttempts(), this.loadPlastics(), this.loadDisputes(), this.loadGuild()]);
        },
        async status(s) {
            const by = ask('¿Quién solicita el cambio a ' + s + '?', who()); if (by === null) return;
            try { await api('POST', `/api/cards/${this.card.id}/status`, { status: s, by }); toast('Tarjeta ' + s, 'ok'); const c = await api('GET', '/api/cards/' + this.card.id); await this.show(c); } catch (e) { fail(e); }
        },
        async loadControls() {
            const box = $('c360Controls');
            try {
                const k = await api('GET', `/api/cards/${this.card.id}/controls`);
                const sw = (id, label, on) => `<label class="ops-switch"><input type="checkbox" id="ctl_${id}" ${on ? 'checked' : ''}> ${label}</label>`;
                box.innerHTML = `
                    <div class="ops-grid-2">
                        <div>${sw('posEnabled', 'Compras en terminal (POS)', k.posEnabled)}${sw('atmEnabled', 'Retiros en cajero (ATM)', k.atmEnabled)}${sw('ecommerceEnabled', 'Compras por internet', k.ecommerceEnabled)}${sw('contactlessEnabled', 'Sin contacto', k.contactlessEnabled)}${sw('internationalEnabled', 'Uso internacional', k.internationalEnabled)}</div>
                        <div>
                            <div class="form-group"><label>Aviso de viaje hasta</label><input id="ctl_travelNoticeUntil" class="form-control" type="date" value="${esc(k.travelNoticeUntil || '')}"></div>
                            <div class="form-group"><label>Límite diario</label><input id="ctl_dailyLimit" class="form-control" type="number" step="0.01" value="${k.dailyLimit ?? ''}" placeholder="hereda del producto"></div>
                            <div class="form-group"><label>Límite semanal</label><input id="ctl_weeklyLimit" class="form-control" type="number" step="0.01" value="${k.weeklyLimit ?? ''}" placeholder="hereda del producto"></div>
                            <div class="form-group"><label>Límite mensual</label><input id="ctl_monthlyLimit" class="form-control" type="number" step="0.01" value="${k.monthlyLimit ?? ''}" placeholder="hereda del producto"></div>
                            <div class="form-group"><label>Máximo por operación</label><input id="ctl_perTransactionMax" class="form-control" type="number" step="0.01" value="${k.perTransactionMax ?? ''}"></div>
                        </div>
                    </div>
                    <div class="ops-toolbar" style="margin-top:0.5rem"><span class="ops-muted">${k.persisted ? 'Controles propios de la tarjeta' : 'Sin controles propios: aplica lo del producto'}</span>
                    <button class="btn btn-primary" onclick="ops.card360.saveControls()">Guardar controles</button></div>`;
            } catch (e) { box.innerHTML = `<div class="ops-error">${esc(e.message)}</div>`; }
        },
        async saveControls() {
            const num = (id) => { const v = $(id).value; return v === '' ? null : Number(v); };
            const body = {
                posEnabled: $('ctl_posEnabled').checked, atmEnabled: $('ctl_atmEnabled').checked, ecommerceEnabled: $('ctl_ecommerceEnabled').checked,
                contactlessEnabled: $('ctl_contactlessEnabled').checked, internationalEnabled: $('ctl_internationalEnabled').checked,
                travelNoticeUntil: $('ctl_travelNoticeUntil').value || null, dailyLimit: num('ctl_dailyLimit'), weeklyLimit: num('ctl_weeklyLimit'),
                monthlyLimit: num('ctl_monthlyLimit'), perTransactionMax: num('ctl_perTransactionMax'), performedBy: who()
            };
            try { await api('PUT', `/api/cards/${this.card.id}/controls`, body); toast('Controles guardados', 'ok'); await this.loadControls(); } catch (e) { fail(e); }
        },
        async loadCore() {
            const box = $('c360Core');
            try {
                const a = await api('GET', `/api/cards/${this.card.id}/core-account`);
                box.innerHTML = kv([['Respaldo', a.coreBacked ? '<span class="badge badge-emerald">Saldo en el core (Mifos)</span>' : '<span class="badge badge-cyan">Ledger interno / línea</span>'],
                    ['Cuenta del core', esc(a.externalAccountId)], ['Cliente del core', esc(a.externalClientId)],
                    ['Disponible', `<strong style="color:var(--accent-emerald)">${money(a.available)}</strong>`], ['Nota', esc(a.message)]]) +
                    `<div class="ops-toolbar" style="margin-top:0.7rem"><input id="c360Recharge" class="form-control" type="number" step="0.01" placeholder="monto" style="width:130px"><button class="btn btn-emerald" onclick="ops.card360.recharge()">Recargar</button></div>`;
            } catch (e) { box.innerHTML = `<div class="ops-error">${esc(e.message)}</div>`; }
        },
        async recharge() {
            const amount = Number($('c360Recharge').value); if (!amount) return;
            try { await api('POST', `/api/cards/${this.card.id}/recharge`, { amount, note: 'consola' }); toast('Recarga aplicada', 'ok'); await this.loadCore(); const c = await api('GET', '/api/cards/' + this.card.id); this.card = c; } catch (e) { fail(e); }
        },
        async loadAttempts() {
            const tbody = $('c360Attempts');
            try {
                const rows = await api('GET', `/api/fraud/attempts?cardId=${this.card.id}`);
                if (!rows.length) return empty(tbody, 8, 'Esta tarjeta no tiene intentos de autorización.');
                tbody.innerHTML = rows.map(r => `<tr>
                    <td class="ops-mono">${dt(r.at)}</td><td>${esc(r.merchantName)}<div class="ops-muted">${esc(r.merchantId)}</div></td><td>${money(r.amount)}</td>
                    <td>${esc(r.channel)} ${r.countryCode ? '· ' + esc(r.countryCode) : ''}</td><td>${rc(r.responseCode, r.approved)} ${r.stepUp ? '<span class="badge badge-amber">step-up</span>' : ''}</td>
                    <td>${r.riskScore ?? 0} <span class="ops-muted">${esc((r.riskReasons || []).join(', '))}</span></td><td class="ops-mono">${esc(r.approvalCode)}</td>
                    <td class="ops-actions">${r.approved && r.approvalCode ? `<button class="btn btn-emerald" onclick="ops.authorizer.captureCode('${esc(r.approvalCode)}')">Capturar</button><button class="btn btn-red" onclick="ops.authorizer.reverseCode('${esc(r.approvalCode)}')">Reversar</button>` : ''}</td></tr>`).join('');
            } catch (e) { errRow(tbody, 8, e); }
        },
        async loadPlastics() {
            const tbody = $('c360Plastics');
            try {
                const rows = await api('GET', `/api/plastics?cardId=${this.card.id}`);
                if (!rows.length) return empty(tbody, 6, 'Sin plásticos (tarjeta virtual o sin pedido).');
                tbody.innerHTML = rows.map(p => `<tr><td>#${p.id}</td><td>${esc(p.reason)}</td><td>${badge(p.status)}</td><td>${esc(p.batchNumber)}</td><td>${esc(p.carrier)} ${esc(p.trackingNumber)}</td><td class="ops-actions">${plastics.actions(p, 'ops.card360.loadPlastics()')}</td></tr>`).join('');
            } catch (e) { errRow(tbody, 6, e); }
        },
        async loadDisputes() {
            const box = $('c360Disputes');
            try {
                const rows = await api('GET', `/api/disputes?cardId=${this.card.id}`);
                box.innerHTML = rows.length ? `<ul class="ops-timeline">${rows.map(x => `<li><span class="t">${d(x.openedAt)}</span><span>Aclaración #${x.id} · ${esc(x.reasonCode)} · ${money(x.amount)} ${badge(x.status)} <a href="#" onclick="ops.disputes.openDetail(${x.id});return false">ver</a></span></li>`).join('')}</ul>` : '<div class="ops-muted">Sin aclaraciones.</div>';
            } catch (e) { box.innerHTML = `<div class="ops-error">${esc(e.message)}</div>`; }
        },
        async loadGuild() {
            const box = $('c360Guild');
            try {
                const rows = (await api('GET', '/api/guild/alerts')).filter(a => a.cardId === this.card.id);
                box.innerHTML = rows.length ? `<ul class="ops-timeline">${rows.map(a => `<li><span class="t">${d(a.createdAt)}</span><span>Gremio ${esc(a.direction)} · ${esc(a.type)} ${badge(a.status)} ${a.guildFolio ? '<span class="ops-mono">' + esc(a.guildFolio) + '</span>' : ''} ${a.respondBy ? '· plazo ' + esc(a.respondBy) : ''}</span></li>`).join('')}</ul>` : '<div class="ops-muted">Sin alertas del gremio.</div>';
            } catch (e) { box.innerHTML = `<div class="ops-error">${esc(e.message)}</div>`; }
        }
    };

    // ------------------------------------------------------------------ AUTORIZADOR
    const authorizer = {
        lastChallenge: null,
        async loadIso() {
            try {
                const [iso, si, core] = await Promise.all([api('GET', '/api/iso/status'), api('GET', '/api/standin/status'), api('GET', '/api/core/outage')]);
                $('isoKpis').innerHTML = kpi('Canal ISO', iso.listening ? 'escuchando :' + iso.port : 'apagado', iso.listening ? 'ok' : 'bad') + kpi('Enlaces', iso.connections) + kpi('HSM', iso.hsmUp ? 'arriba' : 'caído', iso.hsmUp ? 'ok' : 'bad')
                    + kpi('Core', core.down ? (core.forced ? 'caída simulada' : 'sin respuesta') : 'arriba', core.down ? 'bad' : 'ok') + kpi('Stand-in pendiente', si.pendingSettlement, si.pendingSettlement ? 'warn' : '') + kpi('Monto pendiente', money(si.pendingAmount)) + kpi('Máx. stand-in', money(si.maxAmount) + ' · ' + si.maxCountPerCardDaily + '/día');
                const rows = await api('GET', '/api/iso/recent');
                const tbody = $('isoRecent');
                if (!rows.length) return empty(tbody, 7, 'Sin tramas todavía.');
                tbody.innerHTML = rows.slice(0, 30).map(t => `<tr><td class="ops-mono">${dt(t.at)}</td><td class="ops-mono">${esc(t.mti)}</td><td class="ops-mono">${esc(t.pan || '')}</td><td>${t.amount ? (Number(t.amount) / 100).toFixed(2) : ''}</td><td>${rc(t.code, t.code === '00')}</td><td class="ops-muted">${esc(t.note)}</td><td>${t.millis}</td></tr>`).join('');
            } catch (e) { $('isoKpis').innerHTML = `<div class="ops-error">${esc(e.message)}</div>`; }
        },
        async settleStandIn() { try { const r = await api('POST', '/api/standin/settle'); toast('Reservas asentadas en el core: ' + r.settled, 'ok'); await this.loadIso(); } catch (e) { fail(e); } },
        async init() {
            this.loadIso();
            const sel = $('auCard');
            try {
                const cards = await allCards(true);
                const cur = sel.value;
                sel.innerHTML = cards.filter(c => c.status !== 'CANCELED').map(c => `<option value="${c.id}">${esc(cardLabel(c))}</option>`).join('');
                if (cur) sel.value = cur;
                await this.cardChanged();
            } catch (e) { fail(e); }
        },
        async preselect(id) { switchTab({ target: null }, 'tab-authorizer'); await this.init(); $('auCard').value = id; await this.cardChanged(); history.replaceState({ tab: 'tab-authorizer', arg: String(id) }, '', '#tab-authorizer/' + id); },
        async cardChanged() {
            const id = $('auCard').value; const tbody = $('auAttempts');
            if (!id) return empty(tbody, 7);
            try {
                const rows = await api('GET', `/api/fraud/attempts?cardId=${id}`);
                if (!rows.length) return empty(tbody, 7, 'Sin intentos.');
                tbody.innerHTML = rows.slice(0, 40).map(r => `<tr><td class="ops-mono">${dt(r.at)}</td><td>${esc(r.merchantName)}</td><td>${money(r.amount)}</td><td>${esc(r.channel)}</td><td>${rc(r.responseCode, r.approved)}</td><td>${r.riskScore ?? 0} <span class="ops-muted">${esc((r.riskReasons || []).join(', '))}</span></td><td class="ops-mono"><a href="#" onclick="ops.authorizer.pick('${esc(r.approvalCode || '')}');return false">${esc(r.approvalCode)}</a></td></tr>`).join('');
            } catch (e) { errRow(tbody, 7, e); }
        },
        pick(code) { if (code) { $('auCode').value = code; this.lookup(); } },
        async send() {
            const body = { cardId: Number($('auCard').value), amount: Number($('auAmount').value), merchantName: $('auMerchant').value, merchantId: $('auMerchantId').value, channel: $('auChannel').value };
            if ($('auCountry').value) body.countryCode = $('auCountry').value.toUpperCase();
            if ($('auStepUp').value) body.stepUpToken = $('auStepUp').value;
            const opts = $('auIdem').value ? { headers: { 'Idempotency-Key': $('auIdem').value } } : undefined;
            const box = $('auResponse');
            try {
                const r = await api('POST', '/api/authorization', body, opts);
                const cls = r.approved ? 'approved' : (r.responseCode === '1A' ? 'review' : 'declined');
                box.className = 'ops-response ' + cls;
                box.innerHTML = `<div class="code">${esc(r.responseCode)}</div><div class="msg">${esc(r.customerMessage || r.message)}</div>
                    <div class="ops-muted" style="margin-top:0.4rem">${esc(r.message)}</div>
                    ${kv([['Autorización', r.approvalCode ? '<span class="ops-mono">' + esc(r.approvalCode) + '</span>' : '—'], ['Disponible después', money(r.amount)], ['Producto', esc(r.cardType)], ['Retención', badge(r.holdStatus)], ['Riesgo', `${r.riskScore ?? 0} <span class="ops-muted">${esc((r.riskReasons || []).join(', '))}</span>`]])}`;
                if (r.approvalCode) $('auCode').value = r.approvalCode;
                $('auStepUp').value = '';
                if (r.responseCode === '1A' && r.challengeId) {
                    this.lastChallenge = r.challengeId; $('auChallenge').style.display = ''; $('auOtpHint').textContent = r.otpHint ? 'OTP de prueba: ' + r.otpHint : 'token ' + r.challengeId;
                } else $('auChallenge').style.display = 'none';
                await this.cardChanged();
            } catch (e) { box.className = 'ops-response declined'; box.innerHTML = `<div class="code">ERR</div><div class="msg">${esc(e.message)}</div>`; }
        },
        async verifyOtp() {
            if (!this.lastChallenge) return;
            try {
                const r = await api('POST', `/api/fraud/challenges/${this.lastChallenge}/verify`, { otp: $('auOtp').value });
                $('auStepUp').value = r.token || this.lastChallenge; toast('Titular verificado; reintentando la compra', 'ok'); $('auOtp').value = '';
                await this.send();
            } catch (e) { fail(e); }
        },
        async lookup() {
            const code = $('auCode').value.trim(); if (!code) return;
            const box = $('auHold');
            try {
                const h = await api('GET', '/api/authorization/' + code);
                box.style.display = '';
                box.innerHTML = `<h4>Retención ${esc(h.approvalCode)} ${badge(h.status)}</h4>` + kv([['Tarjeta', `<a href="#" onclick="ops.card360.open(${h.cardId});return false">#${h.cardId}</a>`], ['Comercio', esc(h.merchantName) + ' <span class="ops-muted">' + esc(h.merchantId) + '</span>'], ['Autorizado', money(h.amount)], ['Capturado', money(h.capturedAmount)], ['Creada', dt(h.createdAt)], ['Vence', dt(h.expiresAt)], ['Referencia core', esc(h.externalRef)]]);
            } catch (e) { box.style.display = ''; box.innerHTML = `<div class="ops-error">${esc(e.message)}</div>`; }
        },
        async capture() { await this.captureCode($('auCode').value.trim(), $('auCapAmount').value); },
        async captureCode(code, amount) {
            if (!code) return;
            try { const r = await api('POST', `/api/authorization/${code}/capture`, amount ? { amount: Number(amount) } : {}); toast(`Capturada ${money(r.capturedAmount)} · ${r.status}`, 'ok'); $('auCode').value = code; await this.lookup(); await this.cardChanged(); if (card360.card) card360.loadAttempts(); } catch (e) { fail(e); }
        },
        async reverse() { await this.reverseCode($('auCode').value.trim()); },
        async reverseCode(code) {
            if (!code) return;
            try { const r = await api('POST', `/api/authorization/${code}/reverse`); toast('Reversada · ' + r.status, 'ok'); $('auCode').value = code; await this.lookup(); await this.cardChanged(); if (card360.card) card360.loadAttempts(); } catch (e) { fail(e); }
        },
        async expireDue() { try { const r = await api('POST', '/api/authorization/expire-due'); toast('Retenciones expiradas: ' + (r.expired ?? JSON.stringify(r)), 'ok'); } catch (e) { fail(e); } }
    };

    // ------------------------------------------------------------------ CONCILIACIÓN
    const recon = {
        async load() {
            const st = $('rcStatus').value; const tbody = $('rcItems');
            try {
                const rows = await api('GET', '/api/reconciliation/items' + (st ? '?status=' + st : ''));
                const byType = {}; rows.forEach(r => { if (r.status === 'OPEN') byType[r.type] = (byType[r.type] || 0) + 1; });
                $('rcKpis').innerHTML = kpi('Abiertas', rows.filter(r => r.status === 'OPEN').length, rows.some(r => r.status === 'OPEN') ? 'warn' : 'ok') + Object.entries(byType).map(([t, n]) => kpi(t.replace(/_/g, ' ').toLowerCase(), n, 'bad')).join('');
                if (!rows.length) return empty(tbody, 11, 'Sin diferencias: conciliación limpia.');
                tbody.innerHTML = rows.map(r => `<tr><td>#${r.id}</td><td>${badge(r.type)}</td><td><a href="#" onclick="ops.recon.account('${esc(r.accountId)}');return false" class="ops-mono">${esc(r.accountId)}</a></td><td>${r.cardId ? `<a href="#" onclick="ops.card360.open(${r.cardId});return false">#${r.cardId}</a>` : '—'}</td><td class="ops-mono">${esc(r.approvalCode)}</td><td>${money(r.cmsAmount)}</td><td>${money(r.coreAmount)}</td><td class="ops-muted">${esc(r.detail)}</td><td class="ops-mono">${dt(r.lastSeenAt)}</td><td>${badge(r.status)} ${r.resolution ? '<div class="ops-muted">' + esc(r.resolution) + '</div>' : ''}</td><td>${r.status === 'OPEN' ? `<button class="btn btn-emerald" style="padding:0.3rem 0.6rem;font-size:0.72rem" onclick="ops.recon.resolve(${r.id})">Resolver</button>` : ''}</td></tr>`).join('');
            } catch (e) { errRow(tbody, 11, e); }
        },
        async run() {
            const acc = $('rcAccount').value.trim();
            try { const r = await api('POST', '/api/reconciliation/run' + (acc ? '?accountId=' + encodeURIComponent(acc) : '')); toast('Conciliación: ' + JSON.stringify(r).slice(0, 160), 'ok'); await this.load(); } catch (e) { fail(e); }
        },
        async resolve(id) {
            const note = ask('Nota de resolución'); if (note === null) return;
            try { await api('POST', `/api/reconciliation/items/${id}/resolve`, { note }); toast('Diferencia resuelta', 'ok'); await this.load(); } catch (e) { fail(e); }
        },
        async account(id) {
            const box = $('rcAccountDetail');
            try {
                const a = await api('GET', '/api/reconciliation/accounts/' + encodeURIComponent(id));
                box.style.display = ''; box.innerHTML = `<h4>Cuenta ${esc(a.accountId)}</h4>` + kv([['Tarjetas', esc((a.cardIds || []).join(', '))], ['Retenido en CMS', money(a.cmsHeld)], ['Retenido en core', money(a.coreOnHold)], ['Disponible en core', money(a.coreAvailable)], ['Cuadra', a.holdTotalsAgree ? '<span class="badge badge-emerald">sí</span>' : '<span class="badge badge-red">no</span>'], ['Diferencias abiertas', a.openItems]]);
            } catch (e) { box.style.display = ''; box.innerHTML = `<div class="ops-error">${esc(e.message)}</div>`; }
        }
    };

    // ------------------------------------------------------------------ ACLARACIONES
    const disputes = {
        reasons: null,
        async init() {
            if (!this.reasons) {
                try { this.reasons = await api('GET', '/api/disputes/reasons'); $('dsReason').innerHTML = this.reasons.map(r => `<option value="${esc(r.code)}">${esc(r.code)} · ${esc(r.description)} (${esc(r.network)}${r.requiresEvidence ? ', exige evidencia' : ''})</option>`).join(''); } catch (e) { fail(e); }
            }
            await this.load();
        },
        row(x) {
            return `<tr><td>#${x.id}</td><td><a href="#" onclick="ops.card360.open(${x.cardId});return false">#${x.cardId}</a></td><td class="ops-mono">${esc(x.approvalCode)}</td><td>${money(x.amount, x.currency)}</td><td>${esc(x.reasonCode)} <div class="ops-muted">${esc(x.reason)}</div></td><td>${badge(x.status)} ${x.deadlineBreached ? '<span class="badge badge-red">plazo vencido</span>' : ''}</td><td>${esc(x.nextDeadline || '—')}</td><td>${x.provisionalCredit ? '<span class="badge badge-emerald">sí</span>' : '—'}</td><td><button class="btn btn-primary" style="padding:0.3rem 0.6rem;font-size:0.72rem" onclick="ops.disputes.openDetail(${x.id})">Ver</button></td></tr>`;
        },
        async load() {
            const st = $('dsStatus').value; const tbody = $('dsRows');
            try {
                const rows = await api('GET', '/api/disputes' + (st ? '?status=' + st : ''));
                const open = rows.filter(r => !['RESOLVED_CUSTOMER', 'RESOLVED_MERCHANT', 'WITHDRAWN'].includes(r.status));
                $('dsKpis').innerHTML = kpi('Abiertas', open.length, open.length ? 'warn' : 'ok') + kpi('Plazo vencido', rows.filter(r => r.deadlineBreached).length, 'bad') + kpi('Con abono provisional', open.filter(r => r.provisionalCredit).length) + kpi('Monto en disputa', money(open.reduce((s, r) => s + Number(r.amount || 0), 0)));
                if (!rows.length) return empty(tbody, 9, 'Sin aclaraciones.');
                tbody.innerHTML = rows.map(x => this.row(x)).join('');
            } catch (e) { errRow(tbody, 9, e); }
        },
        async loadDue() {
            const tbody = $('dsRows');
            try { const rows = await api('GET', '/api/disputes/due?withinDays=7'); if (!rows.length) return empty(tbody, 9, 'Nada vence en 7 días.'); tbody.innerHTML = rows.map(x => this.row(x)).join(''); } catch (e) { errRow(tbody, 9, e); }
        },
        async open() {
            const body = { cardId: Number($('dsCard').value), approvalCode: $('dsCode').value.trim(), reasonCode: $('dsReason').value, description: $('dsDesc').value, provisionalCredit: $('dsCredit').checked, openedBy: $('dsBy').value };
            if ($('dsAmount').value) body.amount = Number($('dsAmount').value);
            try { const x = await api('POST', '/api/disputes', body); toast(`Aclaración #${x.id} abierta · contracargo antes de ${x.chargebackDeadline}`, 'ok'); await this.load(); await this.openDetail(x.id); } catch (e) { fail(e); }
        },
        async openDetail(id) {
            switchTab({ target: null }, 'tab-disputes');
            history.replaceState({ tab: 'tab-disputes', arg: String(id) }, '', '#tab-disputes/' + id);
            const box = $('dsDetail');
            try {
                const x = await api('GET', '/api/disputes/' + id);
                const ev = await api('GET', `/api/disputes/${id}/evidence`);
                const st = x.status;
                box.style.display = '';
                box.innerHTML = `<h4>Aclaración #${x.id} ${badge(st)} ${x.outcome ? '· a favor de ' + (x.outcome === 'CUSTOMER' ? 'cliente' : 'comercio') : ''}</h4>
                    <div class="ops-grid-2">
                        <div>${kv([['Tarjeta', `<a href="#" onclick="ops.card360.open(${x.cardId});return false">#${x.cardId}</a>`], ['Autorización', '<span class="ops-mono">' + esc(x.approvalCode) + '</span>'], ['Monto', money(x.amount, x.currency)], ['Razón', `${esc(x.reasonCode)} · ${esc(x.reason)} (${esc(x.network)})`], ['Descripción', esc(x.description)], ['Abrió', esc(x.openedBy) + ' · ' + dt(x.openedAt)], ['Abono provisional', x.provisionalCredit ? 'sí · ref ' + esc(x.creditRef) + (x.creditReversalRef ? ' · reversado ' + esc(x.creditReversalRef) : '') : 'no']])}</div>
                        <div>${kv([['Contracargo antes de', esc(x.chargebackDeadline)], ['Representación antes de', esc(x.representmentDeadline)], ['Resolver antes de', esc(x.resolveBy)], ['Próximo plazo', esc(x.nextDeadline)], ['Caso del adquirente', esc(x.acquirerCaseRef)], ['Resuelta', dt(x.resolvedAt) + ' ' + esc(x.resolutionNote || '')]])}</div>
                    </div>
                    <h4 style="margin-top:0.8rem">Evidencia (${ev.length}) <span class="ops-muted">sellada con SHA-256</span></h4>
                    ${ev.length ? `<ul class="ops-timeline">${ev.map(e => `<li><span class="t">${dt(e.addedAt)}</span><span>${esc(e.filename)} · ${esc(e.contentType)} · ${e.size} B · ${esc(e.description)} <span class="ops-mono ops-muted">${esc((e.sha256 || '').slice(0, 16))}</span> · <a href="/api/disputes/${id}/evidence/${e.id}/content" target="_blank">abrir</a></span></li>`).join('')}</ul>` : '<div class="ops-muted">Sin evidencia.</div>'}
                    <div class="ops-toolbar" style="margin-top:0.6rem"><input id="dsEvName" class="form-control" placeholder="archivo.txt" style="width:150px"><input id="dsEvText" class="form-control" placeholder="texto de la evidencia" style="width:280px"><input id="dsEvDesc" class="form-control" placeholder="descripción" style="width:180px"><button class="btn btn-primary" onclick="ops.disputes.addEvidence(${id})">Adjuntar</button></div>
                    <div class="ops-actions" style="margin-top:0.8rem">
                        ${st === 'OPENED' ? `<button class="btn btn-amber" onclick="ops.disputes.act(${id},'chargeback')">Enviar contracargo</button><button class="btn btn-red" onclick="ops.disputes.act(${id},'withdraw')">Retirar</button>` : ''}
                        ${st === 'CHARGEBACK_SENT' ? `<button class="btn btn-primary" onclick="ops.disputes.act(${id},'represent')">Registrar representación</button>` : ''}
                        ${st === 'CHARGEBACK_SENT' || st === 'REPRESENTED' ? `<button class="btn btn-emerald" onclick="ops.disputes.resolve(${id},'CUSTOMER')">Resolver a favor del cliente</button><button class="btn btn-amber" onclick="ops.disputes.resolve(${id},'MERCHANT')">Resolver a favor del comercio</button>` : ''}
                        <a class="btn btn-primary" href="/api/disputes/${id}/file" target="_blank">📄 Expediente</a>
                    </div>`;
                box.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            } catch (e) { box.style.display = ''; box.innerHTML = `<div class="ops-error">${esc(e.message)}</div>`; }
        },
        async addEvidence(id) {
            try { await api('POST', `/api/disputes/${id}/evidence`, { filename: $('dsEvName').value || 'evidencia.txt', contentType: 'text/plain', text: $('dsEvText').value, description: $('dsEvDesc').value, by: who() }); toast('Evidencia sellada', 'ok'); await this.openDetail(id); } catch (e) { fail(e); }
        },
        async act(id, action) {
            const note = ask({ chargeback: 'Referencia del caso en el adquirente (opcional)', represent: 'Nota de la representación', withdraw: 'Motivo del retiro' }[action]); if (note === null) return;
            const body = action === 'chargeback' ? { acquirerCaseRef: note || null, by: who() } : { note, by: who() };
            try { await api('POST', `/api/disputes/${id}/${action}`, body); toast('Listo', 'ok'); await this.load(); await this.openDetail(id); } catch (e) { fail(e); }
        },
        async resolve(id, outcome) {
            const note = ask('Nota de resolución'); if (note === null) return;
            try { await api('POST', `/api/disputes/${id}/resolve`, { outcome, note, by: who() }); toast('Resuelta', 'ok'); await this.load(); await this.openDetail(id); } catch (e) { fail(e); }
        }
    };

    // ------------------------------------------------------------------ FRAUDE
    const fraud = {
        async init() { await Promise.all([this.loadAlerts(), this.loadBlocklist(), this.loadChallenges()]); },
        async loadAlerts() {
            const tbody = $('frRows');
            const first = !(tbody.__pager && tbody.__pager.server);
            const load = async (page, size) => {
                const st = $('frStatus').value || 'ALL';
                try {
                    const p = await api('GET', `/api/fraud/alerts?status=${st}&page=${page}&size=${size}`);
                    const rows = p.content || [];
                    if (page === 0) {
                        const open = st === 'OPEN' ? p.totalElements : rows.filter(a => a.status === 'OPEN').length;
                        $('frKpis').innerHTML = kpi(st === 'OPEN' ? 'Abiertas' : 'Alertas (' + st.toLowerCase() + ')', p.totalElements, open ? 'warn' : 'ok') + kpi('Enumeración en la página', rows.filter(a => a.type === 'ENUMERATION').length, 'bad') + kpi('Declinadas en la página', rows.filter(a => a.type === 'DECLINED').length) + kpi('Verificación reforzada en la página', rows.filter(a => a.type === 'STEP_UP').length);
                    }
                    if (!rows.length) { empty(tbody, 10, 'Sin alertas.'); return { total: 0 }; }
                    tbody.innerHTML = rows.map(a => `<tr><td>#${a.id}</td><td class="ops-mono">${dt(a.at || a.createdAt)}</td><td>${badge(a.type)}</td><td>${a.cardId ? `<a href="#" onclick="ops.card360.open(${a.cardId});return false">#${a.cardId}</a>` : '—'}</td><td style="min-width:150px">${esc(a.merchantName)}<div class="ops-muted">${esc(a.merchantId)}</div></td><td>${money(a.amount)}</td><td><strong>${a.riskScore}</strong></td><td class="ops-muted" style="max-width:190px;white-space:normal;word-break:break-word;font-size:11px">${esc((a.reasons || "").replace(/,/g, ", "))}</td><td>${badge(a.status)}${a.actionTaken ? '<div class="ops-muted">' + esc(a.actionTaken) + ' · ' + esc(a.reviewedBy) + '</div>' : ''}</td>
                        <td class="ops-actions">${a.status === 'OPEN' ? `<select class="form-control" style="min-width:150px;padding:4px 8px;font-size:12px" onchange="ops.fraud.act(${a.id}, this)"><option value="">Acción…</option><option value="REVIEWED">Marcar revisada</option><option value="DISMISS">Descartar</option>${a.merchantId ? `<option value="BLOCK_MERCHANT">Bloquear comercio</option>` : ''}${a.cardId ? `<option value="BLOCK_CARD">Bloquear tarjeta</option>` : ''}</select>` : ''}</td></tr>`).join('');
                    return { total: p.totalElements, page: p.page, size: p.size };
                } catch (e) { errRow(tbody, 10, e); return { total: 0 }; }
            };
            if (first) await pager.server(tbody, load); else await tbody.__pager.reload(true);
        },
        async act(id, sel) { const action = sel.value; sel.value = ''; if (action) await this.review(id, action); },
        async review(id, action) {
            const note = ask('Nota del analista'); if (note === null) return;
            try { await api('POST', `/api/fraud/alerts/${id}/review`, { action, note, by: who() }); toast('Alerta ' + action, 'ok'); await this.init(); } catch (e) { fail(e); }
        },
        async loadBlocklist() {
            const tbody = $('blRows');
            try {
                const rows = await api('GET', '/api/fraud/blocklist');
                if (!rows.length) return empty(tbody, 8, 'Lista vacía.');
                tbody.innerHTML = rows.map(b => `<tr><td>#${b.id}</td><td>${badge(b.type)}</td><td class="ops-mono">${esc(b.value)}</td><td>${b.source === 'EXTERNAL' ? '<span class="badge badge-amber">gremio</span>' : '<span class="badge badge-cyan">interno</span>'}</td><td>${esc(b.reason)}</td><td>${esc(b.addedBy)}</td><td class="ops-mono">${dt(b.addedAt)}</td><td><button class="btn btn-emerald" style="padding:0.3rem 0.6rem;font-size:0.72rem" onclick="ops.fraud.unblock(${b.id})">Quitar</button></td></tr>`).join('');
            } catch (e) { errRow(tbody, 8, e); }
        },
        async block() {
            try { await api('POST', '/api/fraud/blocklist', { type: $('blType').value, value: $('blValue').value.trim(), reason: $('blReason').value, by: who() }); toast('Bloqueado', 'ok'); $('blValue').value = ''; await this.loadBlocklist(); } catch (e) { fail(e); }
        },
        async unblock(id) { try { await api('DELETE', '/api/fraud/blocklist/' + id); toast('Quitado de la lista', 'ok'); await this.loadBlocklist(); } catch (e) { fail(e); } },
        async loadChallenges() {
            const tbody = $('chRows'); if (!tbody) return;
            const first = !(tbody.__pager && tbody.__pager.server);
            const load = async (page, size) => {
                try {
                    const p = await api('GET', `/api/fraud/challenges?status=${$('chStatus').value}&page=${page}&size=${size}`);
                    const rows = p.content || [];
                    if (!rows.length) { empty(tbody, 10, 'Sin retos.'); return { total: 0 }; }
                    tbody.innerHTML = rows.map(c => `<tr><td class="ops-mono">${dt(c.createdAt)}</td><td><a href="#" class="ops-mono" onclick="ops.fraud.pick('${esc(c.token)}');return false">${esc(c.token.slice(0, 12))}…</a></td><td>${badge(c.status)}${c.status === 'PENDING' && c.expired ? ' <span class="ops-muted">vencido</span>' : ''}</td><td>${c.cardId ? `<a href="#" onclick="ops.card360.open(${c.cardId});return false">#${c.cardId}</a>` : '—'}</td><td>${money(c.amount)}</td><td class="ops-muted">${esc(c.merchantId)}</td><td>${badge(c.channel)}</td><td>${c.attempts}</td><td class="ops-mono">${dt(c.expiresAt)}</td>
                        <td class="ops-actions">${c.status === 'PENDING' && !c.expired ? `<button class="btn btn-emerald" onclick="ops.fraud.pick('${esc(c.token)}', true)">Verificar</button>` : ''}</td></tr>`).join('');
                    return { total: p.totalElements, page: p.page, size: p.size };
                } catch (e) { errRow(tbody, 10, e); return { total: 0 }; }
            };
            if (first) await pager.server(tbody, load); else await tbody.__pager.reload(true);
        },
        async pick(token, focusOtp) { $('chToken').value = token; await this.challenge(); if (focusOtp) $('chOtp').focus(); $('chDetail').scrollIntoView({ behavior: 'smooth', block: 'nearest' }); },
        async challenge() {
            const box = $('chDetail'); const t = $('chToken').value.trim(); if (!t) return;
            try { const c = await api('GET', '/api/fraud/challenges/' + t); box.style.display = ''; box.innerHTML = kv([['Token', '<span class="ops-mono">' + esc(c.token) + '</span>'], ['Estado', badge(c.status)], ['Tarjeta', c.cardId ? `<a href="#" onclick="ops.card360.open(${c.cardId});return false">#${c.cardId}</a>` : '—'], ['Monto', money(c.amount)], ['Comercio', esc(c.merchantId)], ['Canal', esc(c.channel)], ['Intentos', c.attempts], ['Creado', dt(c.createdAt)], ['Vence', dt(c.expiresAt) + (c.expired ? ' · vencido' : '')]]); } catch (e) { box.style.display = ''; box.innerHTML = `<div class="ops-error">${esc(e.message)}</div>`; }
        },
        async verify() {
            const t = $('chToken').value.trim(); if (!t) return;
            try { const r = await api('POST', `/api/fraud/challenges/${t}/verify`, { otp: $('chOtp').value }); toast('Verificado · token para reintentar: ' + (r.token || t), 'ok'); await this.challenge(); await this.loadChallenges(); } catch (e) { fail(e); }
        }
    };

    // ------------------------------------------------------------------ GREMIO
    const guild = {
        async load() { await Promise.all([this.status(), this.loadAlerts(), this.loadVerifications()]); },
        async status() {
            try {
                const s = await api('GET', '/api/guild/status');
                $('gdKpis').innerHTML = kpi('Canal', (s.mode === 'simulated' ? 'simulado' : 'http') + (s.up ? ' · arriba' : ' · caído'), s.up ? 'ok' : 'bad') + kpi('Participante', s.participantId) + kpi('Por enviar', s.pendingSend, s.pendingSend ? 'warn' : '') + kpi('Fallidas', s.failed, s.failed ? 'bad' : '') + kpi('En revisión', s.inReview, s.inReview ? 'warn' : '') + kpi('Vencen en 3 días', s.dueSoon, s.dueSoon ? 'bad' : '') + kpi('Quebranto asumido', s.expired, s.expired ? 'bad' : '') + kpi('Política ante caída', s.failOpen ? 'autorizar (fail-open)' : 'declinar');
                $('gdSimTab').style.display = s.mode === 'simulated' ? '' : 'none';
            } catch (e) { $('gdKpis').innerHTML = `<div class="ops-error">${esc(e.message)}</div>`; }
        },
        async loadAlerts() {
            const tbody = $('gdRows');
            const q = []; if ($('gdDir').value) q.push('direction=' + $('gdDir').value); if ($('gdStatus').value) q.push('status=' + $('gdStatus').value);
            try {
                const rows = await api('GET', '/api/guild/alerts' + (q.length ? '?' + q.join('&') : ''));
                if (!rows.length) return empty(tbody, 10, 'Sin alertas.');
                tbody.innerHTML = rows.map(a => `<tr><td>#${a.id}</td><td>${a.direction === 'OUTBOUND' ? '📤 saliente' : '📥 entrante'}</td><td>${badge(a.type)}</td><td>${badge(a.status)}${a.assumedLoss ? '<div class="badge badge-red">quebranto</div>' : ''}${a.lastError ? '<div class="ops-muted" title="' + esc(a.lastError) + '">' + a.attempts + ' intentos</div>' : ''}</td>
                    <td>${a.cardId ? `<a href="#" onclick="ops.card360.open(${a.cardId});return false">#${a.cardId}</a> ` : ''}${a.last4 ? '<span class="ops-mono">' + esc(a.bin) + '****' + esc(a.last4) + '</span>' : ''}${a.merchantId ? '<div class="ops-muted">' + esc(a.merchantName || '') + ' ' + esc(a.merchantId) + '</div>' : ''}</td>
                    <td class="ops-mono">${esc(a.guildFolio)}</td><td>${esc(a.respondBy)}</td><td class="ops-muted">${esc(a.autoAction)}</td><td class="ops-muted" title="${esc(a.description)}">${esc((a.description || '').slice(0, 60))}</td>
                    <td class="ops-actions">${a.status === 'PENDING_SEND' || a.status === 'FAILED' ? `<button class="btn btn-primary" onclick="ops.guild.send(${a.id})">Enviar</button>` : ''}${['SENT', 'RECEIVED', 'IN_REVIEW'].includes(a.status) ? `<button class="btn btn-emerald" onclick="ops.guild.close(${a.id})">Cerrar</button>` : ''}</td></tr>`).join('');
            } catch (e) { errRow(tbody, 10, e); }
        },
        async loadVerifications() {
            const tbody = $('gdVerifRows');
            try {
                const rows = await api('GET', '/api/guild/verifications');
                if (!rows.length) return empty(tbody, 7, 'Todavía no se ha consultado la lista del gremio.');
                tbody.innerHTML = rows.map(v => `<tr><td>${esc(v.subjectType)} <span class="ops-mono">${esc(v.subject)}</span></td><td>${v.listed ? '<span class="badge badge-red">LISTADO</span>' : '<span class="badge badge-emerald">limpio</span>'}</td><td class="ops-mono">${esc(v.folio)}</td><td class="ops-muted">${esc(v.reason)}</td><td>${v.degraded ? '<span class="badge badge-amber">sin respuesta del gremio</span>' : '—'}</td><td class="ops-mono">${dt(v.checkedAt)}</td><td class="ops-mono">${dt(v.validUntil)}</td></tr>`).join('');
            } catch (e) { errRow(tbody, 7, e); }
        },
        async flush() { try { const r = await api('POST', '/api/guild/outbox/flush'); toast('Enviadas: ' + r.sent, 'ok'); await this.load(); } catch (e) { fail(e); } },
        async poll() { try { const r = await api('POST', '/api/guild/inbound/poll'); toast('Alertas nuevas del gremio: ' + r.received, 'ok'); await this.load(); } catch (e) { fail(e); } },
        async deadlines() { try { const r = await api('POST', '/api/guild/deadlines/run'); toast('Vencidas: ' + r.expired, r.expired ? 'err' : 'ok'); await this.load(); } catch (e) { fail(e); } },
        async send(id) { try { const a = await api('POST', `/api/guild/alerts/${id}/send`); toast(a.status === 'SENT' ? 'Enviada · folio ' + a.guildFolio : 'No enviada: ' + a.lastError, a.status === 'SENT' ? 'ok' : 'err'); await this.load(); } catch (e) { fail(e); } },
        async close(id) { const r = ask('Resolución'); if (r === null) return; try { await api('POST', `/api/guild/alerts/${id}/close`, { resolution: r, by: who() }); toast('Cerrada', 'ok'); await this.load(); } catch (e) { fail(e); } },
        async raise() {
            const body = { type: $('gdType').value, cardId: $('gdCard').value ? Number($('gdCard').value) : null, merchantId: $('gdMerchant').value || null, merchantName: $('gdMerchantName').value || null, description: $('gdDesc').value, amount: $('gdAmount').value ? Number($('gdAmount').value) : null, by: $('gdBy').value };
            try { const a = await api('POST', '/api/guild/alerts', body); toast(`Alerta #${a.id} en el outbox`, 'ok'); await this.load(); } catch (e) { fail(e); }
        },
        async verifyCard() { const id = $('gdVerifCard').value; if (id) await this.verifyCardId(id); },
        async verifyCardId(id) {
            try { const v = await api('POST', `/api/guild/verify/card/${id}`); toast(v.listed ? `Tarjeta #${id} LISTADA en el gremio (${v.folio})` : (v.degraded ? 'Gremio sin respuesta: ' + v.reason : `Tarjeta #${id} limpia en el gremio`), v.listed ? 'err' : 'ok'); if ($('gdVerifRows')) await this.loadVerifications(); } catch (e) { fail(e); }
        },
        async verifyMerchant() { const m = $('gdVerifMerchant').value.trim(); if (!m) return; try { const v = await api('POST', `/api/guild/verify/merchant/${encodeURIComponent(m)}`); toast(v.listed ? 'Comercio LISTADO (' + v.folio + ')' : 'Comercio limpio', v.listed ? 'err' : 'ok'); await this.loadVerifications(); } catch (e) { fail(e); } },
        async simListCard() { try { await api('POST', '/api/guild/simulator/listed-cards', { bin: $('simBin').value, last4: $('simLast4').value, reason: $('simReason').value }); toast('Tarjeta listada en el simulador', 'ok'); } catch (e) { fail(e); } },
        async simInbound() { try { const i = await api('POST', '/api/guild/simulator/inbound', { type: $('simType').value, bin: $('simInBin').value || null, last4: $('simInLast4').value || null, merchantId: $('simInMerchant').value || null, merchantName: null, description: $('simInDesc').value }); toast('Encolada en el gremio: ' + i.folio + '. Sondea entrantes para recibirla.', 'ok'); } catch (e) { fail(e); } },
        async simDown(down) { try { await api('POST', '/api/guild/simulator/down', { down }); toast(down ? 'Gremio apagado' : 'Gremio encendido', down ? 'err' : 'ok'); await this.status(); } catch (e) { fail(e); } }
    };

    // ------------------------------------------------------------------ PLÁSTICOS
    const plastics = {
        actions(p, refresh) {
            const call = (action, extra) => `ops.plastics.act(${p.id},'${action}',${extra ? 'true' : 'false'},'${refresh}')`;
            const b = (label, cls, action, extra) => `<button class="btn ${cls}" onclick="${call(action, extra)}">${label}</button>`;
            let out = '';
            if (p.status === 'PRODUCED') out += b('Enviar', 'btn-primary', 'ship', true);
            if (p.status === 'SHIPPED') out += b('Entregado', 'btn-primary', 'deliver') + b('Devuelto', 'btn-amber', 'return', true);
            if (p.status === 'DELIVERED') out += b('Activar', 'btn-emerald', 'activate');
            if (['REQUESTED', 'PRODUCED', 'SHIPPED', 'DELIVERED', 'RETURNED'].includes(p.status)) out += b('Destruir', 'btn-red', 'destroy', true);
            if (['ACTIVATED', 'DELIVERED', 'SHIPPED', 'PRODUCED'].includes(p.status)) out += `<button class="btn btn-amber" onclick="ops.plastics.replace(${p.id},'${refresh}')">Reponer</button>`;
            return out;
        },
        async act(id, action, extra, refresh) {
            let body = { by: who() };
            if (action === 'ship') { const c = ask('Mensajería', 'DHL'); if (c === null) return; const t = ask('Número de guía'); if (t === null) return; body = { carrier: c, trackingNumber: t, by: who() }; }
            else if (extra) { const n = ask('Nota'); if (n === null) return; body.note = n; }
            try { const p = await api('POST', `/api/plastics/${id}/${action}`, body); toast(`Plástico #${id} · ${p.status}`, 'ok'); eval(refresh); } catch (e) { fail(e); }
        },
        async replace(id, refresh) {
            const reason = ask('Motivo: REPLACEMENT_LOST, REPLACEMENT_STOLEN o REPLACEMENT_DAMAGED', 'REPLACEMENT_DAMAGED'); if (reason === null) return;
            const addr = ask('Dirección de entrega (opcional)'); if (addr === null) return;
            try { const p = await api('POST', `/api/plastics/${id}/replace`, { reason, deliveryAddress: addr || null, pinMailer: true, by: who() }); toast(`Reposición #${p.id} pedida (secuencia ${p.sequence})`, 'ok'); eval(refresh); } catch (e) { fail(e); }
        },
        async requestFor(cardId) {
            const addr = ask('Dirección de entrega (opcional)'); if (addr === null) return;
            try { const p = await api('POST', '/api/plastics', { cardId, reason: 'NEW', deliveryAddress: addr || null, pinMailer: true, by: who() }); toast(`Plástico #${p.id} pedido`, 'ok'); if (card360.card) card360.loadPlastics(); } catch (e) { fail(e); }
        },
        async init() { await Promise.all([this.load(), this.loadBatches()]); },
        async load() {
            const st = $('plStatus').value; const tbody = $('plRows');
            try {
                const rows = await api('GET', '/api/plastics' + (st ? '?status=' + st : ''));
                const count = (s) => rows.filter(p => p.status === s).length;
                if (!st) $('plKpis').innerHTML = kpi('Pedidos', count('REQUESTED'), count('REQUESTED') ? 'warn' : '') + kpi('En lote', count('IN_BATCH')) + kpi('En fabricante', count('SENT_TO_MANUFACTURER')) + kpi('Producidos', count('PRODUCED')) + kpi('Enviados', count('SHIPPED')) + kpi('Entregados', count('DELIVERED')) + kpi('Activados', count('ACTIVATED'), 'ok');
                if (!rows.length) return empty(tbody, 10, 'Sin plásticos.');
                tbody.innerHTML = rows.map(p => `<tr><td>#${p.id}</td><td><a href="#" onclick="ops.card360.open(${p.cardId});return false">#${p.cardId}</a></td><td>${p.sequence}</td><td>${esc(p.reason)}</td><td>${badge(p.status)}</td><td>${esc(p.embossedName)}</td><td>${esc(p.expiry)}</td><td class="ops-mono">${esc(p.batchNumber)}</td><td>${esc(p.carrier)} ${esc(p.trackingNumber)}</td><td class="ops-actions">${this.actions(p, 'ops.plastics.load()')}</td></tr>`).join('');
            } catch (e) { errRow(tbody, 10, e); }
        },
        async renewals() { try { const r = await api('POST', `/api/plastics/renewals?withinDays=${$('plRenewDays').value || 60}&by=${encodeURIComponent(who())}`); toast('Renovaciones pedidas: ' + r.length, 'ok'); await this.load(); } catch (e) { fail(e); } },
        async loadBatches() {
            const tbody = $('plBatchRows');
            try {
                const rows = await api('GET', '/api/plastics/batches');
                if (!rows.length) return empty(tbody, 10, 'Sin lotes.');
                tbody.innerHTML = rows.map(b => `<tr><td>#${b.id}</td><td class="ops-mono">${esc(b.batchNumber)}</td><td>${esc(b.manufacturer)}</td><td>${badge(b.status)}</td><td>${b.recordCount}</td><td>${b.failedCount}</td><td class="ops-mono" title="${esc(b.sha256Plain)}">${esc((b.sha256Plain || '').slice(0, 12))}…</td><td>${b.encryptedSize} B</td><td class="ops-mono">${dt(b.builtAt)}</td>
                    <td class="ops-actions"><button class="btn btn-primary" onclick="ops.plastics.batch(${b.id})">Ver</button><a class="btn btn-primary" href="/api/plastics/batches/${b.id}/file">⬇ Archivo</a>${b.status === 'BUILT' ? `<button class="btn btn-amber" onclick="ops.plastics.sendBatch(${b.id})">Enviar al bureau</button>` : ''}${b.status === 'SENT' ? `<button class="btn btn-emerald" onclick="ops.plastics.produced(${b.id})">Reporte del bureau</button>` : ''}</td></tr>`).join('');
            } catch (e) { errRow(tbody, 10, e); }
        },
        async buildBatch() { try { const b = await api('POST', '/api/plastics/batches', { manufacturer: $('plManufacturer').value, by: who() }); toast(`Lote ${b.batchNumber}: ${b.recordCount} registros, sellado y cifrado`, 'ok'); await this.init(); } catch (e) { fail(e); } },
        async sendBatch(id) { try { await api('POST', `/api/plastics/batches/${id}/send`, { by: who() }); toast('Lote enviado al bureau', 'ok'); await this.init(); } catch (e) { fail(e); } },
        async produced(id) {
            const f = ask('IDs de plásticos que el bureau NO pudo producir (separados por coma, vacío si todos salieron)'); if (f === null) return;
            const ids = f.split(',').map(s => s.trim()).filter(Boolean).map(Number);
            const note = ids.length ? ask('Motivo del fallo') : '';
            try { const b = await api('POST', `/api/plastics/batches/${id}/produced`, { failedPlasticIds: ids, note, by: 'bureau' }); toast(`Lote producido · ${b.failedCount} fallidos vuelven a la cola`, 'ok'); await this.init(); } catch (e) { fail(e); }
        },
        async batch(id) {
            const box = $('plBatchDetail');
            try {
                const b = await api('GET', '/api/plastics/batches/' + id);
                box.style.display = '';
                box.innerHTML = `<h4>Lote ${esc(b.batchNumber)} ${badge(b.status)}</h4>` + kv([['Fabricante', esc(b.manufacturer)], ['Registros', b.recordCount + ' · fallidos ' + b.failedCount], ['Sello SHA-256 del texto en claro', '<span class="ops-mono">' + esc(b.sha256Plain) + '</span>'], ['Cifrado', esc(b.algorithm) + ' · ' + b.encryptedSize + ' B'], ['Armó', esc(b.builtBy) + ' · ' + dt(b.builtAt)], ['Enviado', dt(b.sentAt)], ['Producido', dt(b.producedAt)]]) +
                    `<div class="table-container" style="margin-top:0.6rem"><table class="ops-compact"><thead><tr><th>#</th><th>Tarjeta</th><th>Sec.</th><th>Motivo</th><th>Estado</th><th>Nombre</th></tr></thead><tbody>${(b.plastics || []).map(p => `<tr><td>#${p.id}</td><td>#${p.cardId}</td><td>${p.sequence}</td><td>${esc(p.reason)}</td><td>${badge(p.status)}</td><td>${esc(p.embossedName)}</td></tr>`).join('')}</tbody></table></div>
                    <div class="ops-muted" style="margin-top:0.5rem">La vista en claro solo existe si el servidor se arrancó con plastics.expose-plaintext=true. <a href="/api/plastics/batches/${id}/file/preview" target="_blank">Ver texto en claro</a></div>`;
            } catch (e) { box.style.display = ''; box.innerHTML = `<div class="ops-error">${esc(e.message)}</div>`; }
        }
    };

    // ------------------------------------------------------------------ REPORTES
    const reports = {
        catalog: null, current: null,
        async init() {
            if (!$('rpTo').value) { $('rpTo').value = today(); const f = new Date(); f.setDate(f.getDate() - 30); $('rpFrom').value = f.toISOString().slice(0, 10); }
            try {
                this.catalog = await api('GET', '/api/reports');
                $('rpCatalog').innerHTML = this.catalog.map(r => `<div class="ops-report"><span class="badge ${r.tag === 'Regulatorio' ? 'badge-amber' : r.tag === 'Riesgo' ? 'badge-red' : 'badge-cyan'}" style="align-self:flex-start">${esc(r.tag)}${r.regulator ? ' · ' + esc(r.regulator) : ''}</span><h4>${esc(r.name)}</h4><p>${esc(r.description)}</p><div class="ops-muted">${esc(r.frequency)}</div>
                    <div class="ops-actions"><button class="btn btn-primary" onclick="ops.reports.preview('${r.code}')">Ver</button><button class="btn btn-emerald" onclick="ops.reports.run('${r.code}')">Correr y sellar</button></div></div>`).join('');
            } catch (e) { $('rpCatalog').innerHTML = `<div class="ops-error">${esc(e.message)}</div>`; }
            await this.loadRuns();
        },
        query(code) {
            const q = []; const def = this.catalog.find(r => r.code === code);
            if (def && def.periodic) { if ($('rpFrom').value) q.push('from=' + $('rpFrom').value); if ($('rpTo').value) q.push('to=' + $('rpTo').value); }
            return q.length ? '?' + q.join('&') : '';
        },
        async preview(code) {
            this.current = code;
            try {
                const t = await api('GET', '/api/reports/' + code + this.query(code));
                $('rpPreviewCard').style.display = '';
                $('rpPreviewTitle').textContent = t.name; $('rpPreviewSub').textContent = `${t.from} → ${t.to} · ${Object.entries(t.params || {}).map(([k, v]) => k + '=' + v).join(' · ')}${t.truncated ? ' · truncado' : ''}`;
                const sum = t.summary || {};
                $('rpSummary').innerHTML = Object.entries(sum).filter(([k, v]) => typeof v !== 'object').map(([k, v]) => kpi(k, typeof v === 'number' ? v.toLocaleString('es-MX') : v)).join('');
                $('rpTable').innerHTML = t.rows.length ? `<table class="ops-compact"><thead><tr>${t.columns.map(c => `<th>${esc(c)}</th>`).join('')}</tr></thead><tbody>${t.rows.map(r => `<tr>${r.map(v => `<td>${esc(v == null ? '' : (typeof v === 'string' && v.includes('T') && v.length >= 19 ? v.replace('T', ' ').slice(0, 19) : v))}</td>`).join('')}</tr>`).join('')}</tbody></table>` : '<div class="ops-empty">Sin filas en el periodo.</div>';
                $('rpPreviewCard').scrollIntoView({ behavior: 'smooth', block: 'start' });
            } catch (e) { fail(e); }
        },
        async run(code) {
            code = code || this.current; if (!code) return;
            try { const r = await api('POST', '/api/reports/' + code + '/runs' + this.query(code), { by: $('rpBy').value || who() }); toast(`Corrida #${r.id} sellada · ${r.rowCount} filas · ${r.sha256.slice(0, 12)}…`, 'ok'); await this.loadRuns(); } catch (e) { fail(e); }
        },
        async loadRuns() {
            const tbody = $('rpRuns');
            try {
                const rows = await api('GET', '/api/reports/runs');
                if (!rows.length) return empty(tbody, 9, 'Todavía no hay corridas.');
                tbody.innerHTML = rows.map(r => `<tr><td>#${r.id}</td><td>${esc(r.title)}</td><td>${esc(r.from)} → ${esc(r.to)}</td><td class="ops-muted ops-mono" style="white-space:normal;max-width:240px;word-break:break-all;font-size:11px">${esc((r.params || "").replace(/;/g, "; "))}</td><td>${r.rowCount}</td><td>${esc(r.generatedBy)}</td><td class="ops-mono">${dt(r.generatedAt)}</td><td class="ops-mono" title="${esc(r.sha256)}">${esc(r.sha256.slice(0, 12))}…</td>
                    <td class="ops-actions"><a class="btn btn-primary" href="/api/reports/runs/${r.id}/csv">⬇ CSV</a><button class="btn btn-emerald" onclick="ops.reports.verify(${r.id})">Verificar sello</button></td></tr>`).join('');
            } catch (e) { errRow(tbody, 9, e); }
        },
        async verify(id) { try { const v = await api('GET', `/api/reports/runs/${id}/verify`); toast(v.valid ? `Corrida #${id}: el archivo guardado coincide con su sello` : `Corrida #${id}: el archivo NO coincide con el sello`, v.valid ? 'ok' : 'err'); } catch (e) { fail(e); } }
    };

    // ------------------------------------------------------------------ COMPENSACIÓN Y LIQUIDACIÓN
    const clearing = {
        async init() { await Promise.all([this.load(), this.loadCycles(), this.loadExceptions()]); },
        async load() {
            const tbody = $('clBatchRows');
            try {
                const rows = await api('GET', '/api/clearing/batches');
                if (!rows.length) return empty(tbody, 13, 'Sin archivos de compensación. Carga uno o simula un ciclo.');
                tbody.innerHTML = rows.map(b => `<tr><td>#${b.id}</td><td>${badge(b.network)}</td><td>${esc(b.cycleDate)}</td><td>${esc(b.fileName)}</td><td>${badge(b.status)}${b.error ? '<div class="ops-muted" style="max-width:220px;white-space:normal">' + esc(b.error) + '</div>' : ''}</td><td>${b.recordCount} <span class="ops-muted">(${b.matchedCount} · ${b.exceptionCount ? '<span class="badge badge-red">' + b.exceptionCount + '</span>' : '0'})</span></td><td>${b.presentmentsCount} · ${money(b.presentmentsAmount)}</td><td>${money(b.reversalsAmount)}</td><td>${money(b.chargebacksAmount)}</td><td>${money(b.interchangeAmount)}</td><td>${money(b.feesAmount)}</td><td>${esc(b.loadedBy)}<div class="ops-muted">${dt(b.loadedAt)}</div></td>
                    <td class="ops-actions"><button class="btn btn-primary" onclick="ops.clearing.batch(${b.id})">Ver</button><a class="btn btn-primary" href="/api/clearing/batches/${b.id}/file" target="_blank">⬇</a></td></tr>`).join('');
            } catch (e) { errRow(tbody, 13, e); }
        },
        async batch(id) {
            const box = $('clBatchDetail');
            try {
                const [b, recs] = await Promise.all([api('GET', '/api/clearing/batches/' + id), api('GET', `/api/clearing/batches/${id}/records`)]);
                box.style.display = '';
                box.innerHTML = `<h4>Lote #${b.id} · ${esc(b.network)} · ciclo ${esc(b.cycleDate)} ${badge(b.status)}</h4>` + kv([['Archivo', esc(b.fileName) + ' · sello <span class="ops-mono">' + esc((b.sha256 || '').slice(0, 16)) + '…</span>'], ['Cola del archivo', b.trailerCount + ' registros · ' + money(b.trailerAmount)], ['Casados / excepciones', b.matchedCount + ' / ' + b.exceptionCount], ['Ciclo de liquidación', '#' + b.settlementCycleId]]) +
                    `<div class="table-container" style="margin-top:0.6rem"><table class="ops-compact"><thead><tr><th>Línea</th><th>Tipo</th><th>Tarjeta</th><th>RRN</th><th>Aprob.</th><th>Monto</th><th>Intercambio</th><th>Comercio</th><th>Resultado</th><th>Detalle</th></tr></thead><tbody>${recs.map(r => `<tr><td>${r.lineNo}</td><td>${badge(r.type)}</td><td>${r.cardId ? `<a href="#" onclick="ops.card360.open(${r.cardId});return false">#${r.cardId}</a> ` : ''}<span class="ops-mono">${esc(r.pan || '')}</span></td><td class="ops-mono">${esc(r.rrn)}</td><td class="ops-mono">${esc(r.approvalId)}</td><td>${money(r.amount)}</td><td>${money(r.interchangeFee)}</td><td>${esc(r.merchantName)}</td><td>${badge(r.outcome)}</td><td class="ops-muted">${esc(r.detail)}</td></tr>`).join('')}</tbody></table></div>`;
                box.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            } catch (e) { box.style.display = ''; box.innerHTML = `<div class="ops-error">${esc(e.message)}</div>`; }
        },
        async simulate() {
            try { const r = await api('POST', '/api/clearing/simulate', { network: $('clNetwork').value, anomalies: $('clAnomalies').value, ingest: true, by: who() }); toast(`Ciclo simulado y cargado: lote #${r.batch.id}, ${r.batch.recordCount} registros, ${r.batch.exceptionCount} excepciones`, r.batch.exceptionCount ? 'err' : 'ok'); await this.init(); await this.batch(r.batch.id); } catch (e) { fail(e); }
        },
        async upload() {
            const f = $('clFileInput').files[0]; if (!f) return toast('Elige un archivo', 'err');
            const content = await f.text();
            try { const b = await api('POST', '/api/clearing/files', { fileName: $('clFileName').value || f.name, content, by: who() }); toast(`Lote #${b.id} procesado: ${b.matchedCount} casados, ${b.exceptionCount} excepciones`, b.exceptionCount ? 'err' : 'ok'); await this.init(); await this.batch(b.id); } catch (e) { fail(e); }
        },
        async loadCycles() {
            const tbody = $('clCycleRows');
            try {
                const rows = await api('GET', '/api/clearing/settlement/cycles');
                if (!rows.length) return empty(tbody, 14, 'Sin ciclos de liquidación.');
                tbody.innerHTML = rows.map(c => `<tr><td>#${c.id}</td><td>${badge(c.network)}</td><td>${esc(c.cycleDate)}</td><td>${badge(c.status)}</td><td>${c.batchCount}</td><td>${c.presentmentsCount} · ${money(c.presentmentsAmount)}</td><td>${money(c.reversalsAmount)}</td><td>${money(c.chargebacksAmount)}</td><td>${money(c.interchangeAmount)}</td><td>${money(c.feesAmount)}</td><td><strong style="color:${c.direction === 'ISSUER_PAYS' ? 'var(--bad)' : 'var(--ok)'}">${money(c.netPosition, c.currency)}</strong><div class="ops-muted">${c.direction === 'ISSUER_PAYS' ? 'el emisor paga' : 'el emisor recibe'}</div></td><td>${c.exceptionCount}</td><td class="ops-muted">${c.closedAt ? 'cerrado ' + dt(c.closedAt) + ' · ' + esc(c.closedBy) : ''}${c.paidAt ? '<br>pagado ' + dt(c.paidAt) + ' · ' + esc(c.paymentRef) : ''}</td>
                    <td class="ops-actions">${c.status === 'OPEN' ? `<button class="btn btn-emerald" onclick="ops.clearing.close(${c.id})">Cerrar ciclo</button>` : ''}${c.status === 'CLOSED' ? `<button class="btn btn-amber" onclick="ops.clearing.pay(${c.id})">Registrar pago</button>` : ''}${c.status !== 'OPEN' ? `<a class="btn btn-primary" href="/api/clearing/settlement/cycles/${c.id}/file">⬇ Archivo</a>` : ''}</td></tr>`).join('');
            } catch (e) { errRow(tbody, 14, e); }
        },
        async close(id) { if (!confirm('Cerrar el ciclo congela las cifras y sella el archivo para tesorería. ¿Continuar?')) return; try { const c = await api('POST', `/api/clearing/settlement/cycles/${id}/close`, { by: who() }); toast(`Ciclo cerrado · neto ${money(c.netPosition, c.currency)} · sello ${c.sha256.slice(0, 12)}…`, 'ok'); await this.loadCycles(); } catch (e) { fail(e); } },
        async pay(id) { const ref = prompt('Referencia del pago (SPEI / transferencia)'); if (!ref) return; try { await api('POST', `/api/clearing/settlement/cycles/${id}/paid`, { paymentRef: ref, by: who() }); toast('Ciclo pagado', 'ok'); await this.loadCycles(); } catch (e) { fail(e); } },
        async loadExceptions() {
            const tbody = $('clExcRows');
            try {
                const rows = await api('GET', '/api/clearing/exceptions');
                if (!rows.length) return empty(tbody, 7, 'Sin excepciones.');
                tbody.innerHTML = rows.map(r => `<tr><td>${r.batchId ? `<a href="#" onclick="ops.clearing.batch(${r.batchId});return false">#${r.batchId}</a>` : ''}</td><td>${r.lineNo}</td><td>${badge(r.type)}</td><td>${r.cardId ? `<a href="#" onclick="ops.card360.open(${r.cardId});return false">#${r.cardId}</a>` : ''} <span class="ops-mono">${esc(r.pan || '')}</span></td><td>${money(r.amount)}</td><td>${badge(r.outcome)}</td><td class="ops-muted">${esc(r.detail)}</td></tr>`).join('');
            } catch (e) { errRow(tbody, 7, e); }
        }
    };

    // ------------------------------------------------------------------ TERCEROS Y CONTRATOS
    const thirdparties = {
        async init() { await Promise.all([this.load(), this.loadMessages()]); },
        async load() {
            const tbody = $('tpRows');
            try {
                const rows = await api('GET', '/api/thirdparties');
                const up = rows.filter(r => r.health.up).length, active = rows.filter(r => r.contract && r.contract.status === 'ACTIVE').length, pending = rows.filter(r => !r.contract || ['PENDING', 'NEGOTIATION'].includes(r.contract.status)).length, expired = rows.filter(r => r.contract && r.contract.expired).length;
                $('tpKpis').innerHTML = kpi('Terceros', rows.length) + kpi('Responden', up + ' / ' + rows.length, up === rows.length ? 'ok' : 'warn') + kpi('Contratos activos', active, active ? 'ok' : 'warn') + kpi('Sin contratar', pending, pending ? 'warn' : 'ok') + kpi('Vencidos', expired, expired ? 'bad' : 'ok');
                tbody.innerHTML = rows.map(r => this.row(r)).join('');
            } catch (e) { errRow(tbody, 8, e); }
        },
        row(r) {
            const c = r.contract || {}; const vars = r.envVars || []; const set = vars.filter(v => v.set).length;
            return `<tr id="tpRow-${r.key}"><td><strong>${esc(r.name)}</strong><div class="ops-muted">${esc(c.vendor || '')}</div><div class="ops-muted" style="font-size:11px;margin-top:2px;max-width:320px;white-space:normal">${esc(r.role)}</div></td><td>${badge(r.integration)}<div class="ops-muted">${esc(r.mode)}</div></td><td>${r.health.up ? '<span class="badge badge-green">responde</span>' : '<span class="badge badge-red">no responde</span>'}<div class="ops-muted">${esc(r.health.detail)}</div></td><td>${vars.length ? `<span title="${esc(vars.map(v => (v.set ? '✓ ' : '✗ ') + v.name).join('\n'))}">${set} / ${vars.length}</span>` : '—'}</td><td>${badge(c.status || 'PENDING')}${c.contractRef ? '<div class="ops-muted">' + esc(c.contractRef) + '</div>' : ''}</td><td>${c.expiresAt ? (c.expired ? '<span class="badge badge-red">' + esc(c.expiresAt) + '</span>' : esc(c.expiresAt)) : '—'}</td><td>${c.checklistTotal ? `${c.checklistDone} / ${c.checklistTotal}` : '—'}</td>
                <td class="ops-actions"><button class="btn btn-primary" onclick="ops.thirdparties.check('${r.key}')">Verificar</button><button class="btn btn-amber" onclick="ops.thirdparties.edit('${r.key}')">Contrato</button><a class="btn btn-primary" href="https://github.com/Alodiga123/CMS-Mexico-/blob/feature/autorizador-multiproducto/docs/${esc(r.docs)}" target="_blank">Docs</a></td></tr>`;
        },
        async check(key) {
            try { const r = await api('POST', `/api/thirdparties/${key}/check`); toast(`${r.name}: ${r.health.up ? 'responde' : 'NO responde'} · ${r.health.detail}`, r.health.up ? 'ok' : 'err'); const tr = $('tpRow-' + key); if (tr) tr.outerHTML = this.row(r); } catch (e) { fail(e); }
        },
        async edit(key) {
            const box = $('tpContract');
            try {
                const r = await api('GET', '/api/thirdparties/' + key); const c = r.contract || {};
                box.style.display = ''; box.dataset.key = key;
                const opt = (v) => ['PENDING', 'NEGOTIATION', 'SIGNED', 'CERTIFYING', 'ACTIVE', 'SUSPENDED', 'TERMINATED'].map(s => `<option ${s === v ? 'selected' : ''}>${s}</option>`).join('');
                box.innerHTML = `<h4>Contrato · ${esc(r.name)}</h4><div class="ops-muted" style="margin-bottom:0.6rem">${esc(r.role)} · variables: ${(r.envVars || []).map(v => (v.set ? '✓ ' : '✗ ') + v.name).join(', ') || 'ninguna'}</div>
                    <div class="ops-form-grid">
                        <label>Proveedor<input id="tpcVendor" class="form-control" value="${esc(c.vendor || '')}"></label>
                        <label>Referencia del contrato<input id="tpcRef" class="form-control" value="${esc(c.contractRef || '')}"></label>
                        <label>Estado<select id="tpcStatus" class="form-control">${opt(c.status || 'PENDING')}</select></label>
                        <label>Firmado<input id="tpcSigned" type="date" class="form-control" value="${esc(c.signedAt || '')}"></label>
                        <label>Vence<input id="tpcExpires" type="date" class="form-control" value="${esc(c.expiresAt || '')}"></label>
                        <label>Contacto<input id="tpcContact" class="form-control" value="${esc(c.contact || '')}"></label>
                    </div>
                    <label style="display:block;margin-top:0.6rem">Niveles de servicio y notas<textarea id="tpcSla" class="form-control" rows="3">${esc(c.slaNotes || '')}</textarea></label>
                    <label style="display:block;margin-top:0.6rem">Certificación (una línea por punto: [x] hecho, [ ] pendiente)<textarea id="tpcChecklist" class="form-control ops-mono" rows="7">${esc(c.checklist || '')}</textarea></label>
                    <div class="ops-toolbar" style="margin-top:0.6rem"><button class="btn btn-emerald" onclick="ops.thirdparties.save()">Guardar contrato</button><button class="btn btn-primary" onclick="document.getElementById('tpContract').style.display='none'">Cerrar</button><span class="ops-muted">${c.updatedBy ? 'última edición ' + esc(c.updatedBy) + ' · ' + dt(c.updatedAt) : ''}</span></div>`;
                box.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            } catch (e) { fail(e); }
        },
        async save() {
            const key = $('tpContract').dataset.key;
            try {
                await api('PUT', `/api/thirdparties/${key}/contract`, { vendor: $('tpcVendor').value, contractRef: $('tpcRef').value, status: $('tpcStatus').value, signedAt: $('tpcSigned').value, expiresAt: $('tpcExpires').value, contact: $('tpcContact').value, slaNotes: $('tpcSla').value, checklist: $('tpcChecklist').value, by: who() });
                toast('Contrato guardado', 'ok'); $('tpContract').style.display = 'none'; await this.load();
            } catch (e) { fail(e); }
        },
        async loadMessages() {
            const tbody = $('tpMsgRows');
            try { const st = await api('GET', '/api/thirdparties/messaging/status'); $('tpMsgStatusBar').innerHTML = kpi('Proveedor', st.mode) + kpi('Estado', st.up ? 'responde' : 'no responde', st.up ? 'ok' : 'bad') + kpi('En cola', st.queued, st.queued ? 'warn' : 'ok') + kpi('Detalle', st.detail); } catch (e) { $('tpMsgStatusBar').innerHTML = ''; }
            const first = !(tbody.__pager && tbody.__pager.server);
            const load = async (page, size) => {
                try {
                    const p = await api('GET', `/api/thirdparties/messages?status=${$('tpMsgStatus').value}&page=${page}&size=${size}`);
                    const rows = p.content || [];
                    if (!rows.length) { empty(tbody, 11, 'Sin mensajes.'); return { total: 0 }; }
                    tbody.innerHTML = rows.map(m => `<tr><td>#${m.id}</td><td class="ops-mono">${dt(m.createdAt)}</td><td>${badge(m.channel)}</td><td class="ops-mono">${esc(m.recipient)}</td><td>${m.cardId ? `<a href="#" onclick="ops.card360.open(${m.cardId});return false">#${m.cardId}</a>` : '—'}</td><td>${badge(m.template)}</td><td class="ops-muted" style="max-width:320px">${esc(m.text)}</td><td>${badge(m.status)}${m.lastError ? '<div class="ops-muted">' + esc(m.lastError) + '</div>' : ''}</td><td class="ops-mono">${esc(m.providerRef || '')}<div class="ops-muted">${esc(m.providerMode)}</div></td><td>${m.attempts}</td>
                        <td class="ops-actions">${['QUEUED', 'FAILED'].includes(m.status) ? `<button class="btn btn-amber" onclick="ops.thirdparties.retry(${m.id})">Reintentar</button>` : ''}</td></tr>`).join('');
                    return { total: p.totalElements, page: p.page, size: p.size };
                } catch (e) { errRow(tbody, 11, e); return { total: 0 }; }
            };
            if (first) await pager.server(tbody, load); else await tbody.__pager.reload(true);
        },
        async retry(id) { try { const m = await api('POST', `/api/thirdparties/messages/${id}/retry`); toast('Mensaje ' + m.status, m.status === 'QUEUED' ? 'err' : 'ok'); await this.loadMessages(); } catch (e) { fail(e); } },
        async test() { try { const m = await api('POST', '/api/thirdparties/messages/test', { channel: $('tpTestChannel').value, to: $('tpTestTo').value, text: $('tpTestText').value, by: who() }); toast(`Mensaje #${m.id} ${m.status}${m.providerRef ? ' · ' + m.providerRef : ''}`, m.status === 'QUEUED' || m.status === 'FAILED' ? 'err' : 'ok'); await this.loadMessages(); } catch (e) { fail(e); } },
        async simulator() { try { const st = await api('GET', '/api/thirdparties/messaging/status'); const r = await api('POST', '/api/thirdparties/messaging/simulator', { down: !st.simulatorDown }); toast('Simulador de mensajería ' + (r.simulatorDown ? 'apagado' : 'encendido'), r.simulatorDown ? 'err' : 'ok'); await this.loadMessages(); } catch (e) { fail(e); } }
    };

    // ------------------------------------------------------------------ wiring
    const tabs = {
        'tab-card360': () => {
            if (window.__navSilent) return;   // history / deep link: ops.route decides from the hash
            if (card360.card) { card360.show(card360.card); history.replaceState({ tab: 'tab-card360', arg: String(card360.card.id) }, '', '#tab-card360/' + card360.card.id); }
            else card360.recent();
        },
        'tab-authorizer': () => authorizer.init(),
        'tab-reconciliation': () => recon.load(),
        'tab-disputes': () => disputes.init(),
        'tab-fraud': () => fraud.init(),
        'tab-guild': () => guild.load(),
        'tab-plastics': () => plastics.init(),
        'tab-reports': () => reports.init(),
        'tab-clearing': () => clearing.init(),
        'tab-thirdparties': () => thirdparties.init()
    };

    /** What a hash argument means on each screen (used by the browser's back / forward and deep links). */
    async function route(tabId, arg) {
        if (tabId === 'tab-card360') {
            if (arg) { $('c360Query').value = arg; await card360.search(); }
            else { card360.card = null; $('c360Query').value = ''; $('c360Body').style.display = 'none'; await card360.recent(); }
        }
        else if (tabId === 'tab-authorizer' && arg) { await authorizer.init(); $('auCard').value = arg; await authorizer.cardChanged(); }
        else if (tabId === 'tab-disputes' && arg) { await disputes.init(); await disputes.openDetail(Number(arg)); }
    }

    window.ops = { card360, authorizer, recon, disputes, fraud, guild, plastics, reports, clearing, thirdparties, pane, tabs, route, setOperator: (n) => localStorage.setItem('ops.operator', n) };
    // the console's screens exist only now: honour a deep link that names one of them
    if (location.hash && tabs[location.hash.replace(/^#/, '').split('/')[0]] && typeof routeTo === 'function') routeTo(location.hash);
})();
