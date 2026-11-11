import 'package:colabmodel_app/editor/contrato_editor.dart';
import 'package:colabmodel_app/editor/pantallas/hojas.dart';
import 'package:colabmodel_app/visor/aplicar_operacion.dart';
import 'package:colabmodel_app/visor/dibujo_clases.dart';
import 'package:colabmodel_app/visor/modelo.dart';
import 'package:colabmodel_app/visor/sincronizador.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

OperacionAplicada op(String t, Map<String, dynamic> p) => OperacionAplicada(opId: 'o', seq: 1, type: t, payload: p);

EstadoModelo conMiembros() {
  var e = EstadoModelo.vacio('d');
  e = aplicarOperacion(e, op('CREATE_CLASS', {'classId': 'c', 'name': 'Figura', 'x': 40, 'y': 40, 'isAbstract': true}));
  e = aplicarOperacion(
      e, op('ADD_ATTRIBUTE', {'attributeId': 'a1', 'classId': 'c', 'name': 'contador', 'isStatic': true}));
  e = aplicarOperacion(
      e, op('ADD_ATTRIBUTE', {'attributeId': 'a2', 'classId': 'c', 'name': 'codigo', 'readOnly': true}));
  e = aplicarOperacion(
      e, op('ADD_METHOD', {'methodId': 'm1', 'classId': 'c', 'name': 'area', 'isAbstract': true}));
  e = aplicarOperacion(e, op('ADD_METHOD', {'methodId': 'm2', 'classId': 'c', 'name': 'crear', 'isStatic': true}));
  e = aplicarOperacion(e, op('ADD_METHOD', {'methodId': 'm3', 'classId': 'c', 'name': 'pintar'}));
  return e;
}

void main() {
  test('las banderas llegan al modelo local y un metodo comun no las trae', () {
    final c = conMiembros().classes.single;
    expect(c.attributes[0].isStatic, isTrue);
    expect(c.attributes[1].readOnly, isTrue);
    expect(c.methods[0].isAbstract, isTrue);
    expect(c.methods[1].isStatic, isTrue);
    expect(c.methods[2].isStatic, isFalse);
    expect(c.methods[2].isAbstract, isFalse);
    expect(c.methods[2].datos.containsKey('isStatic'), isFalse);
  });

  test('UPDATE_METHOD cambia solo lo que trae y no guarda las marcas de deshacer', () {
    final e = aplicarOperacion(
        conMiembros(),
        op('UPDATE_METHOD', {
          'methodId': 'm3',
          'isStatic': true,
          'esperado': {'isStatic': false},
        }));
    final m = e.classes.single.methods.firstWhere((m) => m.id == 'm3');
    expect(m.isStatic, isTrue);
    expect(m.name, 'pintar');
    expect(m.datos.containsKey('esperado'), isFalse);
    // los demas no se tocan
    expect(e.classes.single.methods.firstWhere((m) => m.id == 'm1').isAbstract, isTrue);
  });

  testWidgets('dibuja subrayado, cursiva y {readOnly} sin fallar', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: CustomPaint(
        size: const Size(800, 400),
        painter: DibujoClases(
          estado: conMiembros(),
          bloqueos: const <String, Bloqueo>{},
          seleccionadosPorOtros: const {},
          colores: ColorScheme.fromSeed(seedColor: Colors.blue),
        ),
      ),
    ));
    expect(tester.takeException(), isNull);
  });

  test('los cambios de estatico y solo lectura cuentan como cambios', () {
    expect(hayCambios(const DatosAtributo(estatico: true)), isTrue);
    expect(hayCambios(const DatosAtributo(soloLectura: false)), isTrue);
    expect(hayCambios(const DatosAtributo()), isFalse);
  });
}
