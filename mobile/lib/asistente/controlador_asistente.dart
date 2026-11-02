// Conversacion del asistente contra el backend generado (P2). Sin widgets:
// toda la logica vive aca para probarla con un cliente HTTP simulado.
//
// El motor de intenciones (domain/) es determinista y no guarda estado; el
// estado de la charla (que se pregunto, que falta) es de este controlador.
// Ninguna linea conoce un dominio: entidades, campos y rutas salen del
// manifiesto (EC-10).

import 'dart:async';

import 'package:flutter/foundation.dart';

import '../data/cliente_backend.dart';
import '../domain/app_manifest.dart';
import '../domain/intent.dart';
import '../domain/lexico.dart';

class Mensaje {
  final String texto;
  final bool deAsistente;

  /// Respuestas posibles a una pregunta del asistente (empates, referencias
  /// con varios candidatos). Vacio si no hay pregunta abierta.
  final List<Opcion> opciones;

  const Mensaje(this.texto, {required this.deAsistente, this.opciones = const []});
}

class ControladorAsistente extends ChangeNotifier {
  final ClienteBackend cliente;
  final AppManifest manifiesto;
  final MotorDeIntenciones _motor;

  /// Cuantas filas se muestran al listar; el resto se resume.
  static const int maxFilas = 20;

  final List<Mensaje> _mensajes = [];
  bool _ocupado = false;

  /// Pregunta de Incompleta abierta: el proximo texto la completa.
  Incompleta? _incompleta;

  /// Respuestas ya dadas al pedido de `_incompleta` (p. ej. la entidad elegida
  /// en las opciones): hay que volver a pasarlas al completarlo.
  Map<String, String> _aclaracionesPendientes = const {};

  /// Pregunta con opciones abierta (ultimo mensaje con `opciones`).
  List<Opcion> _opcionesVigentes = const [];
  Future<void> Function(Opcion)? _alElegir;

  ControladorAsistente(this.cliente, this.manifiesto, {DateTime Function()? reloj})
      : _motor = MotorDeIntenciones(manifiesto, reloj: reloj) {
    _decir('Conectado a ${manifiesto.app}. Decime qué necesitás: puedo crear y listar '
        '${_nombresCreables()}.');
  }

  List<Mensaje> get mensajes => List.unmodifiable(_mensajes);
  bool get ocupado => _ocupado;

  String _nombresCreables() {
    final nombres =
        manifiesto.entities.where((e) => !e.isAbstract).map((e) => e.label.toLowerCase()).toList();
    return enumerar(nombres, conjuncion: 'y');
  }

  void _decir(String texto, {List<Opcion> opciones = const []}) {
    _mensajes.add(Mensaje(texto, deAsistente: true, opciones: opciones));
    notifyListeners();
  }

  /// Lo que el usuario escribio.
  Future<void> enviar(String crudo) async {
    final texto = crudo.trim();
    if (texto.isEmpty || _ocupado) return;
    _mensajes.add(Mensaje(texto, deAsistente: false));
    _ocupado = true;
    notifyListeners();
    try {
      // Una respuesta escrita igual a una opcion abierta equivale a tocarla.
      final alElegir = _alElegir;
      final tocada = _opcionesVigentes
          .where((o) =>
              normalizar(o.etiqueta) == normalizar(texto) || normalizar(o.valor) == normalizar(texto))
          .toList();
      if (alElegir != null && tocada.length == 1) {
        await _elegir(tocada.single, alElegir);
        return;
      }
      _cerrarPregunta();

      var consulta = texto;
      var aclaraciones = const <String, String>{};
      final pendiente = _incompleta;
      if (pendiente != null) {
        final previas = _aclaracionesPendientes;
        _incompleta = null;
        _aclaracionesPendientes = const {};
        // Si el texto solo no es un pedido ("isbn 978, estado disponible"),
        // completa el anterior; si es un pedido nuevo (o "cancelar"), lo reemplaza.
        if (_motor.interpretar(texto) is NoEntendida) {
          consulta = _completar(pendiente, previas, texto);
          aclaraciones = previas;
        }
      }
      await _procesar(consulta, aclaraciones);
    } finally {
      _ocupado = false;
      notifyListeners();
    }
  }

  /// El pedido pendiente con la respuesta ya incorporada. Si solo faltaba un
  /// campo y la respuesta es un valor suelto, es el valor de ese campo: no se
  /// le pregunta al usuario lo que ya dijo el asistente.
  String _completar(Incompleta pendiente, Map<String, String> previas, String respuesta) {
    final simple = '${pendiente.texto}, $respuesta';
    if (pendiente.faltantes.length != 1) return simple;
    final sinCampo = _motor.interpretar(simple, aclaraciones: previas);
    if (sinCampo is Ambigua && sinCampo.clave.startsWith('campo:')) {
      return '${pendiente.texto}, ${pendiente.faltantes.single.nombreHablado} $respuesta';
    }
    return simple;
  }

  /// Toque sobre una de las opciones del ultimo mensaje.
  Future<void> elegir(Opcion opcion) async {
    final alElegir = _alElegir;
    if (alElegir == null || _ocupado) return;
    _mensajes.add(Mensaje(opcion.etiqueta, deAsistente: false));
    _ocupado = true;
    notifyListeners();
    try {
      await _elegir(opcion, alElegir);
    } finally {
      _ocupado = false;
      notifyListeners();
    }
  }

  Future<void> _elegir(Opcion o, Future<void> Function(Opcion) alElegir) {
    _cerrarPregunta();
    return alElegir(o);
  }

  void _cerrarPregunta() {
    _opcionesVigentes = const [];
    _alElegir = null;
  }

  void _preguntar(String pregunta, List<Opcion> opciones, Future<void> Function(Opcion) alElegir) {
    _opcionesVigentes = opciones;
    _alElegir = alElegir;
    _decir(pregunta, opciones: opciones);
  }

  // ------------------------------------------------------------ interpretar

  Future<void> _procesar(String texto, Map<String, String> aclaraciones) async {
    final i = _motor.interpretar(texto, aclaraciones: aclaraciones);
    switch (i) {
      case Resuelta r:
        await _ejecutar(r);
      case Incompleta x:
        _incompleta = x;
        _aclaracionesPendientes = aclaraciones;
        _decir('${x.pregunta} Decime los datos que faltan.');
      case Ambigua a:
        _preguntar(a.pregunta, a.opciones,
            (o) => _procesar(texto, {...aclaraciones, a.clave: o.valor}));
      case Rechazada x:
        _decir('No lo hago: ${x.mensaje}');
      case FueraDeAlcance x:
        _decir(x.motivo);
      case NoEntendida x:
        _decir(x.motivo);
    }
  }

  // --------------------------------------------------------------- ejecutar

  Future<void> _ejecutar(Resuelta r) async {
    try {
      if (r.accion == Accion.listar) {
        await _listar(r.entidad);
      } else {
        await _crear(r, Map<String, dynamic>.from(r.cuerpo), 0);
      }
    } on BackendException catch (e) {
      _decir(e.mensaje);
    } on TimeoutException {
      _decir('El backend no respondió a tiempo. Revisá que esté arriba y la red.');
    } on Exception catch (e) {
      _decir('No pude hablar con el backend: $e');
    }
  }

  Future<void> _listar(EntityDescriptor entidad) async {
    final filas = await cliente.listar(manifiesto, entidad);
    if (filas.isEmpty) {
      _decir('No hay ${entidad.label.toLowerCase()} cargados.');
      return;
    }
    final nombres = await _nombresDeReferencias(entidad);
    final lineas = filas.take(maxFilas).map((f) => '• ${_resumen(entidad, f, nombres)}');
    final resto = filas.length - maxFilas;
    _decir('${entidad.label} (${filas.length}):\n${lineas.join('\n')}'
        '${resto > 0 ? '\n… y $resto más.' : ''}');
  }

  Future<void> _crear(Resuelta r, Map<String, dynamic> cuerpo, int desde) async {
    for (var k = desde; k < r.referencias.length; k++) {
      final ref = r.referencias[k];
      final destino = _entidad(ref.entidadDestino);
      if (destino == null) {
        _decir('El manifiesto no tiene la entidad ${ref.entidadDestino}.');
        return;
      }
      final candidatos = _buscar(await cliente.listar(manifiesto, destino), ref.texto);
      if (candidatos.isEmpty) {
        _decir('No encontré ${destino.label.toLowerCase()} «${ref.texto}». No creé nada.');
        return;
      }
      if (candidatos.length > 1) {
        final opciones = candidatos
            .map((c) => Opcion('${c['id']}', _resumen(destino, c, const {})))
            .toList();
        _preguntar(
            'Hay ${candidatos.length} ${destino.label.toLowerCase()} que coinciden con «${ref.texto}». ¿Cuál?',
            opciones, (o) async {
          try {
            cuerpo[ref.campo.name] = o.valor;
            await _crear(r, cuerpo, k + 1);
          } on BackendException catch (e) {
            _decir(e.mensaje);
          } on TimeoutException {
            _decir('El backend no respondió a tiempo. Revisá que esté arriba y la red.');
          } on Exception catch (e) {
            _decir('No pude hablar con el backend: $e');
          }
        });
        return;
      }
      cuerpo[ref.campo.name] = '${candidatos.single['id']}';
    }
    final creado = await cliente.crear(manifiesto, r.entidad, cuerpo);
    final nombres = await _nombresDeReferencias(r.entidad);
    _decir('Listo, creé ${r.entidad.label.toLowerCase()}: '
        '${_resumen(r.entidad, creado.isEmpty ? cuerpo : creado, nombres)}.');
  }

  // ---------------------------------------------------------------- ayudas

  EntityDescriptor? _entidad(String nombre) {
    for (final e in manifiesto.entities) {
      if (e.name == nombre) return e;
    }
    return null;
  }

  /// Registros cuyo texto coincide con lo dicho: iguales si los hay; si no,
  /// los que contienen todas las palabras.
  List<Map<String, dynamic>> _buscar(List<Map<String, dynamic>> filas, String dicho) {
    final buscado = normalizar(dicho);
    if (buscado.isEmpty) return const [];
    final palabrasBuscadas = palabras(dicho);
    final iguales = <Map<String, dynamic>>[];
    final parciales = <Map<String, dynamic>>[];
    for (final f in filas) {
      final textos = f.entries
          .where((e) => e.key != 'id' && e.value is String)
          .map((e) => normalizar(e.value as String))
          .toList();
      if (textos.any((t) => t == buscado)) {
        iguales.add(f);
      } else if (textos.any((t) {
        final ps = palabras(t);
        return palabrasBuscadas.every(ps.contains);
      })) {
        parciales.add(f);
      }
    }
    return iguales.isNotEmpty ? iguales : parciales;
  }

  /// "campo: valor · campo: valor" con los primeros campos con dato. Las
  /// referencias se muestran por nombre (no por uuid) si se pudo resolver.
  String _resumen(
      EntityDescriptor e, Map<String, dynamic> fila, Map<String, Map<String, String>> nombres) {
    final partes = <String>[];
    for (final c in e.fields) {
      final v = fila[c.name];
      if (v == null || v is Map || v is List) continue;
      if (c.type == FieldType.reference) {
        final n = nombres[c.name]?['$v'];
        if (n != null) partes.add('${c.label}: $n');
      } else {
        partes.add('${c.label}: $v');
      }
      if (partes.length == 4) break;
    }
    return partes.isEmpty ? '${fila['id'] ?? e.label}' : partes.join(' · ');
  }

  /// Para cada campo referencia de la entidad: id -> primer texto del
  /// registro apuntado. Si la consulta falla, el campo simplemente no se muestra.
  Future<Map<String, Map<String, String>>> _nombresDeReferencias(EntityDescriptor e) async {
    final salida = <String, Map<String, String>>{};
    for (final c in e.fields.where((c) => c.type == FieldType.reference && c.target != null)) {
      final destino = _entidad(c.target!);
      if (destino == null) continue;
      try {
        final mapa = <String, String>{};
        for (final f in await cliente.listar(manifiesto, destino)) {
          final textos = f.entries
              .where((x) => x.key != 'id' && x.value is String && (x.value as String).isNotEmpty);
          mapa['${f['id']}'] = textos.isEmpty ? '${f['id']}' : textos.first.value as String;
        }
        salida[c.name] = mapa;
      } on Exception {
        // Sin nombres para este campo.
      }
    }
    return salida;
  }
}
