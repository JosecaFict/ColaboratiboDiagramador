import 'package:colabmodel_app/visor/modelo.dart';
import 'package:flutter_test/flutter_test.dart';

/// Contrato UML 2.5.1 v1, rebanada (c): isStatic y readOnly del atributo (D-15).
/// Aplicar ADD/UPDATE_ATTRIBUTE en el visor es de Pedro (aplicar_operacion.dart).
UmlClass claseConAtributo(Map<String, dynamic> atributo) => UmlClass.desdeJson({
      'id': 'libro',
      'name': 'Libro',
      'attributes': [atributo],
    });

void main() {
  test('un atributo de un snapshot anterior, sin banderas, no es estatico ni de solo lectura', () {
    final a = claseConAtributo({'id': 'a', 'classId': 'libro', 'name': 'total'}).attributes.single;
    expect(a.isStatic, isFalse);
    expect(a.readOnly, isFalse);
  });

  test('las banderas del snapshot se leen y sobreviven a ir y volver por JSON', () {
    final clase = claseConAtributo({'id': 'a', 'name': 'MAX', 'isStatic': true, 'readOnly': true});
    expect(clase.attributes.single.isStatic, isTrue);
    expect(clase.attributes.single.readOnly, isTrue);

    final releida = UmlClass.desdeJson(clase.aJson());
    expect(releida.attributes.single.isStatic, isTrue);
    expect(releida.attributes.single.readOnly, isTrue);
  });

  test('mezclar un cambio parcial (UPDATE_ATTRIBUTE) no pierde la otra bandera', () {
    final a = claseConAtributo({'id': 'a', 'name': 'MAX', 'readOnly': true}).attributes.single;
    final editado = a.mezclar({'isStatic': true});
    expect(editado.readOnly, isTrue);
    expect(editado.isStatic, isTrue);
  });

  test('ADD_ATTRIBUTE aplicado en el visor conserva las banderas', () {},
      skip: 'Pedro: aplicar_operacion.dart arma el atributo campo por campo y descarta isStatic y readOnly');
}
