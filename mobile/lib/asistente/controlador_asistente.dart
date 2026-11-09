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

  /// Antes de crear, el asistente repite lo que va a crear y espera un «sí».
  /// Dictando, un nombre mal oído se corrige antes de que llegue al backend.
  /// Listar no pregunta: no cambia nada.
  final bool confirmarAntesDeCrear;

  /// Respuestas habladas o escritas que valen por las opciones «sí» y «no».
  static const _afirmaciones = {'si', 'dale', 'confirmo', 'confirmar', 'ok', 'okey', 'claro', 'crealo', 'hacelo', 'listo'};
  static const _negaciones = {'no', 'cancelar', 'cancela', 'cancelalo', 'mejor no', 'no gracias'};

  /// Cortan lo que este pendiente (una pregunta, un pedido a medias). No son
  /// un verbo de borrar: «cancelar» a secas no borra nada (T-19).
  static const _cancelaciones = {'cancelar', 'cancela', 'cancelalo', 'olvidalo', 'dejalo', 'nada', 'mejor no'};

  ControladorAsistente(this.cliente, this.manifiesto,
      {DateTime Function()? reloj, this.confirmarAntesDeCrear = true})
      : _motor = MotorDeIntenciones(manifiesto, reloj: reloj) {
    _decir('Conectado a ${manifiesto.app}. Decime qué necesitás: puedo crear, listar, ver, cambiar y borrar '
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
              normalizar(o.etiqueta) == normalizar(texto) ||
              normalizar(o.valor) == normalizar(texto) ||
              _equivale(o.valor, texto))
          .toList();
      if (alElegir != null && tocada.length == 1) {
        await _elegir(tocada.single, alElegir);
        return;
      }
      if (_cancelaciones.contains(normalizar(texto))) {
        final habiaAlgo = alElegir != null || _incompleta != null;
        _cerrarPregunta();
        _incompleta = null;
        _aclaracionesPendientes = const {};
        _decir(habiaAlgo ? 'Cancelado: no hice nada.' : 'No había nada pendiente para cancelar.');
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
    switch (r.accion) {
      case Accion.crear:
        if (!confirmarAntesDeCrear) {
          await _protegido(() => _crear(r, Map<String, dynamic>.from(r.cuerpo), 0));
          return;
        }
        _preguntar('Voy a crear ${r.entidad.label.toLowerCase()} con ${_lectura(r)}. ¿Lo creo?',
            [Opcion('si', 'Sí, crear'), Opcion('no', 'No')], (o) async {
          if (o.valor == 'si') {
            await _protegido(() => _crear(r, Map<String, dynamic>.from(r.cuerpo), 0));
          } else {
            _decir('No creé nada. Decime el pedido de nuevo si querés corregir algo.');
          }
        });
      case Accion.listar:
        await _protegido(() => _listar(r.entidad));
      case Accion.ver:
        await _protegido(() => _ver(r));
      case Accion.borrar:
        await _protegido(() => _elegirRegistro(r, (fila) => _confirmarBorrado(r.entidad, fila)));
      case Accion.actualizar:
        await _protegido(() => _elegirRegistro(r, (fila) => _cambiar(r, fila)));
    }
  }

  /// Los errores de red o del backend se dicen; no rompen la conversacion.
  Future<void> _protegido(Future<void> Function() accion) async {
    try {
      await accion();
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

  /// «mostrame el libro Rayuela»: el registro con todos sus datos, o los que
  /// coinciden si son varios.
  Future<void> _ver(Resuelta r) async {
    final e = r.entidad;
    final candidatos = _buscar(await cliente.listar(manifiesto, e), r.busqueda ?? '');
    if (candidatos.isEmpty) {
      _decir('No encontré ${e.label.toLowerCase()} «${r.busqueda}».');
      return;
    }
    final nombres = await _nombresDeReferencias(e);
    if (candidatos.length == 1) {
      _decir('${e.label}: ${_resumen(e, candidatos.single, nombres, maximo: 1000)}.');
      return;
    }
    final lineas = candidatos.take(maxFilas).map((f) => '• ${_resumen(e, f, nombres)}');
    _decir('${e.label} que coinciden con «${r.busqueda}» (${candidatos.length}):\n${lineas.join('\n')}');
  }

  /// El registro nombrado («el socio Ana»): si coinciden varios se pregunta
  /// cual; si ninguno, se dice. Recien con UNO se sigue.
  Future<void> _elegirRegistro(Resuelta r, Future<void> Function(Map<String, dynamic>) continuar) async {
    final e = r.entidad;
    final candidatos = _buscar(await cliente.listar(manifiesto, e), r.busqueda ?? '');
    if (candidatos.isEmpty) {
      _decir('No encontré ${e.label.toLowerCase()} «${r.busqueda}». No hice nada.');
      return;
    }
    if (candidatos.length == 1) {
      await continuar(candidatos.single);
      return;
    }
    final nombres = await _nombresDeReferencias(e);
    final visibles = candidatos.take(maxFilas).toList();
    _preguntar(
        'Hay ${candidatos.length} ${e.label.toLowerCase()} que coinciden con «${r.busqueda}». ¿Cuál?',
        [for (final c in visibles) Opcion('${c['id']}', _resumen(e, c, nombres))],
        (o) => _protegido(() => continuar(visibles.firstWhere((c) => '${c['id']}' == o.valor))));
  }

  /// Borrar SIEMPRE se confirma (M3): no se deshace y por voz un nombre se
  /// puede oir mal.
  Future<void> _confirmarBorrado(EntityDescriptor e, Map<String, dynamic> fila) async {
    final quien = _resumen(e, fila, await _nombresDeReferencias(e));
    _preguntar('¿Borro ${e.label.toLowerCase()} «$quien»? No se puede deshacer. Decí sí o no.',
        [Opcion('si', 'Sí, borrar'), Opcion('no', 'No')], (o) async {
      if (o.valor != 'si') {
        _decir('No borré nada.');
        return;
      }
      await _protegido(() async {
        await cliente.eliminar(manifiesto, e, '${fila['id']}');
        _decir('Listo, borré ${e.label.toLowerCase()} «$quien».');
      });
    });
  }

  /// PATCH con solo lo que cambia y la `version` leida: si otro escribio en el
  /// medio, el backend responde 409 y se dice en vez de pisarle el cambio.
  Future<void> _cambiar(Resuelta r, Map<String, dynamic> fila) async {
    final e = r.entidad;
    final cambios = Map<String, dynamic>.from(r.cuerpo);
    await _conReferencias(r.referencias, cambios, 0, () async {
      final nombres = await _nombresDeReferencias(e);
      final quien = _resumen(e, fila, nombres);
      Future<void> aplicar() => _protegido(() async {
            final enviado = {...cambios, if (fila['version'] != null) 'version': fila['version']};
            final nuevo = await cliente.actualizar(manifiesto, e, '${fila['id']}', enviado);
            final despues = nuevo.isEmpty ? {...fila, ...cambios} : nuevo;
            _decir('Listo, actualicé ${e.label.toLowerCase()}: '
                '${_resumen(e, despues, await _nombresDeReferencias(e))}.');
          });
      if (!confirmarAntesDeCrear) {
        await aplicar();
        return;
      }
      _preguntar(
          'Voy a cambiar en ${e.label.toLowerCase()} «$quien»: ${_lecturaDeCambios(e, cambios, nombres)}. '
          '¿Lo cambio?',
          [Opcion('si', 'Sí, cambiar'), Opcion('no', 'No')], (o) async {
        if (o.valor == 'si') {
          await aplicar();
        } else {
          _decir('No cambié nada.');
        }
      });
    }, sinHacer: 'No cambié nada.');
  }

  Future<void> _crear(Resuelta r, Map<String, dynamic> cuerpo, int desde) =>
      _conReferencias(r.referencias, cuerpo, desde, () async {
        final creado = await cliente.crear(manifiesto, r.entidad, cuerpo);
        final nombres = await _nombresDeReferencias(r.entidad);
        _decir('Listo, creé ${r.entidad.label.toLowerCase()}: '
            '${_resumen(r.entidad, creado.isEmpty ? cuerpo : creado, nombres)}.');
      }, sinHacer: 'No creé nada.');

  /// Resuelve las referencias dichas por nombre («al socio Ana») a su id, en
  /// orden; si una tiene varios candidatos se pregunta y se sigue desde ahi.
  Future<void> _conReferencias(List<ReferenciaPendiente> refs, Map<String, dynamic> cuerpo, int desde,
      Future<void> Function() alTerminar,
      {required String sinHacer}) async {
    for (var k = desde; k < refs.length; k++) {
      final ref = refs[k];
      final destino = _entidad(ref.entidadDestino);
      if (destino == null) {
        _decir('El manifiesto no tiene la entidad ${ref.entidadDestino}.');
        return;
      }
      final candidatos = _buscar(await cliente.listar(manifiesto, destino), ref.texto);
      if (candidatos.isEmpty) {
        _decir('No encontré ${destino.label.toLowerCase()} «${ref.texto}». $sinHacer');
        return;
      }
      if (candidatos.length > 1) {
        final opciones = candidatos
            .map((c) => Opcion('${c['id']}', _resumen(destino, c, const {})))
            .toList();
        _preguntar(
            'Hay ${candidatos.length} ${destino.label.toLowerCase()} que coinciden con «${ref.texto}». ¿Cuál?',
            opciones,
            (o) => _protegido(() async {
                  cuerpo[ref.campo.name] = o.valor;
                  await _conReferencias(refs, cuerpo, k + 1, alTerminar, sinHacer: sinHacer);
                }));
        return;
      }
      cuerpo[ref.campo.name] = '${candidatos.single['id']}';
    }
    await alTerminar();
  }

  // ---------------------------------------------------------------- ayudas

  /// «sí», «dale», «confirmo»... valen por la opcion 'si'; «no», «cancelar»... por 'no'.
  static bool _equivale(String valorDeOpcion, String dicho) {
    final d = normalizar(dicho);
    if (valorDeOpcion == 'si') return _afirmaciones.contains(d);
    if (valorDeOpcion == 'no') return _negaciones.contains(d);
    return false;
  }

  /// Lo que se va a cambiar, con las referencias ya resueltas por nombre.
  String _lecturaDeCambios(EntityDescriptor e, Map<String, dynamic> cambios,
      Map<String, Map<String, String>> nombres) {
    final partes = <String>[];
    for (final c in e.fields) {
      if (!cambios.containsKey(c.name)) continue;
      final v = cambios[c.name];
      final mostrado = c.type == FieldType.reference ? (nombres[c.name]?['$v'] ?? '$v') : '$v';
      partes.add('${c.label.toLowerCase()} → $mostrado');
    }
    return enumerar(partes, conjuncion: 'y');
  }

  /// Lo que se va a crear, leido como se dijo: «nombre Carla Rojas, socio «Ana»».
  /// Las referencias van con el texto dicho, porque todavia no se resolvieron.
  String _lectura(Resuelta r) {
    final partes = <String>[];
    for (final c in r.entidad.fields) {
      final v = r.cuerpo[c.name];
      if (v == null || v is Map || v is List) continue;
      partes.add('${c.label.toLowerCase()} $v');
    }
    for (final ref in r.referencias) {
      partes.add('${ref.campo.label.toLowerCase()} «${ref.texto}»');
    }
    return partes.isEmpty ? 'los datos por defecto' : enumerar(partes, conjuncion: 'y');
  }

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
      EntityDescriptor e, Map<String, dynamic> fila, Map<String, Map<String, String>> nombres,
      {int maximo = 4}) {
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
      if (partes.length == maximo) break;
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
