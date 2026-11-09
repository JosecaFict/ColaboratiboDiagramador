import 'package:colabmodel_app/editor/comando.dart';
import 'package:colabmodel_app/editor/pantallas/interaccion.dart';
import 'package:colabmodel_app/editor/pantallas/pantalla_editor.dart';
import 'package:colabmodel_app/visor/dibujo_clases.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'controlador_editor_test.dart' show Banco;

/// Las pantallas (T-15b) contra el controlador real (T-15a), con el canal y
/// el REST de mentira: lo que se toca sale como comando y lo que confirma el
/// servidor vuelve a la pantalla.
void main() {
  Future<Banco> abrir(WidgetTester tester) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final b = Banco();
    await tester.runAsync(b.iniciar);
    addTearDown(b.cerrar);
    await tester.pumpWidget(MaterialApp(
        home: PantallaEditor(editor: b.editor, titulo: 'Biblioteca', cerrarAlSalir: false)));
    await tester.pumpAndSettle();
    return b;
  }

  Offset enPantalla(WidgetTester tester, Banco b, Offset punto) {
    final visor = find.byType(InteractiveViewer);
    final limites = limitesDe(b.editor.modelo!);
    final escala = escalaParaEncuadrar(tester.getSize(visor), limites);
    return tester.getTopLeft(visor) + (punto - limites.topLeft) * escala;
  }

  testWidgets('tocar, agregar un atributo y ver la confirmacion del servidor', (tester) async {
    final b = await abrir(tester);
    expect(b.editor.puedeEditar, isTrue);

    // Libro esta en (10, 20); Socio no tiene layout y cae en (0, 0), encima de
    // la parte de arriba de Libro. Tocar abajo de Libro la selecciona y pide el lock.
    await tester.tapAt(enPantalla(tester, b, const Offset(60, 80)));
    await tester.pumpAndSettle();
    expect(b.editor.seleccionada, 'c1');
    expect(b.canal.leases.last, {'elementId': 'c1', 'userName': 'Diego #123', 'held': true});
    expect(find.text('titulo: string'), findsOneWidget);

    await tester.enterText(find.widgetWithText(TextField, 'nuevo atributo'), 'isbn');
    await tester.tap(find.byTooltip('Agregar').first);
    // Mientras espera la confirmacion hay una barra animada: no se asienta.
    await tester.pump();
    final enviado = b.canal.comandos.last;
    expect(enviado.type, CommandType.ADD_ATTRIBUTE);
    expect(enviado.payload, containsPair('classId', 'c1'));
    expect(enviado.payload, containsPair('name', 'isbn'));
    expect(b.editor.esperandoConfirmacion, isTrue);
    expect(find.text('isbn: string'), findsNothing, reason: 'sin escritura optimista');

    b.canal.alOperacion({
      'seq': 11,
      'opId': enviado.opId,
      'type': 'ADD_ATTRIBUTE',
      'payload': enviado.payload,
    });
    await tester.pumpAndSettle();
    expect(b.editor.esperandoConfirmacion, isFalse);
    expect(find.text('isbn: string'), findsOneWidget);

    // Cerrar la hoja suelta la seleccion y el lock.
    await tester.tapAt(const Offset(200, 40));
    await tester.pumpAndSettle();
    expect(b.editor.seleccionada, isNull);
    expect(b.canal.leases.last, {'elementId': 'c1', 'userName': 'Diego #123', 'held': false});
  });

  testWidgets('un rechazo del servidor aparece en la pantalla', (tester) async {
    final b = await abrir(tester);
    await tester.tapAt(enPantalla(tester, b, const Offset(60, 80)));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'nuevo atributo'), 'isbn');
    await tester.tap(find.byTooltip('Agregar').first);
    // Mientras espera la confirmacion hay una barra animada: no se asienta.
    await tester.pump();
    b.canal.alRechazo(RechazoComando(
        opId: b.canal.comandos.last.opId,
        reason: RejectionReason.LOCKED_BY_OTHER,
        message: 'Ese elemento lo esta editando Ana #7'));
    await tester.pumpAndSettle();
    expect(find.textContaining('La está editando Ana #7'), findsWidgets);
    expect(b.editor.esperandoConfirmacion, isFalse);

    // Soltar la clase corta el latido del lock (si no, queda un Timer vivo).
    b.editor.seleccionar(null);
    await tester.pumpAndSettle();
    expect(b.canal.leases.last['held'], isFalse);
  });
}
