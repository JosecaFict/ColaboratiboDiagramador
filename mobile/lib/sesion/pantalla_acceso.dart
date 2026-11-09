import 'dart:async';
import 'package:flutter/material.dart';
import '../visor/almacen_local.dart';
import 'servicio_sesion.dart';
import 'sesion_guardada.dart';

/// Login y registro comparten campos y validaciones; nunca guardan contraseña.
class PantallaAcceso extends StatefulWidget {
  final ServicioSesion servicio;
  final AlmacenLocal almacen;
  const PantallaAcceso(
      {super.key, required this.servicio, required this.almacen});
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
  @override
  void initState() {
    super.initState();
    unawaited(_recordar());
  }

  Future<void> _recordar() async {
    final c = await widget.almacen.leerConexion();
    if (!mounted) return;
    setState(() {
      if (_host.text.isEmpty) _host.text = c.host;
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
    if (widget.servicio.ocupado || !_form.currentState!.validate()) return;
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

  @override
  Widget build(BuildContext context) => PopScope(
        canPop: !_registro,
        onPopInvokedWithResult: (salio, _) {
          if (!salio && _registro && !widget.servicio.ocupado) _cambiar();
        },
        child: Scaffold(
          appBar: AppBar(
              title: Text(_registro ? 'Crear cuenta' : 'Iniciar sesión')),
          body: SafeArea(
              child: Form(
                  key: _form,
                  child: ListView(padding: const EdgeInsets.all(24), children: [
                    Text(_registro
                        ? 'Creá tu cuenta de ColabModel.'
                        : 'Entrá con tu cuenta de la herramienta.'),
                    const SizedBox(height: 20),
                    TextFormField(
                        controller: _host,
                        enabled: !widget.servicio.ocupado,
                        keyboardType: TextInputType.url,
                        textInputAction: TextInputAction.next,
                        decoration: const InputDecoration(
                            labelText: 'Dirección de la herramienta',
                            hintText: '192.168.0.10:8080',
                            helperText:
                                'IP de la máquina. Por USB con adb reverse: 127.0.0.1:8080.',
                            helperMaxLines: 3),
                        validator: (v) => validarDireccion(v ?? '')),
                    const SizedBox(height: 16),
                    if (_registro) ...[
                      TextFormField(
                          controller: _nombre,
                          enabled: !widget.servicio.ocupado,
                          textCapitalization: TextCapitalization.words,
                          autofillHints: const [AutofillHints.name],
                          textInputAction: TextInputAction.next,
                          decoration: const InputDecoration(
                              labelText: 'Nombre completo'),
                          validator: (v) => validarNombre(v ?? '')),
                      const SizedBox(height: 16),
                    ],
                    TextFormField(
                        controller: _email,
                        enabled: !widget.servicio.ocupado,
                        keyboardType: TextInputType.emailAddress,
                        textInputAction: TextInputAction.next,
                        autofillHints: const [AutofillHints.username],
                        autocorrect: false,
                        decoration: const InputDecoration(
                            labelText: 'Correo electrónico'),
                        validator: (v) => validarEmail(v ?? '')),
                    const SizedBox(height: 16),
                    TextFormField(
                        controller: _clave,
                        enabled: !widget.servicio.ocupado,
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
                        decoration: InputDecoration(
                            labelText: 'Contraseña',
                            suffixIcon: IconButton(
                                tooltip: _oculta
                                    ? 'Mostrar contraseña'
                                    : 'Ocultar contraseña',
                                onPressed: widget.servicio.ocupado
                                    ? null
                                    : () => setState(() => _oculta = !_oculta),
                                icon: Icon(_oculta
                                    ? Icons.visibility_outlined
                                    : Icons.visibility_off_outlined))),
                        validator: (v) =>
                            validarClave(v ?? '', registro: _registro)),
                    if (_registro) ...[
                      const SizedBox(height: 16),
                      TextFormField(
                          controller: _repetida,
                          enabled: !widget.servicio.ocupado,
                          obscureText: _oculta,
                          autocorrect: false,
                          enableSuggestions: false,
                          textInputAction: TextInputAction.done,
                          decoration: const InputDecoration(
                              labelText: 'Repetir contraseña'),
                          validator: (v) => v != _clave.text
                              ? 'Las contraseñas no coinciden.'
                              : null,
                          onFieldSubmitted: (_) => unawaited(_enviar())),
                    ],
                    if (widget.servicio.mensaje case final mensaje?) ...[
                      const SizedBox(height: 16),
                      Semantics(
                          liveRegion: true,
                          child: Text(mensaje,
                              style: TextStyle(
                                  color: Theme.of(context).colorScheme.error))),
                    ],
                    const SizedBox(height: 24),
                    FilledButton(
                        style: FilledButton.styleFrom(
                            minimumSize: const Size.fromHeight(48)),
                        onPressed: widget.servicio.ocupado
                            ? null
                            : () => unawaited(_enviar()),
                        child: widget.servicio.ocupado
                            ? const SizedBox(
                                width: 24,
                                height: 24,
                                child:
                                    CircularProgressIndicator(strokeWidth: 2))
                            : Text(_registro ? 'Crear cuenta' : 'Entrar')),
                    const SizedBox(height: 8),
                    TextButton(
                        style: TextButton.styleFrom(
                            minimumSize: const Size.fromHeight(48)),
                        onPressed: widget.servicio.ocupado ? null : _cambiar,
                        child: Text(
                            _registro ? 'Ya tengo cuenta' : 'Creá tu cuenta')),
                  ]))),
        ),
      );
}
