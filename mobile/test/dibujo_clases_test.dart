import 'package:colabmodel_app/visor/aplicar_operacion.dart';
import 'package:colabmodel_app/visor/dibujo_clases.dart';
import 'package:colabmodel_app/visor/modelo.dart';
import 'package:colabmodel_app/visor/sincronizador.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

OperacionAplicada op(String t, Map<String, dynamic> p) => OperacionAplicada(opId: 'o', seq: 1, type: t, payload: p);

EstadoModelo modelo() {
  var e = EstadoModelo.vacio('d');
  e = aplicarOperacion(e, op('CREATE_CLASS', {'classId': 'a', 'name': 'Libro', 'x': 40, 'y': 40}));
  e = aplicarOperacion(e, op('CREATE_CLASS', {'classId': 'b', 'name': 'Socio', 'x': 400, 'y': 60, 'stereotype': 'entity'}));
  e = aplicarOperacion(e, op('CREATE_CLASS', {'classId': 'c', 'name': 'Prestamo', 'x': 220, 'y': 300}));
  for (var i = 0; i < 6; i++) {
    e = aplicarOperacion(e, op('ADD_ATTRIBUTE', {'attributeId': 'a$i', 'classId': 'a', 'name': 'campo$i', 'pk': i == 0}));
  }
  e = aplicarOperacion(e, op('ADD_METHOD', {'methodId': 'm', 'classId': 'a', 'name': 'prestar', 'params': [{'name': 'quien', 'type': 'Socio'}]}));
  for (final k in ['ASSOCIATION', 'AGGREGATION', 'COMPOSITION', 'GENERALIZATION', 'DEPENDENCY']) {
    e = aplicarOperacion(e, op('CREATE_RELATION', {
      'relationId': k, 'sourceClassId': 'b', 'targetClassId': k.length.isEven ? 'a' : 'c', 'kind': k,
      'sourceMultiplicity': '1', 'targetMultiplicity': '0..*', 'name': k == 'ASSOCIATION' ? 'presta' : null,
    }));
  }
  return e;
}

void main() {
  test('una caja nunca es mas chica que su contenido (el layout web puede ser de 60 de alto)', () {
    final e = modelo();
    final libro = e.classes.first;
    final r = rectDe(libro, e.layout['a']);
    expect(e.layout['a']!.height, 60);
    expect(r.height, greaterThan(60));
    expect(r.width, greaterThanOrEqualTo(170));
  });

  test('los limites incluyen todas las cajas con margen', () {
    final e = modelo();
    final l = limitesDe(e);
    for (final c in e.classes) {
      expect(l.contains(rectDe(c, e.layout[c.id]).topLeft), isTrue);
      expect(l.contains(rectDe(c, e.layout[c.id]).bottomRight), isTrue);
    }
    expect(limitesDe(EstadoModelo.vacio('d')).isEmpty, isFalse);
  });

  testWidgets('dibuja clases, los 5 tipos de relacion, locks y presencia sin fallar', (tester) async {
    final e = modelo();
    final l = limitesDe(e);
    await tester.pumpWidget(MaterialApp(
      home: InteractiveViewer(
        constrained: false,
        child: SizedBox(
          width: l.right,
          height: l.bottom,
          child: CustomPaint(
            painter: DibujoClases(
              estado: e,
              bloqueos: {'a': const Bloqueo('a', 'u', 'Ana')},
              seleccionadosPorOtros: {'b'},
              colores: ColorScheme.fromSeed(seedColor: Colors.blue),
            ),
          ),
        ),
      ),
    ));
    expect(tester.takeException(), isNull);
    expect(find.byType(CustomPaint), findsWidgets);
  });

  testWidgets('relaciones con una clase que ya no existe se ignoran, no rompen el dibujo', (tester) async {
    var e = modelo();
    e = e.copiar(relationships: [
      ...e.relationships,
      const UmlRelationship({'id': 'x', 'sourceClassId': 'a', 'targetClassId': 'fantasma', 'kind': 'ASSOCIATION'}),
    ]);
    await tester.pumpWidget(MaterialApp(
      home: CustomPaint(
        size: const Size(800, 600),
        painter: DibujoClases(
          estado: e,
          bloqueos: const {},
          seleccionadosPorOtros: const {},
          colores: ColorScheme.fromSeed(seedColor: Colors.blue),
        ),
      ),
    ));
    expect(tester.takeException(), isNull);
  });
}
