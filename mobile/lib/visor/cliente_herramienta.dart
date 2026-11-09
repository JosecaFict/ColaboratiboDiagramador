import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import 'aplicar_operacion.dart';
import 'modelo.dart';

class HerramientaException implements Exception {
  final String mensaje;
  final int? estado;
  const HerramientaException(this.mensaje, {this.estado});

  /// 401/403 con token: la sesion se vencio o ya no hay permiso.
  bool get sesionInvalida => estado == 401 || estado == 403;

  @override
  String toString() => mensaje;
}

class Sesion {
  final String token;
  final String userId;
  final String nombre;
  const Sesion(
      {required this.token, required this.userId, required this.nombre});
}

class ProyectoResumen {
  final String id;
  final String nombre;
  final String? miRol;
  final int cantidadDiagramas;
  const ProyectoResumen(
      {required this.id,
      required this.nombre,
      this.miRol,
      required this.cantidadDiagramas});
}

class DiagramaResumen {
  final String id;
  final String nombre;
  final int currentSeq;
  const DiagramaResumen(
      {required this.id, required this.nombre, required this.currentSeq});
}

/// Habla con P1: autenticación y lecturas. El editor escribe solo por STOMP.
class ClienteHerramienta {
  final String host;
  final http.Client _http;
  String? _token;
  final void Function()? alSesionVencida;

  ClienteHerramienta(String host,
      {http.Client? cliente, String? token, this.alSesionVencida})
      : host = normalizarHost(host),
        _http = cliente ?? http.Client(),
        _token = token;

  static String normalizarHost(String crudo) {
    var h = crudo.trim();
    if (h.isEmpty) return h;
    if (!h.startsWith('http://') && !h.startsWith('https://')) h = 'http://$h';
    while (h.endsWith('/')) {
      h = h.substring(0, h.length - 1);
    }
    return h;
  }

  String? get token => _token;

  /// Endpoint STOMP con SockJS (`/ws`), el mismo que usa la web. Probado contra
  /// el backend real: `stomp_dart_client` lo habla bien, asi que no hace falta
  /// un endpoint nativo aparte (ADR-036 lo permitia solo si fallaba).
  String get urlStomp => '$host/ws';

  Future<Sesion> iniciarSesion(String email, String password) =>
      _autenticar('login', {'email': email.trim(), 'password': password});

  Future<Sesion> registrar(String email, String password, String fullName) =>
      _autenticar('register', {
        'email': email.trim(),
        'password': password,
        'fullName': fullName.trim()
      });

  Future<Sesion> _autenticar(String accion, Map<String, String> cuerpo) async {
    final r = await _enviar(
        () => _http.post(Uri.parse('$host/api/v1/auth/$accion'),
            headers: {
              'Content-Type': 'application/json',
              'Accept': 'application/json'
            },
            body: jsonEncode(cuerpo)),
        conToken: false);
    final j = _json(r) as Map;
    final sesion = Sesion(
        token: j['token'] as String,
        userId: j['userId'] as String,
        nombre: (j['fullName'] ?? '') as String);
    _token = sesion.token;
    return sesion;
  }

  Future<void> cerrarSesion() async {
    try {
      await _enviar(() => _http.post(Uri.parse('$host/api/v1/auth/logout'),
          headers: _cabeceras()));
    } finally {
      _token = null;
    }
  }

  Future<List<ProyectoResumen>> proyectos() async {
    final j = _json(await _get('/api/v1/projects')) as List;
    return [
      for (final p in j)
        ProyectoResumen(
          id: p['id'] as String,
          nombre: (p['name'] ?? '') as String,
          miRol: p['myRole'] as String?,
          cantidadDiagramas: (p['diagramCount'] as num?)?.toInt() ?? 0,
        ),
    ];
  }

  Future<List<DiagramaResumen>> diagramasDe(String projectId) async {
    final j = _json(await _get('/api/v1/projects/$projectId')) as Map;
    return [
      for (final d in (j['diagrams'] as List? ?? const []))
        DiagramaResumen(
          id: d['id'] as String,
          nombre: (d['name'] ?? '') as String,
          currentSeq: (d['currentSeq'] as num?)?.toInt() ?? 0,
        ),
    ];
  }

  Future<SnapshotDiagrama> snapshot(String diagramId) async {
    final j = _json(await _get('/api/v1/diagrams/$diagramId')) as Map;
    return SnapshotDiagrama(crudo: Map<String, dynamic>.from(j));
  }

  /// Catch-up tras un hueco de `seq` o una reconexion (EC-08).
  Future<List<OperacionAplicada>> operacionesDesde(
      String diagramId, int sinceSeq) async {
    final j = _json(await _get(
        '/api/v1/diagrams/$diagramId/operations?sinceSeq=$sinceSeq')) as List;
    return [
      for (final o in j)
        if (operacionDesdeJson(o) case final op?) op,
    ];
  }

  Future<http.Response> _get(String ruta) =>
      _enviar(() => _http.get(Uri.parse('$host$ruta'), headers: _cabeceras()));

  Map<String, String> _cabeceras() => {
        'Accept': 'application/json',
        if (_token != null) 'Authorization': 'Bearer $_token',
      };

  Future<http.Response> _enviar(Future<http.Response> Function() pedir,
      {bool conToken = true}) async {
    final tokenEnviado = conToken ? _token : null;
    final http.Response r;
    try {
      r = await pedir().timeout(const Duration(seconds: 10));
    } on TimeoutException {
      throw const HerramientaException(
          'La herramienta no respondio. Revisa la direccion y que estes en la misma red.');
    } on http.ClientException catch (e) {
      throw HerramientaException(
          'No se pudo conectar con la herramienta: ${e.message}');
    } on FormatException {
      throw const HerramientaException(
          'La direccion de la herramienta no es valida.');
    }
    if (r.statusCode >= 200 && r.statusCode < 300) return r;
    var detalle = '';
    try {
      final c = jsonDecode(utf8.decode(r.bodyBytes));
      if (c is Map && c['mensaje'] != null) detalle = ': ${c['mensaje']}';
    } on FormatException {
      // cuerpo que no es JSON: se informa solo el codigo
    }
    if (r.statusCode == 401 && !conToken) {
      throw HerramientaException('El email o la contraseña no son correctos',
          estado: 401);
    }
    if (r.statusCode == 401) {
      if (tokenEnviado != null && tokenEnviado == _token)
        alSesionVencida?.call();
      throw const HerramientaException(
          'La sesion se vencio. Inicia sesion de nuevo.',
          estado: 401);
    }
    if (r.statusCode == 409 && !conToken) {
      throw const HerramientaException('Ese email ya está registrado',
          estado: 409);
    }
    throw HerramientaException(
        'La herramienta respondio ${r.statusCode}$detalle',
        estado: r.statusCode);
  }

  dynamic _json(http.Response r) => jsonDecode(utf8.decode(r.bodyBytes));

  void cerrar() => _http.close();
}
