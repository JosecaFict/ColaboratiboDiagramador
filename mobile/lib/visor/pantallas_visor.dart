import 'package:flutter/material.dart';

import '../editor/pantallas/entrada.dart';
import '../editor/pantallas/pantalla_editor.dart';
import 'almacen_local.dart';
import 'cliente_herramienta.dart';
import 'controlador_visor.dart';
import 'dibujo_clases.dart';

/// Paso 1: direccion de la herramienta + login (`POST /api/v1/auth/login`).
class PantallaLoginVisor extends StatefulWidget {
  const PantallaLoginVisor({super.key});

  @override
  State<PantallaLoginVisor> createState() => _PantallaLoginVisorState();
}

class _PantallaLoginVisorState extends State<PantallaLoginVisor> {
  final _almacen = AlmacenLocal();
  final _host = TextEditingController();
  final _email = TextEditingController();
  final _clave = TextEditingController();
  bool _entrando = false;
  String? _error;
  ({String id, String nombre})? _ultimo;

  @override
  void initState() {
    super.initState();
    _recordar();
  }

  Future<void> _recordar() async {
    final c = await _almacen.leerConexion();
    final u = await _almacen.leerUltimo();
    if (!mounted) return;
    setState(() {
      _host.text = c.host;
      _email.text = c.email;
      _ultimo = u;
    });
  }

  Future<void> _entrar() async {
    setState(() {
      _entrando = true;
      _error = null;
    });
    final cliente = ClienteHerramienta(_host.text);
    try {
      final sesion = await cliente.iniciarSesion(_email.text, _clave.text);
      await _almacen.guardarConexion(_host.text.trim(), _email.text.trim());
      if (!mounted) return;
      await Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => PantallaProyectos(cliente: cliente, almacen: _almacen, nombre: sesion.nombre),
      ));
    } on HerramientaException catch (e) {
      if (mounted) setState(() => _error = e.mensaje);
    } finally {
      if (mounted) setState(() => _entrando = false);
    }
  }

  void _abrirUltimoSinRed() {
    final u = _ultimo!;
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => PantallaVisor(
        cliente: ClienteHerramienta(_host.text), // sin token: solo lo guardado
        almacen: _almacen,
        diagramId: u.id,
        titulo: u.nombre,
      ),
    ));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Diagramas en vivo')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const Text('Entra con tu cuenta de la herramienta. El visor solo mira: no modifica nada.'),
          const SizedBox(height: 16),
          TextField(
            controller: _host,
            keyboardType: TextInputType.url,
            decoration: const InputDecoration(
              labelText: 'Direccion de la herramienta',
              hintText: '192.168.0.10:8080',
              helperText: 'La IP de la maquina, no localhost',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _email,
            keyboardType: TextInputType.emailAddress,
            decoration: const InputDecoration(labelText: 'Email', border: OutlineInputBorder()),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _clave,
            obscureText: true,
            onSubmitted: (_) => _entrando ? null : _entrar(),
            decoration: const InputDecoration(labelText: 'Contrasena', border: OutlineInputBorder()),
          ),
          if (_error != null) ...[
            const SizedBox(height: 12),
            Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
          const SizedBox(height: 16),
          FilledButton(
            onPressed: _entrando ? null : _entrar,
            child: _entrando
                ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Entrar'),
          ),
          if (_ultimo != null) ...[
            const SizedBox(height: 8),
            OutlinedButton.icon(
              onPressed: _abrirUltimoSinRed,
              icon: const Icon(Icons.cloud_off),
              label: Text('Ver "${_ultimo!.nombre}" guardado (sin conexion)'),
            ),
          ],
        ],
      ),
    );
  }
}

/// Paso 2: proyectos y, dentro de cada uno, sus diagramas.
class PantallaProyectos extends StatefulWidget {
  final ClienteHerramienta cliente;
  final AlmacenLocal almacen;
  final String nombre;
  const PantallaProyectos({super.key, required this.cliente, required this.almacen, required this.nombre});

  @override
  State<PantallaProyectos> createState() => _PantallaProyectosState();
}

class _PantallaProyectosState extends State<PantallaProyectos> {
  late Future<List<ProyectoResumen>> _proyectos = widget.cliente.proyectos();

  void _recargar() => setState(() => _proyectos = widget.cliente.proyectos());

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('Proyectos de ${widget.nombre}')),
      body: FutureBuilder<List<ProyectoResumen>>(
        future: _proyectos,
        builder: (context, s) {
          if (s.connectionState != ConnectionState.done) return const Center(child: CircularProgressIndicator());
          if (s.hasError) return _Falla(mensaje: '${s.error}', alReintentar: _recargar);
          final lista = s.data!;
          if (lista.isEmpty) return const Center(child: Text('Todavia no tenes proyectos.'));
          return ListView(
            children: [
              for (final p in lista)
                ListTile(
                  leading: const Icon(Icons.folder_outlined),
                  title: Text(p.nombre),
                  subtitle: Text('${p.cantidadDiagramas} diagramas · ${p.miRol ?? ''}'),
                  onTap: () => Navigator.of(context).push(MaterialPageRoute<void>(
                    builder: (_) => PantallaDiagramas(
                        cliente: widget.cliente, almacen: widget.almacen, proyecto: p, nombreUsuario: widget.nombre),
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
  const PantallaDiagramas(
      {super.key, required this.cliente, required this.almacen, required this.proyecto, this.nombreUsuario = ''});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(proyecto.nombre)),
      body: FutureBuilder<List<DiagramaResumen>>(
        future: cliente.diagramasDe(proyecto.id),
        builder: (context, s) {
          if (s.connectionState != ConnectionState.done) return const Center(child: CircularProgressIndicator());
          if (s.hasError) return _Falla(mensaje: '${s.error}');
          final lista = s.data!;
          if (lista.isEmpty) return const Center(child: Text('Este proyecto no tiene diagramas.'));
          return ListView(
            children: [
              for (final d in lista)
                ListTile(
                  leading: const Icon(Icons.account_tree_outlined),
                  title: Text(d.nombre),
                  subtitle: Text('operacion ${d.currentSeq}'),
                  // Editar: solo Modelador o Propietario, y solo si el editor esta instalado (T-15).
                  trailing: fabricaDeEditor != null && rolPuedeEditar(proyecto.miRol)
                      ? IconButton(
                          tooltip: 'Editar diagrama',
                          icon: const Icon(Icons.edit_outlined),
                          onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
                            builder: (_) => PantallaEditor(
                              titulo: d.nombre,
                              editor: fabricaDeEditor!(
                                cliente: cliente,
                                diagramId: d.id,
                                rol: proyecto.miRol,
                                nombreUsuario: nombreUsuario,
                              ),
                            ),
                          )),
                        )
                      : null,
                  onTap: () async {
                    await almacen.guardarUltimo(d.id, d.nombre);
                    if (!context.mounted) return;
                    await Navigator.of(context).push(MaterialPageRoute<void>(
                      builder: (_) => PantallaVisor(cliente: cliente, almacen: almacen, diagramId: d.id, titulo: d.nombre),
                    ));
                  },
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
              if (_c.presentes.isNotEmpty) _Presentes(nombres: [for (final p in _c.presentes) p.userName]),
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
      return const Center(child: Text('El diagrama esta vacio. Cuando alguien dibuje algo, aparece aca.'));
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
          for (final n in nombres) Chip(label: Text(n), visualDensity: VisualDensity.compact),
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
              OutlinedButton(onPressed: alReintentar, child: const Text('Reintentar')),
            ],
          ]),
        ),
      );
}
