package com.uagrm.colabmodel.codegen;

/**
 * La consola de prueba que viaja DENTRO del backend generado (`GET /`).
 *
 * Responde al requisito de "probar el backend con un frontend": ya viene en el
 * zip y sirve para cualquier dominio, porque se arma leyendo
 * `app-manifest.json`, el mismo contrato que consume la app movil (ADR-011).
 *
 * Decisiones, todas para que no falle el dia de la demo:
 *   - va en `resources/static/`: la sirve el mismo Spring Boot, mismo origen,
 *     sin CORS;
 *   - un solo archivo, sin Node, sin npm, sin build;
 *   - no conoce ninguna entidad: todo sale del manifiesto (`path`, `actions`,
 *     `fields`, `type`, `target`, `literals`, `of`).
 *
 * El JavaScript tiene dos capas. `ConsolaNucleo` son funciones puras (URLs,
 * paginas, conversion de tipos, cuerpo del alta y del PATCH, lectura de
 * errores, etiquetas): las prueba `PlantillaConsolaTest` con Node contra
 * manifiestos generados de verdad. La capa de pantalla solo usa `textContent`
 * y `createElement`: ningun dato, etiqueta ni error del backend se interpreta
 * como HTML.
 *
 * Lo unico que no esta en el manifiesto son los nombres tecnicos de version y
 * auditoria, que cambian si el dominio ya usa `version`, `createdAt` o
 * `updatedAt` (`GeneradorSpringBoot.Meta`): se inyectan aca.
 *
 * Ojo al editar: es un text block de Java, asi que el JS no usa barras
 * invertidas (el salto de linea se arma con `String.fromCharCode(10)`).
 */
final class PlantillaConsola {

    private PlantillaConsola() { }

    static String html(GeneradorSpringBoot.Meta meta) {
        return PLANTILLA
                .replace("__META_VERSION__", meta.version())
                .replace("__META_CREADO__", meta.creado())
                .replace("__META_ACTUALIZADO__", meta.actualizado());
    }

    private static final String PLANTILLA = """
            <!DOCTYPE html>
            <html lang="es">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Consola de prueba</title>
            <style>
              * { box-sizing: border-box; }
              body { margin: 0; font: 14px system-ui, sans-serif; color: #1e293b; background: #f8fafc; }
              header { background: #0f172a; color: #fff; padding: 14px 20px; }
              header h1 { margin: 0; font-size: 16px; font-weight: 600; }
              header span { opacity: .75; font-size: 12px; }
              header a { color: #93c5fd; }
              main { display: flex; align-items: flex-start; gap: 20px; padding: 20px; flex-wrap: wrap; }
              nav { min-width: 170px; }
              nav button { display: block; width: 100%; text-align: left; padding: 8px 12px; margin-bottom: 4px;
                           border: 1px solid #cbd5e1; border-radius: 6px; background: #fff; cursor: pointer; font: inherit; }
              nav button.activa { background: #0f172a; color: #fff; border-color: #0f172a; }
              nav small { opacity: .6; }
              section { flex: 1; min-width: 320px; }
              .panel { background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
              .panel h2 { margin: 0 0 12px; font-size: 14px; }
              label { display: block; margin-bottom: 10px; font-size: 12px; color: #475569; }
              label .cap { display: block; margin-bottom: 3px; }
              label em { color: #94a3b8; font-style: normal; }
              input, select, textarea { width: 100%; padding: 6px 8px; border: 1px solid #cbd5e1; border-radius: 5px; font: inherit; }
              textarea { min-height: 64px; font-family: ui-monospace, monospace; font-size: 12px; }
              .err-campo { color: #b91c1c; font-size: 12px; margin-top: 3px; }
              .con-error input, .con-error select, .con-error textarea { border-color: #f87171; background: #fef2f2; }
              .req { color: #dc2626; }
              button { font: inherit; cursor: pointer; }
              button.accion { padding: 8px 16px; border: 0; border-radius: 6px; background: #2563eb; color: #fff; }
              button.sec { padding: 8px 12px; border: 1px solid #cbd5e1; border-radius: 6px; background: #fff; }
              button:disabled { opacity: .5; cursor: default; }
              .fila-botones { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
              .aviso { padding: 10px 12px; border-radius: 6px; margin-bottom: 12px; font-size: 13px; }
              .aviso.ok { background: #f0fdf4; color: #166534; border: 1px solid #bbf7d0; }
              .aviso.mal { background: #fef2f2; color: #991b1b; border: 1px solid #fecaca; }
              .aviso.ojo { background: #fffbeb; color: #92400e; border: 1px solid #fde68a; }
              .herramientas { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
              .herramientas label { margin: 0; min-width: 120px; }
              .tabla { overflow-x: auto; }
              table { width: 100%; border-collapse: collapse; font-size: 12px; }
              th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid #e2e8f0; white-space: nowrap;
                       max-width: 260px; overflow: hidden; text-overflow: ellipsis; }
              th { color: #64748b; font-weight: 600; }
              td.acciones button { border: 0; background: none; padding: 2px 6px; color: #2563eb; }
              td.acciones button.borrar { color: #dc2626; }
              .vacio { color: #94a3b8; font-style: italic; }
              .paginas { display: flex; gap: 8px; align-items: center; margin-top: 10px; font-size: 12px; color: #475569; }
            </style>
            </head>
            <body>
            <header>
              <h1 id="titulo">Consola de prueba</h1>
              <span>Se arma sola desde app-manifest.json &middot;
                <a href="/swagger-ui.html">Swagger</a> &middot; <a href="/app-manifest.json">manifiesto</a></span>
            </header>
            <main>
              <nav id="menu"></nav>
              <section id="cuerpo"></section>
            </main>
            <script>
            'use strict';

            // Nombres tecnicos de version y auditoria de ESTE proyecto (no estan en el manifiesto).
            const META = { version: '__META_VERSION__', creado: '__META_CREADO__', actualizado: '__META_ACTUALIZADO__' };
            const NL = String.fromCharCode(10);

            // ------------------------------------------------------------ nucleo (sin DOM)
            const ConsolaNucleo = (function () {
              const ESCALARES = ['string', 'integer', 'long', 'decimal', 'boolean', 'date', 'datetime', 'enum', 'uuid'];
              const PROPIOS = ['id'];

              function entidad(m, nombre) {
                return (m.entities || []).find(function (e) { return e.name === nombre; }) || null;
              }

              function puede(e, accion) {
                return Array.isArray(e.actions) && e.actions.indexOf(accion) >= 0;
              }

              function etiquetaDe(x) { return x.label || x.name; }

              // Solo los campos que el listado acepta en `sort`: los propios escalares y la auditoria.
              function ordenables(e) {
                const xs = (e.fields || []).filter(function (f) { return ESCALARES.indexOf(f.type) >= 0; })
                  .map(function (f) { return { valor: f.name, texto: etiquetaDe(f) }; });
                xs.push({ valor: META.creado, texto: 'creado' });
                xs.push({ valor: META.actualizado, texto: 'actualizado' });
                return xs;
              }

              // Paginas desde 0, como la API.
              function urlLista(e, estado) {
                const q = ['page=' + estado.pagina, 'size=' + estado.tamano];
                if (estado.orden) q.push('sort=' + encodeURIComponent(estado.orden + ',' + (estado.sentido || 'asc')));
                return e.path + '?' + q.join('&');
              }

              function urlRegistro(e, id) { return e.path + '/' + encodeURIComponent(id); }

              function totalPaginas(total, tamano) {
                return Math.max(1, Math.ceil((Number(total) || 0) / tamano));
              }

              // Si se borro la unica fila de la ultima pagina, se vuelve a la anterior.
              function paginaTrasBorrar(pagina, filasEnPagina) {
                return filasEnPagina <= 1 && pagina > 0 ? pagina - 1 : pagina;
              }

              // Etiqueta legible de un registro: su primer texto con valor, o su primer escalar;
              // siempre con el comienzo del id, para distinguir dos registros con el mismo nombre.
              function etiquetaRegistro(e, fila) {
                const corto = String(fila && fila.id != null ? fila.id : '').slice(0, 8);
                let principal = null;
                const campos = (e && e.fields) || [];
                for (let i = 0; i < campos.length && principal === null; i++) {
                  const f = campos[i];
                  const v = fila[f.name];
                  if (f.type === 'string' && v != null && String(v).trim() !== '') principal = String(v);
                }
                for (let i = 0; i < campos.length && principal === null; i++) {
                  const f = campos[i];
                  const v = fila[f.name];
                  if (ESCALARES.indexOf(f.type) >= 0 && v != null && String(v) !== '') principal = String(v);
                }
                if (principal === null) return corto || '(sin id)';
                return principal + ' · ' + corto;
              }

              // Valor del backend -> texto del control.
              function aTexto(f, v) {
                if (v === null || v === undefined) return '';
                if (f.type === 'boolean') return v ? 'true' : 'false';
                if (f.type === 'datetime') return String(v).slice(0, 19);
                if (f.type === 'embedded') return JSON.stringify(v, null, 2);
                if (f.type === 'list') return Array.isArray(v) ? v.map(String).join(NL) : '';
                return String(v);
              }

              // Texto del control -> valor para la API. '' es "sin valor" (null).
              // Devuelve { valor } o { error }.
              function convertir(f, crudo) {
                const t = crudo == null ? '' : String(crudo);
                if (f.type === 'list') {
                  const lineas = t.split(NL).map(function (x) { return x.trim(); })
                    .filter(function (x) { return x !== ''; });
                  const salida = [];
                  for (let i = 0; i < lineas.length; i++) {
                    const r = convertir({ type: f.items || f.of || 'string', literals: f.literals }, lineas[i]);
                    if (r.error) return { error: 'Linea ' + (i + 1) + ': ' + r.error };
                    salida.push(r.valor);
                  }
                  return { valor: salida };
                }
                if (t.trim() === '') return { valor: null };
                switch (f.type) {
                  case 'integer':
                  case 'long': {
                    const n = Number(t);
                    return Number.isInteger(n) ? { valor: n } : { error: 'Tiene que ser un numero entero' };
                  }
                  case 'decimal': {
                    const n = Number(t);
                    return Number.isFinite(n) ? { valor: n } : { error: 'Tiene que ser un numero' };
                  }
                  case 'boolean':
                    if (t === 'true') return { valor: true };
                    if (t === 'false') return { valor: false };
                    return { error: 'Tiene que ser si o no' };
                  case 'datetime':
                    return { valor: t.length === 16 ? t + ':00' : t };
                  case 'enum':
                    if (Array.isArray(f.literals) && f.literals.length && f.literals.indexOf(t) < 0) {
                      return { error: 'Tiene que ser uno de: ' + f.literals.join(', ') };
                    }
                    return { valor: t };
                  case 'embedded': {
                    let j;
                    try { j = JSON.parse(t); } catch (err) { return { error: 'No es un JSON valido' }; }
                    if (j === null || typeof j !== 'object' || Array.isArray(j)) {
                      return { error: 'Tiene que ser un objeto JSON: { ... }' };
                    }
                    return { valor: j };
                  }
                  default:
                    return { valor: t };
                }
              }

              function normal(f, v) {
                if (v === undefined) return null;
                if (f && f.type === 'datetime' && v != null) return String(v).slice(0, 19);
                return v;
              }

              function iguales(f, a, b) {
                return JSON.stringify(normal(f, a)) === JSON.stringify(normal(f, b));
              }

              // Alta: solo lo que tiene valor; lo vacio no viaja (lo omitido no es null ni false).
              function cuerpoAlta(e, valores) {
                const c = {};
                (e.fields || []).forEach(function (f) {
                  const v = valores[f.name];
                  if (v === null || v === undefined) return;
                  if (f.type === 'list' && Array.isArray(v) && v.length === 0) return;
                  c[f.name] = v;
                });
                return c;
              }

              // Edicion por PATCH: solo lo que cambio (null = vaciar a proposito) y la version
              // LEIDA, para que una escritura ajena en el medio de un 409 en vez de pisarla.
              // Lo que no se toco (embebidos, listas, referencias) no viaja y se conserva.
              // Devuelve null si no hay cambios.
              function cuerpoEdicion(e, original, valores) {
                const c = {};
                (e.fields || []).forEach(function (f) {
                  if (!(f.name in valores)) return;
                  if (!iguales(f, original[f.name], valores[f.name])) c[f.name] = valores[f.name];
                });
                if (Object.keys(c).length === 0) return null;
                if (original[META.version] !== undefined) c[META.version] = original[META.version];
                return c;
              }

              // Columnas de la tabla: los campos del manifiesto, sin la auditoria.
              function columnas(e) {
                return (e.fields || []).map(function (f) { return f; });
              }

              // Lee una respuesta de error de la API: {"error": "...", "errores": {campo: msg}}.
              function interpretarError(estado, texto) {
                let j = null;
                try { j = JSON.parse(texto); } catch (err) { j = null; }
                const mensaje = (j && typeof j.error === 'string' && j.error) || (texto && String(texto).slice(0, 300))
                  || ('HTTP ' + estado);
                const campos = j && j.errores && typeof j.errores === 'object' && !Array.isArray(j.errores)
                  ? j.errores : {};
                let tipo = 'error';
                if (estado === 400) tipo = 'validacion';
                else if (estado === 404) tipo = 'noExiste';
                // Los dos 409 de version del backend generado (el del servicio, "cambio desde que lo
                // leiste (version N, ahora es M)", y el del manejador comun, "Otro usuario modifico")
                // terminan en "Volve a leerlo". Los de integridad y el de Mongo (D-02) no.
                else if (estado === 409) tipo = /leerlo|versi[oó]n/i.test(mensaje) ? 'version' : 'conflicto';
                return { estado: estado, tipo: tipo, mensaje: mensaje, campos: campos };
              }

              return {
                META: META, ESCALARES: ESCALARES, PROPIOS: PROPIOS,
                entidad: entidad, puede: puede, etiquetaDe: etiquetaDe, ordenables: ordenables,
                urlLista: urlLista, urlRegistro: urlRegistro, totalPaginas: totalPaginas,
                paginaTrasBorrar: paginaTrasBorrar, etiquetaRegistro: etiquetaRegistro,
                aTexto: aTexto, convertir: convertir, iguales: iguales, cuerpoAlta: cuerpoAlta,
                cuerpoEdicion: cuerpoEdicion, columnas: columnas, interpretarError: interpretarError
              };
            })();

            // ------------------------------------------------------------ pantalla
            const N = ConsolaNucleo;
            const estado = {
              manifiesto: null, entidad: null, pagina: 0, tamano: 10, orden: '', sentido: 'asc',
              pedido: 0, vista: 0, filas: [], total: 0, editando: null, opciones: new Map()
            };

            function $(id) { return document.getElementById(id); }

            // Crea un elemento; los hijos de texto van como texto, nunca como HTML.
            function el(tag, props, hijos) {
              const n = document.createElement(tag);
              Object.keys(props || {}).forEach(function (k) {
                if (k === 'clase') n.className = props[k];
                else if (k === 'texto') n.textContent = props[k];
                else if (k.indexOf('on') === 0) n[k] = props[k];
                else if (props[k] !== undefined && props[k] !== null && props[k] !== false) n.setAttribute(k, props[k]);
              });
              (hijos || []).forEach(function (h) {
                if (h === null || h === undefined) return;
                n.appendChild(typeof h === 'string' ? document.createTextNode(h) : h);
              });
              return n;
            }

            async function pedir(metodo, ruta, cuerpo) {
              const opciones = { method: metodo, headers: { 'Accept': 'application/json' } };
              if (cuerpo !== undefined) {
                opciones.headers['Content-Type'] = 'application/json';
                opciones.body = JSON.stringify(cuerpo);
              }
              let r;
              try {
                r = await fetch(ruta, opciones);
              } catch (err) {
                return { ok: false, error: { estado: 0, tipo: 'red', mensaje: 'No se pudo llamar a la API: ' + err.message, campos: {} } };
              }
              const texto = await r.text();
              if (!r.ok) return { ok: false, error: N.interpretarError(r.status, texto) };
              let datos = null;
              try { datos = texto ? JSON.parse(texto) : null; } catch (err) { datos = null; }
              return { ok: true, datos: datos, total: r.headers.get('X-Total-Count') };
            }

            async function arrancar() {
              const r = await pedir('GET', 'app-manifest.json');
              if (!r.ok || !r.datos) {
                $('cuerpo').replaceChildren(el('div', { clase: 'panel aviso mal',
                  texto: 'No se pudo leer app-manifest.json: ' + (r.error ? r.error.mensaje : 'vacio') }));
                return;
              }
              estado.manifiesto = r.datos;
              $('titulo').textContent = estado.manifiesto.app || 'Consola de prueba';
              document.title = $('titulo').textContent;
              const ents = estado.manifiesto.entities || [];
              if (!ents.length) {
                $('cuerpo').replaceChildren(el('div', { clase: 'panel vacio', texto: 'El modelo no genero ninguna entidad.' }));
                return;
              }
              dibujarMenu();
              elegir(ents[0]);
            }

            function dibujarMenu() {
              $('menu').replaceChildren.apply($('menu'), (estado.manifiesto.entities || []).map(function (e) {
                return el('button', { 'data-nombre': e.name, onclick: function () { elegir(e); } },
                  [N.etiquetaDe(e), e.abstract ? el('small', { texto: ' (abstracta)' }) : null]);
              }));
            }

            function elegir(e) {
              estado.entidad = e;
              estado.pagina = 0;
              estado.orden = '';
              estado.sentido = 'asc';
              estado.editando = null;
              estado.opciones = new Map();
              estado.pedido++; // una respuesta tardia del listado anterior ya no cuenta
              estado.vista++;  // ni las opciones de referencia pedidas para la entidad anterior
              Array.prototype.forEach.call($('menu').children, function (b) {
                b.classList.toggle('activa', b.dataset.nombre === e.name);
              });
              $('cuerpo').replaceChildren(
                el('div', { id: 'aviso' }),
                el('div', { clase: 'panel', id: 'panel-form' }),
                el('div', { clase: 'panel', id: 'panel-lista' }));
              dibujarFormulario(null);
              listar();
            }

            function avisar(tipo, texto, extra) {
              $('aviso').replaceChildren(texto ? el('div', { clase: 'aviso ' + tipo }, [texto, extra || null]) : null);
            }

            // ---- opciones de las referencias (registros de la entidad destino)
            const MAX_OPCIONES = 200;
            function opcionesDe(destino) {
              if (!estado.opciones.has(destino.name)) {
                estado.opciones.set(destino.name,
                  pedir('GET', N.urlLista(destino, { pagina: 0, tamano: MAX_OPCIONES })).then(function (r) {
                    return r.ok && Array.isArray(r.datos) ? { filas: r.datos, total: Number(r.total) || r.datos.length }
                                                         : { filas: [], total: 0, error: r.error };
                  }));
              }
              return estado.opciones.get(destino.name);
            }

            // ---- formulario (alta o edicion)
            function dibujarFormulario(registro) {
              const e = estado.entidad;
              estado.editando = registro;
              const panel = $('panel-form');
              const edita = registro !== null;
              const titulo = edita ? 'Editar ' + N.etiquetaDe(e) + ' · ' + String(registro.id).slice(0, 8)
                                   : 'Nuevo ' + N.etiquetaDe(e);
              const puedeEscribir = edita ? N.puede(e, 'update') : N.puede(e, 'create');
              if (!puedeEscribir) {
                panel.replaceChildren(el('h2', { texto: N.etiquetaDe(e) + ' · ' + e.path }),
                  el('p', { clase: 'vacio', texto: e.abstract
                    ? 'Es una clase abstracta: se lista y se borra, pero se crea desde sus subclases.'
                    : 'Esta entidad no admite esa operacion.' }));
                return;
              }
              const form = el('form', { id: 'formulario', novalidate: 'novalidate' });
              (e.fields || []).forEach(function (f) {
                const valorTexto = edita ? N.aTexto(f, registro[f.name]) : '';
                form.appendChild(el('label', { 'data-campo': f.name }, [
                  el('span', { clase: 'cap' }, [N.etiquetaDe(f) + ' ', el('em', { texto: tipoVisible(f) }),
                    f.required ? el('span', { clase: 'req', texto: ' *' }) : null]),
                  control(f, valorTexto, edita ? registro[f.name] : null),
                  el('div', { clase: 'err-campo' })
                ]));
              });
              form.appendChild(el('div', { clase: 'fila-botones' }, [
                el('button', { clase: 'accion', type: 'submit', texto: edita ? 'Guardar cambios' : 'Crear' }),
                edita ? el('button', { clase: 'sec', type: 'button', texto: 'Cancelar',
                  onclick: function () { avisar(); dibujarFormulario(null); } }) : null
              ]));
              form.onsubmit = function (ev) { ev.preventDefault(); guardar(form); };
              panel.replaceChildren(el('h2', { texto: titulo + ' · ' + e.path }), form);
            }

            function tipoVisible(f) {
              if (f.type === 'reference') return '-> ' + f.target;
              if (f.type === 'list') return 'lista de ' + (f.items || f.of || 'texto') + ' (uno por linea)';
              if (f.type === 'embedded') return (f.target || 'objeto') + ' en JSON';
              return f.type;
            }

            function control(f, valorTexto, valorCrudo) {
              const nombre = f.name;
              if (f.type === 'boolean') {
                // Tres estados: sin valor, si, no. Una casilla sola no distingue "sin valor" de "no".
                const s = el('select', { name: nombre }, [
                  f.required ? null : el('option', { value: '', texto: '(sin valor)' }),
                  el('option', { value: 'true', texto: 'si' }),
                  el('option', { value: 'false', texto: 'no' })]);
                s.value = valorTexto || (f.required ? 'false' : '');
                return s;
              }
              if (f.type === 'enum') {
                const s = el('select', { name: nombre }, [el('option', { value: '', texto: f.required ? '(elegir)' : '(sin valor)' })]
                  .concat((f.literals || []).map(function (l) { return el('option', { value: l, texto: l }); })));
                s.value = valorTexto;
                return s;
              }
              if (f.type === 'reference') return controlReferencia(f, valorTexto);
              if (f.type === 'embedded' || f.type === 'list') {
                const t = el('textarea', { name: nombre });
                t.value = valorTexto;
                return t;
              }
              const i = el('input', { name: nombre });
              if (f.type === 'integer' || f.type === 'long') { i.type = 'number'; i.step = '1'; }
              else if (f.type === 'decimal') { i.type = 'number'; i.step = 'any'; }
              else if (f.type === 'date') i.type = 'date';
              else if (f.type === 'datetime') { i.type = 'datetime-local'; i.step = '1'; }
              else i.type = 'text';
              if (f.min !== undefined && f.min !== null) i.min = f.min;
              if (f.max !== undefined && f.max !== null) i.max = f.max;
              if (f.maxLength) i.maxLength = f.maxLength;
              i.value = valorTexto;
              return i;
            }

            function controlReferencia(f, actual) {
              const destino = N.entidad(estado.manifiesto, f.target);
              if (!destino) {
                // Destino desconocido: se escribe el id a mano.
                const i = el('input', { name: f.name, type: 'text', placeholder: 'id de ' + f.target });
                i.value = actual;
                return i;
              }
              // Arranca ya con el valor actual: si se guarda antes de que lleguen las opciones,
              // la referencia no se pierde.
              const s = el('select', { name: f.name },
                [el('option', { value: '', texto: f.required ? '(cargando...)' : '(ninguno)' }),
                 actual ? el('option', { value: actual, texto: '(actual) ' + actual.slice(0, 8) }) : null]);
              s.value = actual || '';
              const vistaAlDibujar = estado.vista; // vigencia de la ENTIDAD, no del listado
              opcionesDe(destino).then(function (r) {
                if (vistaAlDibujar !== estado.vista) return;
                const ops = [el('option', { value: '', texto: f.required ? '(elegir ' + N.etiquetaDe(destino) + ')' : '(ninguno)' })];
                r.filas.forEach(function (fila) {
                  ops.push(el('option', { value: fila.id, texto: N.etiquetaRegistro(destino, fila) }));
                });
                if (actual && !r.filas.some(function (fila) { return String(fila.id) === actual; })) {
                  ops.push(el('option', { value: actual, texto: '(actual) ' + actual.slice(0, 8) }));
                }
                if (r.total > r.filas.length) {
                  ops.push(el('option', { value: '', disabled: 'disabled',
                    texto: '... hay ' + r.total + '; se muestran ' + r.filas.length }));
                }
                if (r.error) ops.push(el('option', { value: '', disabled: 'disabled', texto: 'No se pudo cargar: ' + r.error.mensaje }));
                s.replaceChildren.apply(s, ops);
                s.value = actual || '';
              });
              return s;
            }

            function leerFormulario(form) {
              const valores = {};
              const errores = {};
              (estado.entidad.fields || []).forEach(function (f) {
                const ctl = form.elements[f.name];
                if (!ctl) return;
                const r = N.convertir(f, ctl.value);
                if (r.error) errores[f.name] = r.error;
                else valores[f.name] = r.valor;
              });
              return { valores: valores, errores: errores };
            }

            function marcarErrores(form, errores) {
              Array.prototype.forEach.call(form.querySelectorAll('label[data-campo]'), function (lab) {
                const msg = errores[lab.dataset.campo];
                lab.classList.toggle('con-error', !!msg);
                lab.querySelector('.err-campo').textContent = msg || '';
              });
            }

            async function guardar(form) {
              const e = estado.entidad;
              const leido = leerFormulario(form);
              const original = estado.editando;
              if (!original) {
                (e.fields || []).forEach(function (f) {
                  const v = leido.valores[f.name];
                  if (f.required && !leido.errores[f.name] && (v === null || v === undefined)) leido.errores[f.name] = 'Es obligatorio';
                });
              }
              marcarErrores(form, leido.errores);
              if (Object.keys(leido.errores).length) { avisar('mal', 'Revisa los campos marcados.'); return; }

              let r;
              if (original) {
                const cuerpo = N.cuerpoEdicion(e, original, leido.valores);
                if (cuerpo === null) { avisar('ojo', 'No cambiaste nada.'); return; }
                r = await pedir('PATCH', N.urlRegistro(e, original.id), cuerpo);
              } else {
                r = await pedir('POST', e.path, N.cuerpoAlta(e, leido.valores));
              }
              if (estado.entidad !== e) return;
              if (!r.ok) { mostrarError(form, r.error, original); return; }
              estado.opciones = new Map();
              avisar('ok', original ? 'Guardado.' : 'Creado: ' + N.etiquetaRegistro(e, r.datos || {}));
              dibujarFormulario(null);
              listar();
            }

            // Ante un error el formulario NO se borra: el borrador queda para corregirlo.
            function mostrarError(form, error, original) {
              marcarErrores(form, error.campos || {});
              if (error.tipo === 'version' && original) {
                avisar('ojo', 'Version vieja (409): ' + error.mensaje + ' Tu borrador sigue en el formulario. ',
                  el('button', { clase: 'sec', type: 'button', texto: 'Recargar el registro (descarta el borrador)',
                    onclick: function () { recargar(original.id); } }));
              } else if (error.tipo === 'noExiste') {
                avisar('mal', 'Ese registro ya no existe (404). ' + error.mensaje);
              } else if (error.tipo === 'validacion') {
                avisar('mal', 'La API rechazo los datos (400): ' + error.mensaje);
              } else {
                avisar('mal', (error.estado ? 'HTTP ' + error.estado + ': ' : '') + error.mensaje);
              }
            }

            // Lee el registro y lo pone en el formulario. Devuelve false si no se pudo
            // (404, red, 500): el aviso queda visible y el formulario no cambia.
            async function recargar(id) {
              const e = estado.entidad;
              const r = await pedir('GET', N.urlRegistro(e, id));
              if (estado.entidad !== e) return false;
              if (!r.ok) {
                avisar('mal', (r.error.tipo === 'noExiste' ? 'Ese registro ya no existe (404). ' : '') + r.error.mensaje);
                if (r.error.tipo === 'noExiste') listar(); // otro lo borro: la fila deja de mostrarse
                return false;
              }
              avisar('ok', 'Registro recargado con la version actual. Aplica tu cambio de nuevo.');
              dibujarFormulario(r.datos);
              return true;
            }

            async function editar(id) {
              if (!(await recargar(id))) return; // el error de la lectura se queda a la vista
              avisar();
              $('panel-form').scrollIntoView({ behavior: 'smooth', block: 'start' });
            }

            async function borrar(id) {
              const e = estado.entidad;
              const r = await pedir('DELETE', N.urlRegistro(e, id));
              if (estado.entidad !== e) return;
              if (!r.ok) {
                avisar('mal', (r.error.estado ? 'No se pudo borrar (HTTP ' + r.error.estado + '): ' : '') + r.error.mensaje);
                return;
              }
              estado.opciones = new Map();
              if (estado.editando && String(estado.editando.id) === String(id)) dibujarFormulario(null);
              estado.pagina = N.paginaTrasBorrar(estado.pagina, estado.filas.length);
              avisar('ok', 'Borrado.');
              listar();
            }

            // ---- listado
            function herramientas() {
              const e = estado.entidad;
              const orden = el('select', { name: 'orden', onchange: function (ev) {
                estado.orden = ev.target.value; estado.pagina = 0; listar(); } },
                [el('option', { value: '', texto: '(sin orden)' })].concat(N.ordenables(e).map(function (o) {
                  return el('option', { value: o.valor, texto: o.texto }); })));
              orden.value = estado.orden;
              const sentido = el('select', { name: 'sentido', onchange: function (ev) {
                estado.sentido = ev.target.value; estado.pagina = 0; listar(); } },
                [el('option', { value: 'asc', texto: 'ascendente' }), el('option', { value: 'desc', texto: 'descendente' })]);
              sentido.value = estado.sentido;
              const tamano = el('select', { name: 'tamano', onchange: function (ev) {
                estado.tamano = Number(ev.target.value); estado.pagina = 0; listar(); } },
                [5, 10, 20, 50].map(function (n) { return el('option', { value: String(n), texto: n + ' por pagina' }); }));
              tamano.value = String(estado.tamano);
              return el('div', { clase: 'herramientas' }, [
                el('label', {}, [el('span', { clase: 'cap', texto: 'Ordenar por' }), orden]),
                el('label', {}, [el('span', { clase: 'cap', texto: 'Sentido' }), sentido]),
                el('label', {}, [el('span', { clase: 'cap', texto: 'Tamano' }), tamano])]);
            }

            async function listar() {
              const e = estado.entidad;
              const miPedido = ++estado.pedido;
              const panel = $('panel-lista');
              const r = await pedir('GET', N.urlLista(e, estado));
              if (miPedido !== estado.pedido) return; // llego tarde: ya se pidio otra cosa
              const partes = [el('h2', { texto: 'Registros de ' + N.etiquetaDe(e) }), herramientas()];
              if (!r.ok) {
                partes.push(el('div', { clase: 'aviso mal', texto: 'No se pudo listar: ' + r.error.mensaje }));
                panel.replaceChildren.apply(panel, partes);
                return;
              }
              const filas = Array.isArray(r.datos) ? r.datos : [];
              estado.filas = filas;
              estado.total = r.total !== null && r.total !== undefined ? Number(r.total) : filas.length;
              const paginas = N.totalPaginas(estado.total, estado.tamano);
              if (!filas.length && estado.pagina > 0) { estado.pagina = Math.min(estado.pagina - 1, paginas - 1); listar(); return; }
              if (!filas.length) {
                partes.push(el('p', { clase: 'vacio', texto: 'Todavia no hay registros.' }));
              } else {
                const cols = N.columnas(e);
                const cabecera = el('tr', {}, [el('th', { texto: 'id' })].concat(cols.map(function (f) {
                  return el('th', { texto: N.etiquetaDe(f) }); })).concat([el('th', { texto: '' })]));
                const cuerpo = filas.map(function (fila) { return filaTabla(e, cols, fila); });
                partes.push(el('div', { clase: 'tabla' }, [el('table', {}, [el('thead', {}, [cabecera]), el('tbody', {}, cuerpo)])]));
              }
              partes.push(el('div', { clase: 'paginas' }, [
                el('button', { clase: 'sec', type: 'button', texto: 'Anterior', disabled: estado.pagina <= 0 ? 'disabled' : null,
                  onclick: function () { estado.pagina--; listar(); } }),
                el('span', { id: 'pagina-info', texto: 'Pagina ' + (estado.pagina + 1) + ' de ' + paginas + ' · ' + estado.total + ' registros' }),
                el('button', { clase: 'sec', type: 'button', texto: 'Siguiente', disabled: estado.pagina >= paginas - 1 ? 'disabled' : null,
                  onclick: function () { estado.pagina++; listar(); } })]));
              panel.replaceChildren.apply(panel, partes);
            }

            function filaTabla(e, cols, fila) {
              const celdas = [el('td', { texto: String(fila.id).slice(0, 8), title: String(fila.id) })];
              cols.forEach(function (f) {
                const v = fila[f.name];
                const td = el('td', { texto: v === null || v === undefined ? '' :
                  (typeof v === 'object' ? JSON.stringify(v) : String(v)) });
                if (f.type === 'reference' && v) {
                  const destino = N.entidad(estado.manifiesto, f.target);
                  if (destino) {
                    const vistaAlDibujar = estado.vista; // vigencia de la ENTIDAD, no del listado
                    opcionesDe(destino).then(function (r) {
                      if (vistaAlDibujar !== estado.vista) return;
                      const ref = r.filas.find(function (x) { return String(x.id) === String(v); });
                      if (ref) td.textContent = N.etiquetaRegistro(destino, ref);
                    });
                  }
                }
                celdas.push(td);
              });
              celdas.push(el('td', { clase: 'acciones' }, [
                N.puede(e, 'update') ? el('button', { type: 'button', texto: 'Editar', onclick: function () { editar(fila.id); } }) : null,
                N.puede(e, 'delete') ? el('button', { clase: 'borrar', type: 'button', texto: 'Borrar', onclick: function () { borrar(fila.id); } }) : null]));
              return el('tr', { 'data-id': String(fila.id) }, celdas);
            }

            if (typeof document !== 'undefined') {
              window.ConsolaNucleo = ConsolaNucleo;
              arrancar();
            }
            </script>
            </body>
            </html>
            """;
}
