import 'dart:async';
import 'package:flutter/material.dart';
import '../visor/almacen_local.dart';
import 'servicio_sesion.dart';
import 'sesion_guardada.dart';

/// Login y registro comparten campos y validaciones; nunca guardan contraseña.
class PantallaAcceso extends StatefulWidget {
  final ServicioSesion servicio;
  final AlmacenLocal almacen;

  /// El Asistente habla con el backend generado y no necesita cuenta: si se
  /// pasa, el acceso lo ofrece abajo para no dejarlo detras del login.
  final void Function(BuildContext)? alAbrirAsistente;
  const PantallaAcceso(
      {super.key,
      required this.servicio,
      required this.almacen,
      this.alAbrirAsistente});
  @override
  State<PantallaAcceso> createState() => _PantallaAccesoState();
}

class _PantallaAccesoState extends State<PantallaAcceso> {
  var _form = GlobalKey<FormState>();
  final _host = TextEditingController(), _email = TextEditingController();
  final _nombre = TextEditingController(),
      _clave = TextEditingController(),
      _repetida = TextEditingController();
  bool _registro = false, _oculta = true;

  /// La direccion del servidor se escribe una vez: cuando ya hay una
  /// recordada se muestra plegada, para que la pantalla sea correo y clave.
  bool _verServidor = true;
  @override
  void initState() {
    super.initState();
    unawaited(_recordar());
  }

  Future<void> _recordar() async {
    final c = await widget.almacen.leerConexion();
    if (!mounted) return;
    setState(() {
      if (_host.text.isEmpty) {
        _host.text = c.host;
        if (c.host.isNotEmpty) _verServidor = false;
      }
      if (_email.text.isEmpty) _email.text = c.email;
    });
  }

  void _cambiar() {
    setState(() {
      _registro = !_registro;
      _form = GlobalKey<FormState>();
      _clave.clear();
      _repetida.clear();
      _oculta = true;
    });
  }

  Future<void> _enviar() async {
    if (widget.servicio.ocupado) return;
    if (!_verServidor && validarDireccion(_host.text) != null) {
      setState(() => _verServidor = true);
      await WidgetsBinding.instance.endOfFrame;
      if (!mounted) return;
    }
    if (!_form.currentState!.validate()) return;
    final host = _host.text,
        email = _email.text,
        clave = _clave.text,
        nombre = _nombre.text,
        repetida = _repetida.text;
    if (_registro) {
      await widget.servicio.registrar(host, nombre, email, clave, repetida);
    } else {
      await widget.servicio.entrar(host, email, clave);
    }
    if (widget.servicio.estado == EstadoSesion.activa) {
      await widget.almacen.guardarConexion(host.trim(), email.trim());
    }
  }

  @override
  void dispose() {
    for (final c in [_host, _email, _nombre, _clave, _repetida]) {
      c.dispose();
    }
    super.dispose();
  }

  InputDecoration _campo(String etiqueta, IconData icono,
          {String? ayuda, String? ejemplo, Widget? alFinal}) =>
      InputDecoration(
          labelText: etiqueta,
          hintText: ejemplo,
          helperText: ayuda,
          helperMaxLines: 3,
          prefixIcon: Icon(icono),
          suffixIcon: alFinal,
          filled: true,
          border: const OutlineInputBorder(
              borderRadius: BorderRadius.all(Radius.circular(12))));

  Widget _encabezado(BuildContext context) {
    final tema = Theme.of(context);
    return Column(children: [
      Container(
          width: 72,
          height: 72,
          decoration: BoxDecoration(
              color: tema.colorScheme.primaryContainer,
              borderRadius: BorderRadius.circular(20)),
          child: Icon(Icons.account_tree_outlined,
              size: 40, color: tema.colorScheme.onPrimaryContainer)),
      const SizedBox(height: 12),
      Text('ColabModel',
          style: tema.textTheme.titleMedium
              ?.copyWith(color: tema.colorScheme.primary)),
      const SizedBox(height: 4),
      Text(_registro ? 'Crear cuenta' : 'Iniciar sesión',
          style: tema.textTheme.headlineSmall),
      const SizedBox(height: 4),
      Text(
          _registro
              ? 'Creá tu cuenta de ColabModel.'
              : 'Entrá con tu cuenta de la herramienta.',
          textAlign: TextAlign.center,
          style: tema.textTheme.bodyMedium
              ?.copyWith(color: tema.colorScheme.onSurfaceVariant)),
    ]);
  }

  /// Plegado: un renglon con el servidor recordado. Desplegado: el campo.
  Widget _servidor(BuildContext context) {
    final ocupado = widget.servicio.ocupado;
    if (!_verServidor) {
      return Card(
          margin: EdgeInsets.zero,
          child: ListTile(
              leading: const Icon(Icons.dns_outlined),
              title: Text(_host.text, overflow: TextOverflow.ellipsis),
              subtitle: const Text('Servidor de la herramienta'),
              trailing: TextButton(
                  onPressed: ocupado
                      ? null
                      : () => setState(() => _verServidor = true),
                  child: const Text('Cambiar'))));
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      TextFormField(
          controller: _host,
          enabled: !ocupado,
          keyboardType: TextInputType.url,
          autocorrect: false,
          textInputAction: TextInputAction.next,
          decoration: _campo('Dirección de la herramienta', Icons.dns_outlined,
              ejemplo: '192.168.0.10:8080',
              ayuda:
                  'IP de la máquina donde corre la herramienta, con el puerto.'),
          validator: (v) => validarDireccion(v ?? '')),
      ActionChip(
          avatar: const Icon(Icons.usb, size: 18),
          label: const Text('Por USB: 127.0.0.1:8080'),
          onPressed: ocupado
              ? null
              : () => setState(() => _host.text = '127.0.0.1:8080')),
    ]);
  }

  @override
  Widget build(BuildContext context) {
    final ocupado = widget.servicio.ocupado;
    return PopScope(
      canPop: !_registro,
      onPopInvokedWithResult: (salio, _) {
        if (!salio && _registro && !ocupado) _cambiar();
      },
      child: Scaffold(
        body: SafeArea(
            child: Center(
                child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 480),
                    child: Form(
                        key: _form,
                        child: AutofillGroup(
                            child: ListView(
                                shrinkWrap: true,
                                keyboardDismissBehavior:
                                    ScrollViewKeyboardDismissBehavior.onDrag,
                                padding:
                                    const EdgeInsets.fromLTRB(24, 32, 24, 24),
                                children: [
                              _encabezado(context),
                              const SizedBox(height: 24),
                              _servidor(context),
                              const SizedBox(height: 16),
                              if (_registro) ...[
                                TextFormField(
                                    controller: _nombre,
                                    enabled: !ocupado,
                                    textCapitalization:
                                        TextCapitalization.words,
                                    autofillHints: const [AutofillHints.name],
                                    textInputAction: TextInputAction.next,
                                    decoration: _campo('Nombre completo',
                                        Icons.person_outline),
                                    validator: (v) => validarNombre(v ?? '')),
                                const SizedBox(height: 16),
                              ],
                              TextFormField(
                                  controller: _email,
                                  enabled: !ocupado,
                                  keyboardType: TextInputType.emailAddress,
                                  textInputAction: TextInputAction.next,
                                  autofillHints: const [AutofillHints.username],
                                  autocorrect: false,
                                  decoration: _campo('Correo electrónico',
                                      Icons.mail_outline),
                                  validator: (v) => validarEmail(v ?? '')),
                              const SizedBox(height: 16),
                              TextFormField(
                                  controller: _clave,
                                  enabled: !ocupado,
                                  obscureText: _oculta,
                                  autocorrect: false,
                                  enableSuggestions: false,
                                  autofillHints: [
                                    _registro
                                        ? AutofillHints.newPassword
                                        : AutofillHints.password
                                  ],
                                  textInputAction: _registro
                                      ? TextInputAction.next
                                      : TextInputAction.done,
                                  onFieldSubmitted: (_) {
                                    if (!_registro) unawaited(_enviar());
                                  },
                                  decoration: _campo(
                                      'Contraseña', Icons.lock_outline,
                                      alFinal: IconButton(
                                          tooltip: _oculta
                                              ? 'Mostrar contraseña'
                                              : 'Ocultar contraseña',
                                          onPressed: ocupado
                                              ? null
                                              : () => setState(
                                                  () => _oculta = !_oculta),
                                          icon: Icon(_oculta
                                              ? Icons.visibility_outlined
                                              : Icons
                                                  .visibility_off_outlined))),
                                  validator: (v) => validarClave(v ?? '',
                                      registro: _registro)),
                              if (_registro) ...[
                                const SizedBox(height: 16),
                                TextFormField(
                                    controller: _repetida,
                                    enabled: !ocupado,
                                    obscureText: _oculta,
                                    autocorrect: false,
                                    enableSuggestions: false,
                                    textInputAction: TextInputAction.done,
                                    decoration: _campo('Repetir contraseña',
                                        Icons.lock_outline),
                                    validator: (v) => v != _clave.text
                                        ? 'Las contraseñas no coinciden.'
                                        : null,
                                    onFieldSubmitted: (_) =>
                                        unawaited(_enviar())),
                              ],
                              if (widget.servicio.mensaje
                                  case final mensaje?) ...[
                                const SizedBox(height: 16),
                                Semantics(
                                    liveRegion: true,
                                    child: Text(mensaje,
                                        style: TextStyle(
                                            color: Theme.of(context)
                                                .colorScheme
                                                .error))),
                              ],
                              const SizedBox(height: 24),
                              FilledButton(
                                  style: FilledButton.styleFrom(
                                      minimumSize: const Size.fromHeight(52)),
                                  onPressed: ocupado
                                      ? null
                                      : () => unawaited(_enviar()),
                                  child: ocupado
                                      ? const SizedBox(
                                          width: 24,
                                          height: 24,
                                          child: CircularProgressIndicator(
                                              strokeWidth: 2))
                                      : Text(_registro
                                          ? 'Crear cuenta'
                                          : 'Entrar')),
                              const SizedBox(height: 8),
                              TextButton(
                                  style: TextButton.styleFrom(
                                      minimumSize: const Size.fromHeight(48)),
                                  onPressed: ocupado ? null : _cambiar,
                                  child: Text(_registro
                                      ? 'Ya tengo cuenta'
                                      : 'Creá tu cuenta')),
                              if (widget.alAbrirAsistente
                                  case final abrir?) ...[
                                const Divider(height: 32),
                                OutlinedButton.icon(
                                    style: OutlinedButton.styleFrom(
                                        minimumSize:
                                            const Size.fromHeight(48)),
                                    onPressed:
                                        ocupado ? null : () => abrir(context),
                                    icon:
                                        const Icon(Icons.chat_bubble_outline),
                                    label: const Text(
                                        'Usar el Asistente sin cuenta')),
                              ],
                            ])))))),
      ),
    );
  }
}
