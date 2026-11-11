import 'package:colabmodel_app/editor/contrato_editor.dart';
import 'package:colabmodel_app/editor/pantallas/hojas.dart';
import 'package:colabmodel_app/visor/aplicar_operacion.dart';
import 'package:colabmodel_app/visor/dibujo_clases.dart';
import 'package:colabmodel_app/visor/modelo.dart';
import 'package:colabmodel_app/visor/sincronizador.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

OperacionAplicada op(String t, Map<String, dynamic> p) => OperacionAplicada(opId: 'o', seq: 1, type: t, payload: p);

void main() {
  test('una realizacion leida del servidor es REALIZACION, no asociacion', () {
    expect(TipoRelacion.desdeClave('REALIZATION'), TipoRelacion.realizacion);
    expect(TipoRelacion.realizacion.clave, 'REALIZATION');
    expect(nombreDeRelacion(TipoRelacion.realizacion), 'realización');
    // lo desconocido sigue cayendo en asociacion, como antes
    expect(TipoRelacion.desdeClave('OTRA'), TipoRelacion.asociacion);
  });

  test('una clase con estereotipo interface se muestra como interfaz en el editor', () {
    final c = UmlClass(id: 'r', diagramId: 'd', name: 'Repo', stereotype: 'interface');
    expect(tipoDeClase(c), TipoDeElemento.interfaz);
    expect(nombreDeTipo(TipoDeElemento.interfaz), 'Interfaz');
    expect(tipoDeClase(const UmlClass(id: 'x', diagramId: 'd', name: 'X')), TipoDeElemento.clase);
    expect(tipoDeClase(const UmlClass(id: 'x', diagramId: 'd', name: 'X', isAbstract: true)), TipoDeElemento.abstracta);
  });

  testWidgets('dibuja una interfaz y su realizacion sin fallar', (tester) async {
    var e = EstadoModelo.vacio('d');
    e = aplicarOperacion(e, op('CREATE_CLASS', {'classId': 'a', 'name': 'Libro', 'x': 40, 'y': 40}));
    e = aplicarOperacion(
        e, op('CREATE_CLASS', {'classId': 'i', 'name': 'Prestable', 'x': 400, 'y': 40, 'stereotype': 'interface'}));
    e = aplicarOperacion(
        e, op('CREATE_RELATION', {'relationId': 'r', 'sourceClassId': 'a', 'targetClassId': 'i', 'kind': 'REALIZATION'}));
    expect(e.relationships.single.kind, 'REALIZATION');
    await tester.pumpWidget(MaterialApp(
      home: CustomPaint(
        size: const Size(800, 400),
        painter: DibujoClases(
          estado: e,
          bloqueos: const <String, Bloqueo>{},
          seleccionadosPorOtros: const {},
          colores: ColorScheme.fromSeed(seedColor: Colors.blue),
        ),
      ),
    ));
    expect(tester.takeException(), isNull);
  });
}
