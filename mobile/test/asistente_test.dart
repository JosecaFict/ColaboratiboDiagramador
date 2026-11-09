// Asistente conectado (T-03a). TODO SIMULADO: el backend es un http.MockClient
// en memoria que sirve el manifiesto REAL generado por el backend (los de
// test/manifests/) y guarda lo que se le postea. El recorrido contra un
// backend generado de verdad se registra aparte en la ficha.

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:colabmodel_app/asistente/controlador_asistente.dart';
import 'package:colabmodel_app/asistente/pantalla_asistente.dart';
import 'package:colabmodel_app/asistente/reconocedor_voz.dart';
import 'package:colabmodel_app/data/cliente_backend.dart';
import 'package:colabmodel_app/domain/app_manifest.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Backend generado de mentira: GET/POST por la ruta del manifiesto.
class BackendFalso {
  final String manifiestoJson;
  final Map<String, List<Map<String, dynamic>>> tablas = {};
  final List<String> pedidos = [];
  int _ids = 0;

  /// Si se setea, el proximo POST responde esto.
  ({int estado, String cuerpo})? errorEnPost;

  BackendFalso(String nombreManifiesto)
      : manifiestoJson = File('test/manifests/$nombreManifiesto.json').readAsStringSync();

  AppManifest get manifiesto => AppManifest.fromJson(jsonDecode(manifiestoJson) as Map<String, dynamic>);

  void sembrar(String ruta, Map<String, dynamic> fila) =>
      (tablas[ruta] ??= []).add({'id': 'id-${++_ids}', ...fila});

  http.Client cliente() => MockClient((req) async {
        pedidos.add('${req.method} ${req.url.path}');
        if (req.url.path == '/app-manifest.json') {
          return http.Response.bytes(utf8.encode(manifiestoJson), 200);
        }
        if (req.method == 'GET') {
          return http.Response.bytes(utf8.encode(jsonEncode(tablas[req.url.path] ?? [])), 200);
        }
        if (req.method == 'POST') {
          final e = errorEnPost;
          if (e != null) {
            errorEnPost = null;
            return http.Response(e.cuerpo, e.estado);
          }
          final cuerpo = jsonDecode(utf8.decode(req.bodyBytes)) as Map<String, dynamic>;
          final fila = {'id': 'id-${++_ids}', ...cuerpo};
          (tablas[req.url.path] ??= []).add(fila);
          return http.Response.bytes(utf8.encode(jsonEncode(fila)), 201);
        }
        return http.Response('no', 404);
      });

  ClienteBackend clienteBackend() => ClienteBackend('192.168.0.10:8081', cliente: cliente());
}

/// Sin la confirmacion antes de crear: estas pruebas miran como se interpreta
/// y se resuelve el pedido. La confirmacion tiene su propio grupo, abajo.
Future<ControladorAsistente> _conectar(BackendFalso b, {bool confirmar = false}) async {
  final c = b.clienteBackend();
  return ControladorAsistente(c, await c.cargarManifiesto(),
      reloj: () => DateTime(2026, 9, 16, 10), confirmarAntesDeCrear: confirmar);
}

String _ultimo(ControladorAsistente c) => c.mensajes.last.texto;

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  group('controlador: biblioteca', () {
    late BackendFalso b;
    late ControladorAsistente c;
    setUp(() async {
      b = BackendFalso('biblioteca');
      c = await _conectar(b);
    });

    test('saluda diciendo a que backend se conecto y que entidades hay', () {
      expect(c.mensajes.single.texto, contains('Conectado a'));
      expect(c.mensajes.single.texto, contains('libro'));
      expect(c.mensajes.single.texto, contains('socio'));
    });

    test('crear con lo obligatorio hace el POST y confirma', () async {
      await c.enviar('crear libro con título Rayuela, isbn 978-84-376-0494-7 y estado disponible');
      expect(b.pedidos.last, 'POST /api/libro');
      expect(b.tablas['/api/libro']!.single,
          containsPair('titulo', 'Rayuela'));
      expect(_ultimo(c), startsWith('Listo, creé libro'));
      expect(_ultimo(c), contains('Rayuela'));
    });

    test('falta algo: pregunta y el texto siguiente completa el pedido', () async {
      await c.enviar('crear libro con título Rayuela');
      expect(_ultimo(c), contains('falta isbn y estado'));
      expect(b.tablas['/api/libro'], isNull, reason: 'no se postea incompleto');

      await c.enviar('isbn 978-84-376-0494-7 y estado disponible');
      expect(b.tablas['/api/libro']!.single['titulo'], 'Rayuela');
      expect(b.tablas['/api/libro']!.single['isbn'], '978-84-376-0494-7');
      expect(_ultimo(c), startsWith('Listo'));
    });

    test('un pedido nuevo en medio de una pregunta abierta la reemplaza', () async {
      await c.enviar('crear libro con título Rayuela');
      await c.enviar('listar socios');
      expect(_ultimo(c), contains('No hay socio'));
      expect(b.tablas['/api/libro'], isNull);
    });

    // T-03c: con voz, un valor mal oido se corta diciendo "cancelar". Hoy
    // funciona porque "cancelar" es un verbo de borrar (FueraDeAlcance), no
    // NoEntendida, y entonces no se pega al pedido pendiente. Riesgo conocido:
    // la respuesta habla de borrar, no dice "cancelado".
    test('"cancelar" corta la pregunta abierta y no crea nada', () async {
      await c.enviar('crear libro con título Rayuela');
      expect(_ultimo(c), contains('falta isbn y estado'));

      await c.enviar('cancelar');
      expect(c.mensajes.last.deAsistente, isTrue);
      expect(b.pedidos.where((p) => p.startsWith('POST')), isEmpty);

      // Lo que se diga despues ya no completa el pedido cancelado.
      await c.enviar('isbn 978-84-376-0494-7 y estado disponible');
      expect(b.pedidos.where((p) => p.startsWith('POST')), isEmpty);
      expect(b.tablas['/api/libro'], isNull);
    });

    test('"cancelar" cierra una pregunta con opciones sin crear nada', () async {
      b.sembrar('/api/libro', {'titulo': 'Rayuela'});
      b.sembrar('/api/socio', {'nombre': 'Ana Pérez'});
      b.sembrar('/api/socio', {'nombre': 'Ana Gómez'});
      await c.enviar('crear préstamo del libro Rayuela al socio Ana con fecha de préstamo hoy');
      final opciones = c.mensajes.last.opciones;
      expect(opciones, hasLength(2));

      await c.enviar('cancelar');
      expect(c.mensajes.last.opciones, isEmpty);

      // La opcion vieja ya no hace nada.
      await c.elegir(opciones.first);
      expect(b.pedidos.where((p) => p.startsWith('POST')), isEmpty);
      expect(b.tablas['/api/prestamo'], isNull);
    });

    // T-11, caso A: la entidad elegida en las opciones no se puede perder.
    test('pregunta pendiente: la entidad elegida en las opciones se conserva', () async {
      await c.enviar('crear el');
      expect(_ultimo(c), contains('¿Qué querés crear?'));
      await c.enviar('socio');
      expect(_ultimo(c), contains('falta nombre'));

      await c.enviar('ana Pérez');
      expect(_ultimo(c), startsWith('Listo, creé socio'));
      expect(b.tablas['/api/socio']!.single['nombre'], 'ana Pérez');
    });

    // T-11, caso B: si solo falta un campo, la respuesta es ese campo.
    test('pregunta pendiente: con un solo campo faltante la respuesta lo llena', () async {
      await c.enviar('crear socio');
      expect(_ultimo(c), contains('falta nombre'));

      await c.enviar('ana Pérez');
      expect(_ultimo(c), isNot(contains('es nombre o email')));
      expect(_ultimo(c), startsWith('Listo, creé socio'));
      expect(b.tablas['/api/socio']!.single['nombre'], 'ana Pérez');
    });

    test('pregunta pendiente: respondiendo con el nombre del campo sigue funcionando', () async {
      await c.enviar('crear socio');
      await c.enviar('nombre Ana Pérez');
      expect(_ultimo(c), startsWith('Listo, creé socio'));
      expect(b.tablas['/api/socio']!.single['nombre'], 'Ana Pérez');
    });

    test('pregunta pendiente: "cancelar" corta y lo que sigue no completa el pedido viejo', () async {
      await c.enviar('crear socio');
      await c.enviar('cancelar');
      await c.enviar('ana Pérez');
      expect(b.pedidos.where((p) => p.startsWith('POST')), isEmpty);
      expect(b.tablas['/api/socio'], isNull);
    });

    test('pregunta pendiente: "cancelar" también corta tras elegir la entidad', () async {
      await c.enviar('crear el');
      await c.enviar('socio');
      await c.enviar('cancelar');
      await c.enviar('ana Pérez');
      expect(b.pedidos.where((p) => p.startsWith('POST')), isEmpty);
    });

    test('un valor invalido se rechaza antes de pegarle al backend', () async {
      await c.enviar('crear libro con título X, isbn 1 y estado perdido');
      expect(_ultimo(c), startsWith('No lo hago'));
      expect(b.pedidos.where((p) => p.startsWith('POST')), isEmpty);
    });

    test('listar vacio y con datos', () async {
      await c.enviar('listar libros');
      expect(_ultimo(c), 'No hay libro cargados.');
      b.sembrar('/api/libro', {'titulo': 'Rayuela', 'autor': 'Cortázar', 'isbn': '1', 'estado': 'disponible'});
      b.sembrar('/api/libro', {'titulo': 'Ficciones', 'autor': 'Borges', 'isbn': '2', 'estado': 'prestado'});
      await c.enviar('mostrame los libros');
      expect(_ultimo(c), startsWith('Libro (2):'));
      expect(_ultimo(c), contains('Rayuela'));
      expect(_ultimo(c), contains('Ficciones'));
    });

    test('crear con referencias por nombre las resuelve contra el backend', () async {
      b.sembrar('/api/libro', {'titulo': 'Rayuela'});
      b.sembrar('/api/socio', {'nombre': 'Ana Pérez'});
      await c.enviar('registrá un préstamo del libro Rayuela al socio Ana Pérez con fecha de préstamo hoy');
      final fila = b.tablas['/api/prestamo']!.single;
      expect(fila['libroId'], b.tablas['/api/libro']!.single['id']);
      expect(fila['socioId'], b.tablas['/api/socio']!.single['id']);
      expect(fila['fechaPrestamo'], '2026-09-16');
      expect(_ultimo(c), startsWith('Listo, creé préstamo'));
      expect(_ultimo(c), contains('Rayuela'), reason: 'se muestra el nombre, no el uuid');
      expect(_ultimo(c), contains('Ana Pérez'));
    });

    test('listar préstamos muestra la referencia por nombre', () async {
      b.sembrar('/api/libro', {'titulo': 'Rayuela'});
      b.sembrar('/api/socio', {'nombre': 'Ana Pérez'});
      b.sembrar('/api/prestamo', {
        'fechaPrestamo': '2026-09-16',
        'libroId': b.tablas['/api/libro']!.single['id'],
        'socioId': b.tablas['/api/socio']!.single['id'],
      });
      await c.enviar('listar préstamos');
      expect(_ultimo(c), contains('libro: Rayuela'));
      expect(_ultimo(c), contains('socio: Ana Pérez'));
    });

    test('una referencia que no existe no crea nada', () async {
      b.sembrar('/api/socio', {'nombre': 'Ana Pérez'});
      await c.enviar('crear préstamo del libro Fantasma al socio Ana Pérez con fecha de préstamo hoy');
      expect(_ultimo(c), contains('No encontré libro «Fantasma»'));
      expect(b.pedidos.where((p) => p.startsWith('POST')), isEmpty);
    });

    test('una referencia con dos candidatos pregunta cual y sigue al elegir', () async {
      b.sembrar('/api/libro', {'titulo': 'Rayuela'});
      b.sembrar('/api/socio', {'nombre': 'Ana Pérez'});
      b.sembrar('/api/socio', {'nombre': 'Ana Gómez'});
      await c.enviar('crear préstamo del libro Rayuela al socio Ana con fecha de préstamo hoy');
      expect(_ultimo(c), contains('2 socio'));
      final opciones = c.mensajes.last.opciones;
      expect(opciones.map((o) => o.etiqueta).join(), allOf(contains('Ana Pérez'), contains('Ana Gómez')));
      expect(b.pedidos.where((p) => p.startsWith('POST')), isEmpty);

      await c.elegir(opciones.firstWhere((o) => o.etiqueta.contains('Gómez')));
      final gomez = b.tablas['/api/socio']!.firstWhere((s) => s['nombre'] == 'Ana Gómez');
      expect(b.tablas['/api/prestamo']!.single['socioId'], gomez['id']);
      expect(_ultimo(c), startsWith('Listo'));
    });

    test('el backend rechaza el alta: se muestra su motivo', () async {
      b.errorEnPost = (estado: 409, cuerpo: '{"message":"isbn duplicado"}');
      await c.enviar('crear libro con título X, isbn 1 y estado disponible');
      expect(_ultimo(c), contains('HTTP 409'));
      expect(_ultimo(c), contains('isbn duplicado'));
    });

    test('actualizar y borrar se dicen fuera de alcance', () async {
      await c.enviar('borrar el libro Rayuela');
      expect(c.mensajes.last.deAsistente, isTrue);
      expect(b.pedidos.where((p) => p != 'GET /app-manifest.json'), isEmpty);
    });

    test('sin red: lo cuenta en vez de romper', () async {
      final caido = ControladorAsistente(
        ClienteBackend('x:1', cliente: MockClient((_) async => throw const SocketException('sin red'))),
        b.manifiesto,
      );
      await caido.enviar('listar libros');
      expect(caido.mensajes.last.texto, contains('No pude hablar con el backend'));
    });
  });

  // EC-10: otro dominio, mismo codigo. Solo cambia el manifiesto.
  group('controlador: otro dominio sin tocar codigo (EC-10)', () {
    test('clientes y pedidos', () async {
      final b = BackendFalso('02-uno-a-muchos');
      final c = await _conectar(b);
      await c.enviar('crear cliente con nombre Marta');
      expect(b.tablas['/api/cliente']!.single['nombre'], 'Marta');

      await c.enviar('crear pedido del cliente Marta con fecha hoy a las 10:00 y total 1500,50');
      final pedido = b.tablas['/api/pedido']!.single;
      expect(pedido['clienteId'], b.tablas['/api/cliente']!.single['id']);
      expect(pedido['total'], isNotNull);
      expect(_ultimo(c), startsWith('Listo, creé pedido'));
      expect(_ultimo(c), contains('Marta'));

      await c.enviar('listar pedidos');
      expect(_ultimo(c), startsWith('Pedido (1):'));
    });
  });

  group('pantalla', () {
    testWidgets('pide la direccion, baja el manifiesto y conversa', (tester) async {
      final b = BackendFalso('biblioteca');
      String? hostPedido;
      await tester.pumpWidget(MaterialApp(
        home: PantallaAsistente(fabricaCliente: (host) {
          hostPedido = host;
          return ClienteBackend(host, cliente: b.cliente());
        }),
      ));
      expect(find.text('Direccion del backend'), findsOneWidget);

      await tester.enterText(find.byType(TextField), '192.168.0.10:8081');
      await tester.tap(find.text('Conectar'));
      await tester.pumpAndSettle();
      expect(hostPedido, '192.168.0.10:8081');
      expect(find.textContaining('Conectado a'), findsOneWidget);

      b.sembrar('/api/socio', {'nombre': 'Ana Pérez', 'email': 'a@x.com', 'activo': true});
      await tester.enterText(find.byType(TextField), 'listar socios');
      await tester.tap(find.byIcon(Icons.send));
      await tester.pumpAndSettle();
      expect(find.text('listar socios'), findsOneWidget);
      expect(find.textContaining('Ana Pérez'), findsOneWidget);
    });

    testWidgets('si no se puede conectar, lo dice y deja reintentar', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: PantallaAsistente(
            fabricaCliente: (host) =>
                ClienteBackend(host, cliente: MockClient((_) async => http.Response('nada', 502)))),
      ));
      await tester.enterText(find.byType(TextField), '10.0.0.1:9');
      await tester.tap(find.text('Conectar'));
      await tester.pumpAndSettle();
      expect(find.textContaining('502'), findsOneWidget);
      expect(find.text('Conectar'), findsOneWidget);
    });

    testWidgets('una pregunta con opciones se contesta tocando una', (tester) async {
      final b = BackendFalso('biblioteca');
      await tester.pumpWidget(MaterialApp(
        home: PantallaAsistente(fabricaCliente: (h) => ClienteBackend(h, cliente: b.cliente())),
      ));
      await tester.enterText(find.byType(TextField), 'h:1');
      await tester.tap(find.text('Conectar'));
      await tester.pumpAndSettle();

      b.sembrar('/api/libro', {'titulo': 'Rayuela'});
      b.sembrar('/api/socio', {'nombre': 'Ana Pérez'});
      b.sembrar('/api/socio', {'nombre': 'Ana Gómez'});
      await tester.enterText(find.byType(TextField),
          'crear préstamo del libro Rayuela al socio Ana con fecha de préstamo hoy');
      await tester.tap(find.byIcon(Icons.send));
      await tester.pumpAndSettle();
      // Primero repite el pedido y espera el «sí»; recien despues resuelve las referencias.
      expect(find.textContaining('¿Lo creo?'), findsOneWidget);
      await tester.tap(find.widgetWithText(ActionChip, 'Sí, crear'));
      await tester.pumpAndSettle();
      expect(find.byType(ActionChip), findsNWidgets(2));

      await tester.tap(find.byType(ActionChip).first);
      await tester.pumpAndSettle();
      expect(b.tablas['/api/prestamo'], hasLength(1));
      expect(find.byType(ActionChip), findsNothing);
    });
  });

  // Lista del docente, CU-15: la orden se repite para que se confirme ANTES de
  // enviarla. Dictando, un nombre mal oido se corrige sin tocar el backend.
  group('controlador: confirmar antes de crear', () {
    late BackendFalso b;
    late ControladorAsistente c;
    setUp(() async {
      b = BackendFalso('biblioteca');
      c = await _conectar(b, confirmar: true);
    });

    bool huboPost() => b.pedidos.any((p) => p.startsWith('POST'));

    test('repite lo que va a crear y no postea hasta el si', () async {
      await c.enviar('crear socio con nombre Carla Rojas');

      expect(_ultimo(c), startsWith('Voy a crear socio con'));
      expect(_ultimo(c), contains('Carla Rojas'));
      expect(_ultimo(c), endsWith('¿Lo creo?'));
      expect(c.mensajes.last.opciones.map((o) => o.etiqueta), ['Sí, crear', 'No']);
      expect(huboPost(), isFalse);

      await c.enviar('sí');
      expect(b.tablas['/api/socio']!.single['nombre'], 'Carla Rojas');
      expect(_ultimo(c), startsWith('Listo, creé socio'));
    });

    test('vale decirlo de otra forma, como al dictar', () async {
      await c.enviar('crear socio con nombre Carla Rojas');
      await c.enviar('dale');
      expect(b.tablas['/api/socio'], hasLength(1));
    });

    test('con un no no crea nada y lo dice', () async {
      await c.enviar('crear socio con nombre Carla Roja');
      await c.enviar('no');

      expect(huboPost(), isFalse);
      expect(_ultimo(c), startsWith('No creé nada'));
    });

    test('tocar la opcion equivale a decirla', () async {
      await c.enviar('crear socio con nombre Carla Rojas');
      await c.elegir(c.mensajes.last.opciones.first);
      expect(b.tablas['/api/socio'], hasLength(1));
    });

    test('un pedido nuevo en vez de contestar deja el anterior sin crear', () async {
      await c.enviar('crear socio con nombre Carla Rojas');
      await c.enviar('listar socios');

      expect(huboPost(), isFalse);
      expect(_ultimo(c), contains('No hay socio'));
    });

    test('listar no pregunta: no cambia nada', () async {
      b.sembrar('/api/socio', {'nombre': 'Ana'});
      await c.enviar('listar socios');
      expect(_ultimo(c), startsWith('Socio (1):'));
    });

    test('las referencias se leen como se dijeron, antes de buscarlas', () async {
      await c.enviar('crear préstamo del libro Rayuela al socio Ana con fecha de préstamo hoy');
      expect(_ultimo(c), contains('«Rayuela»'));
      expect(_ultimo(c), contains('«Ana»'));
      expect(huboPost(), isFalse);
    });
  });

  // T-03c. TODO SIMULADO: el reconocedor es falso; el de verdad se prueba en
  // el telefono (ver ficha).
  group('voz', () {
    late BackendFalso b;
    late VozFalsa voz;
    setUp(() {
      b = BackendFalso('biblioteca');
      voz = VozFalsa();
    });

    /// Estas pruebas miran el dictado y que cada frase se envie una sola vez;
    /// la confirmacion antes de crear se apaga salvo que la prueba la pida.
    Future<void> conectar(WidgetTester tester, {http.Client? cliente, bool confirmar = false}) async {
      await tester.pumpWidget(MaterialApp(
        home: PantallaAsistente(
          fabricaCliente: (h) => ClienteBackend(h, cliente: cliente ?? b.cliente()),
          reconocedorVoz: voz,
          confirmarAntesDeCrear: confirmar,
        ),
      ));
      await tester.enterText(find.byType(TextField), 'h:1');
      await tester.tap(find.text('Conectar'));
      await tester.pumpAndSettle();
    }

    IconButton microfono(WidgetTester tester) => tester.widget<IconButton>(find.byWidgetPredicate(
        (w) => w is IconButton && w.icon is Icon && (w.icon as Icon).icon == Icons.mic_none));

    String campo(WidgetTester tester) => tester.widget<TextField>(find.byType(TextField)).controller!.text;

    testWidgets('lo dictado se ve mientras se habla y al final se envia solo', (tester) async {
      await conectar(tester);
      b.sembrar('/api/socio', {'nombre': 'Ana Pérez'});

      await tester.tap(find.byTooltip('Dictar'));
      await tester.pump();
      expect(voz.escuchando, isTrue);
      expect(find.byTooltip('Terminar y enviar'), findsOneWidget);
      expect(find.textContaining('Escuchando'), findsOneWidget);

      voz.parcial('listar');
      await tester.pump();
      expect(campo(tester), 'listar');

      voz.fin('listar socios');
      await tester.pumpAndSettle();
      expect(campo(tester), isEmpty);
      // Queda en el chat como mensaje del usuario y el asistente responde.
      expect(find.text('listar socios'), findsOneWidget);
      expect(find.textContaining('Ana Pérez'), findsOneWidget);
      expect(find.byTooltip('Dictar'), findsOneWidget);
      expect(find.textContaining('Escuchando'), findsNothing);
    });

    testWidgets('sin permiso: avisa, deshabilita el microfono y se sigue escribiendo', (tester) async {
      voz.errorAlPreparar = errorVozDesdeCodigo('error_permission');
      await conectar(tester);

      await tester.tap(find.byTooltip('Dictar'));
      await tester.pumpAndSettle();
      expect(find.textContaining('permiso para usar el micrófono'), findsWidgets);
      expect(microfono(tester).onPressed, isNull);
      expect(voz.escuchas, 0);

      b.sembrar('/api/socio', {'nombre': 'Ana Pérez'});
      await tester.enterText(find.byType(TextField), 'listar socios');
      await tester.tap(find.byIcon(Icons.send));
      await tester.pumpAndSettle();
      expect(find.textContaining('Ana Pérez'), findsOneWidget);
    });

    testWidgets('un error pasajero avisa y deja reintentar sin volver a preparar', (tester) async {
      await conectar(tester);
      await tester.tap(find.byTooltip('Dictar'));
      await tester.pump();
      voz.falla('error_no_match');
      await tester.pumpAndSettle();
      expect(find.text('No te entendí. Tocá el micrófono y probá de nuevo.'), findsOneWidget);
      expect(microfono(tester).onPressed, isNotNull);

      await tester.tap(find.byTooltip('Dictar'));
      await tester.pump();
      expect(voz.escuchas, 2);
      expect(voz.preparaciones, 1);
    });

    testWidgets('un final vacio no envia nada', (tester) async {
      await conectar(tester);
      final antes = b.pedidos.length;
      await tester.tap(find.byTooltip('Dictar'));
      await tester.pump();
      voz.fin('   ');
      await tester.pumpAndSettle();
      expect(find.text('No te entendí. Tocá el micrófono y probá de nuevo.'), findsOneWidget);
      expect(b.pedidos.length, antes);
    });

    testWidgets('tocar el microfono mientras escucha lo detiene', (tester) async {
      await conectar(tester);
      await tester.tap(find.byTooltip('Dictar'));
      await tester.pump();
      await tester.tap(find.byTooltip('Terminar y enviar'));
      await tester.pump();
      expect(voz.detenciones, 1);
      expect(find.byTooltip('Dictar'), findsOneWidget);
    });

    // Dictamen de Codex sobre 21e6944: enviar a mano durante el dictado y
    // despues recibir el final mandaba la frase dos veces (dos POST, dos filas).
    int posts(BackendFalso b) => b.pedidos.where((p) => p == 'POST /api/socio').length;
    const frase = 'crear socio con nombre Ana Pérez';

    testWidgets('Enviar durante el dictado envia una vez aunque llegue el final', (tester) async {
      await conectar(tester);
      await tester.tap(find.byTooltip('Dictar'));
      await tester.pump();
      voz.parcial(frase);
      await tester.pump();

      await tester.tap(find.byIcon(Icons.send));
      await tester.pumpAndSettle();
      expect(posts(b), 1);
      expect(voz.cancelaciones, 1);
      expect(find.byTooltip('Dictar'), findsOneWidget);
      expect(find.textContaining('Escuchando'), findsNothing);

      // El reconocedor igual entrega tarde: nada de eso cuenta.
      voz.parcial('basura tardia');
      await tester.pump();
      expect(campo(tester), isEmpty);
      voz.fin(frase);
      await tester.pumpAndSettle();
      expect(posts(b), 1);
      expect(b.tablas['/api/socio'], hasLength(1));
      expect(find.text(frase), findsOneWidget, reason: 'una sola burbuja del usuario');
    });

    testWidgets('la orden dictada se confirma tambien dictando, y se postea una vez', (tester) async {
      await conectar(tester, confirmar: true);
      Future<void> dictarYEnviar(String texto) async {
        await tester.tap(find.byTooltip('Dictar'));
        await tester.pump();
        voz.parcial(texto);
        await tester.pump();
        await tester.tap(find.byIcon(Icons.send));
        await tester.pumpAndSettle();
      }

      await dictarYEnviar(frase);
      expect(find.textContaining('¿Lo creo?'), findsOneWidget);
      expect(posts(b), 0, reason: 'nada se postea antes del sí');

      await dictarYEnviar('sí');
      expect(posts(b), 1);
      expect(b.tablas['/api/socio']!.single['nombre'], 'Ana Pérez');
    });

    testWidgets('Enter del teclado durante el dictado tambien envia una sola vez', (tester) async {
      await conectar(tester);
      await tester.tap(find.byTooltip('Dictar'));
      await tester.pump();
      voz.parcial(frase);
      await tester.pump();

      await tester.showKeyboard(find.byType(TextField));
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();
      expect(posts(b), 1);
      expect(voz.cancelaciones, 1);

      voz.fin(frase);
      await tester.pumpAndSettle();
      expect(posts(b), 1);
      expect(b.tablas['/api/socio'], hasLength(1));
    });

    testWidgets('con un backend lento, el final que llega durante el POST no envia otra vez', (tester) async {
      final puerta = Completer<void>();
      final rapido = b.cliente();
      final lento = MockClient((req) async {
        if (req.method == 'POST') await puerta.future;
        return rapido.send(http.Request(req.method, req.url)
              ..headers.addAll(req.headers)
              ..bodyBytes = req.bodyBytes)
            .then(http.Response.fromStream);
      });
      await conectar(tester, cliente: lento);
      await tester.tap(find.byTooltip('Dictar'));
      await tester.pump();
      voz.parcial(frase);
      await tester.pump();

      await tester.tap(find.byIcon(Icons.send));
      await tester.pump();
      voz.fin(frase); // llega mientras el primer POST espera
      await tester.pump();
      puerta.complete();
      await tester.pumpAndSettle();
      expect(posts(b), 1);
      expect(b.tablas['/api/socio'], hasLength(1));
    });

    testWidgets('sin dictado, Enviar y Enter siguen funcionando igual', (tester) async {
      await conectar(tester);
      await tester.enterText(find.byType(TextField), frase);
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();
      expect(posts(b), 1);
      expect(voz.cancelaciones, 0);
    });

    testWidgets('"cancelar" dictado corta la pregunta abierta', (tester) async {
      await conectar(tester);
      await tester.enterText(find.byType(TextField), 'crear libro con título Rayuela');
      await tester.tap(find.byIcon(Icons.send));
      await tester.pumpAndSettle();
      expect(find.textContaining('falta isbn y estado'), findsOneWidget);

      await tester.tap(find.byTooltip('Dictar'));
      await tester.pump();
      voz.fin('cancelar');
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'isbn 978-84-376-0494-7 y estado disponible');
      await tester.tap(find.byIcon(Icons.send));
      await tester.pumpAndSettle();
      expect(b.pedidos.where((p) => p.startsWith('POST')), isEmpty);
    });

    testWidgets('mientras el asistente esta ocupado no se puede dictar', (tester) async {
      final manifiesto = File('test/manifests/biblioteca.json').readAsStringSync();
      final puerta = Completer<void>();
      final lento = MockClient((req) async {
        if (req.url.path == '/app-manifest.json') return http.Response.bytes(utf8.encode(manifiesto), 200);
        await puerta.future;
        return http.Response('[]', 200);
      });
      await conectar(tester, cliente: lento);
      await tester.enterText(find.byType(TextField), 'listar socios');
      await tester.tap(find.byIcon(Icons.send));
      await tester.pump();
      expect(microfono(tester).onPressed, isNull);

      puerta.complete();
      await tester.pumpAndSettle();
      expect(microfono(tester).onPressed, isNotNull);
    });
  });

  group('errorVozDesdeCodigo', () {
    test('sin permiso es definitivo; no entender o la red no', () {
      expect(errorVozDesdeCodigo('error_permission').definitivo, isTrue);
      expect(errorVozDesdeCodigo('error_no_match').definitivo, isFalse);
      expect(errorVozDesdeCodigo('error_speech_timeout').mensaje, contains('No te entendí'));
      expect(errorVozDesdeCodigo('error_network').mensaje, contains('conexión'));
      expect(errorVozDesdeCodigo('error_busy').mensaje, contains('ocupado'));
      expect(errorVozDesdeCodigo('error_language_unavailable').mensaje, contains('español'));
    });

    test('un codigo desconocido se muestra tal cual y deja escribir', () {
      final e = errorVozDesdeCodigo('error_raro');
      expect(e.mensaje, contains('error_raro'));
      expect(e.mensaje, contains('escribir'));
      expect(e.definitivo, isFalse);
    });
  });
}

/// Reconocedor de mentira: la prueba decide que se "dice".
class VozFalsa implements ReconocedorVoz {
  ErrorVoz? errorAlPreparar;
  int preparaciones = 0;
  int escuchas = 0;
  int detenciones = 0;
  bool escuchando = false;

  void Function(String)? _parcial;
  void Function(String)? _fin;
  void Function(ErrorVoz)? _error;
  void Function()? _terminar;

  @override
  Future<ErrorVoz?> preparar() async {
    preparaciones++;
    return errorAlPreparar;
  }

  @override
  Future<void> escuchar({
    required void Function(String texto) alParcial,
    required void Function(String texto) alFinal,
    required void Function(ErrorVoz error) alError,
    required void Function() alTerminar,
  }) async {
    escuchas++;
    escuchando = true;
    _parcial = alParcial;
    _fin = alFinal;
    _error = alError;
    _terminar = alTerminar;
  }

  void parcial(String t) => _parcial!(t);

  void fin(String t) {
    escuchando = false;
    _fin!(t);
    _terminar!();
  }

  void falla(String codigo) {
    escuchando = false;
    _error!(errorVozDesdeCodigo(codigo));
    _terminar!();
  }

  @override
  Future<void> detener() async {
    detenciones++;
    escuchando = false;
    _terminar?.call();
  }

  /// A proposito NO desconecta los avisos: simula un reconocedor que igual
  /// entrega un final tardio. La pantalla es la que tiene que ignorarlo.
  int cancelaciones = 0;

  @override
  Future<void> cancelar() async {
    cancelaciones++;
    escuchando = false;
  }
}
