package com.uagrm.colabmodel.codegen;

import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Almacen;
import com.uagrm.colabmodel.modelcore.mapping.Ambiguedad;
import com.uagrm.colabmodel.modelcore.mapping.Decisiones;
import com.uagrm.colabmodel.modelcore.metamodel.UmlAttribute;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClass;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * La consola de prueba del backend generado (T-12).
 *
 * Lo importante es el COMPORTAMIENTO, no que el HTML contenga ciertas
 * palabras (dictamen del colider sobre D-10): por eso el nucleo de la consola
 * (`ConsolaNucleo`, funciones puras) se ejecuta con Node contra el
 * `app-manifest.json` que el generador emite de verdad para Tienda,
 * Biblioteca y un dominio que ya usa `version` (y entonces la version tecnica
 * se llama `versionRegistro`). Si la maquina no tiene Node, esa parte se omite.
 *
 * La pantalla (DOM) se recorre en un navegador contra el backend corriendo:
 * eso va en la Entrega de la ficha, con turno de maquina.
 */
class PlantillaConsolaTest {

    private static final String INDEX = "src/main/resources/static/index.html";

    // ------------------------------------------------------------------ sin Node

    @Test
    void las_dos_variantes_traen_la_consola_con_los_nombres_tecnicos_del_proyecto() {
        for (Almacen almacen : Almacen.values()) {
            String html = tienda(almacen).archivos().get(INDEX);
            assertThat(html).as(almacen.name()).isNotNull()
                    .contains("version: 'version', creado: 'createdAt', actualizado: 'updatedAt'")
                    .doesNotContain("__META_");
        }
        String software = software().archivos().get(INDEX);
        assertThat(software)
                .contains("version: 'versionRegistro', creado: 'registroCreadoEn', actualizado: 'registroActualizadoEn'");
    }

    @Test
    void no_interpreta_datos_como_html_ni_usa_los_nombres_viejos_del_manifiesto() {
        String html = tienda(Almacen.POSTGRES).archivos().get(INDEX);
        String script = script(html);
        // Todo se dibuja con textContent/createElement: ningun dato del backend pasa por innerHTML.
        assertThat(script).doesNotContain("innerHTML").doesNotContain("outerHTML").doesNotContain("insertAdjacentHTML");
        // El manifiesto vigente usa path, create/update/delete e integer (no endpoint, crear ni int).
        assertThat(script).doesNotContain(".endpoint").doesNotContain("'crear'").doesNotContain("'int'");
    }

    // ------------------------------------------------------------------ con Node

    @Test
    void nucleo_contra_el_manifiesto_real_de_tienda() throws Exception {
        ProyectoGenerado p = tienda(Almacen.POSTGRES);
        correrConNode(p, """
                const cliente = N.entidad(M, 'Cliente');
                const pedido = N.entidad(M, 'Pedido');
                const producto = N.entidad(M, 'Producto');
                const linea = N.entidad(M, 'LineaPedido');
                ok(cliente && pedido && producto && linea, 'entidades del manifiesto');

                // Lee el manifiesto vigente: path y acciones en ingles.
                igual(cliente.path, '/api/cliente');
                ok(N.puede(cliente, 'create') && N.puede(cliente, 'update') && N.puede(cliente, 'delete'), 'acciones');

                // La referencia de Pedido apunta a una entidad que existe en el manifiesto.
                const ref = pedido.fields.find(f => f.type === 'reference');
                igual(ref.name, 'clienteId');
                igual(N.entidad(M, ref.target).name, 'Cliente');

                // Paginas desde 0, sort con el nombre del campo, total de X-Total-Count.
                igual(N.urlLista(cliente, { pagina: 2, tamano: 5, orden: 'nombre', sentido: 'desc' }),
                      '/api/cliente?page=2&size=5&sort=nombre%2Cdesc');
                igual(N.urlLista(cliente, { pagina: 0, tamano: 10, orden: '' }), '/api/cliente?page=0&size=10');
                igual(N.totalPaginas('11', 5), 3);
                igual(N.totalPaginas(0, 10), 1);
                igual(N.paginaTrasBorrar(2, 1), 1);
                igual(N.paginaTrasBorrar(0, 1), 0);
                igual(N.paginaTrasBorrar(2, 3), 2);

                // Solo se ofrece ordenar por lo que la API acepta: escalares propios + auditoria.
                const ord = N.ordenables(pedido).map(o => o.valor);
                igual(ord, ['fecha', 'estado', 'createdAt', 'updatedAt']);

                // Etiqueta legible y distinguible.
                igual(N.etiquetaRegistro(cliente, { id: 'a1b2c3d4e5f6', nombre: 'Ana' }), 'Ana · a1b2c3d4');
                ok(N.etiquetaRegistro(cliente, { id: '11111111x', nombre: 'Ana' })
                   !== N.etiquetaRegistro(cliente, { id: '22222222x', nombre: 'Ana' }), 'dos Ana se distinguen');
                igual(N.etiquetaRegistro(linea, { id: 'ffffffff0', cantidad: 3 }), '3 · ffffffff');

                // Tipos.
                const cantidad = linea.fields.find(f => f.name === 'cantidad');
                igual(cantidad.type, 'integer');
                igual(N.convertir(cantidad, '3').valor, 3);
                ok(N.convertir(cantidad, '3.5').error, 'un entero no acepta decimales');
                igual(N.convertir(cantidad, '').valor, null);
                const precio = producto.fields.find(f => f.name === 'precio');
                igual(N.convertir(precio, '12.50').valor, 12.5);
                const estado = pedido.fields.find(f => f.name === 'estado');
                ok(Array.isArray(estado.literals) && estado.literals.length > 0, 'enum con literales');
                igual(N.convertir(estado, estado.literals[0]).valor, estado.literals[0]);
                ok(N.convertir(estado, 'CUALQUIERA').error, 'enum fuera de la lista');

                // Alta: lo vacio no viaja.
                igual(N.cuerpoAlta(producto, { nombre: 'Lapiz', precio: null }), { nombre: 'Lapiz' });
                igual(N.cuerpoAlta(pedido, { fecha: '2026-11-05', estado: estado.literals[0], clienteId: 'c-1' }),
                      { fecha: '2026-11-05', estado: estado.literals[0], clienteId: 'c-1' });

                // Edicion (PATCH): solo lo que cambio, null para vaciar, y la version LEIDA.
                const orig = { id: 'p1', fecha: '2026-11-05', estado: estado.literals[0], clienteId: 'c-1',
                               version: 3, createdAt: 'x', updatedAt: 'y' };
                igual(N.cuerpoEdicion(pedido, orig, { fecha: '2026-11-06', estado: estado.literals[0], clienteId: 'c-1' }),
                      { fecha: '2026-11-06', version: 3 });
                igual(N.cuerpoEdicion(pedido, orig, { fecha: '2026-11-05', estado: estado.literals[0], clienteId: 'c-1' }), null);
                igual(N.cuerpoEdicion(pedido, orig, { fecha: '2026-11-05', estado: estado.literals[0], clienteId: 'c-2' }),
                      { clienteId: 'c-2', version: 3 });
                const origCli = { id: 'k', nombre: 'Ana', email: 'a@x.com', version: 0 };
                igual(N.cuerpoEdicion(cliente, origCli, { nombre: 'Ana', email: null }), { email: null, version: 0 });

                // Errores de la API.
                const v = N.interpretarError(409, JSON.stringify({ error:
                  'Otro usuario modifico este registro mientras lo editabas. Volve a leerlo y aplica el cambio de nuevo.',
                  errores: {} }));
                igual(v.tipo, 'version');
                // El 409 de version que manda el SERVICIO generado (lo encontro el recorrido real en PostgreSQL).
                igual(N.interpretarError(409, JSON.stringify({ error:
                  'Cliente 6d90 cambio desde que lo leiste (version 0, ahora es 1). Volve a leerlo y aplica el cambio de nuevo.',
                  errores: {} })).tipo, 'version');
                igual(N.interpretarError(409, JSON.stringify({ error:
                  'La operacion viola una restriccion de la base: un valor unico repetido, o un registro que otro todavia referencia.' })).tipo, 'conflicto');
                igual(N.interpretarError(409, JSON.stringify({ error:
                  'La operacion viola una restriccion de la base: un valor que debe ser unico se repite.' })).tipo, 'conflicto');
                igual(N.interpretarError(409, JSON.stringify({ error:
                  'Elimina primero las partes de este Pedido' })).tipo, 'conflicto');
                const e400 = N.interpretarError(400, JSON.stringify({ error: 'Datos invalidos',
                  errores: { nombre: 'no debe estar vacio' } }));
                igual(e400.tipo, 'validacion');
                igual(e400.campos.nombre, 'no debe estar vacio');
                igual(N.interpretarError(404, '').tipo, 'noExiste');
                igual(N.interpretarError(500, '<html>boom</html>').mensaje, '<html>boom</html>');
                """);
    }

    @Test
    void nucleo_contra_el_manifiesto_real_de_biblioteca() throws Exception {
        ProyectoGenerado p = ManifestDeAppTest.Biblioteca.generar(GeneradorSpringBoot.Opciones.por("Biblioteca", Almacen.MONGO));
        correrConNode(p, """
                const socio = N.entidad(M, 'Socio');
                const prestamo = N.entidad(M, 'Prestamo');
                const libro = N.entidad(M, 'Libro');
                ok(socio && prestamo && libro, 'entidades');

                // Booleano opcional: tres estados distintos (sin valor, si, no).
                const activo = socio.fields.find(f => f.name === 'activo');
                igual(activo.type, 'boolean');
                igual(N.convertir(activo, '').valor, null);
                igual(N.convertir(activo, 'false').valor, false);
                igual(N.convertir(activo, 'true').valor, true);
                igual(N.cuerpoAlta(socio, { nombre: 'Ana', email: null, activo: false }), { nombre: 'Ana', activo: false });
                igual(N.cuerpoAlta(socio, { nombre: 'Ana', email: null, activo: null }), { nombre: 'Ana' });
                const origSocio = { id: 's', nombre: 'Ana', email: null, activo: true, version: 1 };
                igual(N.cuerpoEdicion(socio, origSocio, { nombre: 'Ana', email: null, activo: null }), { activo: null, version: 1 });
                igual(N.cuerpoEdicion(socio, origSocio, { nombre: 'Ana', email: null, activo: false }), { activo: false, version: 1 });

                // Cero no es vacio.
                const anio = libro.fields.find(f => f.name === 'anioPublicacion');
                igual(N.convertir(anio, '0').valor, 0);
                const precio = libro.fields.find(f => f.name === 'precio');
                igual(N.cuerpoAlta(libro, { titulo: 't', isbn: 'i', estado: 'DISPONIBLE', precio: 0 }).precio, 0);

                // Fecha y hora: el control da minutos o segundos; se manda con segundos, y una
                // fraccion que devuelve la base no cuenta como cambio.
                const hora = prestamo.fields.find(f => f.name === 'horaRetiro');
                igual(hora.type, 'datetime');
                igual(N.convertir(hora, '2026-11-05T10:30').valor, '2026-11-05T10:30:00');
                igual(N.aTexto(hora, '2026-11-05T10:30:00.123456'), '2026-11-05T10:30:00');
                const origP = { id: 'x', fechaPrestamo: '2026-11-05', fechaDevolucion: null,
                                horaRetiro: '2026-11-05T10:30:00.123', libroId: 'l1', socioId: 's1', version: 4 };
                igual(N.cuerpoEdicion(prestamo, origP, { fechaPrestamo: '2026-11-05', fechaDevolucion: null,
                      horaRetiro: '2026-11-05T10:30:00', libroId: 'l1', socioId: 's1' }), null);

                // Dos referencias: cada una resuelve su destino.
                const refs = prestamo.fields.filter(f => f.type === 'reference').map(f => N.entidad(M, f.target).name);
                igual(refs, ['Libro', 'Socio']);

                // Ordenables de Prestamo: sin las referencias.
                igual(N.ordenables(prestamo).map(o => o.valor),
                      ['fechaPrestamo', 'fechaDevolucion', 'horaRetiro', 'createdAt', 'updatedAt']);
                """);
    }

    @Test
    void nucleo_con_versionRegistro_cuando_el_dominio_ya_usa_version() throws Exception {
        correrConNode(software(), """
                const sw = N.entidad(M, 'Software');
                ok(sw, 'entidad');
                igual(N.META.version, 'versionRegistro');
                // `version` es un campo del dominio (texto) y se edita como cualquier otro...
                const campo = sw.fields.find(f => f.name === 'version');
                igual(campo.type, 'string');
                // ...y la version tecnica que viaja para el bloqueo optimista es versionRegistro.
                const orig = { id: 'q', version: '1.0', versionRegistro: 7 };
                igual(N.cuerpoEdicion(sw, orig, { version: '2.0' }), { version: '2.0', versionRegistro: 7 });
                igual(N.ordenables(sw).map(o => o.valor), ['version', 'registroCreadoEn', 'registroActualizadoEn']);
                """);
    }

    @Test
    void nucleo_con_embebidos_y_listas_sin_perderlos() throws Exception {
        // Tipos que el manifiesto emite y que la consola no puede desarmar en subcampos.
        correrConNode(tienda(Almacen.MONGO), """
                const dir = { name: 'direccion', type: 'embedded', target: 'Direccion', required: false };
                const tags = { name: 'etiquetas', type: 'list', items: 'integer', required: false };
                const e = { name: 'X', path: '/api/x', fields: [dir, tags] };

                // Ida y vuelta sin cambios = sin cambios (no se borra ni se convierte en texto).
                const orig = { id: '1', direccion: { calle: 'A', numero: 3 }, etiquetas: [1, 2], version: 2 };
                const valores = {
                  direccion: N.convertir(dir, N.aTexto(dir, orig.direccion)).valor,
                  etiquetas: N.convertir(tags, N.aTexto(tags, orig.etiquetas)).valor
                };
                igual(valores.direccion, { calle: 'A', numero: 3 });
                igual(valores.etiquetas, [1, 2]);
                igual(N.cuerpoEdicion(e, orig, valores), null);

                // Si no se tocan, ni siquiera viajan en el PATCH.
                igual(N.cuerpoEdicion(e, orig, {}), null);

                ok(N.convertir(dir, '{ no es json').error, 'JSON invalido');
                ok(N.convertir(dir, '[1,2]').error, 'tiene que ser objeto');
                ok(N.convertir(tags, ['1', 'x'].join(String.fromCharCode(10))).error, 'linea que no es entero');
                igual(N.convertir(tags, '').valor, []);
                """);
    }

    // ------------------------------------------------------------------ pantalla (DOM minimo)
    // Dictamen de Codex sobre d64f6b2: el nucleo solo no alcanzaba. Estas pruebas
    // ejecutan la PANTALLA real con un DOM minimo y un fetch falso que anota cada pedido.

    @Test
    void pantalla_pedido_elige_el_cliente_de_la_lista_tambien_despues_de_guardar() throws Exception {
        correrPantalla(tienda(Almacen.POSTGRES), """
                rutas['GET /api/cliente'] = (url) => url.indexOf('size=200') >= 0 && tardia
                  ? new Promise((r) => { soltar = () => r({ estado: 200, cuerpo: [{ id: 'viejo', nombre: 'Viejo' }], total: 1 }); })
                  : { estado: 200, cuerpo: [{ id: 'cliente-1', nombre: 'Ana' }], total: 1 };
                rutas['GET /api/pedido'] = () => ({ estado: 200, cuerpo: [], total: 0 });
                rutas['POST /api/pedido'] = (url, c) => ({ estado: 201, cuerpo: Object.assign({ id: 'pedido-1', version: 0 }, c) });
                await flush();

                menu('Pedido'); await flush();
                let sel = q('#formulario [name=clienteId]');
                const opcion = sel.querySelectorAll('option').find((o) => o.value === 'cliente-1');
                ok(opcion, 'la lista trae al cliente (antes quedaba en "(cargando...)")');
                igual(opcion.textContent, 'Ana · cliente-');

                sel.value = 'cliente-1';
                q('#formulario [name=fecha]').value = '2026-11-05';
                const est = q('#formulario [name=estado]');
                est.value = est.querySelectorAll('option')[1].value;
                q('#formulario').onsubmit({ preventDefault() {} }); await flush();
                const posts = pedidos.filter((p) => p.metodo === 'POST');
                igual(posts.length, 1);
                igual(posts[0].cuerpo.clienteId, 'cliente-1');
                ok(aviso().indexOf('Creado') === 0, aviso());

                // El formulario de alta que se dibuja despues de guardar tambien trae las opciones.
                sel = q('#formulario [name=clienteId]');
                ok(sel.querySelectorAll('option').some((o) => o.value === 'cliente-1'), 'opciones tras guardar');

                // Una respuesta tardia pedida para Pedido no se dibuja si ya se cambio de entidad.
                tardia = true;
                menu('Pedido'); await flush();
                menu('Cliente'); await flush();
                soltar(); await flush();
                ok(!document.body.querySelectorAll('option').some((o) => o.value === 'viejo'), 'respuesta tardia descartada');
                """);
    }

    @Test
    void pantalla_lista_de_un_manifiesto_generado_conserva_tipos_y_no_manda_lo_que_no_cambio() throws Exception {
        ProyectoGenerado p = conListas();
        assertThat(p.archivos().get(ManifestDeApp.RUTA)).as("el manifiesto real trae listas con items")
                .contains("\"type\" : \"list\"").contains("\"items\" : \"integer\"");
        correrPantalla(p, """
                const e = M.entities.find((x) => x.name === 'Encuesta');
                const notas = e.fields.find((f) => f.name === 'notas');
                igual(notas.type, 'list');
                igual(notas.items, 'integer');
                const reg = { id: 'r1', titulo: 'T', notas: [1, 2], respuestas: [true, false], version: 0 };
                rutas['GET ' + e.path] = () => ({ estado: 200, cuerpo: [reg], total: 1 });
                rutas['GET ' + e.path + '/r1'] = () => ({ estado: 200, cuerpo: reg });
                rutas['PATCH ' + e.path + '/r1'] = (url, c) => ({ estado: 200, cuerpo: Object.assign({}, reg, c) });
                await flush();

                menu('Encuesta'); await flush();
                boton('Editar', q('tr[data-id=r1]')); await flush();
                ok(q('#panel-form h2').textContent.indexOf('Editar') === 0, 'formulario de edicion');

                // Sin tocar nada: no hay PATCH (antes salia {"notas":["1","2"],...}).
                q('#formulario').onsubmit({ preventDefault() {} }); await flush();
                igual(pedidos.filter((x) => x.metodo === 'PATCH').length, 0);
                igual(aviso(), 'No cambiaste nada.');

                // Un cambio viaja con numeros, y lo que no se toco no viaja.
                q('#formulario [name=notas]').value = ['1', '2', '3'].join(NL);
                q('#formulario').onsubmit({ preventDefault() {} }); await flush();
                const patches = pedidos.filter((x) => x.metodo === 'PATCH');
                igual(patches.length, 1);
                igual(patches[0].cuerpo, { notas: [1, 2, 3], version: 0 });

                // Una linea que no es entero se marca y no se manda.
                boton('Editar', q('tr[data-id=r1]')); await flush();
                q('#formulario [name=notas]').value = ['1', 'x'].join(NL);
                q('#formulario').onsubmit({ preventDefault() {} }); await flush();
                igual(pedidos.filter((x) => x.metodo === 'PATCH').length, 1);
                ok(q('label[data-campo=notas] .err-campo').textContent.length > 0, 'error junto al campo');
                """);
    }

    @Test
    void pantalla_editar_un_registro_que_no_se_puede_leer_deja_el_aviso_a_la_vista() throws Exception {
        correrPantalla(tienda(Almacen.MONGO), """
                let borrado = false;
                rutas['GET /api/cliente'] = () => borrado
                  ? { estado: 200, cuerpo: [{ id: 'r8', nombre: 'Cy' }], total: 1 }
                  : { estado: 200, cuerpo: [{ id: 'r9', nombre: 'Bo' }, { id: 'r8', nombre: 'Cy' }], total: 2 };
                rutas['GET /api/cliente/r9'] = () => { borrado = true; return { estado: 404, cuerpo: { error: 'No existe el registro', errores: {} } }; };
                rutas['GET /api/cliente/r8'] = () => ({ estado: 500, cuerpo: 'boom del servidor' });
                await flush();

                menu('Cliente'); await flush();
                boton('Editar', q('tr[data-id=r9]')); await flush();
                ok(aviso().indexOf('No existe el registro') >= 0, 'el 404 queda visible: ' + aviso());
                ok(q('#panel-form h2').textContent.indexOf('Nuevo') === 0, 'no se presenta un formulario de edicion');
                // Otro lo borro: tras el 404 la lista se vuelve a pedir y la fila desaparece.
                ok(!q('tr[data-id=r9]'), 'la fila borrada deja de mostrarse');

                boton('Editar', q('tr[data-id=r8]')); await flush();
                ok(aviso().indexOf('boom del servidor') >= 0, 'el 500 queda visible: ' + aviso());
                """);
    }

    /** Un dominio con atributos multivaluados: el manifiesto los emite como `list` con `items`. */
    private static ProyectoGenerado conListas() {
        UmlClass encuesta = new UmlClass();
        encuesta.setId(UUID.randomUUID());
        encuesta.setDiagramId(UUID.randomUUID());
        encuesta.setName("Encuesta");
        UmlAttribute titulo = atributo(encuesta, "titulo", "string");
        titulo.setRequired(true);
        UmlAttribute notas = atributo(encuesta, "notas", "int");
        notas.setMultivalued(true);
        UmlAttribute respuestas = atributo(encuesta, "respuestas", "boolean");
        respuestas.setMultivalued(true);
        // Leidos como OO (coleccion de valores), que es lo que el manifiesto emite como `list`.
        return GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("Encuestas"),
                List.of(encuesta), List.of(titulo, notas, respuestas), List.of(), List.of(),
                Decisiones.constructor()
                        .con(Ambiguedad.TipoAmbiguedad.MULTIVALUED, notas.getId(), Ambiguedad.Lectura.OO)
                        .con(Ambiguedad.TipoAmbiguedad.MULTIVALUED, respuestas.getId(), Ambiguedad.Lectura.OO)
                        .construir());
    }

    private static UmlAttribute atributo(UmlClass c, String nombre, String tipo) {
        UmlAttribute a = new UmlAttribute();
        a.setId(UUID.randomUUID());
        a.setClassId(c.getId());
        a.setName(nombre);
        a.setDataType(tipo);
        return a;
    }

    /**
     * Un DOM minimo con lo que usa la consola (no es un navegador): elementos,
     * texto, atributos, dataset, classList, selectores simples con descendientes,
     * form.elements y el value de select/input/textarea. Sin barras invertidas:
     * va dentro de un text block.
     */
    private static final String DOM_MINIMO = """
            function camel(s) { return s.split('-').map((p, i) => i ? p.charAt(0).toUpperCase() + p.slice(1) : p).join(''); }
            function Texto(v) { this.nodeType = 3; this.textContent = v; this.parentNode = null; }
            function Nodo(tag) {
              this.nodeType = 1; this.tagName = tag.toUpperCase(); this.childNodes = []; this.parentNode = null;
              this.attrs = {}; this.dataset = {}; this.className = ''; this.style = {}; this._v = ''; this._sel = undefined;
              const yo = this;
              this.classList = {
                contains(c) { return yo.className.split(' ').indexOf(c) >= 0; },
                add(c) { if (!this.contains(c)) yo.className = (yo.className + ' ' + c).trim(); },
                remove(c) { yo.className = yo.className.split(' ').filter((x) => x && x !== c).join(' '); },
                toggle(c, f) { const poner = f === undefined ? !this.contains(c) : !!f; poner ? this.add(c) : this.remove(c); return poner; }
              };
            }
            Nodo.prototype.setAttribute = function (k, v) {
              v = String(v); this.attrs[k] = v;
              if (k === 'id') this.id = v;
              if (k === 'class') this.className = v;
              if (k === 'name') this.name = v;
              if (k === 'disabled') this.disabled = true;
              if (k.indexOf('data-') === 0) this.dataset[camel(k.slice(5))] = v;
            };
            Nodo.prototype.getAttribute = function (k) { return k in this.attrs ? this.attrs[k] : null; };
            Object.defineProperty(Nodo.prototype, 'children', { get() { return this.childNodes.filter((c) => c.nodeType === 1); } });
            Nodo.prototype.appendChild = function (c) {
              if (c.parentNode) c.parentNode.childNodes = c.parentNode.childNodes.filter((x) => x !== c);
              c.parentNode = this; this.childNodes.push(c); return c;
            };
            Nodo.prototype.replaceChildren = function () {
              this.childNodes.forEach((c) => { c.parentNode = null; });
              this.childNodes = [];
              if (this.tagName === 'SELECT') this._sel = undefined;
              for (const c of arguments) { if (c === null || c === undefined) continue; this.appendChild(typeof c === 'string' ? new Texto(c) : c); }
            };
            Object.defineProperty(Nodo.prototype, 'textContent', {
              get() { return this.childNodes.map((c) => c.textContent).join(''); },
              set(v) { this.replaceChildren(new Texto(String(v))); }
            });
            Nodo.prototype.descendientes = function () {
              const out = [];
              (function rec(n) { n.children.forEach((c) => { out.push(c); rec(c); }); })(this);
              return out;
            };
            function parteSelector(s) {
              const p = { tag: null, id: null, clases: [], attrs: [] };
              let i = 0; let tag = '';
              while (i < s.length && '#.['.indexOf(s[i]) < 0) tag += s[i++];
              if (tag) p.tag = tag.toUpperCase();
              while (i < s.length) {
                const c = s[i++];
                if (c === '#' || c === '.') {
                  let v = ''; while (i < s.length && '#.['.indexOf(s[i]) < 0) v += s[i++];
                  if (c === '#') p.id = v; else p.clases.push(v);
                } else if (c === '[') {
                  let v = ''; while (i < s.length && s[i] !== ']') v += s[i++]; i++;
                  const k = v.split('=')[0];
                  let val = v.indexOf('=') >= 0 ? v.slice(v.indexOf('=') + 1) : null;
                  if (val !== null && (val[0] === '"' || val[0] === "'")) val = val.slice(1, -1);
                  p.attrs.push({ k: k, v: val });
                }
              }
              return p;
            }
            function coincide(n, p) {
              if (n.nodeType !== 1) return false;
              if (p.tag && n.tagName !== p.tag) return false;
              if (p.id && n.getAttribute('id') !== p.id) return false;
              if (p.clases.some((c) => !n.classList.contains(c))) return false;
              return p.attrs.every((a) => a.v === null ? n.getAttribute(a.k) !== null : n.getAttribute(a.k) === a.v);
            }
            function cadena(n, partes, i) {
              if (!coincide(n, partes[i])) return false;
              if (i === 0) return true;
              for (let a = n.parentNode; a; a = a.parentNode) if (cadena(a, partes, i - 1)) return true;
              return false;
            }
            Nodo.prototype.querySelectorAll = function (sel) {
              const partes = sel.split(' ').filter((x) => x).map(parteSelector);
              return this.descendientes().filter((n) => cadena(n, partes, partes.length - 1));
            };
            Nodo.prototype.querySelector = function (sel) { return this.querySelectorAll(sel)[0] || null; };
            Nodo.prototype.scrollIntoView = function () {};
            Object.defineProperty(Nodo.prototype, 'elements', { get() {
              const m = {}; this.descendientes().forEach((d) => { const nm = d.getAttribute('name'); if (nm) m[nm] = d; }); return m; } });
            Object.defineProperty(Nodo.prototype, 'value', {
              get() {
                if (this.tagName === 'OPTION') return this.getAttribute('value') !== null ? this.getAttribute('value') : this.textContent;
                if (this.tagName === 'SELECT') {
                  const ops = this.querySelectorAll('option');
                  if (this._sel === null) return '';
                  if (typeof this._sel === 'string' && ops.some((o) => o.value === this._sel)) return this._sel;
                  return ops.length ? ops[0].value : '';
                }
                return this._v;
              },
              set(v) {
                if (this.tagName === 'SELECT') {
                  const hit = this.querySelectorAll('option').find((o) => o.value === String(v));
                  this._sel = hit ? hit.value : null;
                } else { this._v = String(v); }
              }
            });
            const body = new Nodo('body');
            ['h1#titulo', 'nav#menu', 'section#cuerpo'].forEach((d) => {
              const n = new Nodo(d.split('#')[0]); n.setAttribute('id', d.split('#')[1]); body.appendChild(n);
            });
            globalThis.window = globalThis;
            globalThis.document = {
              body: body, title: '',
              createElement: (t) => new Nodo(t),
              createTextNode: (v) => new Texto(String(v)),
              getElementById: (id) => body.descendientes().find((n) => n.getAttribute('id') === id) || null
            };
            const rutas = {};
            const pedidos = [];
            let tardia = false; let soltar = null;
            globalThis.fetch = async function (url, opciones) {
              const metodo = (opciones && opciones.method) || 'GET';
              const cuerpo = opciones && opciones.body ? JSON.parse(opciones.body) : undefined;
              const ruta = String(url).split('?')[0];
              pedidos.push({ metodo: metodo, url: String(url), cuerpo: cuerpo });
              await new Promise((r) => setImmediate(r));
              let resp;
              if (ruta === 'app-manifest.json') resp = { estado: 200, cuerpo: M };
              else if (rutas[metodo + ' ' + ruta]) resp = await rutas[metodo + ' ' + ruta](String(url), cuerpo);
              else resp = metodo === 'GET' ? { estado: 200, cuerpo: [], total: 0 } : { estado: 404, cuerpo: { error: 'sin ruta ' + ruta } };
              const texto = typeof resp.cuerpo === 'string' ? resp.cuerpo : JSON.stringify(resp.cuerpo);
              return { ok: resp.estado >= 200 && resp.estado < 300, status: resp.estado,
                       text: async () => texto,
                       headers: { get: (k) => k.toLowerCase() === 'x-total-count' && resp.total !== undefined ? String(resp.total) : null } };
            };
            """;

    /**
     * Ejecuta la consola REAL (nucleo y pantalla) con el manifiesto REAL del
     * proyecto, sobre el DOM minimo, y despues el escenario.
     */
    private static void correrPantalla(ProyectoGenerado p, String escenario) throws Exception {
        avisarSiFaltaNode();
        assumeTrue(hayNode(), "sin Node en esta maquina: se omite la prueba de la pantalla de la consola");
        String js = "const M = " + p.archivos().get(ManifestDeApp.RUTA) + ";\n"
                + DOM_MINIMO
                + script(p.archivos().get(INDEX))
                + "\n;(async function () {\n"
                + "const assert = require('assert');\n"
                + "let n = 0;\n"
                + "function ok(c, msg) { assert.ok(c, msg); n++; }\n"
                + "function igual(a, b) { assert.deepStrictEqual(a, b); n++; }\n"
                + "const flush = async () => { for (let i = 0; i < 30; i++) await new Promise((r) => setImmediate(r)); };\n"
                + "const q = (s) => document.body.querySelector(s);\n"
                + "const menu = (nombre) => q('#menu button[data-nombre=' + nombre + ']').onclick();\n"
                + "const boton = (texto, dentro) => (dentro || document.body).querySelectorAll('button')"
                + ".find((b) => b.textContent.trim() === texto).onclick();\n"
                + "const aviso = () => (q('#aviso') || { textContent: '' }).textContent;\n"
                + escenario
                + "\nconsole.log('OK ' + n);\n"
                + "})().catch((e) => { console.error(e && e.stack || e); process.exit(1); });\n";
        Path archivo = Files.createTempFile("consola-pantalla-", ".js");
        try {
            Files.writeString(archivo, js, StandardCharsets.UTF_8);
            Process proc = new ProcessBuilder("node", archivo.toString()).redirectErrorStream(true).start();
            String salida = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(proc.waitFor(60, TimeUnit.SECONDS)).as("node termino").isTrue();
            assertThat(proc.exitValue()).as(salida).isZero();
            assertThat(salida).startsWith("OK ");
        } finally {
            Files.deleteIfExists(archivo);
        }
    }

    // ------------------------------------------------------------------ ayudas

    private static ProyectoGenerado tienda(Almacen almacen) {
        return ModelosDePrueba.tienda().generar(GeneradorSpringBoot.Opciones.por("Tienda", almacen));
    }

    /** Un dominio que ya tiene un atributo `version`: la version tecnica pasa a `versionRegistro`. */
    private static ProyectoGenerado software() {
        UmlClass software = new UmlClass();
        software.setId(UUID.randomUUID());
        software.setDiagramId(UUID.randomUUID());
        software.setName("Software");
        UmlAttribute version = new UmlAttribute();
        version.setId(UUID.randomUUID());
        version.setClassId(software.getId());
        version.setName("version");
        version.setDataType("string");
        return GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("Demo"),
                List.of(software), List.of(version), List.of(), List.of());
    }

    private static String script(String html) {
        Matcher m = Pattern.compile("<script>(.*)</script>", Pattern.DOTALL).matcher(html);
        assertThat(m.find()).as("la consola trae su script").isTrue();
        return m.group(1);
    }

    /**
     * Ejecuta el script REAL de la consola generada (sin DOM: solo el nucleo)
     * con el manifiesto REAL del mismo proyecto, y despues las comprobaciones.
     */
    private static void correrConNode(ProyectoGenerado p, String comprobaciones) throws Exception {
        avisarSiFaltaNode();
        assumeTrue(hayNode(), "sin Node en esta maquina: se omite la prueba de comportamiento de la consola");
        String manifiesto = p.archivos().get(ManifestDeApp.RUTA);
        String js = script(p.archivos().get(INDEX))
                + "\n;(function () {\n"
                + "const assert = require('assert');\n"
                + "const N = ConsolaNucleo;\n"
                + "const M = " + manifiesto + ";\n"
                + "let n = 0;\n"
                + "function ok(c, msg) { assert.ok(c, msg); n++; }\n"
                + "function igual(a, b) { assert.deepStrictEqual(a, b); n++; }\n"
                + comprobaciones
                + "\nconsole.log('OK ' + n);\n"
                + "})();\n";
        Path archivo = Files.createTempFile("consola-", ".js");
        try {
            Files.writeString(archivo, js, StandardCharsets.UTF_8);
            Process proc = new ProcessBuilder("node", archivo.toString()).redirectErrorStream(true).start();
            String salida = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(proc.waitFor(60, TimeUnit.SECONDS)).as("node termino").isTrue();
            assertThat(proc.exitValue()).as(salida).isZero();
            assertThat(salida).startsWith("OK ");
        } finally {
            Files.deleteIfExists(archivo);
        }
    }

    /**
     * Sin Node estas pruebas se omiten (no fallan), pero que se vea en la salida
     * de `mvn test` y no pase en silencio (observacion de be sobre f0a98ff).
     */
    private static void avisarSiFaltaNode() {
        if (!hayNode()) {
            System.err.println("AVISO PlantillaConsolaTest: no hay Node en esta maquina."
                    + " Se OMITEN las pruebas de comportamiento de la consola (nucleo y pantalla).");
        }
    }

    private static boolean hayNode() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
