// Como se llega al editor desde la lista de diagramas del visor.
//
// El editor de verdad (ControladorEditor) es de T-15a. Hasta que este
// integrado, la fabrica queda en null y el boton «Editar» no aparece: el
// visor sigue funcionando igual que antes. Al integrar, `main.dart` instala
// la fabrica una sola vez.

import '../../visor/cliente_herramienta.dart';
import '../contrato_editor.dart';

/// Arma el editor de un diagrama con lo que ya tiene el visor.
typedef FabricaDeEditor = EditorDeDiagrama Function({
  required ClienteHerramienta cliente,
  required String diagramId,

  /// OWNER, EDITOR o VIEWER (`ProyectoResumen.miRol`).
  required String? rol,

  /// Para el nombre del lock («La está editando Ana»).
  required String nombreUsuario,
});

/// La instala `main.dart`. null = todavia no hay editor y no se ofrece.
FabricaDeEditor? fabricaDeEditor;

/// Solo Modelador y Propietario editan (ADR-026). El Observador va al visor.
bool rolPuedeEditar(String? rol) => rol == 'EDITOR' || rol == 'OWNER';
