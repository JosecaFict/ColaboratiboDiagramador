import 'package:colabmodel_app/visor/modelo.dart';
import 'package:flutter_test/flutter_test.dart';

/// Contrato UML 2.5.1 v1, rebanada (b): isStatic e isAbstract del metodo (D-15).
/// Aplicar ADD_METHOD en el visor es de Pedro (aplicar_operacion.dart copia campo por campo).
UmlClass claseConMetodo(Map<String, dynamic> metodo) => UmlClass.desdeJson({
      'id': 'figura',
      'name': 'Figura',
      'methods': [metodo],
    });

void main() {
  test('un metodo de un snapshot anterior, sin banderas, no es estatico ni abstracto', () {
    final m = claseConMetodo({'id': 'm', 'classId': 'figura', 'name': 'area'}).methods.single;
    expect(m.isStatic, isFalse);
    expect(m.isAbstract, isFalse);
  });

  test('las banderas del snapshot se leen y sobreviven a ir y volver por JSON', () {
    final clase = claseConMetodo({'id': 'm', 'classId': 'figura', 'name': 'crear', 'isStatic': true});
    expect(clase.methods.single.isStatic, isTrue);
    expect(clase.methods.single.isAbstract, isFalse);

    final releida = UmlClass.desdeJson(clase.aJson());
    expect(releida.methods.single.isStatic, isTrue);
  });

  test('mezclar un cambio parcial (UPDATE_METHOD) no pierde las demas banderas', () {
    final m = claseConMetodo({'id': 'm', 'name': 'area', 'isAbstract': true}).methods.single;
    final editado = m.mezclar({'isStatic': false, 'returnType': 'double'});
    expect(editado.isAbstract, isTrue);
    expect(editado.isStatic, isFalse);
    expect(editado.returnType, 'double');
  });

  test('ADD_METHOD aplicado en el visor conserva las banderas', () {},
      skip: 'Pedro: aplicar_operacion.dart arma el metodo campo por campo y descarta isStatic e isAbstract');
}
