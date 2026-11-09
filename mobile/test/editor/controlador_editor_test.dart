import 'dart:async';
import 'dart:convert';

import 'package:colabmodel_app/editor/comando.dart';
import 'package:colabmodel_app/editor/conexion_editable.dart';
import 'package:colabmodel_app/editor/controlador_editor.dart';
import 'package:colabmodel_app/editor/contrato_editor.dart';
import 'package:colabmodel_app/visor/cliente_herramienta.dart';
import 'package:fake_async/fake_async.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

class CanalFalso extends ConexionEditable {
  CanalFalso(
      {required super.url,
      required super.token,
      required super.diagramId,
      required super.alOperacion,
      required super.alBloqueo,
      required super.alPresencia,
      required super.alRechazo,
      required super.alConectar,
      required super.alCaer});
  bool enRed = false;
  bool cerrado = false;
  bool aceptaEnvios = true;
  final comandos = <Comando>[];
  final leases = <Map<String, dynamic>>[];
  @override
  bool get activa => enRed && !cerrado;
  @override
  void conectar() {}
  void confirmarConexion() {
    enRed = true;
    alConectar();
  }

  void caer() {
    enRed = false;
    alCaer('Sin conexión');
  }

  @override
  bool enviarComando(Comando comando) {
    if (!aceptaEnvios) return false;
    comandos.add(comando);
    return true;
  }

  @override
  bool bloquear(String elementId, String userName) {
    leases.add({'elementId': elementId, 'userName': userName, 'held': true});
    return true;
  }

  @override
  bool desbloquear(String elementId, String userName) {
    leases.add({'elementId': elementId, 'userName': userName, 'held': false});
    return true;
  }

  @override
  void cerrar() {
    cerrado = true;
    enRed = false;
  }
}

Map<String, dynamic> snapshot([int seq = 10, String nombre = 'Libro']) => {
      'id': 'd1',
      'name': 'Principal',
      'currentSeq': seq,
      'classes': [
        {
          'id': 'c1',
          'diagramId': 'd1',
          'name': nombre,
          'attributes': [
            {'id': 'a1', 'name': 'titulo', 'dataType': 'string', 'ordinal': 0}
          ],
          'methods': [
            {'id': 'm1', 'name': 'prestar', 'returnType': 'void', 'ordinal': 0}
          ]
        },
        {
          'id': 'c2',
          'diagramId': 'd1',
          'name': 'Socio',
          'attributes': [],
          'methods': []
        },
      ],
      'relationships': [
        {
          'id': 'r1',
          'sourceClassId': 'c1',
          'targetClassId': 'c2',
          'kind': 'ASSOCIATION'
        }
      ],
      'layout': [
        {'elementId': 'c1', 'x': 10, 'y': 20, 'width': 170, 'height': 60}
      ],
    };
http.Response json(Object contenido, [int codigo = 200]) =>
    http.Response(jsonEncode(contenido), codigo,
        headers: {'content-type': 'application/json; charset=utf-8'});
Map<String, dynamic> renombrar(int seq, String nombre, {String? opId}) => {
      'seq': seq,
      'opId': opId ?? 'o$seq',
      'type': 'RENAME_CLASS',
      'payload': {'classId': 'c1', 'name': nombre},
    };
Future<void> bombear() async {
  for (var i = 0; i < 5; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

class Banco {
  late CanalFalso canal;
  late ControladorEditor editor;
  final pedidos = <Uri>[];
  Banco(
      {String? rol = 'EDITOR',
      String? token = 'JWT',
      Future<http.Response> Function(http.Request)? responder}) {
    final cliente =
        ClienteHerramienta('h', token: token, cliente: MockClient((r) async {
      pedidos.add(r.url);
      expect(r.method, 'GET');
      expect(r.headers['Authorization'], 'Bearer JWT');
      if (responder != null) return responder(r);
      return json(r.url.path.endsWith('/operations') ? [] : snapshot());
    }));
    editor = ControladorEditor(
        cliente: cliente,
        diagramId: 'd1',
        rol: rol,
        nombreUsuario: 'Diego',
        numeroSesion: 123,
        fabricaConexion: (
                {required url,
                required token,
                required diagramId,
                required alOperacion,
                required alBloqueo,
                required alPresencia,
                required alRechazo,
                required alConectar,
                required alCaer}) =>
            canal = CanalFalso(
                url: url,
                token: token,
                diagramId: diagramId,
                alOperacion: alOperacion,
                alBloqueo: alBloqueo,
                alPresencia: alPresencia,
                alRechazo: alRechazo,
                alConectar: alConectar,
                alCaer: alCaer));
  }
  Future<void> iniciar() async {
    await editor.iniciar();
    canal.confirmarConexion();
    await bombear();
  }

  void cerrar() {
    editor.dispose();
    editor.cliente.cerrar();
  }
}

void main() {
  test(
      'una sesión vencida durante catch-up corta el canal y no reintenta indefinidamente',
      () {
    fakeAsync((reloj) {
      var intentos = 0;
      final b = Banco(responder: (r) async {
        if (!r.url.path.endsWith('/operations')) return json(snapshot());
        intentos++;
        return json({}, 401);
      });
      b.editor.iniciar();
      reloj.flushMicrotasks();
      b.canal.confirmarConexion();
      reloj.flushMicrotasks();
      expect(b.editor.puedeEditar, isFalse);
      expect(b.canal.cerrado, isTrue);
      expect(b.editor.ultimoRechazo!.mensaje, contains('sesion'));
      reloj.elapse(const Duration(seconds: 30));
      reloj.flushMicrotasks();
      expect(intentos, 1);
      b.cerrar();
    });
  });
  test('snapshot no habilita edición antes del CONNECT y catch-up', () async {
    final pendiente = Completer<http.Response>();
    final b = Banco(
        responder: (r) async => r.url.path.endsWith('/operations')
            ? pendiente.future
            : json(snapshot()));
    addTearDown(b.cerrar);
    await b.editor.iniciar();
    expect(b.editor.modelo!.classes.first.name, 'Libro');
    expect(b.editor.conectado, isFalse);
    b.editor.renombrarClase('c1', 'Otro');
    expect(b.editor.ultimoRechazo!.codigo, 'LOCAL');
    expect(b.canal.comandos, isEmpty);
    b.canal.confirmarConexion();
    expect(b.editor.puedeEditar, isFalse);
    pendiente.complete(json([]));
    await bombear();
    expect(b.editor.puedeEditar, isTrue);
    expect(b.pedidos.last.query, 'sinceSeq=10');
  });

  for (final tipo in TipoDeElemento.values) {
    test('CREATE_CLASS de ${tipo.name} coincide con la paleta web', () async {
      final b = Banco();
      addTearDown(b.cerrar);
      await b.iniciar();
      final id = b.editor.crearElemento(tipo, '  Pedido  ');
      final c = b.canal.comandos.single;
      expect(
          id,
          matches(RegExp(
              r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')));
      expect(c.opId, isNot(id));
      expect(c.diagramId, 'd1');
      expect(c.baseSeq, 10);
      expect(c.origin, Origin.TEXT);
      expect(c.type, CommandType.CREATE_CLASS);
      expect(c.payload, {
        'classId': id,
        'name': 'Pedido',
        'stereotype': tipo == TipoDeElemento.enumeracion
            ? 'enumeration'
            : tipo == TipoDeElemento.valor
                ? 'embeddable'
                : null,
        if (tipo == TipoDeElemento.abstracta) 'isAbstract': true,
        'x': 600.0,
        'y': 120.0
      });
      expect(b.editor.modelo!.classes, hasLength(2));
      expect(b.editor.esperandoConfirmacion, isTrue);
      b.canal.alOperacion({...c.aJson(), 'seq': 11});
      expect(b.editor.modelo!.classes.last.name, 'Pedido');
      expect(b.editor.esperandoConfirmacion, isFalse);
    });
  }

  test('payload de cada edición usa las mismas claves y ordinales que la web',
      () async {
    final b = Banco();
    addTearDown(b.cerrar);
    await b.iniciar();
    final e = b.editor;
    void enviado(CommandType tipo, Map<String, dynamic> payload,
        [Origin origen = Origin.MOUSE]) {
      final c = b.canal.comandos.last;
      expect(c.type, tipo);
      expect(c.payload, payload);
      expect(c.origin, origen);
      expect(c.baseSeq, 10);
    }

    e.renombrarClase('c1', '  Libros  ');
    enviado(CommandType.RENAME_CLASS, {'classId': 'c1', 'name': 'Libros'},
        Origin.TEXT);
    e.borrarClase('c2');
    enviado(CommandType.DELETE_CLASS, {'classId': 'c2'});
    final a = e.agregarAtributo(
        'c1',
        const DatosAtributo(
            nombre: 'ISBN',
            tipoDato: 'string',
            pk: true,
            obligatorio: true,
            unico: true,
            visibilidad: 'PRIVATE'));
    enviado(
        CommandType.ADD_ATTRIBUTE,
        {
          'attributeId': a,
          'classId': 'c1',
          'name': 'ISBN',
          'dataType': 'string',
          'ordinal': 1,
          'pk': true,
          'required': true,
          'unique': true,
          'visibility': 'PRIVATE'
        },
        Origin.TEXT);
    e.actualizarAtributo(
        'a1',
        const DatosAtributo(
            nombre: 'tituloLargo',
            tipoDato: 'text',
            pk: false,
            obligatorio: false,
            unico: false,
            visibilidad: 'PUBLIC'));
    enviado(
        CommandType.UPDATE_ATTRIBUTE,
        {
          'attributeId': 'a1',
          'name': 'tituloLargo',
          'dataType': 'text',
          'pk': false,
          'required': false,
          'unique': false,
          'visibility': 'PUBLIC'
        },
        Origin.TEXT);
    e.actualizarAtributo('a1', const DatosAtributo(unico: true));
    enviado(
        CommandType.UPDATE_ATTRIBUTE, {'attributeId': 'a1', 'unique': true});
    e.quitarAtributo('a1');
    enviado(CommandType.REMOVE_ATTRIBUTE, {'attributeId': 'a1'});
    final m = e.agregarMetodo('c1', ' devolver ', retorno: 'boolean');
    enviado(
        CommandType.ADD_METHOD,
        {
          'methodId': m,
          'classId': 'c1',
          'name': 'devolver',
          'returnType': 'boolean',
          'visibility': 'PUBLIC',
          'params': [],
          'ordinal': 1
        },
        Origin.TEXT);
    e.quitarMetodo('m1');
    enviado(CommandType.REMOVE_METHOD, {'methodId': 'm1'});
    for (final tipo in TipoRelacion.values) {
      final r = e.crearRelacion('c1', 'c2', tipo);
      enviado(CommandType.CREATE_RELATION, {
        'relationId': r,
        'sourceClassId': 'c1',
        'targetClassId': 'c2',
        'kind': tipo.clave
      });
    }
    e.actualizarRelacion(
        'r1',
        const CambiosRelacion(
            tipo: TipoRelacion.composicion,
            nombre: 'tiene',
            multiplicidadOrigen: '1',
            multiplicidadDestino: '0..*',
            rolOrigen: 'libro',
            rolDestino: 'socios'));
    enviado(
        CommandType.UPDATE_RELATION,
        {
          'relationId': 'r1',
          'kind': 'COMPOSITION',
          'name': 'tiene',
          'sourceMultiplicity': '1',
          'targetMultiplicity': '0..*',
          'sourceRole': 'libro',
          'targetRole': 'socios'
        },
        Origin.TEXT);
    e.actualizarRelacion('r1', const CambiosRelacion(nombre: ''));
    enviado(CommandType.UPDATE_RELATION, {'relationId': 'r1', 'name': ''},
        Origin.TEXT);
    e.borrarRelacion('r1');
    enviado(CommandType.DELETE_RELATION, {'relationId': 'r1'});
    e.moverClase('c1', 55, 66);
    enviado(CommandType.MOVE_ELEMENT,
        {'elementId': 'c1', 'elementKind': 'CLASS', 'x': 55.0, 'y': 66.0});
    expect(e.modelo!.classes.first.name, 'Libro');
    expect(e.modelo!.classes.first.attributes, hasLength(1));
    expect(e.modelo!.classes.first.methods, hasLength(1));
    expect(e.modelo!.relationships.single.kind, 'ASSOCIATION');
    expect(e.modelo!.layout['c1']!.x, 10);
    expect(b.canal.comandos.map((c) => c.opId).toSet(),
        hasLength(b.canal.comandos.length));
  });

  test('usa el último seq aplicado y cada confirmación resuelve solo su envío',
      () async {
    final b = Banco();
    addTearDown(b.cerrar);
    await b.iniciar();
    b.editor.renombrarClase('c1', 'Nuevo');
    final primero = b.canal.comandos.last;
    b.editor.moverClase('c1', 40, 50);
    final segundo = b.canal.comandos.last;
    b.canal.alOperacion(renombrar(11, 'Nuevo', opId: primero.opId));
    expect(b.editor.esperandoConfirmacion, isTrue);
    b.canal.alOperacion({...segundo.aJson(), 'seq': 12});
    expect(b.editor.esperandoConfirmacion, isFalse);
    b.editor.renombrarClase('c1', 'Tercero');
    expect(b.canal.comandos.last.baseSeq, 12);
    b.canal.alOperacion(renombrar(11, 'Viejo'));
    expect(b.editor.modelo!.classes.first.name, 'Nuevo');
  });

  test(
      'lock tomado, renovado cada 10 s, cambiado y soltado; dispose corta el reloj',
      () {
    fakeAsync((reloj) {
      final b = Banco();
      b.editor.iniciar();
      reloj.flushMicrotasks();
      b.canal.confirmarConexion();
      reloj.flushMicrotasks();
      b.editor.seleccionar('c1');
      expect(b.canal.leases.single,
          {'elementId': 'c1', 'userName': 'Diego #123', 'held': true});
      reloj.elapse(const Duration(seconds: 9));
      expect(b.canal.leases, hasLength(1));
      reloj.elapse(const Duration(seconds: 1));
      expect(b.canal.leases, hasLength(2));
      b.editor.seleccionar('r1');
      expect(b.canal.leases[2]['held'], isFalse);
      expect(b.canal.leases[2]['elementId'], 'c1');
      expect(b.canal.leases.last['elementId'], 'r1');
      b.editor.seleccionar(null);
      expect(b.canal.leases.last['held'], isFalse);
      final cantidad = b.canal.leases.length;
      reloj.elapse(const Duration(seconds: 30));
      expect(b.canal.leases, hasLength(cantidad));
      b.editor.seleccionar('c2');
      b.cerrar();
      expect(b.canal.leases.last,
          {'elementId': 'c2', 'userName': 'Diego #123', 'held': false});
      final alCerrar = b.canal.leases.length;
      reloj.elapse(const Duration(seconds: 30));
      expect(b.canal.leases, hasLength(alCerrar));
      expect(b.canal.cerrado, isTrue);
    });
  });

  for (final rol in ['VIEWER', 'OTRO', null]) {
    test('rol $rol lee y selecciona pero ningún método escribe ni toma locks',
        () async {
      final b = Banco(rol: rol);
      addTearDown(b.cerrar);
      await b.iniciar();
      final e = b.editor;
      expect(e.conectado, isTrue);
      expect(e.puedeEditar, isFalse);
      e.seleccionar('c1');
      expect(e.seleccionada, 'c1');
      expect(e.crearElemento(TipoDeElemento.clase, 'Nueva'), isNull);
      e.renombrarClase('c1', 'Nuevo');
      e.borrarClase('c1');
      expect(e.agregarAtributo('c1', const DatosAtributo(nombre: 'nuevo')),
          isNull);
      e.actualizarAtributo('a1', const DatosAtributo(pk: true));
      e.quitarAtributo('a1');
      expect(e.agregarMetodo('c1', 'nuevo'), isNull);
      e.quitarMetodo('m1');
      expect(e.crearRelacion('c1', 'c2', TipoRelacion.asociacion), isNull);
      e.actualizarRelacion('r1', const CambiosRelacion(nombre: 'nuevo'));
      e.borrarRelacion('r1');
      e.moverClase('c1', 1, 2);
      e.seleccionar(null);
      expect(b.canal.comandos, isEmpty);
      expect(b.canal.leases, isEmpty);
      expect(e.ultimoRechazo!.codigo, 'LOCAL');
      expect(e.esperandoConfirmacion, isFalse);
    });
  }
  test(
      'OWNER puede escribir; pasar a Observador libera el lease y deja de renovarlo',
      () {
    fakeAsync((reloj) {
      final b = Banco(rol: 'OWNER');
      b.editor.iniciar();
      reloj.flushMicrotasks();
      b.canal.confirmarConexion();
      reloj.flushMicrotasks();
      expect(b.editor.puedeEditar, isTrue);
      b.editor.seleccionar('c1');
      b.editor.actualizarRol('VIEWER');
      expect(b.canal.leases.last['held'], isFalse);
      final cantidad = b.canal.leases.length;
      reloj.elapse(const Duration(seconds: 30));
      expect(b.canal.leases, hasLength(cantidad));
      b.editor.renombrarClase('c1', 'Otro');
      expect(b.canal.comandos, isEmpty);
      b.editor.actualizarRol('EDITOR');
      expect(b.canal.leases.last['held'], isTrue);
      b.cerrar();
    });
  });

  test('distingue otra sesión del mismo usuario y solo deja mover su clase',
      () async {
    final b = Banco();
    addTearDown(b.cerrar);
    await b.iniciar();
    b.canal.alBloqueo({
      'elementId': 'c1',
      'userId': 'yo',
      'userName': 'Diego #123',
      'held': true
    });
    expect(b.editor.bloqueadaPorOtro('c1'), isNull);
    b.canal.alBloqueo({
      'elementId': 'c1',
      'userId': 'yo',
      'userName': 'Diego #456',
      'held': true
    });
    expect(b.editor.bloqueadaPorOtro('c1'), 'Diego #456');
    b.editor.renombrarClase('c1', 'Otro');
    b.editor.actualizarAtributo('a1', const DatosAtributo(nombre: 'otro'));
    b.editor.quitarMetodo('m1');
    expect(b.canal.comandos, isEmpty);
    expect(b.editor.ultimoRechazo!.mensaje, 'La está editando Diego #456');
    b.editor.moverClase('c1', 77, 88);
    expect(b.canal.comandos.single.type, CommandType.MOVE_ELEMENT);
    b.canal.alBloqueo({'elementId': 'c1', 'held': false});
    expect(b.editor.bloqueadaPorOtro('c1'), isNull);
  });

  for (final motivo in RejectionReason.values) {
    test('rechazo ${motivo.name}: mensaje español, objetivo y envío resuelto',
        () async {
      final b = Banco();
      addTearDown(b.cerrar);
      await b.iniciar();
      b.editor.renombrarClase('c1', 'Nuevo');
      b.canal.alRechazo(RechazoComando(
          opId: b.canal.comandos.last.opId,
          reason: motivo,
          message: motivo == RejectionReason.LOCKED_BY_OTHER
              ? 'Ese elemento lo esta editando Ana #456'
              : 'detalle'));
      final r = b.editor.ultimoRechazo!;
      expect(r.codigo, motivo.name);
      expect(r.elementId, 'c1');
      expect(b.editor.esperandoConfirmacion, isFalse);
      expect(
          r.mensaje,
          switch (motivo) {
            RejectionReason.LOCKED_BY_OTHER => 'La está editando Ana #456',
            RejectionReason.STALE => 'Cambió mientras tanto: probá de nuevo',
            RejectionReason.INVALID => 'El cambio no es válido: detalle',
            RejectionReason.FORBIDDEN => 'Sin permiso para editar',
            RejectionReason.NOT_FOUND => 'El elemento ya no existe',
          });
      if (motivo == RejectionReason.FORBIDDEN)
        expect(b.editor.puedeEditar, isFalse);
      expect(b.editor.modelo!.classes.first.name, 'Libro');
      b.editor.descartarRechazo();
      expect(b.editor.ultimoRechazo, isNull);
    });
  }

  test('valida nombres, tipos, visibilidad y multiplicidades antes de enviar',
      () async {
    final b = Banco();
    addTearDown(b.cerrar);
    await b.iniciar();
    final e = b.editor;
    e.crearElemento(TipoDeElemento.clase, ' ');
    e.renombrarClase('c1', '');
    e.agregarAtributo('c1', const DatosAtributo(nombre: ' '));
    e.actualizarAtributo('a1', const DatosAtributo(tipoDato: ''));
    e.actualizarAtributo('a1', const DatosAtributo(visibilidad: 'OTRO'));
    e.agregarMetodo('c1', ' ');
    e.agregarMetodo('c1', 'm', retorno: '');
    e.actualizarRelacion(
        'r1', const CambiosRelacion(multiplicidadDestino: 'N'));
    e.actualizarRelacion(
        'r1', const CambiosRelacion(multiplicidadOrigen: '-1'));
    e.moverClase('c1', double.nan, 0);
    e.crearElemento(TipoDeElemento.clase, 'N', x: double.infinity);
    expect(b.canal.comandos, isEmpty);
    expect(e.ultimoRechazo!.codigo, 'LOCAL');
    for (final m in ['1', '0..1', '1..*', '0..*']) {
      e.actualizarRelacion('r1', CambiosRelacion(multiplicidadDestino: m));
    }
    expect(b.canal.comandos, hasLength(4));
  });

  test(
      'un hueco recupera desde el último seq y resuelve también el ACK en espera',
      () async {
    final respuesta = Completer<http.Response>();
    var lecturas = 0;
    final b = Banco(responder: (r) async {
      if (!r.url.path.endsWith('/operations')) return json(snapshot());
      return ++lecturas == 1 ? json([]) : respuesta.future;
    });
    addTearDown(b.cerrar);
    await b.iniciar();
    b.editor.renombrarClase('c1', 'Tres');
    final c = b.canal.comandos.last;
    b.canal.alOperacion(renombrar(13, 'Tres', opId: c.opId));
    expect(b.editor.modelo!.classes.first.name, 'Libro');
    expect(b.editor.puedeEditar, isFalse);
    expect(b.editor.esperandoConfirmacion, isTrue);
    respuesta.complete(json([renombrar(12, 'Dos'), renombrar(11, 'Uno')]));
    await bombear();
    expect(b.editor.ultimoSeq, 13);
    expect(b.editor.modelo!.classes.first.name, 'Tres');
    expect(b.editor.esperandoConfirmacion, isFalse);
    expect(b.editor.puedeEditar, isTrue);
    expect(b.pedidos.last.query, 'sinceSeq=10');
  });

  test(
      'al caer deja de enviar y al reconectar recupera aun sin una operación nueva',
      () async {
    var lecturas = 0;
    final b = Banco(responder: (r) async {
      if (!r.url.path.endsWith('/operations')) return json(snapshot());
      return json(++lecturas == 1 ? [] : [renombrar(11, 'Mientras no estaba')]);
    });
    addTearDown(b.cerrar);
    await b.iniciar();
    b.editor.seleccionar('c1');
    b.canal.caer();
    b.editor.renombrarClase('c1', 'No enviar');
    expect(b.canal.comandos, isEmpty);
    expect(b.editor.puedeEditar, isFalse);
    b.canal.confirmarConexion();
    await bombear();
    expect(b.editor.modelo!.classes.first.name, 'Mientras no estaba');
    expect(b.editor.puedeEditar, isTrue);
    expect(b.canal.leases.last['held'], isTrue);
  });

  test(
      'un snapshot de respaldo conserva operaciones en vivo que llegaron mientras se pedía',
      () async {
    var snapshots = 0;
    final reemplazo = Completer<http.Response>();
    final b = Banco(responder: (r) async {
      if (r.url.path.endsWith('/operations')) return json([]);
      return ++snapshots == 1 ? json(snapshot()) : reemplazo.future;
    });
    addTearDown(b.cerrar);
    await b.iniciar();
    b.canal.alOperacion(renombrar(15, 'Quince'));
    await bombear();
    b.canal.alOperacion(renombrar(16, 'Dieciséis'));
    reemplazo.complete(json(snapshot(14, 'Catorce')));
    await bombear();
    expect(b.editor.ultimoSeq, 16);
    expect(b.editor.modelo!.classes.first.name, 'Dieciséis');
    expect(b.editor.puedeEditar, isTrue);
  });

  test('borrado confirmado de la selección libera su lock', () async {
    final b = Banco();
    addTearDown(b.cerrar);
    await b.iniciar();
    b.editor.seleccionar('c1');
    b.canal.alOperacion({
      'seq': 11,
      'type': 'DELETE_CLASS',
      'payload': {'classId': 'c1'}
    });
    expect(b.editor.seleccionada, isNull);
    expect(b.canal.leases.last['held'], isFalse);
  });
  test('sin token y error REST dejan un rechazo visible y no abren el canal',
      () async {
    final sinSesion = Banco(token: null);
    addTearDown(sinSesion.cerrar);
    await sinSesion.editor.iniciar();
    expect(sinSesion.pedidos, isEmpty);
    expect(sinSesion.editor.ultimoRechazo!.mensaje, contains('sesión'));
    final sinAcceso = Banco(responder: (_) async => json({}, 403));
    addTearDown(sinAcceso.cerrar);
    await sinAcceso.editor.iniciar();
    expect(sinAcceso.editor.puedeEditar, isFalse);
    expect(sinAcceso.editor.ultimoRechazo, isNotNull);
  });
  test(
      'la fábrica encaja en el typedef de las pantallas sin adaptar argumentos',
      () {
    final EditorDeDiagrama Function(
        {required ClienteHerramienta cliente,
        required String diagramId,
        required String? rol,
        required String nombreUsuario}) fabrica = crearEditor;
    expect(fabrica, isNotNull);
  });

  test('los cambios de casillas y tipo de relación salen con origen MOUSE',
      () async {
    final b = Banco();
    addTearDown(b.cerrar);
    await b.iniciar();
    b.editor.actualizarAtributo('a1', const DatosAtributo(pk: true));
    expect(b.canal.comandos.last.origin, Origin.MOUSE);
    b.editor.actualizarRelacion(
        'r1', const CambiosRelacion(tipo: TipoRelacion.herencia));
    expect(b.canal.comandos.last.origin, Origin.MOUSE);
  });

  test('los literales de enumeración conservan dataType string como en la web',
      () async {
    final s = snapshot();
    (s['classes'] as List).first['stereotype'] = 'enumeration';
    final b = Banco(
        responder: (r) async =>
            json(r.url.path.endsWith('/operations') ? [] : s));
    addTearDown(b.cerrar);
    await b.iniciar();
    b.editor.agregarAtributo(
        'c1', const DatosAtributo(nombre: 'PRESTADO', tipoDato: 'int'));
    expect(b.canal.comandos.single.payload['dataType'], 'string');
  });

  test(
      'un fallo transitorio de catch-up reintenta sin editar con un seq atrasado',
      () {
    fakeAsync((reloj) {
      var intentos = 0;
      final b = Banco(responder: (r) async {
        if (!r.url.path.endsWith('/operations')) return json(snapshot());
        if (++intentos == 1) return json({}, 503);
        return json([renombrar(11, 'Recuperado')]);
      });
      b.editor.iniciar();
      reloj.flushMicrotasks();
      b.canal.confirmarConexion();
      reloj.flushMicrotasks();
      expect(b.editor.puedeEditar, isFalse);
      b.editor.moverClase('c1', 1, 2);
      expect(b.canal.comandos, isEmpty);
      reloj.elapse(const Duration(seconds: 3));
      reloj.flushMicrotasks();
      expect(intentos, 2);
      expect(b.editor.puedeEditar, isTrue);
      expect(b.editor.ultimoSeq, 11);
      expect(b.editor.modelo!.classes.first.name, 'Recuperado');
      b.cerrar();
      reloj.elapse(const Duration(seconds: 9));
      reloj.flushMicrotasks();
      expect(intentos, 2);
    });
  });

  test(
      'un envío que falla devuelve null y no deja un comando fantasma pendiente',
      () async {
    final b = Banco();
    addTearDown(b.cerrar);
    await b.iniciar();
    b.canal.aceptaEnvios = false;
    expect(b.editor.crearElemento(TipoDeElemento.clase, 'Nueva'), isNull);
    expect(b.editor.esperandoConfirmacion, isFalse);
    expect(b.editor.puedeEditar, isFalse);
    expect(b.editor.ultimoRechazo!.codigo, 'LOCAL');
    expect(b.editor.modelo!.classes, hasLength(2));
  });

  test(
      'un corte con escrituras pendientes informa incertidumbre y no las reenvía',
      () async {
    final b = Banco();
    addTearDown(b.cerrar);
    await b.iniciar();
    b.editor.renombrarClase('c1', 'Quizás');
    b.canal.caer();
    expect(b.editor.ultimoRechazo!.mensaje, contains('sin confirmar'));
    b.canal.confirmarConexion();
    await bombear();
    expect(b.canal.comandos, hasLength(1));
    expect(b.editor.esperandoConfirmacion, isFalse);
  });

  test('el ACK conocido no se presenta como incierto al recuperar un snapshot',
      () async {
    var snapshots = 0;
    final reemplazo = Completer<http.Response>();
    final b = Banco(responder: (r) async {
      if (r.url.path.endsWith('/operations')) return json([]);
      return ++snapshots == 1 ? json(snapshot()) : reemplazo.future;
    });
    addTearDown(b.cerrar);
    await b.iniciar();
    b.editor.renombrarClase('c1', 'Confirmado');
    final op = b.canal.comandos.last;
    b.canal.alOperacion(renombrar(15, 'Confirmado', opId: op.opId));
    await bombear();
    reemplazo.complete(json(snapshot(14, 'Catorce')));
    await bombear();
    expect(b.editor.ultimoSeq, 15);
    expect(b.editor.esperandoConfirmacion, isFalse);
    expect(b.editor.ultimoRechazo, isNull);
  });

  test('dispose durante catch-up corta el canal y descarta la respuesta tardía',
      () async {
    final pendiente = Completer<http.Response>();
    final b = Banco(
        responder: (r) async => r.url.path.endsWith('/operations')
            ? pendiente.future
            : json(snapshot()));
    await b.editor.iniciar();
    b.canal.confirmarConexion();
    b.editor.dispose();
    pendiente.complete(json([renombrar(11, 'Tardío')]));
    await bombear();
    expect(b.editor.modelo!.classes.first.name, 'Libro');
    expect(b.canal.cerrado, isTrue);
    expect(b.editor.puedeEditar, isFalse);
    b.editor.cliente.cerrar();
  });
  test('dispose durante carga o recuperación ignora respuestas tardías',
      () async {
    final pendiente = Completer<http.Response>();
    final b = Banco(responder: (_) => pendiente.future);
    final inicio = b.editor.iniciar();
    b.editor.dispose();
    pendiente.complete(json(snapshot()));
    await inicio;
    expect(b.editor.modelo, isNull);
    expect(b.editor.conectado, isFalse);
    b.editor.cliente.cerrar();
  });
}
