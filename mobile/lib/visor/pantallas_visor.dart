import 'package:flutter/material.dart';

import '../editor/pantallas/entrada.dart';
import '../editor/pantallas/pantalla_editor.dart';
import 'almacen_local.dart';
import 'cliente_herramienta.dart';
import 'controlador_visor.dart';
import 'dibujo_clases.dart';

/// Paso 2: proyectos y, dentro de cada uno, sus diagramas.
class PantallaProyectos extends StatefulWidget {
  final ClienteHerramienta cliente;
  final AlmacenLocal almacen;
  final String nombre;
  final VoidCallback? alCerrarSesion;
  final List<ProyectoResumen>? proyectosIniciales;
  const PantallaProyectos(
      {super.key,
      required this.cliente,
      required this.almacen,
      required this.nombre,
      this.alCerrarSesion,
      this.proyectosIniciales});

  @override
  State<PantallaProyectos> createState() => _PantallaProyectosState();
}

class _PantallaProyectosState extends State<PantallaProyectos> {
  late Future<List<ProyectoResumen>> _proyectos =
      widget.proyectosIniciales == null
          ? widget.cliente.proyectos()
          : Future.value(widget.proyectosIniciales);

  void _recargar() {
    final siguiente = widget.cliente.proyectos();
    // Un 401 puede retirar esta ruta antes del próximo cuadro del FutureBuilder.
    siguiente.ignore();
    setState(() {
      _proyectos = siguiente;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Mis proyectos'), actions: [
        IconButton(
            tooltip: 'Actualizar proyectos',
            icon: const Icon(Icons.refresh),
            onPressed: _recargar),
        if (widget.alCerrarSesion != null)
          IconButton(
              tooltip: 'Cerrar sesión',
              icon: const Icon(Icons.logout),
              onPressed: widget.alCerrarSesion)
      ]),
      body: FutureBuilder<List<ProyectoResumen>>(
        future: _proyectos,
        builder: (context, s) {
          if (s.connectionState != ConnectionState.done)
            return const Center(child: CircularProgressIndicator());
          if (s.hasError)
            return _Falla(mensaje: '${s.error}', alReintentar: _recargar);
          final lista = s.data!;
          if (lista.isEmpty)
            return const Center(child: Text('Todavia no tenes proyectos.'));
          return ListView(
            children: [
              for (final p in lista)
                ListTile(
                  leading: const Icon(Icons.folder_outlined),
                  title: Text(p.nombre),
                  subtitle: Text(
                      '${p.cantidadDiagramas} diagramas · ${p.miRol ?? ''}'),
                  onTap: () =>
                      Navigator.of(context).push(MaterialPageRoute<void>(
                    builder: (_) => PantallaDiagramas(
                        cliente: widget.cliente,
                        almacen: widget.almacen,
                        proyecto: p,
                        nombreUsuario: widget.nombre,
                        alCerrarSesion: widget.alCerrarSesion),
                  )),
                ),
            ],
          );
        },
      ),
    );
  }
}

class PantallaDiagramas extends StatelessWidget {
  final ClienteHerramienta cliente;
  final AlmacenLocal almacen;
  final ProyectoResumen proyecto;

  /// Para el lock del editor («La está editando Ana»).
  final String nombreUsuario;
  final VoidCallback? alCerrarSesion;
  const PantallaDiagramas(
      {super.key,
      required this.cliente,
      required this.almacen,
      required this.proyecto,
      this.nombreUsuario = '',
      this.alCerrarSesion});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(proyecto.nombre)),
      body: FutureBuilder<List<DiagramaResumen>>(
        future: cliente.diagramasDe(proyecto.id),
        builder: (context, s) {
          if (s.connectionState != ConnectionState.done)
            return const Center(child: CircularProgressIndicator());
          if (s.hasError) return _Falla(mensaje: '${s.error}');
          final lista = s.data!;
          if (lista.isEmpty)
            return const Center(
                child: Text('Este proyecto no tiene diagramas.'));
          // Quien puede editar (Modelador o Propietario, con el editor instalado,
          // T-15) entra al editor con el toque normal: asi no cree que la app es
          // de solo lectura. El visor le queda en el ojo. El Observador va al visor.
          final edita =
              fabricaDeEditor != null && rolPuedeEditar(proyecto.miRol);
          Future<void> ver(DiagramaResumen d) async {
            await almacen.guardarUltimo(d.id, d.nombre);
            if (!context.mounted) return;
            await Navigator.of(context).push(MaterialPageRoute<void>(
              builder: (_) => PantallaVisor(
                  cliente: cliente,
                  almacen: almacen,
                  diagramId: d.id,
                  titulo: d.nombre),
            ));
          }

          Future<void> editar(DiagramaResumen d) =>
              Navigator.of(context).push(MaterialPageRoute<void>(
                builder: (_) => PantallaEditor(
                  titulo: d.nombre,
                  alCerrarSesion: alCerrarSesion,
                  editor: fabricaDeEditor!(
                    cliente: cliente,
                    diagramId: d.id,
                    rol: proyecto.miRol,
                    nombreUsuario: nombreUsuario,
                  ),
                ),
              ));

          return ListView(
            children: [
              for (final d in lista)
                ListTile(
                  leading: const Icon(Icons.account_tree_outlined),
                  title: Text(d.nombre),
                  subtitle: Text(edita
                      ? 'operacion ${d.currentSeq} · tocá para editar'
                      : 'operacion ${d.currentSeq} · solo lectura'),
                  trailing: edita
                      ? Row(mainAxisSize: MainAxisSize.min, children: [
                          IconButton(
                              tooltip: 'Ver sin editar',
                              icon: const Icon(Icons.visibility_outlined),
                              onPressed: () => ver(d)),
                          IconButton(
                              tooltip: 'Editar diagrama',
                              icon: const Icon(Icons.edit_outlined),
                              onPressed: () => editar(d)),
                        ])
                      : null,
                  onTap: () => edita ? editar(d) : ver(d),
                ),
            ],
          );
        },
      ),
    );
  }
}

/// Paso 3: el diagrama, en vivo y solo lectura.
class PantallaVisor extends StatefulWidget {
  final ClienteHerramienta cliente;
  final AlmacenLocal almacen;
  final String diagramId;
  final String titulo;
  const PantallaVisor({
    super.key,
    required this.cliente,
    required this.almacen,
    required this.diagramId,
    required this.titulo,
  });

  @override
  State<PantallaVisor> createState() => _PantallaVisorState();
}

class _PantallaVisorState extends State<PantallaVisor> {
  late final ControladorVisor _c = ControladorVisor(
    cliente: widget.cliente,
    almacen: widget.almacen,
    diagramId: widget.diagramId,
  );
  final _zoom = TransformationController();
  bool _encuadrado = false;

  @override
  void initState() {
    super.initState();
    _c.iniciar();
  }

  @override
  void dispose() {
    _c.dispose();
    _zoom.dispose();
    super.dispose();
  }

  static String _hora(DateTime t) =>
      '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';

  /// Ajusta el zoom la primera vez para que entre todo el diagrama.
  void _encuadrar(Size pantalla, Rect limites) {
    if (_encuadrado || pantalla.isEmpty) return;
    _encuadrado = true;
    final escala = (pantalla.width / limites.width).clamp(0.2, 1.0);
    _zoom.value = Matrix4.identity()
      ..scaleByDouble(escala, escala, 1, 1)
      ..translateByDouble(-limites.left, -limites.top, 0, 1);
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _c,
      builder: (context, _) {
        final modelo = _c.modelo;
        return Scaffold(
          appBar: AppBar(
            title: Text(widget.titulo),
            actions: [
              if (_c.estadoVisor == EstadoVisor.enVivo)
                const Padding(
                  padding: EdgeInsets.only(right: 12),
                  child: Chip(
                    avatar: Icon(Icons.circle, size: 10, color: Colors.green),
                    label: Text('En vivo'),
                    visualDensity: VisualDensity.compact,
                  ),
                ),
            ],
          ),
          body: Column(
            children: [
              if (_c.sinConexion) _Aviso(texto: _textoSinConexion()),
              if (_c.presentes.isNotEmpty)
                _Presentes(nombres: [for (final p in _c.presentes) p.userName]),
              Expanded(child: _cuerpo(context, modelo)),
            ],
          ),
        );
      },
    );
  }

  String _textoSinConexion() {
    final t = _c.ultimaActualizacion;
    return 'Sin conexion, ultima actualizacion ${t == null ? '—' : _hora(t)}';
  }

  Widget _cuerpo(BuildContext context, modelo) {
    if (modelo == null) {
      if (_c.estadoVisor == EstadoVisor.error) {
        return _Falla(mensaje: _c.mensaje ?? 'No se pudo abrir el diagrama.');
      }
      return const Center(child: CircularProgressIndicator());
    }
    if (modelo.classes.isEmpty) {
      return const Center(
          child: Text(
              'El diagrama esta vacio. Cuando alguien dibuje algo, aparece aca.'));
    }
    final limites = limitesDe(modelo);
    final seleccionados = <String>{
      for (final p in _c.presentes)
        if (p.elementoSeleccionado != null) p.elementoSeleccionado!,
    };
    return LayoutBuilder(
      builder: (context, caja) {
        _encuadrar(caja.biggest, limites);
        return InteractiveViewer(
          transformationController: _zoom,
          constrained: false,
          minScale: 0.1,
          maxScale: 4,
          boundaryMargin: const EdgeInsets.all(2000),
          child: SizedBox(
            width: limites.right,
            height: limites.bottom,
            child: CustomPaint(
              painter: DibujoClases(
                estado: modelo,
                bloqueos: _c.bloqueos,
                seleccionadosPorOtros: seleccionados,
                colores: Theme.of(context).colorScheme,
              ),
            ),
          ),
        );
      },
    );
  }
}

class _Aviso extends StatelessWidget {
  final String texto;
  const _Aviso({required this.texto});

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        color: Colors.amber.shade200,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        child: Row(children: [
          const Icon(Icons.cloud_off, size: 16),
          const SizedBox(width: 8),
          Expanded(child: Text(texto, style: const TextStyle(fontSize: 12))),
        ]),
      );
}

class _Presentes extends StatelessWidget {
  final List<String> nombres;
  const _Presentes({required this.nombres});

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        child: Wrap(spacing: 6, children: [
          const Icon(Icons.people_outline, size: 16),
          for (final n in nombres)
            Chip(label: Text(n), visualDensity: VisualDensity.compact),
        ]),
      );
}

class _Falla extends StatelessWidget {
  final String mensaje;
  final VoidCallback? alReintentar;
  const _Falla({required this.mensaje, this.alReintentar});

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Text(mensaje, textAlign: TextAlign.center),
            if (alReintentar != null) ...[
              const SizedBox(height: 12),
              OutlinedButton(
                  onPressed: alReintentar, child: const Text('Reintentar')),
            ],
          ]),
        ),
      );
}
