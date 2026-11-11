import 'package:colabmodel_app/visor/aplicar_operacion.dart';
import 'package:colabmodel_app/visor/modelo.dart';
import 'package:flutter_test/flutter_test.dart';

/// Contrato UML 2.5.1 v1, rebanada (a): la interfaz es un estereotipo y REALIZATION
/// un tipo de relacion nuevo (D-15). El visor guarda `kind` como texto, asi que lo
/// que se afirma aca es que ninguno de los dos se pierde ni se confunde.
UmlClass clase(String id, {String? estereotipo}) =>
    UmlClass(id: id, diagramId: 'd', name: id, stereotype: estereotipo);

void main() {
  group('interfaz', () {
    test('una clase con el estereotipo interface es interfaz', () {
      expect(clase('Repo', estereotipo: 'interface').esInterfaz, isTrue);
      expect(clase('Repo', estereotipo: ' Interface ').esInterfaz, isTrue);
    });

    test('una clase comun, una enumeracion o otro texto no lo son', () {
      expect(clase('Libro').esInterfaz, isFalse);
      expect(clase('Estado', estereotipo: 'enumeration').esInterfaz, isFalse);
      expect(clase('X', estereotipo: 'interfaces').esInterfaz, isFalse);
    });

    test('el estereotipo sobrevive a ir y volver por JSON', () {
      final releida = UmlClass.desdeJson(clase('Repo', estereotipo: 'interface').aJson());
      expect(releida.esInterfaz, isTrue);
    });

    test('CREATE_CLASS con estereotipo interface crea una interfaz', () {
      final estado = EstadoModelo.vacio('d');
      final nuevo = aplicarOperacion(
        estado,
        OperacionAplicada(
          opId: 'o1',
          seq: 1,
          type: 'CREATE_CLASS',
          payload: {'classId': 'repo', 'name': 'Repositorio', 'stereotype': 'interface'},
        ),
      );
      expect(nuevo.classes.single.esInterfaz, isTrue);
    });
  });

  group('REALIZATION', () {
    test('CREATE_RELATION conserva el tipo REALIZATION y sus extremos', () {
      final estado = EstadoModelo.vacio('d');
      final nuevo = aplicarOperacion(
        estado,
        OperacionAplicada(opId: 'o1', seq: 1, type: 'CREATE_RELATION', payload: {
          'relationId': 'r1',
          'sourceClassId': 'impl',
          'targetClassId': 'repo',
          'kind': 'REALIZATION',
        }),
      );
      final r = nuevo.relationships.single;
      expect(r.kind, 'REALIZATION');
      expect(r.sourceClassId, 'impl');
      expect(r.targetClassId, 'repo');
    });

    test('UPDATE_RELATION cambia el tipo a REALIZATION', () {
      final inicial = EstadoModelo.vacio('d');
      final conRelacion = aplicarOperacion(
        inicial,
        OperacionAplicada(opId: 'o1', seq: 1, type: 'CREATE_RELATION', payload: {
          'relationId': 'r1',
          'sourceClassId': 'impl',
          'targetClassId': 'repo',
          'kind': 'ASSOCIATION',
        }),
      );
      final nuevo = aplicarOperacion(
        conRelacion,
        OperacionAplicada(
            opId: 'o2', seq: 2, type: 'UPDATE_RELATION', payload: {'relationId': 'r1', 'kind': 'REALIZATION'}),
      );
      expect(nuevo.relationships.single.kind, 'REALIZATION');
    });
  });
}
