import 'dart:convert';

import 'package:stomp_dart_client/stomp_dart_client.dart';

import '../visor/modelo.dart';
import 'comando.dart';

/// Canal propio del editor. El canal de solo lectura del visor no se modifica.
class ConexionEditable {
  final String url;
  final String token;
  final String diagramId;
  final void Function(Json) alOperacion;
  final void Function(Json) alBloqueo;
  final void Function(Json) alPresencia;
  final void Function(RechazoComando) alRechazo;
  final void Function() alConectar;
  final void Function(String) alCaer;
  final StompClient Function(StompConfig) _crearCliente;
  StompClient? _cliente;
  bool _cerrada = false;

  ConexionEditable({
    required this.url,
    required this.token,
    required this.diagramId,
    required this.alOperacion,
    required this.alBloqueo,
    required this.alPresencia,
    required this.alRechazo,
    required this.alConectar,
    required this.alCaer,
    StompClient Function(StompConfig)? crearCliente,
  }) : _crearCliente =
            crearCliente ?? ((config) => StompClient(config: config));

  bool get activa => !_cerrada && (_cliente?.connected ?? false);

  void conectar() {
    if (_cerrada || _cliente != null) return;
    final headers = {'Authorization': 'Bearer $token'};
    _cliente = _crearCliente(StompConfig.sockJS(
      url: url,
      stompConnectHeaders: headers,
      webSocketConnectHeaders: headers,
      reconnectDelay: const Duration(seconds: 3),
      connectionTimeout: const Duration(seconds: 8),
      onConnect: (_) => _suscribir(),
      onWebSocketDone: () => _caer('Se cortó la conexión.'),
      onWebSocketError: (_) => _caer('No se pudo conectar con la herramienta.'),
      onDisconnect: (_) => _caer('Se cerró la conexión.'),
      onStompError: (_) => _caer('La herramienta rechazó el canal de edición.'),
    ));
    _cliente!.activate();
  }

  void _caer(String mensaje) {
    if (!_cerrada) alCaer(mensaje);
  }

  void _suscribir() {
    if (_cerrada) return;
    final cliente = _cliente!;
    final base = '/topic/diagram/$diagramId';
    for (final entrada in <String, void Function(Json)>{
      '$base/ops': alOperacion,
      '$base/locks': alBloqueo,
      '$base/presence': alPresencia,
      '/user/queue/errors': (json) {
        try {
          alRechazo(RechazoComando.desdeJson(json));
        } on ArgumentError {
          // Un motivo desconocido no se interpreta como otro rechazo.
        } on TypeError {
          // Frame ajeno al contrato.
        }
      },
    }.entries) {
      cliente.subscribe(
          destination: entrada.key,
          callback: (frame) {
            if (_cerrada || frame.body == null) return;
            final dynamic json;
            try {
              json = jsonDecode(frame.body!);
            } on FormatException {
              return;
            }
            if (json is Map<String, dynamic>) entrada.value(json);
          });
    }
    alConectar(); // También en cada reconexión: el controlador pide sinceSeq.
  }

  bool enviarComando(Comando comando) => _enviar('op', comando.aJson());
  bool bloquear(String elementId, String userName) => _enviar(
      'lock', {'elementId': elementId, 'userName': userName, 'held': true});
  bool desbloquear(String elementId, String userName) => _enviar(
      'unlock', {'elementId': elementId, 'userName': userName, 'held': false});

  bool _enviar(String destino, Json cuerpo) {
    if (!activa) return false;
    try {
      _cliente!.send(
          destination: '/app/diagram/$diagramId/$destino',
          headers: {'content-type': 'application/json'},
          body: jsonEncode(cuerpo));
      return true;
    } on Exception {
      _caer('No se pudo enviar: se cortó la conexión.');
      return false;
    }
  }

  void cerrar() {
    if (_cerrada) return;
    _cerrada = true;
    _cliente?.deactivate();
    _cliente = null;
  }
}
