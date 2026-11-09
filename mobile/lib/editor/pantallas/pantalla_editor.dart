// La pantalla del editor (T-15b). Solo habla con [EditorDeDiagrama]: no sabe
// de STOMP, de locks ni de payloads, que son de T-15a.
//
// Organizacion pensada para el telefono, igual que la version web movil:
//   - Lienzo a pantalla completa con zoom y paneo (InteractiveViewer).
//   - Menu ☰ (cajon) con lo del proyecto, y la PALETA en una hoja que sube.
//   - Tocar una clase o una relacion abre su hoja de propiedades.
//   - Mantener apretado y arrastrar mueve una clase.
//   - Con una relacion armada: tocar el origen y despues el destino.

import 'package:flutter/material.dart';

import '../../visor/dibujo_clases.dart';
import '../../visor/modelo.dart';
import '../../visor/sincronizador.dart';
import '../contrato_editor.dart';
import 'hojas.dart';
import 'interaccion.dart';

class PantallaEditor extends StatefulWidget {
  final EditorDeDiagrama editor;
  final String titulo;

  /// Si la pantalla cierra el editor al salir (en las pruebas, no).
  final bool cerrarAlSalir;

  const PantallaEditor({super.key, required this.editor, required this.titulo, this.cerrarAlSalir = true});

  @override
  State<PantallaEditor> createState() => _PantallaEditorState();
}

class _PantallaEditorState extends State<PantallaEditor> {
  EditorDeDiagrama get _e => widget.editor;

  Herramienta _herramienta = const Seleccionar();
  final _zoom = TransformationController();
  bool _encuadrado = false;
  Size _pantalla = Size.zero;

  /// Arrastre en curso: que clase, desde donde y cuanto se movio.
  String? _arrastrada;
  Offset _inicioArrastre = Offset.zero;
  Offset _desplazamiento = Offset.zero;

  /// Hay una hoja de propiedades abierta (para no abrir dos).
  bool _hojaAbierta = false;

  @override
  void dispose() {
    _zoom.dispose();
    if (widget.cerrarAlSalir) _e.dispose();
    super.dispose();
  }

  // ------------------------------------------------------------- zoom

  void _encuadrar(Rect limites, {bool forzar = false}) {
    if ((_encuadrado && !forzar) || _pantalla.isEmpty) return;
    _encuadrado = true;
    final escala = escalaParaEncuadrar(_pantalla, limites);
    _zoom.value = Matrix4.identity()
      ..scaleByDouble(escala, escala, 1, 1)
      ..translateByDouble(-limites.left, -limites.top, 0, 1);
  }

  Rect _visible() {
    final m = _zoom.value;
    return visibleEnModelo(_pantalla, m.getMaxScaleOnAxis(), Offset(m.getTranslation().x, m.getTranslation().y));
  }

  // ------------------------------------------------------------- toques

  void _alTocar(EstadoModelo modelo, Offset punto) {
    final t = tocar(modelo, _herramienta, punto);
    setState(() => _herramienta = t.herramienta);
    switch (t.efecto) {
      case SeleccionarElemento(:final id):
        if (id == null) {
          _e.seleccionar(null);
        } else {
          _abrirPropiedades(id);
        }
      case CrearRelacion(:final origenId, :final destinoId, :final tipo):
        _e.crearRelacion(origenId, destinoId, tipo);
      case EsperarDestino():
      case Nada():
        break;
    }
  }

  Future<void> _abrirPropiedades(String id) async {
    if (_hojaAbierta) return;
    _e.seleccionar(id);
    _hojaAbierta = true;
    await mostrarHojaDeElemento(context, _e, id);
    _hojaAbierta = false;
    if (mounted && _e.seleccionada == id) _e.seleccionar(null);
  }

  void _alEmpezarArrastre(EstadoModelo modelo, Offset punto) {
    if (!_e.puedeEditar || _herramienta is! Seleccionar) return;
    final id = claseEn(modelo, punto);
    if (id == null) return;
    final l = modelo.layout[id];
    setState(() {
      _arrastrada = id;
      _inicioArrastre = Offset(l?.x ?? 0, l?.y ?? 0);
      _desplazamiento = Offset.zero;
    });
  }

  void _alSoltar() {
    final id = _arrastrada;
    if (id == null) return;
    if (esMovimiento(_desplazamiento)) {
      final destino = posicionArrastrada(_inicioArrastre, _desplazamiento);
      _e.moverClase(id, destino.dx, destino.dy);
    }
    setState(() => _arrastrada = null);
  }

  // ------------------------------------------------------------- acciones

  Future<void> _abrirPaleta() async {
    final elegido = await mostrarPaleta(context, puedeEditar: _e.puedeEditar, actual: _herramienta);
    if (!mounted || elegido == null) return;
    switch (elegido) {
      case ElegirHerramienta(:final herramienta):
        setState(() => _herramienta = herramienta);
      case ElegirElemento(:final tipo):
        final nombre = await pedirNombre(context, tituloParaCrear(tipo));
        final modelo = _e.modelo;
        if (nombre == null || modelo == null) return;
        final lugar = posicionNueva(modelo, _visible());
        _e.crearElemento(tipo, nombre, x: lugar.dx, y: lugar.dy);
    }
  }

  // ------------------------------------------------------------- dibujo

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _e,
      builder: (context, _) {
        final modelo = _e.modelo;
        return Scaffold(
          appBar: AppBar(
            title: Text(widget.titulo, overflow: TextOverflow.ellipsis),
            actions: [
              Padding(
                padding: const EdgeInsets.only(right: 12),
                child: Tooltip(
                  message: _e.conectado ? 'En directo' : 'Sin conexión',
                  child: Icon(Icons.circle, size: 12, color: _e.conectado ? Colors.green : Colors.red),
                ),
              ),
            ],
          ),
          drawer: _Menu(
            titulo: widget.titulo,
            puedeEditar: _e.puedeEditar,
            conectado: _e.conectado,
            alAbrirPaleta: () {
              Navigator.of(context).pop();
              _abrirPaleta();
            },
            alEncuadrar: () {
              Navigator.of(context).pop();
              final m = _e.modelo;
              if (m != null) _encuadrar(limitesDe(m), forzar: true);
            },
          ),
          body: Column(
            children: [
              if (!_e.conectado) const _Franja(texto: 'Sin conexión: no se puede editar hasta que vuelva', color: Colors.red),
              if (_e.conectado && !_e.puedeEditar)
                const _Franja(texto: 'Solo lectura: tu rol no permite editar este diagrama', color: Colors.blueGrey),
              if (_e.esperandoConfirmacion) const LinearProgressIndicator(minHeight: 2),
              if (_e.ultimoRechazo case final r?) _Rechazo(rechazo: r, alCerrar: _e.descartarRechazo),
              if (_herramienta case ArmarRelacion(:final tipo, :final origenId))
                _GuiaRelacion(
                  texto: origenId == null
                      ? 'Tocá la clase de origen de la ${nombreDeRelacion(tipo)}'
                      : 'Ahora tocá la clase de destino',
                  alCancelar: () => setState(() => _herramienta = const Seleccionar()),
                ),
              Expanded(child: _lienzo(context, modelo)),
            ],
          ),
          floatingActionButton: modelo == null
              ? null
              : FloatingActionButton.extended(
                  onPressed: _abrirPaleta,
                  icon: const Icon(Icons.add),
                  label: const Text('Paleta'),
                ),
        );
      },
    );
  }

  Widget _lienzo(BuildContext context, EstadoModelo? modelo) {
    if (modelo == null) return const Center(child: CircularProgressIndicator());
    final dibujado = _arrastrada == null
        ? modelo
        : conClaseEn(modelo, _arrastrada!, posicionArrastrada(_inicioArrastre, _desplazamiento));
    final limites = limitesDe(dibujado);
    final bloqueos = <String, Bloqueo>{
      for (final id in [...modelo.classes.map((c) => c.id), ...modelo.relationships.map((r) => r.id)])
        if (_e.bloqueadaPorOtro(id) case final quien?) id: Bloqueo(id, null, quien),
    };
    final origen = switch (_herramienta) {
      ArmarRelacion(:final origenId) => origenId,
      Seleccionar() => null,
    };
    return LayoutBuilder(
      builder: (context, caja) {
        _pantalla = caja.biggest;
        _encuadrar(limites);
        return Stack(
          children: [
            InteractiveViewer(
              transformationController: _zoom,
              constrained: false,
              minScale: 0.1,
              maxScale: 4,
              boundaryMargin: const EdgeInsets.all(2000),
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTapUp: (d) => _alTocar(modelo, d.localPosition),
                onLongPressStart: (d) => _alEmpezarArrastre(modelo, d.localPosition),
                onLongPressMoveUpdate: (d) {
                  // En coordenadas del lienzo, no de la pantalla: con zoom no son lo mismo.
                  if (_arrastrada != null) setState(() => _desplazamiento = d.localOffsetFromOrigin);
                },
                onLongPressEnd: (_) => _alSoltar(),
                child: SizedBox(
                  width: limites.right,
                  height: limites.bottom,
                  child: CustomPaint(
                    painter: DibujoClases(
                      estado: dibujado,
                      bloqueos: bloqueos,
                      seleccionadosPorOtros: const {},
                      colores: Theme.of(context).colorScheme,
                    ),
                    foregroundPainter: MarcasDelEditor(
                      estado: dibujado,
                      seleccionada: _e.seleccionada,
                      origenRelacion: origen,
                      color: Theme.of(context).colorScheme.primary,
                    ),
                  ),
                ),
              ),
            ),
            if (modelo.classes.isEmpty)
              const IgnorePointer(
                child: Center(
                  child: Padding(
                    padding: EdgeInsets.all(32),
                    child: Text('El diagrama está vacío. Tocá «Paleta» para crear la primera clase.',
                        textAlign: TextAlign.center),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

/// Lo que dibuja el editor encima del diagrama: lo seleccionado y el origen
/// de una relacion a medio armar.
class MarcasDelEditor extends CustomPainter {
  final EstadoModelo estado;
  final String? seleccionada;
  final String? origenRelacion;
  final Color color;

  MarcasDelEditor({required this.estado, required this.seleccionada, required this.origenRelacion, required this.color});

  @override
  void paint(Canvas canvas, Size size) {
    final trazo = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3
      ..color = color;
    for (final c in estado.classes) {
      final r = rectDe(c, estado.layout[c.id]).inflate(3);
      if (c.id == seleccionada) {
        canvas.drawRRect(RRect.fromRectAndRadius(r, const Radius.circular(8)), trazo);
      }
      if (c.id == origenRelacion) {
        canvas.drawRRect(
          RRect.fromRectAndRadius(r.inflate(3), const Radius.circular(10)),
          Paint()
            ..style = PaintingStyle.fill
            ..color = color.withValues(alpha: 0.15),
        );
        canvas.drawRRect(RRect.fromRectAndRadius(r.inflate(3), const Radius.circular(10)), trazo);
      }
    }
    // Una relacion seleccionada: su linea, mas gruesa.
    for (final rel in estado.relationships) {
      if (rel.id != seleccionada) continue;
      final a = estado.classes.where((c) => c.id == rel.sourceClassId).firstOrNull;
      final b = estado.classes.where((c) => c.id == rel.targetClassId).firstOrNull;
      if (a == null || b == null) continue;
      canvas.drawLine(
        rectDe(a, estado.layout[a.id]).center,
        rectDe(b, estado.layout[b.id]).center,
        Paint()
          ..strokeWidth = 6
          ..color = color.withValues(alpha: 0.35),
      );
    }
  }

  @override
  bool shouldRepaint(MarcasDelEditor old) =>
      old.estado != estado || old.seleccionada != seleccionada || old.origenRelacion != origenRelacion;
}

// ----------------------------------------------------------- piezas chicas

class _Menu extends StatelessWidget {
  final String titulo;
  final bool puedeEditar;
  final bool conectado;
  final VoidCallback alAbrirPaleta;
  final VoidCallback alEncuadrar;

  const _Menu({
    required this.titulo,
    required this.puedeEditar,
    required this.conectado,
    required this.alAbrirPaleta,
    required this.alEncuadrar,
  });

  @override
  Widget build(BuildContext context) {
    return Drawer(
      child: SafeArea(
        child: ListView(
          children: [
            ListTile(
              title: Text(titulo, style: Theme.of(context).textTheme.titleMedium),
              subtitle: Text(!conectado
                  ? 'Sin conexión'
                  : puedeEditar
                      ? 'Podés editar'
                      : 'Solo lectura'),
            ),
            const Divider(),
            const _Seccion('Dibujar'),
            ListTile(leading: const Icon(Icons.widgets_outlined), title: const Text('Paleta'), onTap: alAbrirPaleta),
            ListTile(leading: const Icon(Icons.fit_screen_outlined), title: const Text('Ver todo el diagrama'), onTap: alEncuadrar),
            const _Seccion('Proyecto'),
            ListTile(
              leading: const Icon(Icons.arrow_back),
              title: const Text('Volver a los diagramas'),
              onTap: () => Navigator.of(context)
                ..pop()
                ..maybePop(),
            ),
            const Padding(
              padding: EdgeInsets.fromLTRB(16, 16, 16, 8),
              child: Text(
                'Deshacer, Modo IA, importar, generar el backend y versiones se usan desde la web.',
                style: TextStyle(fontSize: 12),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Seccion extends StatelessWidget {
  final String texto;
  const _Seccion(this.texto);
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
        child: Text(texto.toUpperCase(),
            style: Theme.of(context).textTheme.labelSmall?.copyWith(letterSpacing: 1.1)),
      );
}

class _Franja extends StatelessWidget {
  final String texto;
  final Color color;
  const _Franja({required this.texto, required this.color});
  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        color: color.withValues(alpha: 0.12),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        child: Text(texto, style: TextStyle(color: color, fontSize: 12)),
      );
}

class _Rechazo extends StatelessWidget {
  final RechazoVisible rechazo;
  final VoidCallback alCerrar;
  const _Rechazo({required this.rechazo, required this.alCerrar});
  @override
  Widget build(BuildContext context) => MaterialBanner(
        content: Text(rechazo.mensaje),
        leading: const Icon(Icons.error_outline),
        actions: [TextButton(onPressed: alCerrar, child: const Text('Entendido'))],
      );
}

class _GuiaRelacion extends StatelessWidget {
  final String texto;
  final VoidCallback alCancelar;
  const _GuiaRelacion({required this.texto, required this.alCancelar});
  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        color: Theme.of(context).colorScheme.primaryContainer,
        padding: const EdgeInsets.only(left: 12),
        child: Row(
          children: [
            Expanded(child: Text(texto)),
            TextButton(onPressed: alCancelar, child: const Text('Cancelar')),
          ],
        ),
      );
}
