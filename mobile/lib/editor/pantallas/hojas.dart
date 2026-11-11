// Las hojas que suben desde abajo: la paleta, las propiedades de una clase y
// las de una relacion. Mismos nombres y misma organizacion que la web.
//
// Las hojas de propiedades escuchan al editor: lo que se ve es lo que el
// servidor ya confirmo. Un cambio enviado aparece cuando vuelve, no antes.

import 'package:flutter/material.dart';

import '../../visor/modelo.dart';
import '../contrato_editor.dart';
import 'interaccion.dart';

// ------------------------------------------------------------------ nombres

String nombreDeTipo(TipoDeElemento t) => switch (t) {
      TipoDeElemento.clase => 'Clase',
      TipoDeElemento.abstracta => 'Clase abstracta',
      TipoDeElemento.interfaz => 'Interfaz',
      TipoDeElemento.enumeracion => 'Enumeración',
      TipoDeElemento.valor => 'Valor embebido',
    };

String tituloParaCrear(TipoDeElemento t) => switch (t) {
      TipoDeElemento.clase => 'Nombre de la clase',
      TipoDeElemento.abstracta => 'Nombre de la clase abstracta',
      TipoDeElemento.interfaz => 'Nombre de la interfaz',
      TipoDeElemento.enumeracion => 'Nombre de la enumeración',
      TipoDeElemento.valor => 'Nombre del valor (ej. Direccion)',
    };

String nombreDeRelacion(TipoRelacion t) => switch (t) {
      TipoRelacion.asociacion => 'asociación',
      TipoRelacion.agregacion => 'agregación',
      TipoRelacion.composicion => 'composición',
      TipoRelacion.herencia => 'herencia',
      TipoRelacion.dependencia => 'dependencia',
      TipoRelacion.realizacion => 'realización',
    };

TipoDeElemento tipoDeClase(UmlClass c) {
  final e = (c.stereotype ?? '').toLowerCase();
  if (e == 'enumeration') return TipoDeElemento.enumeracion;
  if (e == 'embeddable') return TipoDeElemento.valor;
  if (c.esInterfaz) return TipoDeElemento.interfaz;
  return c.isAbstract ? TipoDeElemento.abstracta : TipoDeElemento.clase;
}

// ------------------------------------------------------------------- paleta

/// Lo que se eligio en la paleta.
sealed class EleccionDePaleta {
  const EleccionDePaleta();
}

class ElegirHerramienta extends EleccionDePaleta {
  final Herramienta herramienta;
  const ElegirHerramienta(this.herramienta);
}

class ElegirElemento extends EleccionDePaleta {
  final TipoDeElemento tipo;
  const ElegirElemento(this.tipo);
}

const _iconoDeElemento = {
  TipoDeElemento.clase: Icons.crop_square,
  TipoDeElemento.abstracta: Icons.crop_free,
  TipoDeElemento.interfaz: Icons.radio_button_unchecked,
  TipoDeElemento.enumeracion: Icons.format_list_bulleted,
  TipoDeElemento.valor: Icons.inventory_2_outlined,
};

Future<EleccionDePaleta?> mostrarPaleta(BuildContext context,
    {required bool puedeEditar, required Herramienta actual}) {
  return showModalBottomSheet<EleccionDePaleta>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (context) => SafeArea(
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            ListTile(
              leading: const Icon(Icons.touch_app_outlined),
              title: const Text('Seleccionar'),
              selected: actual is Seleccionar,
              onTap: () => Navigator.of(context).pop(const ElegirHerramienta(Seleccionar())),
            ),
            const _Titulo('Elementos'),
            for (final t in TipoDeElemento.values)
              ListTile(
                enabled: puedeEditar,
                leading: Icon(_iconoDeElemento[t]),
                title: Text(nombreDeTipo(t)),
                onTap: () => Navigator.of(context).pop(ElegirElemento(t)),
              ),
            const _Titulo('Relaciones'),
            for (final r in TipoRelacion.values)
              ListTile(
                enabled: puedeEditar,
                selected: actual is ArmarRelacion && actual.tipo == r,
                leading: SizedBox(width: 48, height: 20, child: CustomPaint(painter: NotacionDeRelacion(r))),
                title: Text(nombreDeRelacion(r)),
                onTap: () => Navigator.of(context).pop(ElegirHerramienta(ArmarRelacion(r))),
              ),
            if (!puedeEditar)
              const Padding(
                padding: EdgeInsets.all(16),
                child: Text('Solo lectura: la paleta no crea nada.'),
              )
            else
              const Padding(
                padding: EdgeInsets.fromLTRB(16, 8, 16, 16),
                child: Text('Elegí un elemento para crearlo, o una relación y tocá el origen y después el destino.',
                    style: TextStyle(fontSize: 12)),
              ),
          ],
        ),
      ),
    ),
  );
}

/// El dibujito de cada relacion, como en la paleta de la web.
class NotacionDeRelacion extends CustomPainter {
  final TipoRelacion tipo;
  NotacionDeRelacion(this.tipo);

  @override
  void paint(Canvas canvas, Size size) {
    final y = size.height / 2;
    final trazo = Paint()
      ..color = const Color(0xFF475569)
      ..strokeWidth = 1.4
      ..style = PaintingStyle.stroke;
    final relleno = Paint()..color = const Color(0xFF475569);
    final blanco = Paint()..color = Colors.white;
    final conRombo = tipo == TipoRelacion.agregacion || tipo == TipoRelacion.composicion;
    final x0 = conRombo ? 16.0 : 2.0;
    final x1 = tipo == TipoRelacion.herencia || tipo == TipoRelacion.dependencia || tipo == TipoRelacion.realizacion
        ? size.width - 12
        : size.width - 2;
    if (tipo == TipoRelacion.dependencia || tipo == TipoRelacion.realizacion) {
      for (var x = x0; x < x1; x += 7) {
        canvas.drawLine(Offset(x, y), Offset((x + 4).clamp(x0, x1), y), trazo);
      }
    } else {
      canvas.drawLine(Offset(x0, y), Offset(x1, y), trazo);
    }
    if (conRombo) {
      final rombo = Path()
        ..moveTo(2, y)
        ..lineTo(9, y - 5)
        ..lineTo(16, y)
        ..lineTo(9, y + 5)
        ..close();
      canvas.drawPath(rombo, tipo == TipoRelacion.composicion ? relleno : blanco);
      canvas.drawPath(rombo, trazo);
    }
    if (tipo == TipoRelacion.herencia || tipo == TipoRelacion.realizacion) {
      final t = Path()
        ..moveTo(x1, y - 5)
        ..lineTo(size.width - 2, y)
        ..lineTo(x1, y + 5)
        ..close();
      canvas.drawPath(t, blanco);
      canvas.drawPath(t, trazo);
    }
    if (tipo == TipoRelacion.dependencia) {
      canvas.drawLine(Offset(size.width - 8, y - 4), Offset(size.width - 2, y), trazo);
      canvas.drawLine(Offset(size.width - 8, y + 4), Offset(size.width - 2, y), trazo);
    }
  }

  @override
  bool shouldRepaint(NotacionDeRelacion old) => old.tipo != tipo;
}

/// Pide un nombre en un dialogo. null si se cancela o queda vacio.
Future<String?> pedirNombre(BuildContext context, String titulo, {String inicial = ''}) async {
  final texto = await pedirTexto(context, titulo, inicial: inicial, accion: 'Aceptar');
  return texto == null ? null : nombreLimpio(texto);
}

/// Pide un texto en un dialogo. null si se cancela; puede volver vacio.
Future<String?> pedirTexto(BuildContext context, String titulo, {String inicial = '', String accion = 'Guardar'}) =>
    showDialog<String>(
      context: context,
      builder: (context) => _DialogoTexto(titulo: titulo, inicial: inicial, accion: accion),
    );

/// El dialogo es dueño de su campo: lo libera cuando termina de cerrarse, no
/// antes (liberarlo al volver de showDialog rompe la animacion de salida).
class _DialogoTexto extends StatefulWidget {
  final String titulo;
  final String inicial;
  final String accion;
  const _DialogoTexto({required this.titulo, required this.inicial, required this.accion});
  @override
  State<_DialogoTexto> createState() => _DialogoTextoState();
}

class _DialogoTextoState extends State<_DialogoTexto> {
  late final _control = TextEditingController(text: widget.inicial);

  @override
  void dispose() {
    _control.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: Text(widget.titulo),
        content: TextField(
          controller: _control,
          autofocus: true,
          textCapitalization: TextCapitalization.sentences,
          onSubmitted: (v) => Navigator.of(context).pop(v),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancelar')),
          FilledButton(onPressed: () => Navigator.of(context).pop(_control.text), child: Text(widget.accion)),
        ],
      );
}

Future<bool> confirmar(BuildContext context, String pregunta, {String accion = 'Borrar'}) async {
  final si = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      content: Text(pregunta),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancelar')),
        FilledButton(onPressed: () => Navigator.of(context).pop(true), child: Text(accion)),
      ],
    ),
  );
  return si ?? false;
}

// --------------------------------------------------------- hoja de elemento

/// Abre la hoja de la clase o de la relacion [id]. Termina al cerrarla.
Future<void> mostrarHojaDeElemento(BuildContext context, EditorDeDiagrama editor, String id) {
  return showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    useSafeArea: true,
    builder: (context) => DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.6,
      minChildSize: 0.3,
      maxChildSize: 0.95,
      builder: (context, scroll) => ListenableBuilder(
        listenable: editor,
        builder: (context, _) {
          final modelo = editor.modelo;
          final clase = modelo?.classes.where((c) => c.id == id).firstOrNull;
          final relacion = modelo?.relationships.where((r) => r.id == id).firstOrNull;
          if (modelo == null || (clase == null && relacion == null)) {
            return const Center(
                child: Padding(padding: EdgeInsets.all(24), child: Text('Este elemento ya no existe.')));
          }
          final quien = editor.bloqueadaPorOtro(id);
          final editable = editor.puedeEditar && quien == null;
          return ListView(
            controller: scroll,
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
            children: [
              if (quien != null) _AvisoBloqueo(quien: quien),
              if (!editor.puedeEditar && quien == null)
                const Padding(
                  padding: EdgeInsets.only(bottom: 8),
                  child: Text('Solo lectura.', style: TextStyle(fontStyle: FontStyle.italic)),
                ),
              if (clase != null)
                HojaDeClase(editor: editor, modelo: modelo, clase: clase, editable: editable)
              else
                HojaDeRelacion(editor: editor, modelo: modelo, relacion: relacion!, editable: editable),
            ],
          );
        },
      ),
    ),
  );
}

class _AvisoBloqueo extends StatelessWidget {
  final String quien;
  const _AvisoBloqueo({required this.quien});
  @override
  Widget build(BuildContext context) => Container(
        margin: const EdgeInsets.only(bottom: 12),
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(color: Colors.orange.shade50, borderRadius: BorderRadius.circular(8)),
        child: Text('La está editando $quien. Vas a poder cambiarla cuando la suelte.',
            style: TextStyle(color: Colors.orange.shade900)),
      );
}

// ------------------------------------------------------------ hoja de clase

class HojaDeClase extends StatelessWidget {
  final EditorDeDiagrama editor;
  final EstadoModelo modelo;
  final UmlClass clase;
  final bool editable;

  const HojaDeClase(
      {super.key, required this.editor, required this.modelo, required this.clase, required this.editable});

  bool get _esEnum => tipoDeClase(clase) == TipoDeElemento.enumeracion;

  @override
  Widget build(BuildContext context) {
    final tipos = tiposDeAtributo(modelo, excepto: clase.id);
    final relaciones =
        modelo.relationships.where((r) => r.sourceClassId == clase.id || r.targetClassId == clase.id).toList();
    String nombre(String id) => modelo.classes.where((c) => c.id == id).firstOrNull?.name ?? '?';

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(clase.name,
                  style: Theme.of(context)
                      .textTheme
                      .titleLarge
                      ?.copyWith(fontStyle: clase.isAbstract ? FontStyle.italic : FontStyle.normal)),
            ),
            IconButton(
              tooltip: 'Renombrar',
              icon: const Icon(Icons.edit_outlined),
              onPressed: !editable
                  ? null
                  : () async {
                      final n = await pedirNombre(context, 'Nuevo nombre', inicial: clase.name);
                      if (n != null && n != clase.name) editor.renombrarClase(clase.id, n);
                    },
            ),
          ],
        ),
        Text(nombreDeTipo(tipoDeClase(clase)), style: Theme.of(context).textTheme.bodySmall),
        const SizedBox(height: 12),
        _Titulo(_esEnum ? 'Literales' : 'Atributos'),
        if (clase.attributes.isEmpty)
          const Text('Sin atributos todavía.', style: TextStyle(fontStyle: FontStyle.italic)),
        for (final a in clase.attributes)
          ListTile(
            contentPadding: EdgeInsets.zero,
            dense: true,
            title: Text(_esEnum ? a.name : '${a.name}: ${a.dataType}'),
            subtitle: _esEnum ? null : _marcas(a),
            onTap: !editable || _esEnum
                ? null
                : () async {
                    final cambios = await _editarAtributo(context, a, tipos);
                    if (cambios != null) editor.actualizarAtributo(a.id, cambios);
                  },
            trailing: IconButton(
              tooltip: 'Quitar',
              icon: const Icon(Icons.delete_outline),
              onPressed: !editable
                  ? null
                  : () async {
                      if (await confirmar(context, '¿Quitar «${a.name}» de ${clase.name}?', accion: 'Quitar')) {
                        editor.quitarAtributo(a.id);
                      }
                    },
            ),
          ),
        if (editable)
          _NuevoAtributo(tipos: tipos, esEnum: _esEnum, alAgregar: (d) => editor.agregarAtributo(clase.id, d)),
        if (!_esEnum) ...[
          const SizedBox(height: 12),
          const _Titulo('Métodos'),
          if (clase.methods.isEmpty) const Text('Sin métodos.', style: TextStyle(fontStyle: FontStyle.italic)),
          for (final m in clase.methods)
            ListTile(
              contentPadding: EdgeInsets.zero,
              dense: true,
              title: Text('${m.name}(): ${m.returnType}'),
              trailing: IconButton(
                tooltip: 'Quitar',
                icon: const Icon(Icons.delete_outline),
                onPressed: !editable
                    ? null
                    : () async {
                        if (await confirmar(context, '¿Quitar el método «${m.name}»?', accion: 'Quitar')) {
                          editor.quitarMetodo(m.id);
                        }
                      },
              ),
            ),
          if (editable)
            _NuevoMetodo(
                tipos: tiposDeRetorno(modelo, excepto: clase.id),
                alAgregar: (n, r) => editor.agregarMetodo(clase.id, n, retorno: r)),
        ],
        if (relaciones.isNotEmpty) ...[
          const SizedBox(height: 12),
          const _Titulo('Relaciones'),
          for (final r in relaciones)
            ListTile(
              contentPadding: EdgeInsets.zero,
              dense: true,
              leading: SizedBox(
                  width: 40,
                  height: 16,
                  child: CustomPaint(painter: NotacionDeRelacion(TipoRelacion.desdeClave(r.kind)))),
              title: Text('${nombre(r.sourceClassId)} — ${nombre(r.targetClassId)}'),
              subtitle: Text(nombreDeRelacion(TipoRelacion.desdeClave(r.kind))),
            ),
        ],
        const SizedBox(height: 20),
        OutlinedButton.icon(
          style: OutlinedButton.styleFrom(foregroundColor: Colors.red.shade700),
          icon: const Icon(Icons.delete_forever_outlined),
          label: const Text('Borrar la clase'),
          onPressed: !editable
              ? null
              : () async {
                  final hay = relaciones.isEmpty ? '' : ' También se borran sus ${relaciones.length} relaciones.';
                  if (await confirmar(context, '¿Borrar «${clase.name}»?$hay')) {
                    editor.borrarClase(clase.id);
                    if (context.mounted) Navigator.of(context).pop();
                  }
                },
        ),
      ],
    );
  }

  Widget? _marcas(UmlAttribute a) {
    final m = [if (a.pk) 'PK', if (a.required) 'obligatorio', if (a.unique) 'único'];
    return m.isEmpty ? null : Text(m.join(' · '));
  }
}

/// Dialogo para editar un atributo. Devuelve SOLO lo que cambio, o null.
Future<DatosAtributo?> _editarAtributo(BuildContext context, UmlAttribute a, List<String> tipos) {
  return showDialog<DatosAtributo>(
    context: context,
    builder: (context) => _DialogoAtributo(atributo: a, tipos: tipos),
  );
}

class _DialogoAtributo extends StatefulWidget {
  final UmlAttribute atributo;
  final List<String> tipos;
  const _DialogoAtributo({required this.atributo, required this.tipos});
  @override
  State<_DialogoAtributo> createState() => _DialogoAtributoState();
}

class _DialogoAtributoState extends State<_DialogoAtributo> {
  late final _nombre = TextEditingController(text: widget.atributo.name);
  late String _tipo = widget.atributo.dataType;
  late bool _pk = widget.atributo.pk;
  late bool _obligatorio = widget.atributo.required;
  late bool _unico = widget.atributo.unique;

  @override
  void dispose() {
    _nombre.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final a = widget.atributo;
    final tipos = {...widget.tipos, a.dataType}.toList();
    return AlertDialog(
      title: Text('Atributo «${a.name}»'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: _nombre, decoration: const InputDecoration(labelText: 'Nombre')),
            DropdownButtonFormField<String>(
              initialValue: _tipo,
              decoration: const InputDecoration(labelText: 'Tipo'),
              items: [for (final t in tipos) DropdownMenuItem(value: t, child: Text(t))],
              onChanged: (v) => setState(() => _tipo = v ?? _tipo),
            ),
            SwitchListTile(title: const Text('Clave primaria'), value: _pk, onChanged: (v) => setState(() => _pk = v)),
            SwitchListTile(
                title: const Text('Obligatorio'),
                value: _obligatorio,
                onChanged: (v) => setState(() => _obligatorio = v)),
            SwitchListTile(title: const Text('Único'), value: _unico, onChanged: (v) => setState(() => _unico = v)),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancelar')),
        FilledButton(
          onPressed: () {
            final nombre = nombreLimpio(_nombre.text);
            final cambios = DatosAtributo(
              nombre: nombre != null && nombre != a.name ? nombre : null,
              tipoDato: _tipo != a.dataType ? _tipo : null,
              pk: _pk != a.pk ? _pk : null,
              obligatorio: _obligatorio != a.required ? _obligatorio : null,
              unico: _unico != a.unique ? _unico : null,
            );
            Navigator.of(context).pop(hayCambios(cambios) ? cambios : null);
          },
          child: const Text('Guardar'),
        ),
      ],
    );
  }
}

/// Si unos cambios de atributo dicen algo (para no mandar un comando vacio).
bool hayCambios(DatosAtributo d) =>
    d.nombre != null ||
    d.tipoDato != null ||
    d.pk != null ||
    d.obligatorio != null ||
    d.unico != null ||
    d.visibilidad != null;

class _NuevoAtributo extends StatefulWidget {
  final List<String> tipos;
  final bool esEnum;
  final void Function(DatosAtributo) alAgregar;
  const _NuevoAtributo({required this.tipos, required this.esEnum, required this.alAgregar});
  @override
  State<_NuevoAtributo> createState() => _NuevoAtributoState();
}

class _NuevoAtributoState extends State<_NuevoAtributo> {
  final _nombre = TextEditingController();
  String _tipo = 'string';

  /// El tipo elegido que desaparecio (otra sesion borro o renombro esa
  /// enumeracion o ese valor). Se avisa en vez de cambiarlo en silencio.
  String? _perdido;

  @override
  void didUpdateWidget(_NuevoAtributo anterior) {
    super.didUpdateWidget(anterior);
    if (!widget.tipos.contains(_tipo)) {
      _perdido = _tipo;
      _tipo = 'string';
    }
  }

  @override
  void dispose() {
    _nombre.dispose();
    super.dispose();
  }

  void _agregar() {
    final n = nombreLimpio(_nombre.text);
    if (n == null) return;
    widget.alAgregar(DatosAtributo(nombre: n, tipoDato: widget.esEnum ? 'string' : _tipo));
    _nombre.clear();
    setState(() => _perdido = null);
  }

  @override
  Widget build(BuildContext context) {
    return _ConAviso(
      aviso: _perdido == null ? null : '«$_perdido» ya no existe: quedó $_tipo.',
      child: Row(
        children: [
          Expanded(
            flex: 3,
            child: TextField(
              controller: _nombre,
              decoration: InputDecoration(hintText: widget.esEnum ? 'nuevo literal' : 'nuevo atributo'),
              onSubmitted: (_) => _agregar(),
            ),
          ),
          if (!widget.esEnum) ...[
            const SizedBox(width: 8),
            Expanded(
              flex: 2,
              child: DropdownButton<String>(
                isExpanded: true,
                value: _tipo,
                items: [for (final t in widget.tipos) DropdownMenuItem(value: t, child: Text(t))],
                // Un menu abierto antes de un cambio remoto puede ofrecer un tipo
                // que ya no existe: esa eleccion no se toma.
                onChanged: (v) => setState(() {
                  if (v == null) return;
                  if (widget.tipos.contains(v)) {
                    _tipo = v;
                    _perdido = null;
                  } else {
                    _perdido = v;
                  }
                }),
              ),
            ),
          ],
          IconButton(tooltip: 'Agregar', icon: const Icon(Icons.add_circle_outline), onPressed: _agregar),
        ],
      ),
    );
  }
}

class _NuevoMetodo extends StatefulWidget {
  final List<String> tipos;
  final void Function(String nombre, String retorno) alAgregar;
  const _NuevoMetodo({required this.tipos, required this.alAgregar});
  @override
  State<_NuevoMetodo> createState() => _NuevoMetodoState();
}

class _NuevoMetodoState extends State<_NuevoMetodo> {
  final _nombre = TextEditingController();
  String _retorno = 'void';
  String? _perdido;

  @override
  void didUpdateWidget(_NuevoMetodo anterior) {
    super.didUpdateWidget(anterior);
    if (!widget.tipos.contains(_retorno)) {
      _perdido = _retorno;
      _retorno = 'void';
    }
  }

  @override
  void dispose() {
    _nombre.dispose();
    super.dispose();
  }

  void _agregar() {
    final n = nombreLimpio(_nombre.text);
    if (n == null) return;
    widget.alAgregar(n, _retorno);
    _nombre.clear();
    setState(() => _perdido = null);
  }

  @override
  Widget build(BuildContext context) {
    return _ConAviso(
      aviso: _perdido == null ? null : '«$_perdido» ya no existe: quedó $_retorno.',
      child: Row(
        children: [
          Expanded(
            flex: 3,
            child: TextField(
              controller: _nombre,
              decoration: const InputDecoration(hintText: 'nuevo método'),
              onSubmitted: (_) => _agregar(),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            flex: 2,
            child: DropdownButton<String>(
              isExpanded: true,
              value: _retorno,
              items: [for (final t in widget.tipos) DropdownMenuItem(value: t, child: Text(t))],
              onChanged: (v) => setState(() {
                if (v == null) return;
                if (widget.tipos.contains(v)) {
                  _retorno = v;
                  _perdido = null;
                } else {
                  _perdido = v;
                }
              }),
            ),
          ),
          IconButton(tooltip: 'Agregar', icon: const Icon(Icons.add_circle_outline), onPressed: _agregar),
        ],
      ),
    );
  }
}

/// Una fila de alta con un aviso debajo, si hay.
class _ConAviso extends StatelessWidget {
  final String? aviso;
  final Widget child;
  const _ConAviso({required this.aviso, required this.child});

  @override
  Widget build(BuildContext context) {
    final texto = aviso;
    if (texto == null) return child;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        child,
        Text(texto, style: TextStyle(color: Theme.of(context).colorScheme.error)),
      ],
    );
  }
}

// --------------------------------------------------------- hoja de relacion

class HojaDeRelacion extends StatelessWidget {
  final EditorDeDiagrama editor;
  final EstadoModelo modelo;
  final UmlRelationship relacion;
  final bool editable;

  const HojaDeRelacion(
      {super.key, required this.editor, required this.modelo, required this.relacion, required this.editable});

  String _nombre(String id) => modelo.classes.where((c) => c.id == id).firstOrNull?.name ?? '?';

  @override
  Widget build(BuildContext context) {
    final origen = _nombre(relacion.sourceClassId);
    final destino = _nombre(relacion.targetClassId);
    final tipo = TipoRelacion.desdeClave(relacion.kind);
    final esHerencia = tipo == TipoRelacion.herencia || tipo == TipoRelacion.realizacion;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('$origen — $destino', style: Theme.of(context).textTheme.titleLarge),
        if (relacion.name?.isNotEmpty ?? false) Text('«${relacion.name}»'),
        const SizedBox(height: 12),
        const _Titulo('Tipo'),
        Wrap(
          spacing: 8,
          runSpacing: 4,
          children: [
            for (final t in TipoRelacion.values)
              ChoiceChip(
                label: Text(nombreDeRelacion(t)),
                selected: t == tipo,
                onSelected: !editable || t == tipo
                    ? null
                    : (_) => editor.actualizarRelacion(relacion.id, CambiosRelacion(tipo: t)),
              ),
          ],
        ),
        if (!esHerencia) ...[
          const SizedBox(height: 12),
          const _Titulo('Cardinalidades'),
          _Multiplicidad(
            etiqueta: 'Junto a $origen',
            valor: relacion.sourceMultiplicity,
            editable: editable,
            alCambiar: (v) => editor.actualizarRelacion(relacion.id, CambiosRelacion(multiplicidadOrigen: v)),
          ),
          _Multiplicidad(
            etiqueta: 'Junto a $destino',
            valor: relacion.targetMultiplicity,
            editable: editable,
            alCambiar: (v) => editor.actualizarRelacion(relacion.id, CambiosRelacion(multiplicidadDestino: v)),
          ),
          const SizedBox(height: 12),
          const _Titulo('Nombre y roles'),
          _TextoEditable(
            etiqueta: 'Nombre de la relación',
            valor: relacion.name ?? '',
            editable: editable,
            alGuardar: (v) => editor.actualizarRelacion(relacion.id, CambiosRelacion(nombre: v)),
          ),
          _TextoEditable(
            etiqueta: 'Rol de $origen',
            valor: relacion.sourceRole ?? '',
            editable: editable,
            alGuardar: (v) => editor.actualizarRelacion(relacion.id, CambiosRelacion(rolOrigen: v)),
          ),
          _TextoEditable(
            etiqueta: 'Rol de $destino',
            valor: relacion.targetRole ?? '',
            editable: editable,
            alGuardar: (v) => editor.actualizarRelacion(relacion.id, CambiosRelacion(rolDestino: v)),
          ),
        ],
        const SizedBox(height: 20),
        OutlinedButton.icon(
          style: OutlinedButton.styleFrom(foregroundColor: Colors.red.shade700),
          icon: const Icon(Icons.delete_forever_outlined),
          label: const Text('Borrar la relación'),
          onPressed: !editable
              ? null
              : () async {
                  if (await confirmar(context, '¿Borrar la relación $origen — $destino?')) {
                    editor.borrarRelacion(relacion.id);
                    if (context.mounted) Navigator.of(context).pop();
                  }
                },
        ),
      ],
    );
  }
}

class _Multiplicidad extends StatelessWidget {
  final String etiqueta;
  final String? valor;
  final bool editable;
  final void Function(String) alCambiar;
  const _Multiplicidad({required this.etiqueta, required this.valor, required this.editable, required this.alCambiar});

  @override
  Widget build(BuildContext context) {
    final actual = valor ?? '';
    final opciones = opcionesDeMultiplicidad(actual);
    return Row(
      children: [
        Expanded(child: Text(etiqueta)),
        DropdownButton<String>(
          value: actual,
          items: [
            for (final m in opciones) DropdownMenuItem(value: m, child: Text(m.isEmpty ? 'sin definir' : m)),
          ],
          onChanged: !editable ? null : (v) => v == null || v == actual ? null : alCambiar(v),
        ),
      ],
    );
  }
}

class _TextoEditable extends StatelessWidget {
  final String etiqueta;
  final String valor;
  final bool editable;
  final void Function(String) alGuardar;
  const _TextoEditable({required this.etiqueta, required this.valor, required this.editable, required this.alGuardar});

  @override
  Widget build(BuildContext context) => ListTile(
        contentPadding: EdgeInsets.zero,
        dense: true,
        title: Text(etiqueta),
        subtitle: Text(valor.isEmpty ? '—' : valor),
        trailing: const Icon(Icons.edit_outlined, size: 18),
        enabled: editable,
        onTap: () async {
          final escrito = await pedirTexto(context, etiqueta, inicial: valor);
          final nuevo = escrito?.trim();
          if (nuevo != null && nuevo != valor) alGuardar(nuevo);
        },
      );
}

class _Titulo extends StatelessWidget {
  final String texto;
  const _Titulo(this.texto);
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(0, 8, 0, 4),
        child: Text(texto.toUpperCase(), style: Theme.of(context).textTheme.labelSmall?.copyWith(letterSpacing: 1.1)),
      );
}
