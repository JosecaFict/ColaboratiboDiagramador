import 'package:colabmodel_app/editor/contrato_editor.dart';
import 'package:colabmodel_app/editor/pantallas/interaccion.dart';
import 'package:colabmodel_app/editor/pantallas/pantalla_editor.dart';
import 'package:colabmodel_app/visor/dibujo_clases.dart';
import 'package:colabmodel_app/visor/modelo.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'editor_falso.dart';

/// La pantalla en un telefono de 400×800, sin cerrar el editor al salir.
Future<void> abrir(WidgetTester tester, EditorFalso e) async {
  tester.view.physicalSize = const Size(400, 800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(MaterialApp(home: PantallaEditor(editor: e, titulo: 'Tienda', cerrarAlSalir: false)));
  // Mientras carga hay una ruedita que nunca se asienta: ahi alcanza con un cuadro.
  if (e.modelo == null) {
    await tester.pump();
  } else {
    await tester.pumpAndSettle();
  }
}

/// Donde cae en la pantalla un punto del modelo, con el encuadre inicial.
Offset enPantalla(WidgetTester tester, EstadoModelo modelo, Offset punto) {
  final visor = find.byType(InteractiveViewer);
  final esquina = tester.getTopLeft(visor);
  final limites = limitesDe(modelo);
  final escala = escalaParaEncuadrar(tester.getSize(visor), limites);
  return esquina + (punto - limites.topLeft) * escala;
}

Future<void> tocarEnModelo(WidgetTester tester, EstadoModelo m, Offset punto) async {
  await tester.tapAt(enPantalla(tester, m, punto));
  await tester.pumpAndSettle();
}

Future<void> elegirEnPaleta(WidgetTester tester, String opcion) async {
  await tester.tap(find.text('Paleta'));
  await tester.pumpAndSettle();
  await tester.tap(find.text(opcion));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('mientras carga muestra la espera y no ofrece la paleta', (tester) async {
    await abrir(tester, EditorFalso());
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(find.text('Paleta'), findsNothing);
  });

  testWidgets('un diagrama vacio invita a crear la primera clase', (tester) async {
    await abrir(tester, EditorFalso(modelo: const EstadoModelo(diagramId: 'd')));
    expect(find.textContaining('El diagrama está vacío'), findsOneWidget);
  });

  testWidgets('Paleta → Clase pide el nombre y la crea', (tester) async {
    final e = EditorFalso(modelo: modeloDePrueba());
    await abrir(tester, e);
    await elegirEnPaleta(tester, 'Clase');
    expect(find.text('Nombre de la clase'), findsOneWidget);
    await tester.enterText(find.byType(TextField), '  Producto ');
    await tester.tap(find.text('Aceptar'));
    await tester.pumpAndSettle();
    expect(e.llamadas, contains('crearElemento:clase:Producto'));
  });

  testWidgets('Paleta → Clase abstracta y Enumeración crean su tipo', (tester) async {
    final e = EditorFalso(modelo: modeloDePrueba());
    await abrir(tester, e);
    await elegirEnPaleta(tester, 'Clase abstracta');
    await tester.enterText(find.byType(TextField), 'Persona');
    await tester.tap(find.text('Aceptar'));
    await tester.pumpAndSettle();
    await elegirEnPaleta(tester, 'Enumeración');
    await tester.enterText(find.byType(TextField), 'Estado');
    await tester.tap(find.text('Aceptar'));
    await tester.pumpAndSettle();
    expect(e.llamadas, containsAll(['crearElemento:abstracta:Persona', 'crearElemento:enumeracion:Estado']));
  });

  testWidgets('un nombre vacio no crea nada', (tester) async {
    final e = EditorFalso(modelo: modeloDePrueba());
    await abrir(tester, e);
    await elegirEnPaleta(tester, 'Clase');
    await tester.enterText(find.byType(TextField), '   ');
    await tester.tap(find.text('Aceptar'));
    await tester.pumpAndSettle();
    expect(e.llamadas.where((l) => l.startsWith('crearElemento')), isEmpty);
  });

  testWidgets('relacion: elegir herencia, tocar origen y destino la crea', (tester) async {
    final m = modeloDePrueba();
    final e = EditorFalso(modelo: m);
    await abrir(tester, e);
    await elegirEnPaleta(tester, 'herencia');
    expect(find.textContaining('Tocá la clase de origen'), findsOneWidget);
    await tocarEnModelo(tester, m, const Offset(150, 120));
    expect(find.text('Ahora tocá la clase de destino'), findsOneWidget);
    await tocarEnModelo(tester, m, const Offset(560, 130));
    expect(e.llamadas, contains('crearRelacion:pedido:cliente:GENERALIZATION'));
    expect(find.textContaining('Tocá la clase'), findsNothing, reason: 'vuelve a Seleccionar');
  });

  testWidgets('tocar una clase la selecciona y abre sus propiedades; cerrar la suelta', (tester) async {
    final m = modeloDePrueba();
    final e = EditorFalso(modelo: m);
    await abrir(tester, e);
    await tocarEnModelo(tester, m, const Offset(150, 120));
    expect(e.llamadas, contains('seleccionar:pedido'));
    expect(find.text('ATRIBUTOS'), findsOneWidget);
    expect(find.text('total: decimal'), findsOneWidget);

    await tester.enterText(find.widgetWithText(TextField, 'nuevo atributo'), 'fecha');
    await tester.tap(find.byTooltip('Agregar').first);
    await tester.pumpAndSettle();
    expect(e.llamadas, contains('agregarAtributo:pedido:fecha:string'));

    await tester.tapAt(const Offset(200, 40)); // fuera de la hoja
    await tester.pumpAndSettle();
    expect(e.llamadas.last, 'seleccionar:null');
  });

  testWidgets('editar un atributo manda solo lo que cambio', (tester) async {
    final m = modeloDePrueba();
    final e = EditorFalso(modelo: m);
    await abrir(tester, e);
    await tocarEnModelo(tester, m, const Offset(150, 120));
    await tester.tap(find.text('total: decimal'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Obligatorio'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Guardar'));
    await tester.pumpAndSettle();
    expect(e.llamadas, contains('actualizarAtributo:total:null:null:null:true:null'));
  });

  testWidgets('borrar una clase pide confirmacion', (tester) async {
    final m = modeloDePrueba();
    final e = EditorFalso(modelo: m);
    await abrir(tester, e);
    await tocarEnModelo(tester, m, const Offset(150, 120));
    await tester.ensureVisible(find.text('Borrar la clase'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Borrar la clase'));
    await tester.pumpAndSettle();
    expect(find.textContaining('También se borran sus 1 relaciones'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Borrar'));
    await tester.pumpAndSettle();
    expect(e.llamadas, contains('borrarClase:pedido'));
  });

  testWidgets('tocar una relacion abre su hoja y cambiar el tipo lo envia', (tester) async {
    final m = modeloDePrueba();
    final e = EditorFalso(modelo: m);
    await abrir(tester, e);
    await tocarEnModelo(tester, m, const Offset(385, 132));
    expect(e.llamadas, contains('seleccionar:r1'));
    expect(find.text('Pedido — Cliente'), findsOneWidget);
    await tester.tap(find.text('composición'));
    await tester.pumpAndSettle();
    expect(e.llamadas, contains('actualizarRelacion:r1:COMPOSITION:null:null'));
  });

  testWidgets('una clase tomada por otro se ve pero no se edita', (tester) async {
    final m = modeloDePrueba();
    final e = EditorFalso(modelo: m)..tomados['pedido'] = 'Ana';
    await abrir(tester, e);
    await tocarEnModelo(tester, m, const Offset(150, 120));
    expect(find.textContaining('La está editando Ana'), findsOneWidget);
    expect(find.widgetWithText(TextField, 'nuevo atributo'), findsNothing);
    expect(tester.widget<IconButton>(find.widgetWithIcon(IconButton, Icons.edit_outlined)).onPressed, isNull);
  });

  testWidgets('un Observador ve todo, la paleta no crea y no se arrastra', (tester) async {
    final m = modeloDePrueba();
    final e = EditorFalso(modelo: m, puedeEditar: false);
    await abrir(tester, e);
    expect(find.textContaining('Solo lectura'), findsOneWidget);
    await tester.tap(find.text('Paleta'));
    await tester.pumpAndSettle();
    expect(tester.widget<ListTile>(find.widgetWithText(ListTile, 'Clase')).enabled, isFalse);
    expect(tester.widget<ListTile>(find.widgetWithText(ListTile, 'herencia')).enabled, isFalse);
  });

  testWidgets('mantener apretado y arrastrar mueve la clase', (tester) async {
    final m = modeloDePrueba();
    final e = EditorFalso(modelo: m);
    await abrir(tester, e);
    final escala = escalaParaEncuadrar(tester.getSize(find.byType(InteractiveViewer)), limitesDe(m));
    final gesto = await tester.startGesture(enPantalla(tester, m, const Offset(150, 120)));
    await tester.pump(const Duration(milliseconds: 600)); // pulsacion larga
    await gesto.moveBy(Offset(0, 50 * escala));
    await tester.pump();
    await gesto.moveBy(Offset(0, 50 * escala));
    await tester.pump();
    await gesto.up();
    await tester.pumpAndSettle();
    expect(e.llamadas.where((l) => l.startsWith('moverClase:pedido:100:')), hasLength(1));
    final y = int.parse(e.llamadas.firstWhere((l) => l.startsWith('moverClase')).split(':').last);
    expect(y, inInclusiveRange(195, 205));
  });

  testWidgets('un rechazo se muestra y «Entendido» lo descarta', (tester) async {
    final e = EditorFalso(modelo: modeloDePrueba());
    await abrir(tester, e);
    e.rechazar(const RechazoVisible(codigo: 'LOCKED_BY_OTHER', mensaje: 'La está editando Ana'));
    await tester.pumpAndSettle();
    expect(find.text('La está editando Ana'), findsOneWidget);
    await tester.tap(find.text('Entendido'));
    await tester.pumpAndSettle();
    expect(e.llamadas, contains('descartarRechazo'));
    expect(find.text('La está editando Ana'), findsNothing);
  });

  testWidgets('sin conexion lo avisa', (tester) async {
    await abrir(tester, EditorFalso(modelo: modeloDePrueba(), conectado: false));
    expect(find.textContaining('Sin conexión'), findsOneWidget);
  });
}
