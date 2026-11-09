import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import 'modelo.dart';

/// Ultimo snapshot de cada diagrama, guardado en el telefono para poder
/// mostrarlo sin red. Solo se guarda el diagrama (nunca la contrasena ni el
/// token): la sesión se guarda aparte en el almacenamiento seguro del sistema.
class AlmacenLocal {
  static const _prefijo = 'visor.snapshot.';
  static const _claveHost = 'visor.host';
  static const _claveEmail = 'visor.email';
  static const _claveUltimo = 'visor.ultimo';

  Future<SharedPreferences> get _prefs => SharedPreferences.getInstance();

  Future<void> guardarSnapshot(SnapshotDiagrama s, DateTime cuando, {int? seqActual}) async {
    final crudo = {...s.crudo};
    if (seqActual != null) crudo['currentSeq'] = seqActual;
    await (await _prefs).setString(
      '$_prefijo${s.id}',
      jsonEncode({'cuando': cuando.toIso8601String(), 'snapshot': crudo}),
    );
  }

  Future<({SnapshotDiagrama snapshot, DateTime cuando})?> leerSnapshot(String diagramId) async {
    final texto = (await _prefs).getString('$_prefijo$diagramId');
    if (texto == null) return null;
    try {
      final j = jsonDecode(texto) as Map;
      return (
        snapshot: SnapshotDiagrama(crudo: Map<String, dynamic>.from(j['snapshot'] as Map)),
        cuando: DateTime.parse(j['cuando'] as String),
      );
    } on Object {
      return null; // dato viejo o corrupto: es como no tener nada
    }
  }

  Future<void> guardarConexion(String host, String email) async {
    final p = await _prefs;
    await p.setString(_claveHost, host);
    await p.setString(_claveEmail, email);
  }

  Future<({String host, String email})> leerConexion() async {
    final p = await _prefs;
    return (host: p.getString(_claveHost) ?? '', email: p.getString(_claveEmail) ?? '');
  }

  /// El ultimo diagrama que se abrio, para reabrirlo sin red.
  Future<void> guardarUltimo(String diagramId, String nombre) async =>
      (await _prefs).setString(_claveUltimo, jsonEncode({'id': diagramId, 'nombre': nombre}));

  Future<({String id, String nombre})?> leerUltimo() async {
    final t = (await _prefs).getString(_claveUltimo);
    if (t == null) return null;
    try {
      final j = jsonDecode(t) as Map;
      return (id: j['id'] as String, nombre: (j['nombre'] ?? '') as String);
    } on Object {
      return null;
    }
  }
}
