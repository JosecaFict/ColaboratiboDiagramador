import 'dart:async';

import 'package:flutter/foundation.dart';

import 'almacen_local.dart';
import 'aplicar_operacion.dart';
import 'cliente_herramienta.dart';
import 'conexion_en_vivo.dart';
import 'modelo.dart';
import 'sincronizador.dart';

/// Crea el canal en vivo. En produccion es [ConexionEnVivo]; las pruebas
/// inyectan uno que no abre sockets.
typedef FabricaConexion = ConexionEnVivo Function({
  required String url,
  required String token,
  required String diagramId,
  required AlJson alOperacion,
  required AlJson alBloqueo,
  required AlJson alPresencia,
  required void Function() alConectar,
  required void Function(String motivo) alCaer,
});

ConexionEnVivo _conexionReal({
  required String url,
  required String token,
  required String diagramId,
  required AlJson alOperacion,
  required AlJson alBloqueo,
  required AlJson alPresencia,
  required void Function() alConectar,
  required void Function(String motivo) alCaer,
}) =>
    ConexionEnVivo(
      url: url,
      token: token,
      diagramId: diagramId,
      alOperacion: alOperacion,
      alBloqueo: alBloqueo,
      alPresencia: alPresencia,
      alConectar: alConectar,
      alCaer: alCaer,
    );

enum EstadoVisor { cargando, enVivo, sinConexion, error }

/// Une REST + STOMP + Sincronizador + almacen local para UN diagrama.
/// Es solo lectura: no tiene ningun camino hacia `/app/...`.
class ControladorVisor extends ChangeNotifier {
  final ClienteHerramienta cliente;
  final AlmacenLocal almacen;
  final String diagramId;
  final FabricaConexion _fabrica;
  final DateTime Function() _ahora;

  Sincronizador? _sync;
  ConexionEnVivo? _conexion;
  EstadoVisor estadoVisor = EstadoVisor.cargando;
  String? mensaje;

  /// Cuando se obtuvo por ultima vez algo fresco del servidor.
  DateTime? ultimaActualizacion;
  bool _sincronizando = false;
  bool _cerrado = false;
  Timer? _reloj;

  ControladorVisor({
    required this.cliente,
    required this.almacen,
    required this.diagramId,
    FabricaConexion? fabricaConexion,
    DateTime Function()? ahora,
  })  : _fabrica = fabricaConexion ?? _conexionReal,
        _ahora = ahora ?? DateTime.now;

  EstadoModelo? get modelo => _sync?.estado;
  Map<String, Bloqueo> get bloqueos => _sync?.bloqueos ?? const {};
  List<Presencia> get presentes => _sync?.presentes(_ahora()) ?? const [];
  int get ultimoSeq => _sync?.ultimoSeq ?? 0;
  bool get sinConexion => estadoVisor == EstadoVisor.sinConexion;

  Future<void> iniciar() async {
    // 1. Lo guardado, para tener algo que mostrar desde el primer cuadro.
    final guardado = await almacen.leerSnapshot(diagramId);
    if (guardado != null) {
      _sync = Sincronizador(guardado.snapshot);
      ultimaActualizacion = guardado.cuando;
      notifyListeners();
    }
    // 2. Lo fresco. Sin sesion (se reabrio la app sin red) solo hay lo guardado.
    if (cliente.token == null) {
      estadoVisor = guardado != null ? EstadoVisor.sinConexion : EstadoVisor.error;
      mensaje = guardado != null ? 'Sin sesion: se muestra lo ultimo guardado.' : 'No hay nada guardado de este diagrama.';
      notifyListeners();
      return;
    }
    try {
      final s = await cliente.snapshot(diagramId);
      if (_cerrado) return;
      if (_sync == null) {
        _sync = Sincronizador(s);
      } else {
        _sync!.reiniciar(s);
      }
      ultimaActualizacion = _ahora();
      estadoVisor = EstadoVisor.enVivo;
      mensaje = null;
      await _guardar(s);
      _abrirCanal();
    } on HerramientaException catch (e) {
      if (_cerrado) return;
      if (e.sesionInvalida) {
        estadoVisor = EstadoVisor.error;
        mensaje = e.mensaje;
      } else if (_sync != null) {
        estadoVisor = EstadoVisor.sinConexion; // hay copia guardada: se muestra con aviso
        mensaje = e.mensaje;
      } else {
        estadoVisor = EstadoVisor.error;
        mensaje = e.mensaje;
      }
    }
    // El aviso "hace N minutos" se repinta solo.
    _reloj = Timer.periodic(const Duration(seconds: 15), (_) {
      if (!_cerrado) notifyListeners();
    });
    notifyListeners();
  }

  void _abrirCanal() {
    final token = cliente.token;
    if (token == null) return;
    _conexion = _fabrica(
      url: cliente.urlStomp,
      token: token,
      diagramId: diagramId,
      alOperacion: _alOperacion,
      alBloqueo: (m) {
        _sync?.recibirBloqueo(m);
        _avisar();
      },
      alPresencia: (m) {
        _sync?.recibirPresencia(m, _ahora());
        _avisar();
      },
      alConectar: () {
        // Reconexion: lo que paso mientras no habia canal se pide por REST.
        estadoVisor = EstadoVisor.enVivo;
        unawaited(_ponerseAlDia());
      },
      alCaer: (motivo) {
        if (_cerrado) return;
        estadoVisor = EstadoVisor.sinConexion;
        mensaje = motivo;
        _avisar();
      },
    )..conectar();
  }

  void _alOperacion(Map<String, dynamic> m) {
    final op = operacionDesdeJson(m);
    final sync = _sync;
    if (op == null || sync == null) return;
    final r = sync.recibir(op);
    if (r == ResultadoOperacion.hueco) unawaited(_ponerseAlDia());
    if (r == ResultadoOperacion.aplicada) ultimaActualizacion = _ahora();
    _avisar();
    if (r == ResultadoOperacion.aplicada) unawaited(_guardarEstadoActual());
  }

  /// `GET .../operations?sinceSeq=N` y sigue. Si el servidor ya no tiene el
  /// tramo, o algo no cuadra, se baja el snapshot entero.
  Future<void> _ponerseAlDia() async {
    final sync = _sync;
    if (sync == null || _sincronizando || _cerrado) return;
    _sincronizando = true;
    try {
      final ops = await cliente.operacionesDesde(diagramId, sync.ultimoSeq);
      sync.aplicarCatchUp(ops);
      if (sync.hayHueco) {
        final s = await cliente.snapshot(diagramId);
        sync.reiniciar(s);
        await _guardar(s);
      }
      ultimaActualizacion = _ahora();
      estadoVisor = EstadoVisor.enVivo;
      mensaje = null;
      await _guardarEstadoActual();
    } on HerramientaException catch (e) {
      estadoVisor = e.sesionInvalida ? EstadoVisor.error : EstadoVisor.sinConexion;
      mensaje = e.mensaje;
    } finally {
      _sincronizando = false;
      _avisar();
    }
  }

  SnapshotDiagrama? _ultimoSnapshot;

  Future<void> _guardar(SnapshotDiagrama s) async {
    _ultimoSnapshot = s;
    await almacen.guardarSnapshot(s, ultimaActualizacion ?? _ahora());
  }

  /// El snapshot guardado se mantiene al dia con las operaciones aplicadas: lo
  /// que se guarda es el estado ACTUAL, no el del momento de entrar.
  Future<void> _guardarEstadoActual() async {
    final s = _ultimoSnapshot;
    final sync = _sync;
    if (s == null || sync == null) return;
    final e = sync.estado;
    final actual = SnapshotDiagrama(crudo: {
      ...s.crudo,
      'currentSeq': sync.ultimoSeq,
      'classes': [for (final c in e.classes) c.aJson()],
      'relationships': [for (final r in e.relationships) r.datos],
      'layout': [for (final l in e.layout.values) l.aJson()],
    });
    _ultimoSnapshot = actual;
    await almacen.guardarSnapshot(actual, ultimaActualizacion ?? _ahora());
  }

  void _avisar() {
    if (!_cerrado) notifyListeners();
  }

  @override
  void dispose() {
    _cerrado = true;
    _reloj?.cancel();
    _conexion?.cerrar();
    super.dispose();
  }
}
