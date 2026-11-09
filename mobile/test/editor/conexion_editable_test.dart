import 'dart:convert';
import 'dart:typed_data';

import 'package:colabmodel_app/editor/comando.dart';
import 'package:colabmodel_app/editor/conexion_editable.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stomp_dart_client/stomp_dart_client.dart';

class StompFalso extends StompClient {
  StompFalso(StompConfig config) : super(config: config);
  bool enRed = false;
  bool cerrado = false;
  int activaciones = 0;
  final suscripciones = <String, StompFrameCallback>{};
  final envios = <Map<String, dynamic>>[];
  @override
  bool get connected => enRed;
  @override
  void activate() {
    activaciones++;
  }

  @override
  void deactivate() {
    cerrado = true;
    enRed = false;
  }

  @override
  StompUnsubscribe subscribe(
      {required String destination,
      required StompFrameCallback callback,
      Map<String, String>? headers}) {
    suscripciones[destination] = callback;
    return ({Map<String, String>? unsubscribeHeaders}) {};
  }

  @override
  void send(
      {required String destination,
      Map<String, String>? headers,
      String? body,
      Uint8List? binaryBody}) {
    envios.add({'destination': destination, 'body': jsonDecode(body!)});
  }

  void conectar() {
    enRed = true;
    config.onConnect(StompFrame(command: 'CONNECTED'));
  }

  void recibir(String destino, String cuerpo) =>
      suscripciones[destino]!(StompFrame(command: 'MESSAGE', body: cuerpo));
}

void main() {
  late StompFalso stomp;
  late ConexionEditable canal;
  late List<Map<String, dynamic>> ops, locks, presencias;
  late List<RechazoComando> rechazos;
  late List<String> caidas;
  var conexiones = 0;
  setUp(() {
    ops = [];
    locks = [];
    presencias = [];
    rechazos = [];
    caidas = [];
    conexiones = 0;
    canal = ConexionEditable(
        url: 'http://h/ws',
        token: 'JWT',
        diagramId: 'd',
        alOperacion: ops.add,
        alBloqueo: locks.add,
        alPresencia: presencias.add,
        alRechazo: rechazos.add,
        alCaer: caidas.add,
        alConectar: () => conexiones++,
        crearCliente: (config) => stomp = StompFalso(config));
    canal.conectar();
  });
  tearDown(() => canal.cerrar());
  test('SockJS autenticado y cuatro suscripciones antes de avisar conexión',
      () {
    expect(stomp.config.useSockJS, isTrue);
    expect(stomp.config.url, 'http://h/ws');
    expect(stomp.config.stompConnectHeaders, {'Authorization': 'Bearer JWT'});
    expect(
        stomp.config.webSocketConnectHeaders, {'Authorization': 'Bearer JWT'});
    expect(canal.activa, isFalse);
    stomp.conectar();
    expect(stomp.suscripciones.keys, [
      '/topic/diagram/d/ops',
      '/topic/diagram/d/locks',
      '/topic/diagram/d/presence',
      '/user/queue/errors'
    ]);
    expect(conexiones, 1);
    stomp.conectar();
    expect(
        conexiones, 2); // Cada CONNECT pide al controlador recuperar sinceSeq.
    canal.conectar();
    expect(stomp.activaciones, 1);
  });
  test('envía comandos y leases a las rutas de la web sin userId', () {
    final c = Comando(
        opId: 'o',
        diagramId: 'd',
        baseSeq: 10,
        type: CommandType.DELETE_CLASS,
        origin: Origin.MOUSE,
        payload: {'classId': 'c'});
    expect(canal.enviarComando(c), isFalse);
    stomp.conectar();
    expect(canal.enviarComando(c), isTrue);
    canal.bloquear('c', 'Ana #123');
    canal.desbloquear('c', 'Ana #123');
    expect(stomp.envios, [
      {'destination': '/app/diagram/d/op', 'body': c.aJson()},
      {
        'destination': '/app/diagram/d/lock',
        'body': {'elementId': 'c', 'userName': 'Ana #123', 'held': true}
      },
      {
        'destination': '/app/diagram/d/unlock',
        'body': {'elementId': 'c', 'userName': 'Ana #123', 'held': false}
      },
    ]);
  });
  test('distribuye JSON válido, descarta frames ajenos y lee rechazos privados',
      () {
    stomp.conectar();
    stomp.recibir('/topic/diagram/d/ops', '{"seq": 11}');
    stomp.recibir('/topic/diagram/d/locks', '{"elementId": "c", "held": true}');
    stomp.recibir('/topic/diagram/d/presence', '{"userId": "u"}');
    stomp.recibir('/user/queue/errors',
        '{"opId": "o", "reason": "STALE", "message": "cambió"}');
    for (final cuerpo in ['no es JSON', '[]', '{"reason": "OTRO"}', '{}']) {
      stomp.recibir('/user/queue/errors', cuerpo);
    }
    expect(ops, [
      {'seq': 11}
    ]);
    expect(locks.single['elementId'], 'c');
    expect(presencias.single['userId'], 'u');
    expect(rechazos.single.reason, RejectionReason.STALE);
  });
  test('avisa cortes reales del socket y cerrar impide enviar y recibir', () {
    stomp.conectar();
    stomp.enRed = false;
    stomp.config.onWebSocketDone();
    expect(caidas.single, contains('conexión'));
    expect(canal.activa, isFalse);
    canal.cerrar();
    expect(stomp.cerrado, isTrue);
    expect(canal.bloquear('c', 'Ana'), isFalse);
    stomp.recibir('/topic/diagram/d/ops', '{"seq": 12}');
    stomp.config.onWebSocketError('error tardío');
    expect(ops, isEmpty);
    expect(caidas, hasLength(1));
  });
}
