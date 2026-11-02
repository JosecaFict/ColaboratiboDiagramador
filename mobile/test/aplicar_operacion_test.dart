import 'package:colabmodel_app/visor/aplicar_operacion.dart';
import 'package:colabmodel_app/visor/modelo.dart';
import 'package:flutter_test/flutter_test.dart';

/// Repite los casos de `applyOperation.ts` tal como los ejercitan
/// `historialLocal.test.ts`, `propuesta.test.ts` y `traductor.test.ts`.
/// Si un caso cambia alla, cambia aca (quinto espejo, ADR-036).

OperacionAplicada op(String tipo, Map<String, dynamic> payload, {int seq = 1}) =>
    OperacionAplicada(opId: 'op$seq', seq: seq, type: tipo, payload: payload);

UmlClass libro() => UmlClass(
      id: 'libro',
      diagramId: 'd',
      name: 'Libro',
      attributes: [
        UmlAttribute({'id': 'paginas', 'classId': 'libro', 'name': 'paginas', 'dataType': 'string'}),
      ],
    );

EstadoModelo conLibroEn(double x, double y) => EstadoModelo(
      diagramId: 'd',
      classes: [libro()],
      relationships: [
        UmlRelationship({
          'id': 'rel',
          'diagramId': 'd',
          'sourceClassId': 'libro',
          'targetClassId': 'libro',
          'kind': 'ASSOCIATION',
          'sourceMultiplicity': '1',
          'targetMultiplicity': '0..*',
          'identifying': false,
          'degree': 2,
        }),
      ],
      layout: {'libro': ElementLayout(elementId: 'libro', x: x, y: y, width: 170, height: 60)},
    );

void main() {
  group('espejo (applyOperation)', () {
    test('las marcas de un undo no terminan como campos del atributo', () {
      final despues = aplicarOperacion(
        conLibroEn(0, 0),
        op('UPDATE_ATTRIBUTE', {
          'attributeId': 'paginas',
          'dataType': 'int',
          'deshace': 'op1',
          'esperado': {'dataType': 'string'},
        }, seq: 2),
      );
      final a = despues.classes[0].attributes[0].datos;
      expect(a['dataType'], 'int');
      expect(a.containsKey('esperado'), isFalse);
      expect(a.containsKey('deshace'), isFalse);
      expect(a.containsKey('attributeId'), isFalse);
    });

    test('UPDATE_RELATION tampoco deja las marcas de deshacer', () {
      final despues = aplicarOperacion(
        conLibroEn(0, 0),
        op('UPDATE_RELATION', {
          'relationId': 'rel',
          'targetMultiplicity': '1..*',
          'deshace': 'x',
          'esperado': {'targetMultiplicity': '0..*'},
        }),
      );
      final r = despues.relationships[0].datos;
      expect(r['targetMultiplicity'], '1..*');
      expect(r.containsKey('deshace'), isFalse);
      expect(r.containsKey('esperado'), isFalse);
      expect(r.containsKey('relationId'), isFalse);
    });
  });

  group('BATCH en el espejo del cliente', () {
    test('aplica todos los cambios del lote como una sola operacion', () {
      final lote = op('BATCH', {
        'descripcion': 'm.json',
        'comandos': [
          {'type': 'CREATE_CLASS', 'payload': {'classId': 'c1', 'name': 'Cliente', 'x': 10, 'y': 20}},
          {'type': 'CREATE_CLASS', 'payload': {'classId': 'c2', 'name': 'Pedido'}},
          {
            'type': 'ADD_ATTRIBUTE',
            'payload': {'attributeId': 'a1', 'classId': 'c2', 'name': 'fecha', 'dataType': 'date'},
          },
          {
            'type': 'CREATE_RELATION',
            'payload': {
              'relationId': 'r1',
              'sourceClassId': 'c1',
              'targetClassId': 'c2',
              'kind': 'ASSOCIATION',
            },
          },
        ],
      }, seq: 7);

      final estado = aplicarOperacion(EstadoModelo.vacio('d'), lote);

      expect(estado.classes.map((c) => c.name), ['Cliente', 'Pedido']);
      expect(estado.classes[1].attributes.map((a) => a.name), ['fecha']);
      expect(estado.relationships, hasLength(1));
      expect(estado.layout['c1']!.x, 10);
    });

    test('lo traducido de una foto reconstruye el diagrama (clases, atributo, relacion, posicion)', () {
      final lote = op('BATCH', {
        'comandos': [
          {'type': 'CREATE_CLASS', 'payload': {'classId': 'libro', 'name': 'Libro', 'x': 40, 'y': 40}},
          {'type': 'CREATE_CLASS', 'payload': {'classId': 'socio', 'name': 'Socio', 'x': 420, 'y': 40}},
          {
            'type': 'ADD_ATTRIBUTE',
            'payload': {'attributeId': 't', 'classId': 'libro', 'name': 'titulo'},
          },
          {
            'type': 'CREATE_RELATION',
            'payload': {'relationId': 'r', 'sourceClassId': 'socio', 'targetClassId': 'libro', 'kind': 'ASSOCIATION'},
          },
        ],
      }, seq: 42);

      final estado = aplicarOperacion(EstadoModelo.vacio('d'), lote);

      expect(estado.classes.map((c) => c.name), ['Libro', 'Socio']);
      expect(estado.classes[0].attributes.map((a) => a.name), ['titulo']);
      expect(estado.relationships, hasLength(1));
      expect(estado.layout[estado.classes[1].id]!.x, 420);
    });
  });

  group('clases', () {
    test('CREATE_CLASS usa los valores por defecto del servidor', () {
      final e = aplicarOperacion(EstadoModelo.vacio('d'), op('CREATE_CLASS', {'classId': 'k', 'name': 'K'}));
      final c = e.classes.single;
      expect(c.erKind, 'STRONG');
      expect(c.weak, isFalse);
      expect(c.isAbstract, isFalse);
      expect(c.inheritanceStrategy, 'JOINED');
      final l = e.layout['k']!;
      expect([l.x, l.y, l.width, l.height], [200, 200, 170, 60]);
    });

    test('CREATE_CLASS es idempotente: recuperar del log lo que vino en el snapshot no la dibuja dos veces', () {
      final e = aplicarOperacion(conLibroEn(5, 5), op('CREATE_CLASS', {'classId': 'libro', 'name': 'Otro'}));
      expect(e.classes, hasLength(1));
      expect(e.classes.single.name, 'Libro');
      expect(e.layout['libro']!.x, 5);
    });

    test('RENAME_CLASS cambia solo el nombre', () {
      final e = aplicarOperacion(conLibroEn(0, 0), op('RENAME_CLASS', {'classId': 'libro', 'name': 'Libros'}));
      expect(e.classes.single.name, 'Libros');
      expect(e.classes.single.attributes, hasLength(1));
    });

    test('DELETE_CLASS se lleva su layout y las relaciones que la tocan', () {
      final e = aplicarOperacion(conLibroEn(0, 0), op('DELETE_CLASS', {'classId': 'libro'}));
      expect(e.classes, isEmpty);
      expect(e.relationships, isEmpty);
      expect(e.layout, isEmpty);
    });
  });

  group('atributos y metodos', () {
    test('ADD_ATTRIBUTE con valores por defecto', () {
      final e = aplicarOperacion(
        conLibroEn(0, 0),
        op('ADD_ATTRIBUTE', {'attributeId': 'a', 'classId': 'libro', 'name': 'isbn'}),
      );
      final a = e.classes.single.attributes.last;
      expect(a.dataType, 'string');
      expect(a.visibility, 'PRIVATE');
      expect(a.pk, isFalse);
      expect(a.datos['multivalued'], isFalse);
      expect(a.datos['ordinal'], 0);
    });

    test('ADD_ATTRIBUTE es idempotente', () {
      final e = aplicarOperacion(
        conLibroEn(0, 0),
        op('ADD_ATTRIBUTE', {'attributeId': 'paginas', 'classId': 'libro', 'name': 'otra'}),
      );
      expect(e.classes.single.attributes, hasLength(1));
      expect(e.classes.single.attributes.single.name, 'paginas');
    });

    test('REMOVE_ATTRIBUTE', () {
      final e = aplicarOperacion(conLibroEn(0, 0), op('REMOVE_ATTRIBUTE', {'attributeId': 'paginas'}));
      expect(e.classes.single.attributes, isEmpty);
    });

    test('ADD_METHOD y REMOVE_METHOD', () {
      var e = aplicarOperacion(
        conLibroEn(0, 0),
        op('ADD_METHOD', {'methodId': 'm', 'classId': 'libro', 'name': 'prestar'}),
      );
      final m = e.classes.single.methods.single;
      expect(m.returnType, 'void');
      expect(m.visibility, 'PUBLIC');
      e = aplicarOperacion(e, op('ADD_METHOD', {'methodId': 'm', 'classId': 'libro', 'name': 'otra'}));
      expect(e.classes.single.methods, hasLength(1));
      e = aplicarOperacion(e, op('REMOVE_METHOD', {'methodId': 'm'}));
      expect(e.classes.single.methods, isEmpty);
    });
  });

  group('relaciones', () {
    test('CREATE_RELATION con valores por defecto e idempotencia', () {
      var e = aplicarOperacion(
        conLibroEn(0, 0),
        op('CREATE_RELATION', {'relationId': 'r2', 'sourceClassId': 'libro', 'targetClassId': 'libro', 'kind': 'COMPOSITION'}),
      );
      final r = e.relationships.last.datos;
      expect(r['identifying'], isFalse);
      expect(r['degree'], 2);
      expect(r['name'], isNull);
      e = aplicarOperacion(
        e,
        op('CREATE_RELATION', {'relationId': 'r2', 'sourceClassId': 'libro', 'targetClassId': 'libro', 'kind': 'ASSOCIATION'}),
      );
      expect(e.relationships, hasLength(2));
      expect(e.relationships.last.kind, 'COMPOSITION');
    });

    test('DELETE_RELATION', () {
      final e = aplicarOperacion(conLibroEn(0, 0), op('DELETE_RELATION', {'relationId': 'rel'}));
      expect(e.relationships, isEmpty);
    });
  });

  group('banderas ER', () {
    test('SET_ER_FLAG sobre una clase toca solo la bandera pedida', () {
      var e = aplicarOperacion(
        conLibroEn(0, 0),
        op('SET_ER_FLAG', {'targetType': 'CLASS', 'targetId': 'libro', 'flag': 'weak', 'value': true}),
      );
      expect(e.classes.single.weak, isTrue);
      expect(e.classes.single.erKind, 'STRONG');
      e = aplicarOperacion(
        e,
        op('SET_ER_FLAG', {'targetType': 'CLASS', 'targetId': 'libro', 'flag': 'erKind', 'erKind': 'ASSOCIATIVE'}),
      );
      expect(e.classes.single.erKind, 'ASSOCIATIVE');
      expect(e.classes.single.weak, isTrue);
      e = aplicarOperacion(
        e,
        op('SET_ER_FLAG', {'targetType': 'CLASS', 'targetId': 'libro', 'flag': 'inheritanceStrategy', 'strategy': 'SINGLE_TABLE'}),
      );
      expect(e.classes.single.inheritanceStrategy, 'SINGLE_TABLE');
    });

    test('SET_ER_FLAG sobre un atributo pone [flag] = value', () {
      final e = aplicarOperacion(
        conLibroEn(0, 0),
        op('SET_ER_FLAG', {'targetType': 'ATTRIBUTE', 'targetId': 'paginas', 'flag': 'multivalued', 'value': true}),
      );
      expect(e.classes.single.attributes.single.datos['multivalued'], isTrue);
    });
  });

  group('geometria', () {
    test('MOVE_ELEMENT mueve y conserva tamano y quiebres', () {
      final base = conLibroEn(100, 100);
      final e = aplicarOperacion(base, op('MOVE_ELEMENT', {'elementId': 'libro', 'elementKind': 'CLASS', 'x': 300, 'y': 300}));
      final l = e.layout['libro']!;
      expect([l.x, l.y, l.width, l.height], [300, 300, 170, 60]);
    });

    test('MOVE_ELEMENT con waypoints solo acomoda la linea (aunque la lista venga vacia)', () {
      var e = aplicarOperacion(
        conLibroEn(0, 0),
        op('MOVE_ELEMENT', {
          'elementId': 'rel',
          'elementKind': 'RELATION',
          'waypoints': [
            {'x': 10, 'y': 20},
          ],
        }),
      );
      expect(e.layout['rel']!.waypoints!.single.y, 20);
      expect([e.layout['rel']!.x, e.layout['rel']!.y], [0, 0]);

      // una lista vacia es "verdadera" en JS: vuelve a recta, no mueve nada
      e = aplicarOperacion(e, op('MOVE_ELEMENT', {'elementId': 'rel', 'waypoints': <dynamic>[]}));
      expect(e.layout['rel']!.waypoints, isEmpty);
    });

    test('RESIZE_ELEMENT cambia el tamano, conserva la posicion y suelta los quiebres', () {
      final e = aplicarOperacion(
        conLibroEn(7, 9),
        op('RESIZE_ELEMENT', {'elementId': 'libro', 'width': 300, 'height': 90, 'deshace': 'z'}),
      );
      final l = e.layout['libro']!;
      expect([l.x, l.y, l.width, l.height], [7, 9, 300, 90]);
      expect(l.waypoints, isNull);
    });
  });

  group('lo que no se dibuja', () {
    test('SET_MODELING_DECISION y CONFIRM_SCHEMA no cambian el modelo', () {
      final base = conLibroEn(0, 0);
      expect(aplicarOperacion(base, op('SET_MODELING_DECISION', {'elementId': 'rel'})), same(base));
      expect(aplicarOperacion(base, op('CONFIRM_SCHEMA', {})), same(base));
      expect(aplicarOperacion(base, op('COMANDO_FUTURO', {})), same(base));
    });
  });

  group('operacionDesdeJson', () {
    test('por STOMP: type y payload como objeto', () {
      final o = operacionDesdeJson({
        'opId': 'a',
        'seq': 5,
        'type': 'RENAME_CLASS',
        'origin': 'MOUSE',
        'payload': {'classId': 'libro', 'name': 'X'},
        'userId': 'u',
        'ts': '2026-01-01T00:00:00Z',
      })!;
      expect(o.seq, 5);
      expect(o.type, 'RENAME_CLASS');
      expect(o.payload['name'], 'X');
    });

    test('por REST ?sinceSeq=: opType y payload como TEXTO json', () {
      final o = operacionDesdeJson({
        'seq': 6,
        'opType': 'CREATE_CLASS',
        'payload': '{"classId":"k","name":"K"}',
        'createdAt': '2026-01-01T00:00:00Z',
      })!;
      expect(o.type, 'CREATE_CLASS');
      expect(o.payload['classId'], 'k');
    });

    test('lo que no se entiende se descarta sin romper', () {
      expect(operacionDesdeJson('x'), isNull);
      expect(operacionDesdeJson({'seq': 1}), isNull);
      expect(operacionDesdeJson({'seq': 1, 'type': 'BATCH', 'payload': '{no es json'}), isNull);
    });
  });
}
