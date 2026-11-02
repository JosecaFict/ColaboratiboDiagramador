import 'package:flutter/material.dart';

import 'asistente/pantalla_asistente.dart';
import 'visor/pantallas_visor.dart';

void main() => runApp(const ColabModelApp());

class ColabModelApp extends StatelessWidget {
  const ColabModelApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ColabModel Assistant',
      theme: ThemeData(colorSchemeSeed: const Color(0xFF1D4ED8), useMaterial3: true),
      home: const PantallaInicio(),
    );
  }
}

/// Pantalla inicial: el Asistente (contra el backend generado) o el visor en
/// vivo (contra la herramienta, solo lectura, ADR-036).
class PantallaInicio extends StatelessWidget {
  const PantallaInicio({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('ColabModel')),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              FilledButton.icon(
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(builder: (_) => const PantallaAsistente()),
                ),
                icon: const Icon(Icons.chat_bubble_outline),
                label: const Text('Asistente'),
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(builder: (_) => const PantallaLoginVisor()),
                ),
                icon: const Icon(Icons.visibility_outlined),
                label: const Text('Ver diagrama en vivo'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
