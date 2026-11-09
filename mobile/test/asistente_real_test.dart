// Recorrido REAL del asistente contra un backend generado (T-03a). No usa
// dobles: habla HTTP de verdad. Se omite si no hay un backend arriba.
//
//   $env:BACKEND_URL = "http://localhost:8081"
//   flutter test test/asistente_real_test.dart
//
// El backend sale de arrancar el zip del generador (README, "Generar Spring
// Boot"); sirve su propio /app-manifest.json, asi que la prueba no sabe de
// que dominio es: solo la frase cambia.

import 'dart:io';

import 'package:colabmodel_app/asistente/controlador_asistente.dart';
import 'package:colabmodel_app/data/cliente_backend.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final url = Platform.environment['BACKEND_URL'];
  final omitir = url == null ? 'definir BACKEND_URL con un backend generado arriba' : null;

  test('crear con referencia por nombre y listar contra el backend generado', () async {
    final cliente = ClienteBackend(url!);
    final manifiesto = await cliente.cargarManifiesto();
    final c = ControladorAsistente(cliente, manifiesto,
        reloj: () => DateTime(2026, 9, 16, 10), confirmarAntesDeCrear: false);
    // ignore: avoid_print
    void dicho() => print('  asistente> ${c.mensajes.last.texto.replaceAll('\n', '\n             ')}');

    // Un nombre unico por corrida: el backend guarda lo que se le crea.
    final marca = DateTime.now().millisecondsSinceEpoch % 100000;
    final nombre = 'Marta$marca';

    for (final frase in [
      'crear cliente con nombre $nombre',
      'listar clientes',
      'crear pedido del cliente $nombre con fecha hoy a las 10:00 y total 1500,50',
      'listar pedidos',
    ]) {
      // ignore: avoid_print
      print('  usuario>   $frase');
      await c.enviar(frase);
      dicho();
    }

    final textos = c.mensajes.where((m) => m.deAsistente).map((m) => m.texto).toList();
    expect(textos.any((t) => t.startsWith('Listo, creé cliente') && t.contains(nombre)), isTrue);
    expect(textos.any((t) => t.startsWith('Listo, creé pedido') && t.contains(nombre)), isTrue,
        reason: 'el pedido quedo atado al cliente por su id y se muestra por nombre');
    expect(textos.last, contains('1500.5'));

    // Lo creado esta en el backend, no solo en la charla.
    final pedidos = await cliente.listar(
        manifiesto, manifiesto.entities.firstWhere((e) => e.name == 'Pedido'));
    final clientes = await cliente.listar(
        manifiesto, manifiesto.entities.firstWhere((e) => e.name == 'Cliente'));
    final creado = clientes.firstWhere((x) => x['nombre'] == nombre);
    expect(pedidos.any((p) => p['clienteId'] == creado['id']), isTrue);
    cliente.cerrar();
  }, skip: omitir);
}
