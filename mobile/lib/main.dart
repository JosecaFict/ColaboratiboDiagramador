import 'package:flutter/material.dart';

import 'asistente/pantalla_asistente.dart';
import 'editor/controlador_editor.dart';
import 'editor/pantallas/entrada.dart';
import 'sesion/entrada_sesion.dart';
import 'tema.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
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
      // Claro u oscuro segun el telefono.
      theme: temaClaro(),
      darkTheme: temaOscuro(),
      // Se entra por el login (los diagramas en vivo piden cuenta). El Asistente
      // no la pide: se ofrece en el login y, ya adentro, en Mis proyectos.
      home: const EntradaSesion(alAbrirAsistente: abrirAsistente),
    );
  }
}

/// Va por el navegador raiz: el Asistente no es parte de la sesion y sigue
/// abierto aunque la sesion venza por debajo.
void abrirAsistente(BuildContext context) =>
    Navigator.of(context, rootNavigator: true).push(
        MaterialPageRoute<void>(builder: (_) => const PantallaAsistente()));
