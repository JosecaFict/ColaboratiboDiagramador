/// Modelo del diagrama tal como lo ve el visor (solo lectura).
///
/// ESPEJO de `frontend/src/contracts/command.ts` y de `DiagramSnapshot.java`.
/// Las claves JSON son las del servidor; no se renombra nada.
library;

typedef Json = Map<String, dynamic>;

double _num(dynamic v, double porDefecto) => v is num ? v.toDouble() : porDefecto;

class UmlAttribute {
  final Json datos;
  const UmlAttribute(this.datos);

  String get id => datos['id'] as String;
  String get name => (datos['name'] ?? '') as String;
  String get dataType => (datos['dataType'] ?? 'string') as String;
  String get visibility => (datos['visibility'] ?? 'PRIVATE') as String;
  bool get pk => datos['pk'] == true;
  bool get required => datos['required'] == true;
  bool get unique => datos['unique'] == true;
  int get ordinal => (datos['ordinal'] as num?)?.toInt() ?? 0;

  /// UML 2.5.1 isStatic (D-15): el atributo es de la clase. Ausente = false (snapshots anteriores).
  bool get isStatic => datos['isStatic'] == true;

  /// UML 2.5.1 isReadOnly (D-15). Ausente = false.
  bool get readOnly => datos['readOnly'] == true;

  UmlAttribute mezclar(Json cambios) => UmlAttribute({...datos, ...cambios});
}

class UmlMethod {
  final Json datos;
  const UmlMethod(this.datos);

  String get id => datos['id'] as String;
  String get name => (datos['name'] ?? '') as String;
  String get returnType => (datos['returnType'] ?? 'void') as String;
  String get visibility => (datos['visibility'] ?? 'PUBLIC') as String;
  List<dynamic> get params => (datos['params'] as List?) ?? const [];

  /// UML 2.5.1 isStatic (D-15). Ausente = false: asi lo leen los snapshots anteriores.
  bool get isStatic => datos['isStatic'] == true;

  /// UML 2.5.1 isAbstract (D-15). Ausente = false.
  bool get isAbstract => datos['isAbstract'] == true;

  UmlMethod mezclar(Json cambios) => UmlMethod({...datos, ...cambios});
}

class UmlClass {
  final String id;
  final String diagramId;
  final String name;
  final String? stereotype;
  final bool isAbstract;
  final bool weak;
  final String erKind;
  final String inheritanceStrategy;
  final List<UmlAttribute> attributes;
  final List<UmlMethod> methods;

  /// Estereotipo de una interfaz UML (D-15, opcion A). Espejo de `Interfaces.ESTEREOTIPO` en el backend.
  static const estereotipoInterfaz = 'interface';

  /// Una interfaz es un contrato, no una entidad. Se compara sin distinguir mayusculas ni espacios.
  bool get esInterfaz => (stereotype ?? '').trim().toLowerCase() == estereotipoInterfaz;

  const UmlClass({
    required this.id,
    required this.diagramId,
    required this.name,
    this.stereotype,
    this.isAbstract = false,
    this.weak = false,
    this.erKind = 'STRONG',
    this.inheritanceStrategy = 'JOINED',
    this.attributes = const [],
    this.methods = const [],
  });

  factory UmlClass.desdeJson(Json j) => UmlClass(
        id: j['id'] as String,
        diagramId: (j['diagramId'] ?? '') as String,
        name: (j['name'] ?? '') as String,
        stereotype: j['stereotype'] as String?,
        isAbstract: j['isAbstract'] == true || j['abstract'] == true,
        weak: j['weak'] == true,
        erKind: (j['erKind'] ?? 'STRONG') as String,
        inheritanceStrategy: (j['inheritanceStrategy'] ?? 'JOINED') as String,
        attributes: [
          for (final a in (j['attributes'] as List? ?? const [])) UmlAttribute(Map<String, dynamic>.from(a as Map)),
        ],
        methods: [
          for (final m in (j['methods'] as List? ?? const [])) UmlMethod(Map<String, dynamic>.from(m as Map)),
        ],
      );

  Json aJson() => {
        'id': id,
        'diagramId': diagramId,
        'name': name,
        'stereotype': stereotype,
        'isAbstract': isAbstract,
        'weak': weak,
        'erKind': erKind,
        'inheritanceStrategy': inheritanceStrategy,
        'attributes': [for (final a in attributes) a.datos],
        'methods': [for (final m in methods) m.datos],
      };

  UmlClass copiar({
    String? name,
    bool? weak,
    String? erKind,
    String? inheritanceStrategy,
    List<UmlAttribute>? attributes,
    List<UmlMethod>? methods,
  }) =>
      UmlClass(
        id: id,
        diagramId: diagramId,
        name: name ?? this.name,
        stereotype: stereotype,
        isAbstract: isAbstract,
        weak: weak ?? this.weak,
        erKind: erKind ?? this.erKind,
        inheritanceStrategy: inheritanceStrategy ?? this.inheritanceStrategy,
        attributes: attributes ?? this.attributes,
        methods: methods ?? this.methods,
      );
}

class UmlRelationship {
  final Json datos;
  const UmlRelationship(this.datos);

  String get id => datos['id'] as String;
  String get sourceClassId => datos['sourceClassId'] as String;
  String get targetClassId => datos['targetClassId'] as String;
  String get kind => (datos['kind'] ?? 'ASSOCIATION') as String;
  String? get name => datos['name'] as String?;
  String? get sourceMultiplicity => datos['sourceMultiplicity'] as String?;
  String? get targetMultiplicity => datos['targetMultiplicity'] as String?;
  String? get sourceRole => datos['sourceRole'] as String?;
  String? get targetRole => datos['targetRole'] as String?;

  UmlRelationship mezclar(Json cambios) => UmlRelationship({...datos, ...cambios});
}

class Punto {
  final double x;
  final double y;
  const Punto(this.x, this.y);

  factory Punto.desdeJson(dynamic j) => Punto(_num((j as Map)['x'], 0), _num(j['y'], 0));
  Json aJson() => {'x': x, 'y': y};
}

class ElementLayout {
  final String elementId;
  final double x;
  final double y;
  final double width;
  final double height;
  final List<Punto>? waypoints;

  const ElementLayout({
    required this.elementId,
    required this.x,
    required this.y,
    required this.width,
    required this.height,
    this.waypoints,
  });

  factory ElementLayout.desdeJson(Json j) => ElementLayout(
        elementId: j['elementId'] as String,
        x: _num(j['x'], 0),
        y: _num(j['y'], 0),
        width: _num(j['width'], 170),
        height: _num(j['height'], 60),
        waypoints: j['waypoints'] == null ? null : [for (final p in (j['waypoints'] as List)) Punto.desdeJson(p)],
      );

  Json aJson() => {
        'elementId': elementId,
        'x': x,
        'y': y,
        'width': width,
        'height': height,
        if (waypoints != null) 'waypoints': [for (final p in waypoints!) p.aJson()],
      };
}

/// Estado del diagrama que dibuja el visor. Inmutable: cada operacion devuelve otro.
class EstadoModelo {
  final String diagramId;
  final List<UmlClass> classes;
  final List<UmlRelationship> relationships;
  final Map<String, ElementLayout> layout;

  const EstadoModelo({
    required this.diagramId,
    this.classes = const [],
    this.relationships = const [],
    this.layout = const {},
  });

  factory EstadoModelo.vacio(String diagramId) => EstadoModelo(diagramId: diagramId);

  EstadoModelo copiar({
    List<UmlClass>? classes,
    List<UmlRelationship>? relationships,
    Map<String, ElementLayout>? layout,
  }) =>
      EstadoModelo(
        diagramId: diagramId,
        classes: classes ?? this.classes,
        relationships: relationships ?? this.relationships,
        layout: layout ?? this.layout,
      );
}

/// Lo que devuelve `GET /diagrams/{id}`: espejo de `DiagramSnapshot.java`.
class SnapshotDiagrama {
  final String id;
  final String name;
  final int currentSeq;
  final String defaultView;
  final Json crudo;

  SnapshotDiagrama({required this.crudo})
      : id = crudo['id'] as String,
        name = (crudo['name'] ?? '') as String,
        currentSeq = (crudo['currentSeq'] as num?)?.toInt() ?? 0,
        defaultView = (crudo['defaultView'] ?? 'CLASS') as String;

  /// Arrancar desde el snapshot en vez de re-aplicar todo el log.
  EstadoModelo aEstado() => EstadoModelo(
        diagramId: id,
        classes: [
          for (final c in (crudo['classes'] as List? ?? const [])) UmlClass.desdeJson(Map<String, dynamic>.from(c as Map)),
        ],
        relationships: [
          for (final r in (crudo['relationships'] as List? ?? const []))
            UmlRelationship(Map<String, dynamic>.from(r as Map)),
        ],
        layout: {
          for (final l in (crudo['layout'] as List? ?? const []))
            (l as Map)['elementId'] as String: ElementLayout.desdeJson(Map<String, dynamic>.from(l)),
        },
      );
}

/// Operacion ya aceptada y secuenciada. Llega de dos formas distintas:
///  - por STOMP (`AppliedOperation.java`): `type`, `payload` como objeto, `ts`;
///  - por REST `?sinceSeq=` (entidad `DiagramOperation`): `opType`, `payload`
///    como TEXTO JSON, `createdAt`.
class OperacionAplicada {
  final String opId;
  final int seq;
  final String type;
  final String origin;
  final Json payload;
  final String? userId;

  const OperacionAplicada({
    required this.opId,
    required this.seq,
    required this.type,
    required this.payload,
    this.origin = 'MOUSE',
    this.userId,
  });

  Json aJson() => {
        'opId': opId,
        'seq': seq,
        'type': type,
        'origin': origin,
        'payload': payload,
        'userId': userId,
      };
}
