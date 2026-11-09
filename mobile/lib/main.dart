import 'package:flutter/material.dart';

import 'asistente/pantalla_asistente.dart';
import 'editor/controlador_editor.dart';
import 'editor/pantallas/entrada.dart';
import 'visor/pantallas_visor.dart';

void main() {
  // El editor (D-12) se instala aca para que el visor no dependa del canal.
  fabricaDeEditor = crearEditor;
  runApp(const ColabModelApp());
}

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

/// Pantalla inicial: el Asistente (contra el backend generado) o los diagramas
/// en vivo de la herramienta: los ve cualquiera y los edita un Editor o el
/// Propietario (ADR-036, enmendado por D-12).
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
                label: const Text('Diagramas en vivo'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
