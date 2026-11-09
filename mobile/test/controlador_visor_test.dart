import 'dart:convert';

import 'package:colabmodel_app/visor/almacen_local.dart';
import 'package:colabmodel_app/visor/cliente_herramienta.dart';
import 'package:colabmodel_app/visor/conexion_en_vivo.dart';
import 'package:colabmodel_app/visor/controlador_visor.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Canal que no abre sockets: deja que la prueba "reciba" frames a mano.
class CanalFalso extends ConexionEnVivo {
  bool conectado = false;
  bool cerrado = false;
  CanalFalso({
    required super.url,
    required super.token,
    required super.diagramId,
    required super.alOperacion,
    required super.alBloqueo,
    required super.alPresencia,
    required super.alConectar,
    required super.alCaer,
  });

  @override
  void conectar() => conectado = true;

  @override
  void cerrar() => cerrado = true;
}

http.Response json(Object c, [int estado = 200]) =>
    http.Response(jsonEncode(c), estado, headers: {'content-type': 'application/json; charset=utf-8'});

Map<String, dynamic> snapshot(int seq, String nombre) => {
      'id': 'd1',
      'name': 'Principal',
      'currentSeq': seq,
      'defaultView': 'CLASS',
      'classes': [
        {
          'id': 'libro',
          'diagramId': 'd1',
          'name': nombre,
          'isAbstract': false,
          'weak': false,
          'erKind': 'STRONG',
          'inheritanceStrategy': 'JOINED',
          'attributes': [],
          'methods': [],
        },
      ],
      'relationships': [],
      'layout': [
        {'elementId': 'libro', 'x': 0, 'y': 0, 'width': 170, 'height': 60},
      ],
    };

void main() {
  late CanalFalso canal;
  late List<String> pedidos;
  var ahora = DateTime(2026, 11, 1, 9, 30);

  ControladorVisor armar(http.Client http, {String? token = 'JWT'}) {
    SharedPreferences.setMockInitialValues({});
    return ControladorVisor(
      cliente: ClienteHerramienta('h', cliente: http, token: token),
      almacen: AlmacenLocal(),
      diagramId: 'd1',
      ahora: () => ahora,
      fabricaConexion: ({
        required url,
        required token,
        required diagramId,
        required alOperacion,
        required alBloqueo,
        required alPresencia,
        required alConectar,
        required alCaer,
      }) {
        expect(url, 'http://h/ws');
        expect(token, 'JWT');
        return canal = CanalFalso(
          url: url,
          token: token,
          diagramId: diagramId,
          alOperacion: alOperacion,
          alBloqueo: alBloqueo,
          alPresencia: alPresencia,
          alConectar: alConectar,
          alCaer: alCaer,
        );
      },
    );
  }

  Map<String, dynamic> renombrar(int seq, String nombre) =>
      {'opId': 'o$seq', 'seq': seq, 'type': 'RENAME_CLASS', 'origin': 'MOUSE', 'payload': {'classId': 'libro', 'name': nombre}};

  setUp(() {
    pedidos = [];
    ahora = DateTime(2026, 11, 1, 9, 30);
  });

  test('entra: baja el snapshot, abre el canal y aplica lo que llega en vivo', () async {
    final c = armar(MockClient((r) async {
      pedidos.add('${r.method} ${r.url.path}');
      if (r.url.path == '/api/v1/diagrams/d1') return json(snapshot(10, 'Libro'));
      if (r.url.path == '/api/v1/diagrams/d1/operations') return json([]);
      return http.Response('?', 404);
    }));
    await c.iniciar();
    expect(c.estadoVisor, EstadoVisor.enVivo);
    expect(c.modelo!.classes.single.name, 'Libro');
    expect(canal.conectado, isTrue);

    canal.alConectar(); // el servidor acepto el CONNECT y las 3 suscripciones
    await Future<void>.delayed(Duration.zero);
    expect(pedidos, contains('GET /api/v1/diagrams/d1/operations'));

    canal.alOperacion(renombrar(11, 'Libros'));
    expect(c.modelo!.classes.single.name, 'Libros');
    expect(c.ultimoSeq, 11);

    canal.alBloqueo({'elementId': 'libro', 'userId': 'u', 'userName': 'Ana', 'held': true});
    expect(c.bloqueos['libro']!.userName, 'Ana');
    canal.alPresencia({'userId': 'u', 'userName': 'Ana', 'x': 1, 'y': 1, 'selectedElementId': 'libro'});
    expect(c.presentes.map((p) => p.userName), ['Ana']);

    // Solo lee: ni un POST/PUT/DELETE, y el canal no tiene como enviar.
    expect(pedidos.every((p) => p.startsWith('GET ')), isTrue);
    c.dispose();
    expect(canal.cerrado, isTrue);
  });

  test('un hueco de seq pide ?sinceSeq=N y se pone al dia', () async {
    final c = armar(MockClient((r) async {
      if (r.url.path == '/api/v1/diagrams/d1') return json(snapshot(10, 'Libro'));
      if (r.url.path == '/api/v1/diagrams/d1/operations') {
        pedidos.add(r.url.query);
        return json([
          // forma REST: opType + payload como texto
          {'seq': 11, 'opType': 'RENAME_CLASS', 'payload': '{"classId":"libro","name":"Uno"}'},
          {'seq': 12, 'opType': 'RENAME_CLASS', 'payload': '{"classId":"libro","name":"Dos"}'},
        ]);
      }
      return http.Response('?', 404);
    }));
    await c.iniciar();

    canal.alOperacion(renombrar(13, 'Tres')); // faltan la 11 y la 12
    expect(c.modelo!.classes.single.name, 'Libro'); // todavia no se aplica
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);

    expect(pedidos, ['sinceSeq=10']);
    expect(c.ultimoSeq, 13);
    expect(c.modelo!.classes.single.name, 'Tres');
    c.dispose();
  });

  test('si el catch-up no alcanza, baja el snapshot entero', () async {
    var snapshots = 0;
    final c = armar(MockClient((r) async {
      if (r.url.path == '/api/v1/diagrams/d1') {
        snapshots++;
        return json(snapshot(snapshots == 1 ? 10 : 20, snapshots == 1 ? 'Libro' : 'Reemplazado'));
      }
      if (r.url.path == '/api/v1/diagrams/d1/operations') return json([]); // el servidor ya no tiene el tramo
      return http.Response('?', 404);
    }));
    await c.iniciar();
    canal.alOperacion(renombrar(15, 'Quince'));
    for (var i = 0; i < 5; i++) {
      await Future<void>.delayed(Duration.zero);
    }
    expect(snapshots, 2);
    expect(c.ultimoSeq, 20);
    expect(c.modelo!.classes.single.name, 'Reemplazado');
    c.dispose();
  });

  test('sin red: muestra el ultimo snapshot guardado con el aviso de hora', () async {
    // 1) con red, se guarda
    SharedPreferences.setMockInitialValues({});
    final almacen = AlmacenLocal();
    final conRed = ControladorVisor(
      cliente: ClienteHerramienta('h', token: 'JWT', cliente: MockClient((r) async {
        if (r.url.path == '/api/v1/diagrams/d1') return json(snapshot(10, 'Libro'));
        return json([]);
      })),
      almacen: almacen,
      diagramId: 'd1',
      ahora: () => DateTime(2026, 11, 1, 9, 30),
      fabricaConexion: ({
        required url,
        required token,
        required diagramId,
        required alOperacion,
        required alBloqueo,
        required alPresencia,
        required alConectar,
        required alCaer,
      }) =>
          canal = CanalFalso(
              url: url,
              token: token,
              diagramId: diagramId,
              alOperacion: alOperacion,
              alBloqueo: alBloqueo,
              alPresencia: alPresencia,
              alConectar: alConectar,
              alCaer: alCaer),
    );
    await conRed.iniciar();
    canal.alOperacion(renombrar(11, 'Libros')); // un cambio en vivo tambien se guarda
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    conRed.dispose();

    // 2) se cae la red: se reabre y debe mostrar lo ultimo, con aviso
    final sinRed = ControladorVisor(
      cliente: ClienteHerramienta('h', token: 'JWT', cliente: MockClient((_) async => throw http.ClientException('sin ruta'))),
      almacen: almacen,
      diagramId: 'd1',
      ahora: () => DateTime(2026, 11, 1, 18, 5),
    );
    await sinRed.iniciar();
    expect(sinRed.sinConexion, isTrue);
    expect(sinRed.modelo!.classes.single.name, 'Libros'); // lo ultimo, no lo del primer snapshot
    expect(sinRed.ultimoSeq, 11);
    expect(sinRed.ultimaActualizacion, DateTime(2026, 11, 1, 9, 30)); // "ultima actualizacion 09:30"
    sinRed.dispose();
  });

  test('sin sesion y sin nada guardado: error claro, no pantalla en blanco', () async {
    final c = armar(MockClient((_) async => http.Response('?', 500)), token: null);
    await c.iniciar();
    expect(c.estadoVisor, EstadoVisor.error);
    expect(c.modelo, isNull);
    expect(c.mensaje, contains('nada guardado'));
    c.dispose();
  });

  test('una sesion vencida es un error, no un "sin conexion" que parece pasajero', () async {
    final c = armar(MockClient((_) async => json({}, 401)));
    await c.iniciar();
    expect(c.estadoVisor, EstadoVisor.error);
    expect(c.mensaje, contains('vencio'));
    c.dispose();
  });

  test('si el canal cae, avisa sin conexion; al reconectar se pone al dia solo', () async {
    var op = 0;
    final c = armar(MockClient((r) async {
      if (r.url.path == '/api/v1/diagrams/d1') return json(snapshot(10, 'Libro'));
      op++;
      return json([
        {'seq': 11, 'opType': 'RENAME_CLASS', 'payload': '{"classId":"libro","name":"Mientras no estaba"}'},
      ]);
    }));
    await c.iniciar();
    canal.alCaer('desconectado');
    expect(c.sinConexion, isTrue);

    canal.alConectar(); // volvio el canal
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    expect(op, 1);
    expect(c.estadoVisor, EstadoVisor.enVivo);
    expect(c.modelo!.classes.single.name, 'Mientras no estaba');
    c.dispose();
  });
}
