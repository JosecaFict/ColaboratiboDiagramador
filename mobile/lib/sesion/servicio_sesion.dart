import 'dart:async';
import 'package:flutter/foundation.dart';
import '../visor/cliente_herramienta.dart';
import 'almacen_sesion.dart';
import 'sesion_guardada.dart';

enum EstadoSesion { inicial, sinSesion, activa, sinRed, errorAlmacen }

typedef FabricaClienteSesion = ClienteHerramienta Function(
    String direccion, String? token, void Function() alVencer);

class ServicioSesion extends ChangeNotifier {
  static const avisoVencida = 'Tu sesión venció: iniciá sesión de nuevo';
  final AlmacenSesion almacen;
  final FabricaClienteSesion _crear;
  EstadoSesion estado = EstadoSesion.inicial;
  bool ocupado = false;
  String? mensaje;
  SesionGuardada? _datos;
  ClienteHerramienta? _cliente;
  ClienteHerramienta? _enCurso;
  List<ProyectoResumen>? proyectosRestaurados;
  Timer? _vigilancia;
  bool _verificando = false;
  bool _cerrado = false;
  bool _borrarPendiente = false;
  int _generacion = 0;
  Future<void> _cola = Future.value();

  ServicioSesion({required this.almacen, FabricaClienteSesion? crearCliente})
      : _crear = crearCliente ??
            ((direccion, token, alVencer) => ClienteHerramienta(direccion,
                token: token, alSesionVencida: alVencer));
  SesionGuardada? get datos => _datos;
  ClienteHerramienta? get cliente => _cliente;
  bool get _activa => !_cerrado && estado == EstadoSesion.activa;
  void _avisar() {
    if (!_cerrado) notifyListeners();
  }

  bool _vigente(int g) => !_cerrado && g == _generacion;

  Future<void> _persistir(Future<void> Function() accion) {
    final siguiente = _cola.then((_) => accion());
    _cola = siguiente.catchError((Object _) {});
    return siguiente;
  }

  ClienteHerramienta _nuevo(String direccion, String? token, int g) {
    late ClienteHerramienta c;
    c = _crear(direccion, token, () {
      if (_vigente(g) && identical(_cliente, c) && _activa) unawaited(vencer());
    });
    return c;
  }

  Future<void> restaurar() async {
    if (_cerrado || ocupado) return;
    if (_borrarPendiente) {
      await _invalidar(mensaje);
      return;
    }
    ocupado = true;
    mensaje = null;
    estado = EstadoSesion.inicial;
    final g = ++_generacion;
    _vigilancia?.cancel();
    _cliente?.cerrar();
    _cliente = null;
    _datos = null;
    proyectosRestaurados = null;
    _avisar();
    try {
      final crudo = await almacen.leer();
      if (!_vigente(g)) return;
      if (crudo == null) {
        estado = EstadoSesion.sinSesion;
        return;
      }
      try {
        _datos = SesionGuardada.leer(crudo);
      } catch (_) {
        await _invalidar(
            'La sesión guardada no es válida. Iniciá sesión de nuevo.');
        return;
      }
      final s = _datos!;
      _cliente = _nuevo(s.direccion, s.token, g);
      await _validarRestauracion(g);
    } catch (_) {
      if (_vigente(g)) {
        estado = EstadoSesion.errorAlmacen;
        mensaje = 'No se pudo leer la sesión segura. Reintentá.';
      }
    } finally {
      if (_vigente(g)) {
        ocupado = false;
        _avisar();
      }
    }
  }

  Future<void> _validarRestauracion(int g) async {
    try {
      final proyectos = await _cliente!.proyectos();
      if (!_vigente(g)) return;
      proyectosRestaurados = proyectos;
      estado = EstadoSesion.activa;
      _vigilar();
    } on HerramientaException catch (e) {
      if (!_vigente(g)) return;
      if (e.estado == 401) {
        await _invalidar(avisoVencida);
      } else {
        estado = EstadoSesion.sinRed;
        mensaje = e.mensaje;
      }
    } catch (_) {
      if (_vigente(g)) {
        estado = EstadoSesion.sinRed;
        mensaje = 'No se pudo validar la sesión. Reintentá.';
      }
    }
  }

  Future<void> entrar(String direccion, String email, String clave) =>
      _acceder(direccion, email, clave);
  Future<void> registrar(String direccion, String nombre, String email,
          String clave, String repetida) =>
      _acceder(direccion, email, clave, nombre: nombre, repetida: repetida);

  Future<void> _acceder(String direccion, String email, String clave,
      {String? nombre, String? repetida}) async {
    if (_cerrado || ocupado || _borrarPendiente) return;
    final registro = nombre != null;
    final error = validarDireccion(direccion) ??
        validarEmail(email) ??
        validarClave(clave, registro: registro) ??
        (registro ? validarNombre(nombre) : null) ??
        (registro && clave != repetida
            ? 'Las contraseñas no coinciden.'
            : null);
    if (error != null) {
      mensaje = error;
      _avisar();
      return;
    }
    final g = ++_generacion;
    ocupado = true;
    mensaje = null;
    _avisar();
    final host = ClienteHerramienta.normalizarHost(direccion);
    final c = _nuevo(host, null, g);
    _enCurso = c;
    var adoptado = false;
    try {
      final s = registro
          ? await c.registrar(email, clave, nombre)
          : await c.iniciarSesion(email, clave);
      if (!_vigente(g)) return;
      final guardada = SesionGuardada.de(host, s);
      await _persistir(() => almacen.guardar(guardada.serializar()));
      if (!_vigente(g)) return;
      _cliente?.cerrar();
      _cliente = c;
      _datos = guardada;
      proyectosRestaurados = null;
      adoptado = true;
      estado = EstadoSesion.activa;
      _vigilar();
    } on HerramientaException catch (e) {
      if (_vigente(g)) {
        mensaje = e.mensaje;
        estado = EstadoSesion.sinSesion;
      }
    } catch (_) {
      if (_vigente(g)) {
        await _invalidar(
            'No se pudo guardar la sesión de forma segura. Volvé a entrar.');
      }
    } finally {
      if (identical(_enCurso, c)) _enCurso = null;
      if (!adoptado) c.cerrar();
      if (_vigente(g)) {
        ocupado = false;
        _avisar();
      }
    }
  }

  /// Vigila también tokens revocados mientras el editor usa solo WebSocket.
  /// Los fallos de red no borran credenciales. Un 403 no es un cierre de sesión.
  void _vigilar() {
    _vigilancia?.cancel();
    _vigilancia = Timer.periodic(
        const Duration(minutes: 1), (_) => unawaited(validarEnUso()));
  }

  Future<void> validarEnUso() async {
    if (!_activa || _verificando) return;
    _verificando = true;
    final c = _cliente;
    try {
      await c?.proyectos();
    } catch (_) {
      /* El 401 lo atiende el callback central. Sin red, conserva la sesión. */
    } finally {
      _verificando = false;
    }
  }

  Future<void> reintentar() => restaurar();
  Future<void> vencer() => _invalidar(avisoVencida);
  Future<void> cerrarSesion() => _invalidar(null, revocar: true);

  Future<void> _revocar(ClienteHerramienta c) async {
    try {
      await c.cerrarSesion();
    } catch (_) {/* Salir localmente siempre es posible. */} finally {
      c.cerrar();
    }
  }

  Future<void> _invalidar(String? aviso, {bool revocar = false}) async {
    if (_cerrado) return;
    final g = ++_generacion;
    final c = _cliente;
    _cliente = null;
    _datos = null;
    proyectosRestaurados = null;
    _vigilancia?.cancel();
    ocupado = true;
    mensaje = aviso;
    estado = EstadoSesion.inicial;
    final salida = revocar && c != null ? _revocar(c) : null;
    if (salida == null) c?.cerrar();
    _avisar();
    try {
      await _persistir(almacen.borrar);
      if (!_vigente(g)) return;
      _borrarPendiente = false;
      estado = EstadoSesion.sinSesion;
    } catch (_) {
      if (_vigente(g)) {
        _borrarPendiente = true;
        estado = EstadoSesion.errorAlmacen;
        mensaje =
            'No se pudo borrar la sesión segura. Reintentá antes de entrar.';
      }
    } finally {
      if (_vigente(g)) {
        ocupado = false;
        _avisar();
      }
    }
    if (salida != null) await salida;
  }

  @override
  void dispose() {
    _cerrado = true;
    _generacion++;
    _vigilancia?.cancel();
    _cliente?.cerrar();
    _enCurso?.cerrar();
    super.dispose();
  }
}
