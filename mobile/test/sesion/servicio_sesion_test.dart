import 'dart:async';
import 'dart:convert';
import 'package:colabmodel_app/sesion/almacen_sesion.dart';
import 'package:colabmodel_app/sesion/servicio_sesion.dart';
import 'package:colabmodel_app/sesion/sesion_guardada.dart';
import 'package:colabmodel_app/visor/cliente_herramienta.dart';
import 'package:fake_async/fake_async.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

class MemoriaSesion implements AlmacenSesion {
  String? contenido;
  int escrituras = 0, borrados = 0;
  bool fallaLectura = false, fallaEscritura = false, fallaBorrado = false;
  Completer<void>? pausa;
  @override
  Future<String?> leer() async {
    if (fallaLectura) throw StateError('almacén');
    return contenido;
  }

  @override
  Future<void> guardar(String valor) async {
    escrituras++;
    if (fallaEscritura) throw StateError('almacén');
    if (pausa != null) await pausa!.future;
    contenido = valor;
  }

  @override
  Future<void> borrar() async {
    if (fallaBorrado) throw StateError('almacén');
    borrados++;
    contenido = null;
  }
}

http.Response respuesta(Object cuerpo, [int estado = 200]) =>
    http.Response(jsonEncode(cuerpo), estado,
        headers: {'content-type': 'application/json; charset=utf-8'});
const datos = SesionGuardada(
    direccion: 'http://h:8080',
    token: 'JWT',
    userId: 'u1',
    fullName: 'Ana Pérez');
Map<String, dynamic> token([String t = 'JWT']) =>
    {'token': t, 'userId': 'u1', 'fullName': 'Ana Pérez'};
ServicioSesion servicio(MemoriaSesion memoria,
        Future<http.Response> Function(http.Request) responder) =>
    ServicioSesion(
        almacen: memoria,
        crearCliente: (host, token, alVencer) => ClienteHerramienta(host,
            token: token,
            alSesionVencida: alVencer,
            cliente: MockClient(responder)));
Future<void> bombear() async {
  for (var i = 0; i < 5; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
      'entra y persiste dirección, token, identidad y nombre; jamás contraseña',
      () async {
    final m = MemoriaSesion();
    final s = servicio(m, (r) async {
      expect(r.url.toString(), 'http://h:8080/api/v1/auth/login');
      expect(r.headers['Authorization'], isNull);
      expect(jsonDecode(r.body),
          {'email': 'ana@ejemplo.com', 'password': 'secreto123'});
      return respuesta(token());
    });
    addTearDown(s.dispose);
    await s.entrar(' h:8080/ ', ' ana@ejemplo.com ', 'secreto123');
    expect(s.estado, EstadoSesion.activa);
    expect(s.cliente!.token, 'JWT');
    expect(
        jsonDecode(m.contenido!), {'direccion': 'http://h:8080', ...token()});
    expect(m.contenido, isNot(contains('secreto123')));
    expect(m.escrituras, 1);
  });
  test('registro usa el endpoint y entra directo con fullName de la cuenta',
      () async {
    final m = MemoriaSesion();
    final s = servicio(m, (r) async {
      expect(r.url.path, '/api/v1/auth/register');
      expect(jsonDecode(r.body),
          {'email': 'a@b.com', 'password': 'secreto123', 'fullName': 'Ana'});
      return respuesta(token());
    });
    addTearDown(s.dispose);
    await s.registrar('h:8080', ' Ana ', 'a@b.com', 'secreto123', 'secreto123');
    expect(s.estado, EstadoSesion.activa);
    expect(s.datos!.fullName, 'Ana Pérez');
  });
  test(
      'restaurar valida el token con proyectos y conserva el nombre para el lock',
      () async {
    final m = MemoriaSesion()..contenido = datos.serializar();
    final s = servicio(m, (r) async {
      expect(r.url.path, '/api/v1/projects');
      expect(r.headers['Authorization'], 'Bearer JWT');
      return respuesta([
        {'id': 'p', 'name': 'P', 'myRole': 'EDITOR', 'diagramCount': 1}
      ]);
    });
    addTearDown(s.dispose);
    await s.restaurar();
    expect(s.estado, EstadoSesion.activa);
    expect(s.proyectosRestaurados!.single.miRol, 'EDITOR');
    expect(s.datos!.fullName, 'Ana Pérez');
  });
  test('sin sesión guardada muestra login sin tocar la red', () async {
    final s = servicio(
        MemoriaSesion(), (_) async => throw StateError('no debe llamar'));
    addTearDown(s.dispose);
    await s.restaurar();
    expect(s.estado, EstadoSesion.sinSesion);
    expect(s.cliente, isNull);
  });
  test('401 al restaurar borra sesión y avisa vencimiento', () async {
    final m = MemoriaSesion()..contenido = datos.serializar();
    final s = servicio(m, (_) async => respuesta({}, 401));
    addTearDown(s.dispose);
    await s.restaurar();
    expect(s.estado, EstadoSesion.sinSesion);
    expect(s.mensaje, ServicioSesion.avisoVencida);
    expect(m.contenido, isNull);
    expect(s.datos, isNull);
  });
  test('sin red conserva la sesión segura y reintentar la restaura', () async {
    var sinRed = true;
    final m = MemoriaSesion()..contenido = datos.serializar();
    final s = servicio(m, (_) async {
      if (sinRed) throw http.ClientException('sin ruta');
      return respuesta([]);
    });
    addTearDown(s.dispose);
    await s.restaurar();
    expect(s.estado, EstadoSesion.sinRed);
    expect(m.contenido, datos.serializar());
    expect(m.borrados, 0);
    sinRed = false;
    await s.reintentar();
    expect(s.estado, EstadoSesion.activa);
  });
  test('403 de permisos no borra el token ni se confunde con sesión vencida',
      () async {
    final m = MemoriaSesion()..contenido = datos.serializar();
    final s = servicio(m, (_) async => respuesta({}, 403));
    addTearDown(s.dispose);
    await s.restaurar();
    expect(s.estado, EstadoSesion.sinRed);
    expect(m.borrados, 0);
  });
  for (final codigo in [400, 401, 409]) {
    test('error $codigo de autenticación es legible y no persiste credenciales',
        () async {
      final m = MemoriaSesion();
      final s = servicio(
          m, (_) async => respuesta({'mensaje': 'Revisá los datos'}, codigo));
      addTearDown(s.dispose);
      await s.registrar('h:8080', 'Ana', 'a@b.com', 'secreto123', 'secreto123');
      expect(s.estado, EstadoSesion.sinSesion);
      expect(m.contenido, isNull);
      expect(m.borrados, 0);
      expect(
          s.mensaje,
          codigo == 409
              ? 'Ese email ya está registrado'
              : codigo == 401
                  ? contains('no son correctos')
                  : 'La herramienta respondio 400: Revisá los datos');
    });
  }
  test('validaciones de registro no llaman la red', () async {
    var llamadas = 0;
    final s = servicio(MemoriaSesion(), (_) async {
      llamadas++;
      return respuesta(token());
    });
    addTearDown(s.dispose);
    await s.registrar('', 'Ana', 'a@b.com', 'secreto123', 'secreto123');
    await s.registrar('h', '', 'a@b.com', 'secreto123', 'secreto123');
    await s.registrar('h', 'Ana', 'mal', 'secreto123', 'secreto123');
    await s.registrar('h', 'Ana', 'a@b.com', 'corta', 'corta');
    await s.registrar('h', 'Ana', 'a@b.com', 'secreto123', 'otra');
    expect(llamadas, 0);
    expect(validarNombre('x' * 161), isNotNull);
    expect(validarDireccion('https://usuario:clave@h'), isNotNull);
  });
  test(
      'cerrar sesión revoca por REST, borra local y no cierra con credenciales guardadas',
      () async {
    final m = MemoriaSesion()..contenido = datos.serializar();
    var revocaciones = 0;
    final s = servicio(m, (r) async {
      if (r.url.path.endsWith('/logout')) {
        revocaciones++;
        expect(r.method, 'POST');
        expect(r.headers['Authorization'], 'Bearer JWT');
        return http.Response('', 204);
      }
      return respuesta([]);
    });
    addTearDown(s.dispose);
    await s.restaurar();
    final c = s.cliente!;
    await s.cerrarSesion();
    expect(revocaciones, 1);
    expect(m.contenido, isNull);
    expect(c.token, isNull);
    expect(s.estado, EstadoSesion.sinSesion);
    expect(s.datos, isNull);
  });
  test('si logout no llega al servidor, igual borra la sesión local', () async {
    final m = MemoriaSesion()..contenido = datos.serializar();
    final s = servicio(m, (r) async {
      if (r.url.path.endsWith('/logout')) throw http.ClientException('sin red');
      return respuesta([]);
    });
    addTearDown(s.dispose);
    await s.restaurar();
    await s.cerrarSesion();
    expect(s.estado, EstadoSesion.sinSesion);
    expect(m.contenido, isNull);
  });
  test('un 401 de snapshot en uso dispara el mismo cierre central', () async {
    final m = MemoriaSesion()..contenido = datos.serializar();
    final s = servicio(
        m,
        (r) async =>
            respuesta([], r.url.path.endsWith('/projects') ? 200 : 401));
    addTearDown(s.dispose);
    await s.restaurar();
    await expectLater(
        s.cliente!.snapshot('d'), throwsA(isA<HerramientaException>()));
    await bombear();
    expect(s.estado, EstadoSesion.sinSesion);
    expect(s.mensaje, ServicioSesion.avisoVencida);
    expect(m.contenido, isNull);
  });
  test('vigila token revocado durante WebSocket sin borrar por fallos de red',
      () {
    fakeAsync((reloj) {
      final m = MemoriaSesion()..contenido = datos.serializar();
      var lecturas = 0;
      final s =
          servicio(m, (_) async => respuesta([], ++lecturas == 1 ? 200 : 401));
      s.restaurar();
      reloj.flushMicrotasks();
      expect(s.estado, EstadoSesion.activa);
      reloj.elapse(const Duration(minutes: 1));
      reloj.flushMicrotasks();
      expect(s.estado, EstadoSesion.sinSesion);
      expect(m.contenido, isNull);
      s.dispose();
    });
  });
  test('sesión corrupta se limpia y un fallo de lectura permite reintentar',
      () async {
    final m = MemoriaSesion()..contenido = 'no es JSON';
    final s = servicio(m, (_) async => respuesta([]));
    addTearDown(s.dispose);
    await s.restaurar();
    expect(s.estado, EstadoSesion.sinSesion);
    expect(m.contenido, isNull);
    m.fallaLectura = true;
    await s.restaurar();
    expect(s.estado, EstadoSesion.errorAlmacen);
    m.fallaLectura = false;
    await s.reintentar();
    expect(s.estado, EstadoSesion.sinSesion);
  });
  test(
      'si el sistema no puede guardar el token, no entra con sesión solo en memoria',
      () async {
    final m = MemoriaSesion()..fallaEscritura = true;
    final s = servicio(m, (_) async => respuesta(token()));
    addTearDown(s.dispose);
    await s.entrar('h:8080', 'a@b.com', 'secreto123');
    expect(s.estado, EstadoSesion.sinSesion);
    expect(s.cliente, isNull);
    expect(s.mensaje, contains('segura'));
  });
  test(
      'un fallo borrando no permite restaurar el token hasta reintentar la limpieza',
      () async {
    final m = MemoriaSesion()..contenido = datos.serializar();
    final s = servicio(m, (_) async => respuesta([]));
    addTearDown(s.dispose);
    await s.restaurar();
    m.fallaBorrado = true;
    await s.cerrarSesion();
    expect(s.estado, EstadoSesion.errorAlmacen);
    expect(s.datos, isNull);
    m.fallaBorrado = false;
    await s.reintentar();
    expect(m.contenido, isNull);
    expect(s.estado, EstadoSesion.sinSesion);
  });
  test('no duplica solicitudes de entrar durante el envío', () async {
    final pendiente = Completer<http.Response>();
    var llamadas = 0;
    final s = servicio(MemoriaSesion(), (_) async {
      llamadas++;
      return pendiente.future;
    });
    addTearDown(s.dispose);
    final inicio = s.entrar('h', 'a@b.com', 'secreto123');
    await s.entrar('h', 'a@b.com', 'secreto123');
    await bombear();
    expect(llamadas, 1);
    pendiente.complete(respuesta(token()));
    await inicio;
  });
  test('salir durante una escritura segura espera su turno y borra después',
      () async {
    final m = MemoriaSesion()..pausa = Completer<void>();
    final s = servicio(m, (_) async => respuesta(token()));
    addTearDown(s.dispose);
    final inicio = s.entrar('h', 'a@b.com', 'secreto123');
    await bombear();
    final salida = s.cerrarSesion();
    m.pausa!.complete();
    await inicio;
    await salida;
    expect(m.contenido, isNull);
    expect(s.estado, EstadoSesion.sinSesion);
  });
  test(
      'almacén seguro guarda y elimina solo su entrada, usando un doble de plataforma',
      () async {
    FlutterSecureStorage.setMockInitialValues({'otro': 'conservar'});
    final a = AlmacenSesionSeguro();
    await a.guardar(datos.serializar());
    expect(await a.leer(), datos.serializar());
    await a.borrar();
    expect(await a.leer(), isNull);
    expect(await const FlutterSecureStorage().read(key: 'otro'), 'conservar');
  });
}
