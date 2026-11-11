import 'dart:convert';

import 'modelo.dart';

/// ESPEJO del efecto que aplica el servidor.
///
/// Quinto espejo del proyecto:
///   `CommandApplier.java` <-> `frontend/src/canvas/applyOperation.ts` <-> este archivo.
/// El servidor es la fuente de verdad; esto solo reconstruye en el telefono lo
/// que ya paso por el bus. Si cambia el efecto de un comando, cambia en los tres.
///
/// Cada rama copia a su par de `applyOperation.ts` (mismo orden, mismos
/// valores por defecto, misma idempotencia). Las pruebas de
/// `test/aplicar_operacion_test.dart` repiten los casos del TypeScript.
EstadoModelo aplicarOperacion(EstadoModelo estado, OperacionAplicada op) {
  final p = op.payload;

  switch (op.type) {
    // Aterrizaje atomico de una foto o de una importacion XMI: UNA operacion,
    // UN seq; se pliega comando por comando.
    case 'BATCH':
      final comandos = (p['comandos'] as List?) ?? const [];
      var acc = estado;
      for (final c in comandos) {
        final m = c as Map;
        acc = aplicarOperacion(
          acc,
          OperacionAplicada(
            opId: op.opId,
            seq: op.seq,
            origin: op.origin,
            userId: op.userId,
            type: m['type'] as String,
            payload: Map<String, dynamic>.from((m['payload'] as Map?) ?? const {}),
          ),
        );
      }
      return acc;

    case 'CREATE_CLASS':
      {
        // Idempotente: lo que ya vino en el snapshot no se dibuja dos veces.
        final classId = p['classId'] as String;
        if (estado.classes.any((x) => x.id == classId)) return estado;
        final c = UmlClass(
          id: classId,
          diagramId: estado.diagramId,
          name: p['name'] as String,
          stereotype: p['stereotype'] as String?,
          isAbstract: p['isAbstract'] ?? false,
          weak: p['weak'] ?? false,
          erKind: (p['erKind'] ?? 'STRONG') as String,
          inheritanceStrategy: 'JOINED',
        );
        final l = ElementLayout(
          elementId: classId,
          x: _n(p['x'], 200),
          y: _n(p['y'], 200),
          width: _n(p['width'], 170),
          height: _n(p['height'], 60),
        );
        return estado.copiar(
          classes: [...estado.classes, c],
          layout: {...estado.layout, classId: l},
        );
      }

    case 'RENAME_CLASS':
      return _conClase(estado, p['classId'] as String, (c) => c.copiar(name: p['name'] as String));

    case 'DELETE_CLASS':
      {
        final classId = p['classId'] as String;
        final layout = {...estado.layout}..remove(classId);
        return estado.copiar(
          classes: [for (final c in estado.classes) if (c.id != classId) c],
          relationships: [
            for (final r in estado.relationships)
              if (r.sourceClassId != classId && r.targetClassId != classId) r,
          ],
          layout: layout,
        );
      }

    case 'ADD_ATTRIBUTE':
      {
        final attributeId = p['attributeId'] as String;
        if (estado.classes.any((c) => c.attributes.any((x) => x.id == attributeId))) return estado;
        final a = UmlAttribute({
          'id': attributeId,
          'classId': p['classId'],
          'name': p['name'],
          'dataType': p['dataType'] ?? 'string',
          'visibility': p['visibility'] ?? 'PRIVATE',
          'length': p['length'],
          'pk': p['pk'] ?? false,
          'required': p['required'] ?? false,
          'unique': p['unique'] ?? false,
          'defaultValue': p['defaultValue'],
          'ordinal': p['ordinal'] ?? 0,
          'multivalued': p['multivalued'] ?? false,
          'derived': p['derived'] ?? false,
          'partialKey': p['partialKey'] ?? false,
          'parentAttributeId': p['parentAttributeId'],
          'minValue': p['minValue'],
          'maxValue': p['maxValue'],
          if (p['isStatic'] == true) 'isStatic': true,
          if (p['readOnly'] == true) 'readOnly': true,
        });
        return _conClase(estado, p['classId'] as String, (c) => c.copiar(attributes: [...c.attributes, a]));
      }

    case 'UPDATE_ATTRIBUTE':
      {
        // `attributeId` identifica, no es un campo; las marcas de deshacer
        // (ADR-027) tampoco: son precondicion y auditoria.
        final cambios = {...p}
          ..remove('attributeId')
          ..remove('deshace')
          ..remove('esperado');
        return _conAtributo(estado, p['attributeId'] as String, (a) => a.mezclar(cambios));
      }

    case 'REMOVE_ATTRIBUTE':
      return estado.copiar(
        classes: [
          for (final c in estado.classes)
            c.copiar(attributes: [for (final a in c.attributes) if (a.id != p['attributeId']) a]),
        ],
      );

    case 'ADD_METHOD':
      {
        final methodId = p['methodId'] as String;
        if (estado.classes.any((c) => c.methods.any((x) => x.id == methodId))) return estado;
        final m = UmlMethod({
          'id': methodId,
          'classId': p['classId'],
          'name': p['name'],
          'returnType': p['returnType'] ?? 'void',
          'params': p['params'] ?? const [],
          'visibility': p['visibility'] ?? 'PUBLIC',
          'ordinal': p['ordinal'] ?? 0,
          if (p['isStatic'] == true) 'isStatic': true,
          if (p['isAbstract'] == true) 'isAbstract': true,
        });
        return _conClase(estado, p['classId'] as String, (c) => c.copiar(methods: [...c.methods, m]));
      }

    case 'UPDATE_METHOD':
      {
        // Igual que UPDATE_ATTRIBUTE: `methodId` identifica y las marcas de deshacer no son campos.
        final cambios = {...p}
          ..remove('methodId')
          ..remove('deshace')
          ..remove('esperado');
        return estado.copiar(
          classes: [
            for (final c in estado.classes)
              c.copiar(methods: [for (final m in c.methods) m.id == p['methodId'] ? m.mezclar(cambios) : m]),
          ],
        );
      }

    case 'REMOVE_METHOD':
      return estado.copiar(
        classes: [
          for (final c in estado.classes)
            c.copiar(methods: [for (final m in c.methods) if (m.id != p['methodId']) m]),
        ],
      );

    case 'CREATE_RELATION':
      {
        final relationId = p['relationId'] as String;
        if (estado.relationships.any((x) => x.id == relationId)) return estado;
        final r = UmlRelationship({
          'id': relationId,
          'diagramId': estado.diagramId,
          'sourceClassId': p['sourceClassId'],
          'targetClassId': p['targetClassId'],
          'kind': p['kind'],
          'name': p['name'],
          'sourceRole': p['sourceRole'],
          'targetRole': p['targetRole'],
          'sourceMultiplicity': p['sourceMultiplicity'],
          'targetMultiplicity': p['targetMultiplicity'],
          'identifying': p['identifying'] ?? false,
          'sourceParticipation': p['sourceParticipation'],
          'targetParticipation': p['targetParticipation'],
          'degree': p['degree'] ?? 2,
        });
        return estado.copiar(relationships: [...estado.relationships, r]);
      }

    case 'UPDATE_RELATION':
      {
        final cambios = {...p}
          ..remove('relationId')
          ..remove('deshace')
          ..remove('esperado');
        return estado.copiar(
          relationships: [
            for (final r in estado.relationships) r.id == p['relationId'] ? r.mezclar(cambios) : r,
          ],
        );
      }

    case 'DELETE_RELATION':
      return estado.copiar(
        relationships: [for (final r in estado.relationships) if (r.id != p['relationId']) r],
      );

    case 'SET_ER_FLAG':
      {
        if (p['targetType'] == 'CLASS') {
          return _conClase(
            estado,
            p['targetId'] as String,
            (c) => c.copiar(
              weak: p['flag'] == 'weak' ? p['value'] as bool? : c.weak,
              erKind: p['flag'] == 'erKind' ? p['erKind'] as String? : c.erKind,
              inheritanceStrategy: p['flag'] == 'inheritanceStrategy' ? p['strategy'] as String? : c.inheritanceStrategy,
            ),
          );
        }
        return _conAtributo(estado, p['targetId'] as String, (a) => a.mezclar({p['flag'] as String: p['value']}));
      }

    case 'MOVE_ELEMENT':
      {
        final elementId = p['elementId'] as String;
        final prev = estado.layout[elementId];
        // Mover una caja y acomodar los quiebres de una linea entran por el
        // mismo comando: los distingue el payload. En JS un arreglo vacio es
        // "verdadero", asi que aqui basta con que la clave no sea null.
        final ElementLayout l;
        if (p['waypoints'] != null) {
          l = ElementLayout(
            elementId: elementId,
            x: prev?.x ?? 0,
            y: prev?.y ?? 0,
            width: prev?.width ?? 170,
            height: prev?.height ?? 60,
            waypoints: [for (final w in (p['waypoints'] as List)) Punto.desdeJson(w)],
          );
        } else {
          l = ElementLayout(
            elementId: elementId,
            x: _n(p['x'], 0),
            y: _n(p['y'], 0),
            width: prev?.width ?? 170,
            height: prev?.height ?? 60,
            waypoints: prev?.waypoints,
          );
        }
        return estado.copiar(layout: {...estado.layout, elementId: l});
      }

    case 'RESIZE_ELEMENT':
      {
        // Igual que en el TS: el tamano nuevo no conserva los quiebres.
        final elementId = p['elementId'] as String;
        final prev = estado.layout[elementId];
        final l = ElementLayout(
          elementId: elementId,
          x: prev?.x ?? 0,
          y: prev?.y ?? 0,
          width: _n(p['width'], 170),
          height: _n(p['height'], 60),
        );
        return estado.copiar(layout: {...estado.layout, elementId: l});
      }

    default:
      // SET_MODELING_DECISION y CONFIRM_SCHEMA cambian el esquema derivado,
      // no el modelo conceptual que se dibuja: el visor los ignora.
      return estado;
  }
}

double _n(dynamic v, double porDefecto) => v is num ? v.toDouble() : porDefecto;

EstadoModelo _conClase(EstadoModelo estado, String classId, UmlClass Function(UmlClass) f) =>
    estado.copiar(classes: [for (final c in estado.classes) c.id == classId ? f(c) : c]);

EstadoModelo _conAtributo(EstadoModelo estado, String attributeId, UmlAttribute Function(UmlAttribute) f) =>
    estado.copiar(
      classes: [
        for (final c in estado.classes)
          c.copiar(attributes: [for (final a in c.attributes) a.id == attributeId ? f(a) : a]),
      ],
    );

/// Lee una operacion venga por STOMP (`type`, `payload` objeto) o por REST
/// `?sinceSeq=` (`opType`, `payload` texto JSON). Devuelve null si no se entiende.
OperacionAplicada? operacionDesdeJson(dynamic crudo) {
  if (crudo is! Map) return null;
  final tipo = (crudo['type'] ?? crudo['opType']) as String?;
  final seq = crudo['seq'];
  if (tipo == null || seq is! num) return null;
  dynamic payload = crudo['payload'];
  if (payload is String) {
    try {
      payload = jsonDecode(payload);
    } on FormatException {
      return null;
    }
  }
  if (payload is! Map) return null;
  return OperacionAplicada(
    opId: (crudo['opId'] ?? '') as String,
    seq: seq.toInt(),
    type: tipo,
    origin: (crudo['origin'] ?? 'MOUSE') as String,
    userId: crudo['userId'] as String?,
    payload: Map<String, dynamic>.from(payload),
  );
}
