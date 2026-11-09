import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../data/cliente_backend.dart';
import '../domain/intent.dart';
import 'controlador_asistente.dart';
import 'reconocedor_voz.dart';

const _claveHost = 'asistente_host';

typedef FabricaCliente = ClienteBackend Function(String host);

/// El asistente: primero se dice a que backend generado conectarse (P2), se
/// baja su manifiesto y desde ahi la unica interfaz es el dialogo. Sin
/// formularios ni botones de CRUD, por requisito del docente. Se escribe o se
/// dicta (T-03c): lo dictado entra por el mismo `enviar` que lo escrito.
class PantallaAsistente extends StatefulWidget {
  /// Para pruebas: permite inyectar un cliente con HTTP simulado.
  final FabricaCliente? fabricaCliente;

  /// Para pruebas: un reconocedor falso. Por defecto, el del sistema.
  final ReconocedorVoz? reconocedorVoz;

  /// Repetir la orden y esperar un «sí» antes de crear. En la app, siempre.
  final bool confirmarAntesDeCrear;

  const PantallaAsistente(
      {super.key, this.fabricaCliente, this.reconocedorVoz, this.confirmarAntesDeCrear = true});

  @override
  State<PantallaAsistente> createState() => _PantallaAsistenteState();
}

class _PantallaAsistenteState extends State<PantallaAsistente> {
  final _host = TextEditingController();
  ControladorAsistente? _controlador;
  ClienteBackend? _cliente;
  bool _conectando = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _recordarHost();
  }

  Future<void> _recordarHost() async {
    try {
      final p = await SharedPreferences.getInstance();
      final h = p.getString(_claveHost);
      if (h != null && mounted && _host.text.isEmpty) _host.text = h;
    } on Object {
      // Sin almacenamiento: se escribe a mano.
    }
  }

  Future<void> _conectar() async {
    final crudo = _host.text.trim();
    if (crudo.isEmpty || _conectando) return;
    setState(() {
      _conectando = true;
      _error = null;
    });
    final cliente = (widget.fabricaCliente ?? ClienteBackend.new)(crudo);
    try {
      final manifiesto = await cliente.cargarManifiesto();
      try {
        final p = await SharedPreferences.getInstance();
        await p.setString(_claveHost, crudo);
      } on Object {
        // Recordar el host es una comodidad.
      }
      if (!mounted) {
        cliente.cerrar();
        return;
      }
      setState(() {
        _cliente = cliente;
        _controlador = ControladorAsistente(cliente, manifiesto,
            confirmarAntesDeCrear: widget.confirmarAntesDeCrear);
        _conectando = false;
      });
    } on Exception catch (e) {
      cliente.cerrar();
      if (!mounted) return;
      setState(() {
        _conectando = false;
        _error = e is BackendException ? e.mensaje : 'No pude conectarme a $crudo: $e';
      });
    }
  }

  @override
  void dispose() {
    _host.dispose();
    _controlador?.dispose();
    _cliente?.cerrar();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = _controlador;
    return Scaffold(
      appBar: AppBar(title: Text(c == null ? 'Asistente' : 'Asistente · ${c.manifiesto.app}')),
      body: c == null
          ? _conexion()
          : _Charla(controlador: c, reconocedor: widget.reconocedorVoz ?? ReconocedorSistema()),
    );
  }

  Widget _conexion() {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('Backend generado al que te conectás.'),
            const SizedBox(height: 12),
            TextField(
              controller: _host,
              keyboardType: TextInputType.url,
              decoration: const InputDecoration(
                labelText: 'Direccion del backend',
                hintText: '192.168.0.10:8081',
                border: OutlineInputBorder(),
              ),
              onSubmitted: (_) => _conectar(),
            ),
            if (_error != null) ...[
              const SizedBox(height: 12),
              Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ],
            const SizedBox(height: 12),
            FilledButton(
              onPressed: _conectando ? null : _conectar,
              child: _conectando
                  ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Conectar'),
            ),
          ],
        ),
      ),
    );
  }
}

class _Charla extends StatefulWidget {
  final ControladorAsistente controlador;
  final ReconocedorVoz reconocedor;
  const _Charla({required this.controlador, required this.reconocedor});

  @override
  State<_Charla> createState() => _CharlaState();
}

class _CharlaState extends State<_Charla> {
  final _texto = TextEditingController();
  final _scroll = ScrollController();

  /// Se crea una vez: si `build` de la pantalla armara otro reconocedor, este
  /// conserva el que ya tiene el permiso y el idioma.
  late final ReconocedorVoz _voz = widget.reconocedor;
  bool _vozPreparada = false;
  bool _escuchando = false;

  /// Despues de dispose el reconocedor todavia puede avisar (al detenerlo):
  /// esos avisos ya no tocan la pantalla.
  bool _desechado = false;
  bool get _vivo => mounted && !_desechado;

  /// Si no es null, el microfono esta deshabilitado por este motivo.
  String? _vozBloqueada;

  ControladorAsistente get _c => widget.controlador;

  @override
  void initState() {
    super.initState();
    _c.addListener(_alFondo);
  }

  void _alFondo() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) _scroll.jumpTo(_scroll.position.maxScrollExtent);
    });
  }

  /// Numero de la sesion de dictado vigente. Cada aviso del reconocedor trae
  /// el numero de su sesion: si ya no es la vigente (se envio a mano, se
  /// cancelo), se ignora. Asi una frase se envia una sola vez (dictamen de
  /// Codex, T-03c).
  int _sesionVoz = 0;

  /// Enviar a mano (boton o Enter del teclado). Si se estaba dictando, ESTE es
  /// el cierre del dictado: se manda lo que hay en el campo y la sesion de voz
  /// se cancela sin entregar nada, para que su final no llegue como segundo envio.
  void _enviar([String? _]) {
    final t = _texto.text;
    if (t.trim().isEmpty) return;
    if (_escuchando) {
      _sesionVoz++;
      setState(() => _escuchando = false);
      _voz.cancelar();
    }
    _texto.clear();
    // Un aviso pasajero del microfono ya no aplica; el de sin permiso se
    // queda, porque explica por que el microfono esta deshabilitado.
    if (_avisoVoz != null && _vozBloqueada == null) setState(() => _avisoVoz = null);
    _c.enviar(t);
  }

  // ------------------------------------------------------------------ voz

  Future<void> _microfono() async {
    if (_escuchando) {
      await _voz.detener();
      return;
    }
    if (_avisoVoz != null) setState(() => _avisoVoz = null);
    if (!_vozPreparada) {
      final error = await _voz.preparar();
      if (!_vivo) return;
      if (error != null) {
        _avisar(error);
        return;
      }
      _vozPreparada = true;
    }
    final sesion = ++_sesionVoz;
    bool vigente() => _vivo && sesion == _sesionVoz;
    setState(() => _escuchando = true);
    await _voz.escuchar(
      alParcial: (t) {
        if (!vigente()) return;
        _texto.value = TextEditingValue(text: t, selection: TextSelection.collapsed(offset: t.length));
      },
      alFinal: (t) {
        if (!vigente()) return;
        _sesionVoz++; // esta sesion ya envio: nada mas de ella cuenta
        _texto.clear();
        setState(() => _escuchando = false);
        if (t.trim().isEmpty) {
          _avisar(errorVozDesdeCodigo('error_no_match'));
        } else {
          // Se envia solo y queda en el chat como mensaje del usuario, para
          // que se vea que entendio el reconocedor (decision del lider, T-03c).
          _c.enviar(t);
        }
      },
      alError: (e) {
        if (!vigente()) return;
        _avisar(e);
      },
      alTerminar: () {
        if (vigente() && _escuchando) setState(() => _escuchando = false);
      },
    );
  }

  /// Aviso del microfono, encima del campo. No es un SnackBar: ese tapa la
  /// fila del teclado y del microfono justo cuando hay que reintentar.
  String? _avisoVoz;

  void _avisar(ErrorVoz e) {
    setState(() {
      _escuchando = false;
      _avisoVoz = e.mensaje;
      if (e.definitivo) _vozBloqueada = e.mensaje;
    });
  }

  @override
  void dispose() {
    _desechado = true;
    if (_escuchando) _voz.detener();
    _c.removeListener(_alFondo);
    _texto.dispose();
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _c,
      builder: (context, _) {
        final mensajes = _c.mensajes;
        return Column(
          children: [
            Expanded(
              child: ListView.builder(
                controller: _scroll,
                padding: const EdgeInsets.all(12),
                itemCount: mensajes.length,
                itemBuilder: (_, i) => _Burbuja(
                  mensajes[i],
                  // Solo la ultima pregunta sigue abierta.
                  alElegir: i == mensajes.length - 1 && !_c.ocupado ? _c.elegir : null,
                ),
              ),
            ),
            if (_c.ocupado) const LinearProgressIndicator(minHeight: 2),
            if (_escuchando)
              // Que se vea que sigue escuchando entre pausa y pausa, y como se termina.
              Container(
                width: double.infinity,
                color: Theme.of(context).colorScheme.primaryContainer,
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                child: Row(
                  children: [
                    Icon(Icons.graphic_eq, size: 18, color: Theme.of(context).colorScheme.onPrimaryContainer),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text('Escuchando… Tocá ■ para terminar y enviar.',
                          style: TextStyle(color: Theme.of(context).colorScheme.onPrimaryContainer)),
                    ),
                  ],
                ),
              ),
            if (_avisoVoz != null)
              Container(
                width: double.infinity,
                color: Theme.of(context).colorScheme.errorContainer,
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                child: Row(
                  children: [
                    Icon(Icons.mic_off, size: 18, color: Theme.of(context).colorScheme.onErrorContainer),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(_avisoVoz!,
                          style: TextStyle(color: Theme.of(context).colorScheme.onErrorContainer)),
                    ),
                  ],
                ),
              ),
            const Divider(height: 1),
            Padding(
              padding: const EdgeInsets.all(8),
              child: Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _texto,
                      decoration: const InputDecoration(
                        hintText: 'Escribí o dictá qué necesitás…',
                        border: OutlineInputBorder(),
                        isDense: true,
                      ),
                      onSubmitted: _enviar,
                    ),
                  ),
                  const SizedBox(width: 8),
                  IconButton.filledTonal(
                    tooltip: _vozBloqueada ?? (_escuchando ? 'Terminar y enviar' : 'Dictar'),
                    isSelected: _escuchando,
                    onPressed: _vozBloqueada != null || (_c.ocupado && !_escuchando) ? null : _microfono,
                    icon: const Icon(Icons.mic_none),
                    selectedIcon: const Icon(Icons.stop),
                  ),
                  const SizedBox(width: 4),
                  IconButton.filled(
                    tooltip: 'Enviar',
                    onPressed: _c.ocupado ? null : _enviar,
                    icon: const Icon(Icons.send),
                  ),
                ],
              ),
            ),
          ],
        );
      },
    );
  }
}

class _Burbuja extends StatelessWidget {
  final Mensaje mensaje;
  final Future<void> Function(Opcion opcion)? alElegir;
  const _Burbuja(this.mensaje, {this.alElegir});

  @override
  Widget build(BuildContext context) {
    final asistente = mensaje.deAsistente;
    return Align(
      alignment: asistente ? Alignment.centerLeft : Alignment.centerRight,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        constraints: const BoxConstraints(maxWidth: 320),
        decoration: BoxDecoration(
          color: asistente ? const Color(0xFFE2E8F0) : const Color(0xFF1D4ED8),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(mensaje.texto, style: TextStyle(color: asistente ? Colors.black87 : Colors.white)),
            if (mensaje.opciones.isNotEmpty && alElegir != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    for (final o in mensaje.opciones)
                      ActionChip(label: Text(o.etiqueta), onPressed: () => alElegir!(o)),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }
}
