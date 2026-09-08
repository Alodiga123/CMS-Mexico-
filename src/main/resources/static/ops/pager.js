/* Paginación de tablas para toda la consola. Se engancha a cada <tbody>: cuando una pantalla
   vuelca filas, el paginador las guarda y muestra solo la página actual, con sus controles debajo.
   Filas de un solo mensaje (cargando, vacío, error) no se paginan. */
(function () {
    'use strict';
    const KEY = 'console.pageSize';
    const SIZES = [10, 20, 50, 100];
    const defaultSize = () => { try { return Number(localStorage.getItem(KEY)) || 20; } catch (e) { return 20; } };

    function isPlaceholder(rows) {
        return rows.length === 1 && rows[0].children.length === 1 && rows[0].children[0].hasAttribute('colspan');
    }

    function controlsFor(tbody) {
        const table = tbody.closest('table');
        const host = table.closest('.table-container') || table;
        let bar = host.nextElementSibling;
        if (!bar || !bar.classList.contains('pager')) {
            bar = document.createElement('div');
            bar.className = 'pager';
            host.insertAdjacentElement('afterend', bar);
        }
        return bar;
    }

    function render(tbody) {
        const st = tbody.__pager;
        const total = st.rows.length;
        const bar = controlsFor(tbody);
        if (isPlaceholder(st.rows) || total === 0) {
            st.applying = true; tbody.replaceChildren(...st.rows); Promise.resolve().then(() => st.applying = false);
            bar.innerHTML = ''; bar.style.display = 'none';
            return;
        }
        const pages = Math.max(1, Math.ceil(total / st.size));
        if (st.page > pages) st.page = pages;
        if (st.page < 1) st.page = 1;
        const from = (st.page - 1) * st.size;
        const slice = st.rows.slice(from, from + st.size);
        st.applying = true;
        tbody.replaceChildren(...slice);
        Promise.resolve().then(() => st.applying = false);

        // page buttons: first, window around current, last
        const win = new Set([1, pages, st.page - 1, st.page, st.page + 1].filter(p => p >= 1 && p <= pages));
        const btns = [];
        let prev = 0;
        [...win].sort((a, b) => a - b).forEach(p => {
            if (p - prev > 1) btns.push('<span class="pager-gap">…</span>');
            btns.push(`<button type="button" class="pager-btn ${p === st.page ? 'on' : ''}" data-page="${p}">${p}</button>`);
            prev = p;
        });
        bar.style.display = '';
        bar.innerHTML = `
            <span class="pager-info">Mostrando <strong>${from + 1}–${Math.min(from + st.size, total)}</strong> de <strong>${total}</strong></span>
            <span class="pager-nav">
                <button type="button" class="pager-btn" data-page="${st.page - 1}" ${st.page <= 1 ? 'disabled' : ''} title="Anterior">‹</button>
                ${btns.join('')}
                <button type="button" class="pager-btn" data-page="${st.page + 1}" ${st.page >= pages ? 'disabled' : ''} title="Siguiente">›</button>
            </span>
            <label class="pager-size">Filas por página
                <select>${SIZES.map(s => `<option value="${s}" ${s === st.size ? 'selected' : ''}>${s}</option>`).join('')}</select>
            </label>`;
        bar.querySelectorAll('.pager-btn[data-page]').forEach(b => b.addEventListener('click', () => { st.page = Number(b.dataset.page); render(tbody); }));
        bar.querySelector('select').addEventListener('change', (e) => {
            st.size = Number(e.target.value); st.page = 1;
            try { localStorage.setItem(KEY, String(st.size)); } catch (err) { /* sin almacenamiento */ }
            render(tbody);
        });
    }

    function attach(tbody) {
        if (tbody.__pager) return;
        const st = { rows: [...tbody.children], page: 1, size: defaultSize(), applying: false };
        tbody.__pager = st;
        new MutationObserver(() => {
            if (st.applying) return;
            st.rows = [...tbody.children];
            st.page = 1;
            render(tbody);
        }).observe(tbody, { childList: true });
        render(tbody);
    }

    function attachAll(root) {
        (root || document).querySelectorAll('table > tbody').forEach(attach);
    }

    // tables added later (the operations console injects its screens after load)
    new MutationObserver((muts) => {
        muts.forEach(m => m.addedNodes.forEach(n => {
            if (n.nodeType !== 1) return;
            if (n.matches && n.matches('tbody')) attach(n);
            else if (n.querySelectorAll) n.querySelectorAll('table > tbody').forEach(attach);
        }));
    }).observe(document.documentElement, { childList: true, subtree: true });

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => attachAll());
    else attachAll();

    window.pager = { attachAll, attach };
})();
