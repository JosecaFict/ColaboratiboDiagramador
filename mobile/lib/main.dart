import 'package:flutter/material.dart';

void main() => runApp(const ColabModelApp());

class ColabModelApp extends StatelessWidget {
  const ColabModelApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ColabModel Assistant',
      theme: ThemeData(colorSchemeSeed: const Color(0xFF1D4ED8), useMaterial3: true),
      home: const PantallaAsistente(),
    );
  }
}

/// FASE 0: cascaron conversacional.
///
/// Sin formularios y sin botones de CRUD, por requisito del docente: la
/// interfaz es el dialogo. En Fase 3 se conecta el STT; en Fase 4, el outbox.
class PantallaAsistente extends StatefulWidget {
  const PantallaAsistente({super.key});

  @override
  State<PantallaAsistente> createState() => _PantallaAsistenteState();
}

class _PantallaAsistenteState extends State<PantallaAsistente> {
  final List<_Burbuja> _mensajes = [
    _Burbuja('Hola. Deci que necesitas.', deAsistente: true),
  ];
  final _controlador = TextEditingController();

  void _enviar(String texto) {
    if (texto.trim().isEmpty) return;
    setState(() {
      _mensajes.add(_Burbuja(texto, deAsistente: false));
      _mensajes.add(_Burbuja(
        'Fase 0: todavia no hay manifiesto cargado. '
        'En Fase 3 esto pasa por el motor de intenciones local.',
        deAsistente: true,
      ));
    });
    _controlador.clear();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Asistente')),
      body: Column(
        children: [
          Expanded(
            child: ListView.builder(
              padding: const EdgeInsets.all(12),
              itemCount: _mensajes.length,
              itemBuilder: (_, i) => _mensajes[i],
            ),
          ),
          const Divider(height: 1),
          Padding(
            padding: const EdgeInsets.all(8),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _controlador,
                    decoration: const InputDecoration(
                      hintText: 'Escribi o habla...',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                    onSubmitted: _enviar,
                  ),
                ),
                const SizedBox(width: 8),
                // Fase 3: aca va speech_to_text con preferOffline: true
                IconButton.filled(
                  onPressed: () => _enviar(_controlador.text),
                  icon: const Icon(Icons.mic),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Burbuja extends StatelessWidget {
  final String texto;
  final bool deAsistente;
  const _Burbuja(this.texto, {required this.deAsistente});

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: deAsistente ? Alignment.centerLeft : Alignment.centerRight,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        constraints: const BoxConstraints(maxWidth: 300),
        decoration: BoxDecoration(
          color: deAsistente ? const Color(0xFFE2E8F0) : const Color(0xFF1D4ED8),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Text(texto,
            style: TextStyle(color: deAsistente ? Colors.black87 : Colors.white)),
      ),
    );
  }
}
