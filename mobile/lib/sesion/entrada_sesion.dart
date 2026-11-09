import 'dart:async';
import 'package:flutter/material.dart';
import '../visor/almacen_local.dart';
import '../visor/pantallas_visor.dart';
import 'almacen_sesion.dart';
import 'pantalla_acceso.dart';
import 'servicio_sesion.dart';

/// Frontera de navegación de P1: una sesión nueva nunca hereda rutas de la anterior.
class EntradaSesion extends StatefulWidget {
  final ServicioSesion? servicio;
  final AlmacenLocal? almacen;
  const EntradaSesion({super.key, this.servicio, this.almacen});
  @override
  State<EntradaSesion> createState() => _EntradaSesionState();
}

class _EntradaSesionState extends State<EntradaSesion>
    with WidgetsBindingObserver {
  late final _s =
      widget.servicio ?? ServicioSesion(almacen: AlmacenSesionSeguro());
  late final _almacen = widget.almacen ?? AlmacenLocal();
  final _navegacion = GlobalKey<NavigatorState>();
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_s.restaurar());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState estado) {
    if (estado == AppLifecycleState.resumed) unawaited(_s.validarEnUso());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    if (widget.servicio == null) _s.dispose();
    super.dispose();
  }

  Future<void> _cerrarSesion() async {
    final overlay = _navegacion.currentState?.overlay;
    if (overlay == null || _s.ocupado) return;
    final salir = await showDialog<bool>(
        context: overlay.context,
        useRootNavigator: false,
        builder: (context) => AlertDialog(
                title: const Text('¿Cerrar sesión?'),
                content: const Text(
                    'Vas a volver a la pantalla de inicio de sesión.'),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.of(context).pop(false),
                      child: const Text('Cancelar')),
                  FilledButton(
                      onPressed: () => Navigator.of(context).pop(true),
                      child: const Text('Cerrar sesión'))
                ]));
    if (mounted && salir == true) await _s.cerrarSesion();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
      listenable: _s,
      builder: (context, _) {
        if (_s.estado == EstadoSesion.activa) {
          return NavigatorPopHandler(
              onPopWithResult: (_) => _navegacion.currentState!.pop(),
              child: Navigator(
                  key: _navegacion,
                  onGenerateRoute: (_) => MaterialPageRoute<void>(
                      builder: (_) => PantallaProyectos(
                          cliente: _s.cliente!,
                          almacen: _almacen,
                          nombre: _s.datos!.fullName,
                          proyectosIniciales: _s.proyectosRestaurados,
                          alCerrarSesion: () => unawaited(_cerrarSesion())))));
        }
        if (_s.estado == EstadoSesion.sinSesion)
          return PantallaAcceso(servicio: _s, almacen: _almacen);
        final espera = _s.ocupado || _s.estado == EstadoSesion.inicial;
        return Scaffold(
            appBar: AppBar(title: const Text('Diagramas en vivo')),
            body: Center(
                child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(mainAxisSize: MainAxisSize.min, children: [
                      if (espera)
                        const CircularProgressIndicator()
                      else ...[
                        Text(
                            _s.mensaje ??
                                'No se pudo validar la sesión. Revisá la conexión.',
                            textAlign: TextAlign.center),
                        const SizedBox(height: 16),
                        FilledButton(
                            onPressed: () => unawaited(_s.reintentar()),
                            child: const Text('Reintentar')),
                        if (_s.estado == EstadoSesion.sinRed)
                          TextButton(
                              onPressed: () => unawaited(_s.cerrarSesion()),
                              child: const Text('Usar otra cuenta')),
                      ],
                    ]))));
      });
}
