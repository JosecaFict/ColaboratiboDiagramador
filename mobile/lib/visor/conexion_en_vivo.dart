import 'dart:convert';

import 'package:stomp_dart_client/stomp_dart_client.dart';

typedef AlJson = void Function(Map<String, dynamic> cuerpo);

/// Canal STOMP de SOLO LECTURA hacia la herramienta (ADR-036).
///
/// Suscribe `/topic/diagram/{id}/ops`, `/locks` y `/presence`. Esta clase no
/// tiene ningun metodo para enviar: el visor NUNCA escribe en `/app/...`. Eso lo
/// garantiza el tipo, no la disciplina.
class ConexionEnVivo {
  final String url;
  final String token;
  final String diagramId;

  final AlJson alOperacion;
  final AlJson alBloqueo;
  final AlJson alPresencia;

  /// Se llama en CADA conexion, la primera y las reconexiones. Es el momento
  /// de pedir `?sinceSeq=`: lo que paso mientras no habia canal no llego por aqui.
  final void Function() alConectar;
  final void Function(String motivo) alCaer;

  StompClient? _cliente;
  final List<void Function({Map<String, String>? unsubscribeHeaders})> _bajas = [];

  ConexionEnVivo({
    required this.url,
    required this.token,
    required this.diagramId,
    required this.alOperacion,
    required this.alBloqueo,
    required this.alPresencia,
    required this.alConectar,
    required this.alCaer,
  });

  bool get activa => _cliente?.connected ?? false;

  /// Abre el canal. Sobrescribible en pruebas para no tocar la red.
  void conectar() {
    final cabeceras = {'Authorization': 'Bearer $token'};
    final base = (StompFrame frame) => _suscribir();
    _cliente = StompClient(
      config: StompConfig.sockJS(
        url: url,
        stompConnectHeaders: cabeceras,
        webSocketConnectHeaders: cabeceras,
        onConnect: base,
        onWebSocketError: (e) => alCaer('$e'),
        onStompError: (f) => alCaer(f.headers['message'] ?? f.body ?? 'error STOMP'),
        onDisconnect: (_) => alCaer('desconectado'),
        reconnectDelay: const Duration(seconds: 3),
        connectionTimeout: const Duration(seconds: 8),
      ),
    );
    _cliente!.activate();
  }

  void _suscribir() {
    final c = _cliente;
    if (c == null) return;
    _bajas.clear();
    _bajas.add(c.subscribe(destination: '/topic/diagram/$diagramId/ops', callback: _recibe(alOperacion)));
    _bajas.add(c.subscribe(destination: '/topic/diagram/$diagramId/locks', callback: _recibe(alBloqueo)));
    _bajas.add(c.subscribe(destination: '/topic/diagram/$diagramId/presence', callback: _recibe(alPresencia)));
    alConectar();
  }

  void Function(StompFrame) _recibe(AlJson destino) => (frame) {
        final cuerpo = frame.body;
        if (cuerpo == null || cuerpo.isEmpty) return;
        try {
          final j = jsonDecode(cuerpo);
          if (j is Map) destino(Map<String, dynamic>.from(j));
        } on FormatException {
          // un frame que no es JSON no es nuestro: se descarta
        }
      };

  void cerrar() {
    for (final baja in _bajas) {
      baja();
    }
    _bajas.clear();
    _cliente?.deactivate();
    _cliente = null;
  }
}
