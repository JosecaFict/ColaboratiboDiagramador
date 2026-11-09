import 'package:flutter_secure_storage/flutter_secure_storage.dart';

abstract interface class AlmacenSesion {
  Future<String?> leer();
  Future<void> guardar(String contenido);
  Future<void> borrar();
}

/// Una sola entrada cifrada: evita sesiones mezcladas por escrituras parciales.
class AlmacenSesionSeguro implements AlmacenSesion {
  static const clave = 'colabmodel.sesion.v1';
  final FlutterSecureStorage storage;
  AlmacenSesionSeguro({FlutterSecureStorage? storage})
      : storage = storage ?? const FlutterSecureStorage();
  @override
  Future<String?> leer() => storage.read(key: clave);
  @override
  Future<void> guardar(String contenido) =>
      storage.write(key: clave, value: contenido);
  @override
  Future<void> borrar() => storage.delete(key: clave);
}
