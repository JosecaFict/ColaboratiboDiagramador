import 'dart:convert';

import 'package:http/http.dart' as http;

import '../domain/app_manifest.dart';

/// Habla con el backend GENERADO (P2), no con la herramienta (P1).
///
/// Es la unica clase de la app que sabe de red, y no sabe de ningun dominio:
/// las rutas salen del manifiesto. Cambiar de biblioteca a veterinaria no toca
/// una linea de aca (EC-10).
class ClienteBackend {
  /// Host del backend generado, tal como lo escribio el usuario.
  final String host;
  final http.Client _http;

  ClienteBackend(String host, {http.Client? cliente})
      : host = _normalizarHost(host),
        _http = cliente ?? http.Client();

  /// Acepta "192.168.0.10:8081" y lo completa; saca la barra final.
  static String _normalizarHost(String crudo) {
    var h = crudo.trim();
    if (h.isEmpty) return h;
    if (!h.startsWith('http://') && !h.startsWith('https://')) h = 'http://$h';
    while (h.endsWith('/')) {
      h = h.substring(0, h.length - 1);
    }
    return h;
  }

  /// El contrato P2 -> P3 (ADR-011). Es lo primero que hace la app.
  Future<AppManifest> cargarManifiesto() async {
    final r = await _http.get(Uri.parse('$host/app-manifest.json')).timeout(const Duration(seconds: 10));
    if (r.statusCode != 200) {
      throw BackendException('El servidor respondio ${r.statusCode} al pedir app-manifest.json. '
          'Revisa que sea un backend generado por ColabModel y que este arriba.');
    }
    try {
      return AppManifest.fromJson(jsonDecode(utf8.decode(r.bodyBytes)) as Map<String, dynamic>);
    } on FormatException catch (e) {
      throw BackendException('El manifiesto no se pudo leer: ${e.message}');
    }
  }

  /// Resuelve la ruta del manifiesto contra el host.
  ///
  /// El manifiesto NO trae host, y esta bien que no lo traiga: para un
  /// telefono, cualquier direccion escrita ahi --y `localhost` la primera--
  /// esta mal, y queda vieja apenas cambia la red del aula. El unico host que
  /// el telefono sabe alcanzar es aquel del que se bajo el manifiesto.
  Uri _uri(AppManifest manifiesto, EntityDescriptor entidad, [String extra = '']) {
    return Uri.parse('$host${entidad.path}$extra');
  }

  Future<List<Map<String, dynamic>>> listar(AppManifest manifiesto, EntityDescriptor entidad) async {
    final r = await _http.get(_uri(manifiesto, entidad)).timeout(const Duration(seconds: 10));
    if (r.statusCode != 200) {
      throw BackendException('No se pudo listar ${entidad.label}: HTTP ${r.statusCode}');
    }
    final cuerpo = jsonDecode(utf8.decode(r.bodyBytes));
    if (cuerpo is! List) throw BackendException('La API no devolvio una lista');
    return cuerpo.cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> crear(
      AppManifest manifiesto, EntityDescriptor entidad, Map<String, dynamic> datos) async {
    final r = await _http
        .post(_uri(manifiesto, entidad),
            headers: {'Content-Type': 'application/json; charset=utf-8'}, body: utf8.encode(jsonEncode(datos)))
        .timeout(const Duration(seconds: 15));
    if (r.statusCode < 200 || r.statusCode >= 300) {
      // El cuerpo del error del backend generado es util: dice que campo
      // fallo la validacion. Se lo pasa tal cual en vez de esconderlo.
      throw BackendException('La API rechazo el alta (HTTP ${r.statusCode}). ${_motivo(r)}');
    }
    final cuerpo = jsonDecode(utf8.decode(r.bodyBytes));
    return cuerpo is Map<String, dynamic> ? cuerpo : <String, dynamic>{};
  }

  /// Cambio parcial (PATCH): solo los campos que cambian. Si `cambios` trae la
  /// `version` que se leyo, el backend generado devuelve 409 cuando otro
  /// escribio en el medio (bloqueo optimista), en vez de pisarle el cambio.
  Future<Map<String, dynamic>> actualizar(
      AppManifest manifiesto, EntityDescriptor entidad, String id, Map<String, dynamic> cambios) async {
    final r = await _http
        .patch(_uri(manifiesto, entidad, '/$id'),
            headers: {'Content-Type': 'application/json; charset=utf-8'}, body: utf8.encode(jsonEncode(cambios)))
        .timeout(const Duration(seconds: 15));
    if (r.statusCode == 404) throw BackendException(_yaNoExiste(entidad));
    if (r.statusCode == 409) {
      throw BackendException('Alguien cambió ese ${entidad.label.toLowerCase()} mientras tanto: no pisé su cambio. '
          'Pedímelo de nuevo para trabajar sobre la versión actual.');
    }
    if (r.statusCode < 200 || r.statusCode >= 300) {
      throw BackendException('La API rechazó el cambio (HTTP ${r.statusCode}). ${_motivo(r)}');
    }
    final cuerpo = jsonDecode(utf8.decode(r.bodyBytes));
    return cuerpo is Map<String, dynamic> ? cuerpo : <String, dynamic>{};
  }

  Future<void> eliminar(AppManifest manifiesto, EntityDescriptor entidad, String id) async {
    final r = await _http.delete(_uri(manifiesto, entidad, '/$id')).timeout(const Duration(seconds: 10));
    if (r.statusCode == 404) throw BackendException(_yaNoExiste(entidad));
    if (r.statusCode < 200 || r.statusCode >= 300) {
      // Un 409 al borrar es otra fila que lo usa (una FK): el backend lo explica.
      throw BackendException('No se pudo borrar (HTTP ${r.statusCode}). ${_motivo(r)}');
    }
  }

  /// El motivo que da el backend generado (`{"error": ...}` o `{"message": ...}`),
  /// no el JSON crudo. Si no es JSON, el cuerpo tal cual.
  static String _motivo(http.Response r) {
    final cuerpo = utf8.decode(r.bodyBytes);
    try {
      final j = jsonDecode(cuerpo);
      if (j is Map) {
        final m = j['error'] ?? j['message'] ?? j['mensaje'];
        if (m is String && m.isNotEmpty) return m;
      }
    } on FormatException {
      // no es JSON
    }
    return cuerpo;
  }

  static String _yaNoExiste(EntityDescriptor entidad) =>
      'Ese ${entidad.label.toLowerCase()} ya no existe: quizás alguien lo borró recién.';

  void cerrar() => _http.close();
}

class BackendException implements Exception {
  final String mensaje;
  BackendException(this.mensaje);

  @override
  String toString() => mensaje;
}
