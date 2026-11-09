import 'dart:convert';

import 'package:colabmodel_app/visor/cliente_herramienta.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

http.Response json(Object cuerpo, [int estado = 200]) => http.Response(
      jsonEncode(cuerpo),
      estado,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

void main() {
  test('normaliza la direccion como el cliente del backend generado', () {
    expect(ClienteHerramienta.normalizarHost('192.168.0.10:8080/'), 'http://192.168.0.10:8080');
    expect(ClienteHerramienta.normalizarHost(' https://x.io '), 'https://x.io');
  });

  test('el canal STOMP es el /ws con SockJS que ya usa la web', () {
    expect(ClienteHerramienta('10.0.0.5:8080').urlStomp, 'http://10.0.0.5:8080/ws');
    expect(ClienteHerramienta('https://x.io').urlStomp, 'https://x.io/ws');
  });

  test('login, proyectos, diagramas y snapshot siguen el recorrido del diseno', () async {
    final pedidos = <String>[];
    final c = ClienteHerramienta('h:8080', cliente: MockClient((r) async {
      pedidos.add('${r.method} ${r.url.path}${r.url.hasQuery ? '?${r.url.query}' : ''} auth=${r.headers['Authorization']}');
      switch (r.url.path) {
        case '/api/v1/auth/login':
          expect(jsonDecode(r.body), {'email': 'a@b.c', 'password': 'secreta123'});
          return json({'token': 'JWT', 'userId': 'u1', 'fullName': 'Ana'});
        case '/api/v1/projects':
          return json([
            {'id': 'p1', 'name': 'Biblioteca', 'myRole': 'VIEWER', 'diagramCount': 1},
          ]);
        case '/api/v1/projects/p1':
          return json({
            'id': 'p1',
            'diagrams': [
              {'id': 'd1', 'name': 'Principal', 'currentSeq': 12},
            ],
          });
        case '/api/v1/diagrams/d1':
          return json({'id': 'd1', 'name': 'Principal', 'currentSeq': 12, 'classes': [], 'relationships': [], 'layout': []});
      }
      return http.Response('no', 404);
    }));

    final sesion = await c.iniciarSesion(' a@b.c ', 'secreta123');
    expect(sesion.nombre, 'Ana');
    expect(c.token, 'JWT');
    final proyectos = await c.proyectos();
    expect(proyectos.single.nombre, 'Biblioteca');
    final diagramas = await c.diagramasDe('p1');
    expect(diagramas.single.currentSeq, 12);
    final s = await c.snapshot('d1');
    expect(s.currentSeq, 12);

    expect(pedidos.first, 'POST /api/v1/auth/login auth=null'); // el login no lleva token
    expect(pedidos.skip(1).every((p) => p.endsWith('auth=Bearer JWT')), isTrue);
    // Solo lee: nada que no sea el login es un POST/PUT/PATCH/DELETE.
    expect(pedidos.skip(1).every((p) => p.startsWith('GET ')), isTrue);
  });

  test('?sinceSeq= entiende la forma REST (opType + payload en texto) y descarta lo ilegible', () async {
    final c = ClienteHerramienta('h', token: 'T', cliente: MockClient((r) async {
      expect(r.url.path, '/api/v1/diagrams/d1/operations');
      expect(r.url.queryParameters['sinceSeq'], '12');
      return json([
        {'seq': 13, 'opType': 'RENAME_CLASS', 'origin': 'MOUSE', 'payload': '{"classId":"k","name":"Nuevo"}', 'userId': null},
        {'seq': 14, 'opType': 'BATCH', 'payload': 'no es json'},
        {'seq': 15, 'opType': 'MOVE_ELEMENT', 'payload': '{"elementId":"k","x":1,"y":2}'},
      ]);
    }));
    final ops = await c.operacionesDesde('d1', 12);
    expect(ops.map((o) => o.seq), [13, 15]);
    expect(ops.first.payload['name'], 'Nuevo');
  });

  test('401 en el login dice que las credenciales no son correctas; 401 con token, que la sesion se vencio', () async {
    final login = ClienteHerramienta('h', cliente: MockClient((_) async => json({'mensaje': 'x'}, 401)));
    await expectLater(
      login.iniciarSesion('a@b.c', 'mala'),
      throwsA(isA<HerramientaException>().having((e) => e.mensaje, 'mensaje', contains('no son correctos'))),
    );

    final vencida = ClienteHerramienta('h', token: 'T', cliente: MockClient((_) async => json({}, 401)));
    await expectLater(
      vencida.proyectos(),
      throwsA(isA<HerramientaException>()
          .having((e) => e.sesionInvalida, 'sesionInvalida', isTrue)
          .having((e) => e.mensaje, 'mensaje', contains('vencio'))),
    );
  });

  test('sin red: la excepcion habla de la direccion, no de la pila', () async {
    final c = ClienteHerramienta('h', token: 'T', cliente: MockClient((_) async => throw http.ClientException('sin ruta')));
    await expectLater(
      c.proyectos(),
      throwsA(isA<HerramientaException>().having((e) => e.mensaje, 'mensaje', contains('No se pudo conectar'))),
    );
  });
}
