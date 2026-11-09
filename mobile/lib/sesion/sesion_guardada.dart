import 'dart:convert';
import '../visor/cliente_herramienta.dart';

/// Única credencial persistida: el token. No contiene contraseña.
class SesionGuardada {
  final String direccion;
  final String token;
  final String userId;
  final String fullName;
  const SesionGuardada(
      {required this.direccion,
      required this.token,
      required this.userId,
      required this.fullName});

  factory SesionGuardada.de(String direccion, Sesion sesion) => SesionGuardada(
      direccion: direccion,
      token: sesion.token,
      userId: sesion.userId,
      fullName: sesion.nombre);

  String serializar() => jsonEncode({
        'direccion': direccion,
        'token': token,
        'userId': userId,
        'fullName': fullName
      });

  factory SesionGuardada.leer(String texto) {
    final j = jsonDecode(texto) as Map;
    final s = SesionGuardada(
        direccion: j['direccion'] as String,
        token: j['token'] as String,
        userId: j['userId'] as String,
        fullName: j['fullName'] as String);
    if (s.token.isEmpty ||
        s.userId.isEmpty ||
        validarDireccion(s.direccion) != null) {
      throw const FormatException('Sesión guardada inválida');
    }
    return s;
  }
}

String? validarDireccion(String texto) {
  if (texto.contains('://') &&
      !texto.trim().startsWith('http://') &&
      !texto.trim().startsWith('https://'))
    return 'Usá una dirección http o https.';
  final u = Uri.tryParse(ClienteHerramienta.normalizarHost(texto));
  if (u == null ||
      !['http', 'https'].contains(u.scheme) ||
      u.host.isEmpty ||
      u.userInfo.isNotEmpty ||
      u.hasQuery ||
      u.hasFragment) {
    return 'Ingresá la dirección de la herramienta (IP o URL http/https).';
  }
  return null;
}

String? validarEmail(String texto) =>
    RegExp(r'^[^\s@]+@[^\s@]+$').hasMatch(texto.trim())
        ? null
        : 'Ingresá un correo válido.';
String? validarNombre(String texto) => texto.trim().isEmpty
    ? 'Ingresá tu nombre.'
    : texto.trim().length > 160
        ? 'El nombre no puede pasar de 160 caracteres.'
        : null;
String? validarClave(String texto, {bool registro = false}) =>
    texto.trim().isEmpty
        ? 'Ingresá la contraseña.'
        : registro && texto.length < 8
            ? 'La contraseña debe tener al menos 8 caracteres.'
            : null;
