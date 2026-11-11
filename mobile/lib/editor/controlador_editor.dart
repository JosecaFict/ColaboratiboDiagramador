import 'dart:async';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:uuid/uuid.dart';

import '../visor/aplicar_operacion.dart';
import '../visor/cliente_herramienta.dart';
import '../visor/modelo.dart';
import '../visor/sincronizador.dart';
import 'comando.dart';
import 'conexion_editable.dart';
import 'contrato_editor.dart';

/// Compatible con FabricaDeEditor de T-15b; inicia la carga sin cambiar la UI.
EditorDeDiagrama crearEditor(
    {required ClienteHerramienta cliente,
    required String diagramId,
    required String? rol,
    required String nombreUsuario}) {
  final editor = ControladorEditor(
      cliente: cliente,
      diagramId: diagramId,
      rol: rol,
      nombreUsuario: nombreUsuario);
  unawaited(editor.iniciar());
  return editor;
}

typedef FabricaConexionEditable = ConexionEditable Function({
  required String url,
  required String token,
  required String diagramId,
  required void Function(Json) alOperacion,
  required void Function(Json) alBloqueo,
  required void Function(Json) alPresencia,
  required void Function(RechazoComando) alRechazo,
  required void Function() alConectar,
  required void Function(String) alCaer,
});

/// Solo modifica el modelo al recibir operaciones secuenciadas del servidor.
class ControladorEditor extends ChangeNotifier implements EditorDeDiagrama {
  final ClienteHerramienta cliente;
  final String diagramId;
  final String nombreDeSesion;
  final FabricaConexionEditable _fabrica;
  final Map<String, Comando> _pendientes = {};
  String? _rol;
  Sincronizador? _sync;
  ConexionEditable? _canal;
  Timer? _latido;
  Timer? _reintento;
  bool _cerrado = false;
  bool _iniciado = false;
  bool _enRed = false;
  bool _alDia = false;
  Future<void>? _recuperacion;
  int _generacion = 0;
  String? _seleccionada;
  RechazoVisible? _rechazo;

  ControladorEditor(
      {required this.cliente,
      required this.diagramId,
      required String? rol,
      required String nombreUsuario,
      FabricaConexionEditable? fabricaConexion,
      int? numeroSesion})
      : _rol = rol,
        nombreDeSesion =
            '$nombreUsuario #${numeroSesion ?? (100 + Random.secure().nextInt(900))}',
        _fabrica = fabricaConexion ?? ConexionEditable.new;

  @override
  EstadoModelo? get modelo => _sync?.estado;
  int get ultimoSeq => _sync?.ultimoSeq ?? 0;
  @override
  bool get conectado =>
      !_cerrado && _enRed && _alDia && (_canal?.activa ?? false);
  bool get _rolEditable => _rol == 'OWNER' || _rol == 'EDITOR';
  @override
  bool get puedeEditar => _rolEditable && conectado;
  @override
  String? get seleccionada => _seleccionada;
  @override
  bool get esperandoConfirmacion => _pendientes.isNotEmpty;
  @override
  RechazoVisible? get ultimoRechazo => _rechazo;

  @override
  String? bloqueadaPorOtro(String elementId) {
    final bloqueo = _sync?.bloqueos[elementId];
    if (bloqueo == null || bloqueo.userName == nombreDeSesion) return null;
    return bloqueo.userName ?? 'otra sesión';
  }

  /// Se espera explícitamente en pruebas. crearEditor la llama al construir.
  Future<void> iniciar() async {
    if (_iniciado || _cerrado) return;
    _iniciado = true;
    if (cliente.token == null) {
      _local('No hay sesión. Iniciá sesión de nuevo.');
      return;
    }
    try {
      final snapshot = await cliente.snapshot(diagramId);
      if (_cerrado) return;
      _sync = Sincronizador(snapshot);
      _canal = _fabrica(
        url: cliente.urlStomp,
        token: cliente.token!,
        diagramId: diagramId,
        alOperacion: _recibir,
        alBloqueo: (json) {
          if (_cerrado) return;
          _sync?.recibirBloqueo(json);
          _avisar();
        },
        alPresencia: (json) {
          if (_cerrado) return;
          _sync?.recibirPresencia(json, DateTime.now());
          _avisar();
        },
        alRechazo: _recibirRechazo,
        alConectar: () {
          if (_cerrado) return;
          _enRed = true;
          _alDia = false;
          _generacion++;
          unawaited(_recuperar());
          _avisar();
        },
        alCaer: _caer,
      );
      _canal!.conectar();
      _avisar();
    } catch (_) {
      if (!_cerrado)
        _local('No se pudo abrir el diagrama. Revisá la conexión y tu sesión.');
    }
  }

  void _caer(String motivo) {
    if (_cerrado) return;
    _enRed = false;
    _alDia = false;
    for (final id in _sync?.bloqueos.keys.toList() ?? <String>[]) {
      _sync!.recibirBloqueo({'elementId': id, 'held': false});
    }
    _generacion++;
    _latido?.cancel();
    _reintento?.cancel();
    // No se reenvían escrituras a ciegas: podrían estar aceptadas en el servidor.
    final inciertos = _pendientes.isNotEmpty;
    _pendientes.clear();
    _local(inciertos
        ? '$motivo Hay envíos sin confirmar; revisá el diagrama al reconectar.'
        : motivo);
  }

  void _recibir(Json json) {
    if (_cerrado) return;
    final op = operacionDesdeJson(json);
    final sync = _sync;
    if (op == null || sync == null) return;
    _recibidas[op.seq] = op;
    _confirmadas[op.opId] = op.seq;
    final resultado = sync.recibir(op);
    // Un ACK salteado no deja de estar pendiente hasta aplicarse en orden.
    _limpiarConfirmadas();
    if (resultado == ResultadoOperacion.hueco) {
      _alDia = false;
      _latido?.cancel();
      unawaited(_recuperar());
    }
    _revisarSeleccion();
    _avisar();
  }

  Future<void> _recuperar() {
    if (_cerrado || !_enRed) return Future.value();
    return _recuperacion ??= _hacerRecuperacion().whenComplete(() {
      _recuperacion = null;
      if (!_cerrado && _enRed && !_alDia) _programarReintento();
    });
  }

  Future<void> _hacerRecuperacion() async {
    final sync = _sync;
    if (sync == null) return;
    final generacion = _generacion;
    _reintento?.cancel();
    try {
      final ops = await cliente.operacionesDesde(diagramId, sync.ultimoSeq);
      if (_cerrado || !_enRed || generacion != _generacion) return;
      sync.aplicarCatchUp(ops);
      for (final op in ops) {
        _confirmadas[op.opId] = op.seq;
      }
      _limpiarConfirmadas();
      if (sync.hayHueco) {
        _cargandoSnapshot = true;
        final snapshot = await cliente.snapshot(diagramId);
        if (_cerrado || !_enRed || generacion != _generacion) return;
        // Conserva las operaciones recibidas mientras bajaba el snapshot.
        final nuevo = Sincronizador(snapshot);
        final recibidas = _recibidas.values.toList()
          ..sort((a, b) => a.seq.compareTo(b.seq));
        for (final op in recibidas) {
          nuevo.recibir(op);
        }
        _sync = nuevo;
        if (nuevo.hayHueco)
          throw const HerramientaException('Faltan operaciones.');
        _limpiarConfirmadas();
        if (_pendientes.isNotEmpty) {
          _pendientes.clear();
          _rechazo = const RechazoVisible(
              codigo: 'LOCAL',
              mensaje:
                  'Se recuperó el diagrama completo. Revisá los envíos que estaban pendientes.');
        }
      }
      _limpiarConfirmadas();
      _alDia = true;
      _revisarSeleccion();
      _retomarLock();
    } catch (error) {
      if (_cerrado || !_enRed || generacion != _generacion) return;
      _alDia = false;
      if (error is HerramientaException && error.sesionInvalida) {
        _enRed = false;
        _rol = null;
        _latido?.cancel();
        _canal?.cerrar();
        _local(error.mensaje);
      } else {
        _local('No se pudieron recuperar los cambios. Reintentando…');
      }
    } finally {
      _cargandoSnapshot = false;
      _recibidas.removeWhere((seq, _) => seq <= ultimoSeq);
      _avisar();
    }
  }

  final Map<int, OperacionAplicada> _recibidas = {};
  final Map<String, int> _confirmadas = {};
  bool _cargandoSnapshot = false;

  void _limpiarConfirmadas() {
    _confirmadas.removeWhere((id, seq) {
      if (seq > ultimoSeq) return false;
      _pendientes.remove(id);
      return true;
    });
    if (!_cargandoSnapshot)
      _recibidas.removeWhere((seq, _) => seq <= ultimoSeq);
  }

  void _programarReintento() {
    _reintento?.cancel();
    _reintento =
        Timer(const Duration(seconds: 3), () => unawaited(_recuperar()));
  }

  void _revisarSeleccion() {
    final id = _seleccionada;
    if (id != null && !_existe(id)) seleccionar(null);
  }

  bool _existe(String id) =>
      (modelo?.classes.any((c) => c.id == id) ?? false) ||
      (modelo?.relationships.any((r) => r.id == id) ?? false);

  @override
  void seleccionar(String? elementId) {
    if (_cerrado) return;
    if (elementId != null && !_existe(elementId)) {
      _local('Ese elemento ya no existe.', elementId);
      return;
    }
    final anterior = _seleccionada;
    if (anterior != null &&
        anterior != elementId &&
        _rolEditable &&
        (_canal?.activa ?? false)) {
      _canal?.desbloquear(anterior, nombreDeSesion);
    }
    _latido?.cancel();
    _seleccionada = elementId;
    _retomarLock();
    _avisar();
  }

  void _retomarLock() {
    _latido?.cancel();
    final id = _seleccionada;
    if (id == null || !puedeEditar) return;
    _canal!.bloquear(id, nombreDeSesion);
    _latido = Timer.periodic(const Duration(seconds: 10), (_) {
      if (puedeEditar && _seleccionada == id)
        _canal!.bloquear(id, nombreDeSesion);
    });
  }

  /// Para quien revalide el rol del proyecto mientras el editor está abierto.
  void actualizarRol(String? rol) {
    if (_cerrado || rol == _rol) return;
    final id = _seleccionada;
    if (_rolEditable && id != null && (_canal?.activa ?? false)) {
      _canal!.desbloquear(id, nombreDeSesion);
    }
    _rol = rol;
    _retomarLock();
    _avisar();
  }

  void _recibirRechazo(RechazoComando rechazo) {
    if (_cerrado) return;
    final comando = _pendientes.remove(rechazo.opId);
    final id = comando == null ? null : _objetivo(comando.payload);
    final duenio = id == null ? null : bloqueadaPorOtro(id);
    final nombre = duenio ??
        RegExp(r'editando\s+(.+)$').firstMatch(rechazo.message)?.group(1) ??
        'otra persona';
    final mensaje = switch (rechazo.reason) {
      RejectionReason.LOCKED_BY_OTHER => 'La está editando $nombre',
      RejectionReason.STALE => 'Cambió mientras tanto: probá de nuevo',
      RejectionReason.INVALID => 'El cambio no es válido: ${rechazo.message}',
      RejectionReason.FORBIDDEN => 'Sin permiso para editar',
      RejectionReason.NOT_FOUND => 'El elemento ya no existe',
    };
    if (rechazo.reason == RejectionReason.FORBIDDEN) actualizarRol(null);
    _rechazo = RechazoVisible(
        codigo: rechazo.reason.name, mensaje: mensaje, elementId: id);
    _avisar();
  }

  String? _objetivo(Json payload) {
    final classId = payload['classId'] as String?;
    if (classId != null) return classId;
    final relationId = payload['relationId'] as String?;
    if (relationId != null) return relationId;
    for (final c in modelo?.classes ?? <UmlClass>[]) {
      if (c.attributes.any((a) => a.id == payload['attributeId']) ||
          c.methods.any((m) => m.id == payload['methodId'])) return c.id;
    }
    return payload['elementId'] as String?;
  }

  bool _enviar(CommandType type, Json payload,
      {Origin origin = Origin.MOUSE, bool semantica = true}) {
    if (_cerrado) return false;
    if (!puedeEditar) {
      _local(_rolEditable
          ? 'Sin conexión: esperá a que se recupere el diagrama.'
          : 'Sin permiso para editar');
      return false;
    }
    final id = _objetivo(payload);
    final otro = id == null ? null : bloqueadaPorOtro(id);
    if (semantica && otro != null) {
      _rechazo = RechazoVisible(
          codigo: 'LOCKED_BY_OTHER',
          mensaje: 'La está editando $otro',
          elementId: id);
      _avisar();
      return false;
    }
    final comando = Comando(
        opId: const Uuid().v4(),
        diagramId: diagramId,
        baseSeq: ultimoSeq,
        type: type,
        origin: origin,
        payload: payload);
    _pendientes[comando.opId] = comando;
    if (!_canal!.enviarComando(comando)) {
      _pendientes.remove(comando.opId);
      _caer('No se pudo enviar el cambio.');
      return false;
    }
    _avisar();
    return true;
  }

  bool _nombre(String? nombre) {
    if (nombre != null && nombre.trim().isNotEmpty) return true;
    _local('El nombre no puede estar vacío.');
    return false;
  }

  UmlClass? _clase(String id) {
    for (final c in modelo?.classes ?? <UmlClass>[]) {
      if (c.id == id) return c;
    }
    _local('La clase ya no existe.', id);
    return null;
  }

  void _local(String mensaje, [String? id]) {
    if (_cerrado) return;
    _rechazo = RechazoVisible(codigo: 'LOCAL', mensaje: mensaje, elementId: id);
    _avisar();
  }

  void _avisar() {
    if (!_cerrado) notifyListeners();
  }

  @override
  void descartarRechazo() {
    if (_cerrado) return;
    _rechazo = null;
    _avisar();
  }

  @override
  String? crearElemento(TipoDeElemento tipo, String nombre,
      {double? x, double? y}) {
    if (!_nombre(nombre)) return null;
    if ((x != null && !x.isFinite) || (y != null && !y.isFinite)) {
      _local('La posición no es válida.');
      return null;
    }
    final id = const Uuid().v4();
    final n = modelo?.classes.length ?? 0;
    final ok = _enviar(
        CommandType.CREATE_CLASS,
        {
          'classId': id,
          'name': nombre.trim(),
          'stereotype': switch (tipo) {
            TipoDeElemento.interfaz => 'interface',
            TipoDeElemento.enumeracion => 'enumeration',
            TipoDeElemento.valor => 'embeddable',
            _ => null
          },
          if (tipo == TipoDeElemento.abstracta) 'isAbstract': true,
          'x': x ?? 160 + (n % 4) * 220.0,
          'y': y ?? 120 + (n ~/ 4) * 180.0,
        },
        origin: Origin.TEXT,
        semantica: false);
    return ok ? id : null;
  }

  @override
  void renombrarClase(String classId, String nombre) {
    if (_nombre(nombre))
      _enviar(
          CommandType.RENAME_CLASS, {'classId': classId, 'name': nombre.trim()},
          origin: Origin.TEXT);
  }

  @override
  void borrarClase(String classId) =>
      _enviar(CommandType.DELETE_CLASS, {'classId': classId});

  Json _datosAtributo(DatosAtributo datos) => {
        if (datos.nombre != null) 'name': datos.nombre!.trim(),
        if (datos.tipoDato != null) 'dataType': datos.tipoDato!.trim(),
        if (datos.pk != null) 'pk': datos.pk,
        if (datos.obligatorio != null) 'required': datos.obligatorio,
        if (datos.unico != null) 'unique': datos.unico,
        if (datos.visibilidad != null) 'visibility': datos.visibilidad,
      };
  bool _validarAtributo(DatosAtributo datos) {
    if (datos.nombre != null && !_nombre(datos.nombre)) return false;
    if (datos.tipoDato != null && datos.tipoDato!.trim().isEmpty) {
      _local('El tipo no puede estar vacío.');
      return false;
    }
    if (datos.visibilidad != null &&
        !['PUBLIC', 'PRIVATE', 'PROTECTED', 'PACKAGE']
            .contains(datos.visibilidad)) {
      _local('La visibilidad no es válida.');
      return false;
    }
    return true;
  }

  @override
  String? agregarAtributo(String classId, DatosAtributo datos) {
    if (!_nombre(datos.nombre) || !_validarAtributo(datos)) return null;
    final clase = _clase(classId);
    if (clase == null) return null;
    final id = const Uuid().v4();
    final ok = _enviar(
        CommandType.ADD_ATTRIBUTE,
        {
          'attributeId': id,
          'classId': classId,
          'dataType': 'string',
          'ordinal': clase.attributes.length,
          ..._datosAtributo(datos),
          if (clase.stereotype?.toLowerCase() == 'enumeration')
            'dataType': 'string',
        },
        origin: Origin.TEXT);
    return ok ? id : null;
  }

  @override
  void actualizarAtributo(String attributeId, DatosAtributo cambios) {
    if (_validarAtributo(cambios))
      _enviar(CommandType.UPDATE_ATTRIBUTE,
          {'attributeId': attributeId, ..._datosAtributo(cambios)},
          origin: cambios.nombre != null ? Origin.TEXT : Origin.MOUSE);
  }

  @override
  void quitarAtributo(String attributeId) =>
      _enviar(CommandType.REMOVE_ATTRIBUTE, {'attributeId': attributeId});
  @override
  String? agregarMetodo(String classId, String nombre,
      {String retorno = 'void'}) {
    if (!_nombre(nombre)) return null;
    final clase = _clase(classId);
    if (clase == null) return null;
    if (retorno.trim().isEmpty) {
      _local('El tipo de retorno no puede estar vacío.');
      return null;
    }
    final id = const Uuid().v4();
    final ok = _enviar(
        CommandType.ADD_METHOD,
        {
          'methodId': id,
          'classId': classId,
          'name': nombre.trim(),
          'returnType': retorno.trim(),
          'visibility': 'PUBLIC',
          'params': <Json>[],
          'ordinal': clase.methods.length,
        },
        origin: Origin.TEXT);
    return ok ? id : null;
  }

  @override
  void quitarMetodo(String methodId) =>
      _enviar(CommandType.REMOVE_METHOD, {'methodId': methodId});
  @override
  String? crearRelacion(String origenId, String destinoId, TipoRelacion tipo) {
    if (_clase(origenId) == null || _clase(destinoId) == null) return null;
    final id = const Uuid().v4();
    final ok = _enviar(
        CommandType.CREATE_RELATION,
        {
          'relationId': id,
          'sourceClassId': origenId,
          'targetClassId': destinoId,
          'kind': tipo.clave
        },
        semantica: false);
    return ok ? id : null;
  }

  @override
  void actualizarRelacion(String relationId, CambiosRelacion cambios) {
    for (final multiplicidad in [
      cambios.multiplicidadOrigen,
      cambios.multiplicidadDestino
    ]) {
      if (multiplicidad != null &&
          !RegExp(r'^\d+(\.\.(\d+|\*))?$').hasMatch(multiplicidad.trim())) {
        _local('La multiplicidad debe tener forma 1, 0..1, 1..* o 0..*.',
            relationId);
        return;
      }
    }
    _enviar(
        CommandType.UPDATE_RELATION,
        {
          'relationId': relationId,
          if (cambios.tipo != null) 'kind': cambios.tipo!.clave,
          if (cambios.nombre != null) 'name': cambios.nombre!.trim(),
          if (cambios.multiplicidadOrigen != null)
            'sourceMultiplicity': cambios.multiplicidadOrigen!.trim(),
          if (cambios.multiplicidadDestino != null)
            'targetMultiplicity': cambios.multiplicidadDestino!.trim(),
          if (cambios.rolOrigen != null)
            'sourceRole': cambios.rolOrigen!.trim(),
          if (cambios.rolDestino != null)
            'targetRole': cambios.rolDestino!.trim(),
        },
        origin: cambios.nombre != null ||
                cambios.rolOrigen != null ||
                cambios.rolDestino != null ||
                cambios.multiplicidadOrigen != null ||
                cambios.multiplicidadDestino != null
            ? Origin.TEXT
            : Origin.MOUSE);
  }

  @override
  void borrarRelacion(String relationId) =>
      _enviar(CommandType.DELETE_RELATION, {'relationId': relationId});
  @override
  void moverClase(String classId, double x, double y) {
    if (!x.isFinite || !y.isFinite) {
      _local('La posición no es válida.');
      return;
    }
    _enviar(CommandType.MOVE_ELEMENT,
        {'elementId': classId, 'elementKind': 'CLASS', 'x': x, 'y': y},
        semantica: false);
  }

  @override
  void dispose() {
    if (_cerrado) return;
    final id = _seleccionada;
    if (id != null && _rolEditable && (_canal?.activa ?? false)) {
      _canal!.desbloquear(id, nombreDeSesion);
    }
    _seleccionada = null;
    _cerrado = true;
    _latido?.cancel();
    _reintento?.cancel();
    _pendientes.clear();
    _canal?.cerrar();
    super.dispose();
  }
}
