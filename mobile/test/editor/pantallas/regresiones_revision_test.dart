// Regresiones de la revision de T-15b (Codex, sobre 52118c6). Cada grupo
// reproduce un hallazgo; las de Codex estan adaptadas de su worktree
// revision-t15b-codex.

import 'package:colabmodel_app/editor/comando.dart';
import 'package:colabmodel_app/editor/pantallas/hojas.dart';
import 'package:colabmodel_app/editor/pantallas/interaccion.dart';
import 'package:colabmodel_app/editor/pantallas/pantalla_editor.dart';
import 'package:colabmodel_app/visor/dibujo_clases.dart';
import 'package:colabmodel_app/visor/modelo.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../controlador_editor_test.dart' show Banco;
import 'editor_falso.dart';

void telefono(WidgetTester t) {
  t.view.physicalSize = const Size(400, 800);
  t.view.devicePixelRatio = 1;
  addTearDown(t.view.reset);
}

/// Donde cae en la pantalla un punto del modelo, con el encuadre inicial.
Offset enPantalla(WidgetTester t, EstadoModelo modelo, Offset punto) {
  final visor = find.byType(InteractiveViewer);
  final limites = limitesDe(modelo);
  final escala = escalaParaEncuadrar(t.getSize(visor), limites);
  return t.getTopLeft(visor) + (punto - limites.topLeft) * escala;
}

const negativa = EstadoModelo(
  diagramId: 'd',
  classes: [UmlClass(id: 'c', diagramId: 'd', name: 'Negativa')],
  layout: {'c': ElementLayout(elementId: 'c', x: -400, y: -300, width: 170, height: 60)},
);

EstadoModelo conClase(EstadoModelo base, UmlClass clase, [ElementLayout? lugar]) => base.copiar(
      classes: [...base.classes, clase],
      layout: {...base.layout, if (lugar != null) clase.id: lugar},
    );

/// La hoja de clase atada al editor, como la muestra la pantalla.
Future<void> abrirHoja(WidgetTester t, EditorFalso e, String classId) async {
  await t.pumpWidget(MaterialApp(
    home: Scaffold(
      body: ListenableBuilder(
        listenable: e,
        builder: (context, _) => SingleChildScrollView(
          child: HojaDeClase(
              editor: e,
              modelo: e.modelo!,
              clase: e.modelo!.classes.firstWhere((c) => c.id == classId),
              editable: true),
        ),
      ),
    ),
  ));
  await t.pumpAndSettle();
}

Future<void> elegir(WidgetTester t, Finder selector, String opcion) async {
  await t.tap(selector);
  await t.pumpAndSettle();
  await t.tap(find.text(opcion).last);
  await t.pumpAndSettle();
}

void main() {
  group('P1 · coordenadas negativas', () {
    test('la escena incluye el diagrama y el origen', () {
      expect(escenaDe(const Rect.fromLTRB(-460, -360, -170, -180)), const Rect.fromLTRB(-460, -360, 0, 0));
      expect(escenaDe(const Rect.fromLTRB(40, 40, 730, 280)), const Rect.fromLTRB(0, 0, 730, 280));
    });

    testWidgets('una clase en (-400,-300) se dibuja, se toca y se arrastra', (t) async {
      telefono(t);
      final e = EditorFalso(modelo: negativa);
      await t.pumpWidget(MaterialApp(home: PantallaEditor(editor: e, titulo: 'D', cerrarAlSalir: false)));
      await t.pumpAndSettle();
      expect(t.takeException(), isNull);

      await t.tapAt(enPantalla(t, negativa, const Offset(-350, -280)));
      await t.pumpAndSettle();
      expect(e.llamadas, contains('seleccionar:c'));
      await t.tapAt(const Offset(200, 40)); // cierra la hoja
      await t.pumpAndSettle();

      final escala = escalaParaEncuadrar(t.getSize(find.byType(InteractiveViewer)), limitesDe(negativa));
      final gesto = await t.startGesture(enPantalla(t, negativa, const Offset(-350, -280)));
      await t.pump(const Duration(milliseconds: 600));
      await gesto.moveBy(Offset(0, 30 * escala));
      await t.pump();
      await gesto.moveBy(Offset(0, 30 * escala));
      await t.pump();
      await gesto.up();
      await t.pumpAndSettle();
      final movida = e.llamadas.lastWhere((l) => l.startsWith('moverClase:c:'));
      expect(movida.split(':')[2], '-400');
      expect(int.parse(movida.split(':')[3]), inInclusiveRange(-245, -235));
    });

    testWidgets('si aparece algo arriba a la izquierda, lo que se ve no salta', (t) async {
      telefono(t);
      final base = modeloDePrueba();
      final e = EditorFalso(modelo: base);
      await t.pumpWidget(MaterialApp(home: PantallaEditor(editor: e, titulo: 'D', cerrarAlSalir: false)));
      await t.pumpAndSettle();
      final pedido = enPantalla(t, base, const Offset(150, 120));

      // Otra sesion mueve una clase a negativo: la escena cambia de esquina.
      e.modelo = conClase(base, const UmlClass(id: 'lejos', diagramId: 'd', name: 'Lejos'),
          const ElementLayout(elementId: 'lejos', x: -600, y: -400, width: 170, height: 60));
      await t.pumpAndSettle();
      expect(t.takeException(), isNull);
      await t.tapAt(pedido);
      await t.pumpAndSettle();
      expect(e.llamadas, contains('seleccionar:pedido'));
    });
  });

  group('P2 · tipos que desaparecen', () {
    final conEstado = conClase(
        modeloDePrueba(), const UmlClass(id: 'estado', diagramId: 'd', name: 'Estado', stereotype: 'enumeration'));

    testWidgets('borrar la enum elegida para un atributo avisa y vuelve a string', (t) async {
      telefono(t);
      final e = EditorFalso(modelo: conEstado);
      await abrirHoja(t, e, 'pedido');
      await elegir(t, find.byType(DropdownButton<String>).first, 'Estado');

      e.modelo = modeloDePrueba(); // DELETE_CLASS de otra sesion
      await t.pumpAndSettle();
      expect(t.takeException(), isNull);
      expect(find.text('«Estado» ya no existe: quedó string.'), findsOneWidget);

      await t.enterText(find.widgetWithText(TextField, 'nuevo atributo'), 'fecha');
      await t.tap(find.byTooltip('Agregar').first);
      await t.pumpAndSettle();
      expect(e.llamadas, contains('agregarAtributo:pedido:fecha:string'));
      expect(find.textContaining('ya no existe'), findsNothing);
    });

    testWidgets('renombrar la enum elegida como retorno de un metodo avisa y vuelve a void', (t) async {
      telefono(t);
      final e = EditorFalso(modelo: conEstado);
      await abrirHoja(t, e, 'pedido');
      await t.ensureVisible(find.byType(DropdownButton<String>).last);
      await elegir(t, find.byType(DropdownButton<String>).last, 'Estado');

      e.modelo = conClase(modeloDePrueba(),
          const UmlClass(id: 'estado', diagramId: 'd', name: 'EstadoPedido', stereotype: 'enumeration'));
      await t.pumpAndSettle();
      expect(t.takeException(), isNull);
      expect(find.text('«Estado» ya no existe: quedó void.'), findsOneWidget);
    });

    // Reevaluacion de Codex sobre 046fe6c: el menu ya estaba abierto cuando llego el borrado.
    Future<void> elegirEnMenuViejo(WidgetTester t, EditorFalso e, Finder selector) async {
      await t.ensureVisible(selector);
      await t.tap(selector);
      await t.pumpAndSettle();
      e.modelo = modeloDePrueba(); // DELETE_CLASS de otra sesion, con el menu abierto
      await t.pumpAndSettle();
      await t.tap(find.text('Estado').last);
      await t.pumpAndSettle();
      expect(t.takeException(), isNull);
    }

    testWidgets('elegir en el menu de atributo una enum borrada mientras estaba abierto no la toma', (t) async {
      telefono(t);
      final e = EditorFalso(modelo: conEstado);
      await abrirHoja(t, e, 'pedido');
      await elegirEnMenuViejo(t, e, find.byType(DropdownButton<String>).first);
      expect(find.text('«Estado» ya no existe: quedó string.'), findsOneWidget);

      await t.enterText(find.widgetWithText(TextField, 'nuevo atributo'), 'fecha');
      await t.tap(find.byTooltip('Agregar').first);
      await t.pumpAndSettle();
      expect(e.llamadas, contains('agregarAtributo:pedido:fecha:string'));
    });

    testWidgets('elegir en el menu de retorno una enum borrada mientras estaba abierto no la toma', (t) async {
      telefono(t);
      final e = EditorFalso(modelo: conEstado);
      await abrirHoja(t, e, 'pedido');
      await elegirEnMenuViejo(t, e, find.byType(DropdownButton<String>).last);
      expect(find.text('«Estado» ya no existe: quedó void.'), findsOneWidget);

      await t.enterText(find.widgetWithText(TextField, 'nuevo método'), 'cerrar');
      await t.tap(find.byTooltip('Agregar').last);
      await t.pumpAndSettle();
      expect(e.llamadas, contains('agregarMetodo:pedido:cerrar:void'));
    });

    test('dos clases con el mismo nombre no repiten la opcion', () {
      final m =
          conClase(conEstado, const UmlClass(id: 'estado2', diagramId: 'd', name: 'Estado', stereotype: 'enumeration'));
      final tipos = tiposDeAtributo(m, excepto: 'pedido');
      expect(tipos.where((t) => t == 'Estado'), hasLength(1));
      expect(tiposDeRetorno(m, excepto: 'pedido').where((t) => t == 'Estado'), hasLength(1));
    });
  });

  group('P2 · multiplicidad sin definir', () {
    test('«sin definir» solo se ofrece si ya esta asi', () {
      expect(opcionesDeMultiplicidad(''), ['', '1', '0..1', '1..*', '0..*']);
      expect(opcionesDeMultiplicidad('1'), ['1', '0..1', '1..*', '0..*']);
      expect(opcionesDeMultiplicidad('2..5'), ['1', '0..1', '1..*', '0..*', '2..5']);
    });

    testWidgets('con el controlador real, cada opcion visible sale por el bus', (t) async {
      telefono(t);
      final b = Banco();
      await t.runAsync(b.iniciar);
      addTearDown(b.cerrar);
      b.canal.alOperacion({
        'seq': 11,
        'opId': 'o11',
        'type': 'UPDATE_RELATION',
        'payload': {'relationId': 'r1', 'sourceMultiplicity': '1', 'targetMultiplicity': '0..*'},
      });
      await t.pumpWidget(MaterialApp(
          home: Scaffold(
              body: SingleChildScrollView(
                  child: HojaDeRelacion(
                      editor: b.editor,
                      modelo: b.editor.modelo!,
                      relacion: b.editor.modelo!.relationships.single,
                      editable: true)))));
      await t.pumpAndSettle();
      await t.tap(find.byType(DropdownButton<String>).first);
      await t.pumpAndSettle();
      expect(find.text('sin definir'), findsNothing, reason: 'el contrato no puede borrar una multiplicidad');
      await t.tap(find.text('0..1').last);
      await t.pump();
      expect(b.canal.comandos, hasLength(1));
      expect(b.canal.comandos.single.type, CommandType.UPDATE_RELATION);
      expect(b.canal.comandos.single.payload, containsPair('sourceMultiplicity', '0..1'));
      expect(b.editor.ultimoRechazo, isNull);
    });
  });

  group('P2 · valores embebidos como tipo', () {
    final m = conClase(
        modeloDePrueba(), const UmlClass(id: 'direccion', diagramId: 'd', name: 'Direccion', stereotype: 'embeddable'));

    test('una clase comun puede tener un atributo de tipo valor', () {
      expect(tiposDeAtributo(m, excepto: 'pedido'), contains('Direccion'));
    });

    test('un valor no se ofrece dentro de otro valor ni dentro de si mismo', () {
      final conOtro =
          conClase(m, const UmlClass(id: 'coordenada', diagramId: 'd', name: 'Coordenada', stereotype: 'embeddable'));
      expect(tiposDeAtributo(conOtro, excepto: 'direccion'), isNot(contains('Coordenada')));
      expect(tiposDeAtributo(conOtro, excepto: 'direccion'), isNot(contains('Direccion')));
    });

    test('los valores no son tipos de retorno', () {
      expect(tiposDeRetorno(m, excepto: 'pedido'), isNot(contains('Direccion')));
      expect(tiposDeRetorno(m, excepto: 'pedido').first, 'void');
    });
  });
}
