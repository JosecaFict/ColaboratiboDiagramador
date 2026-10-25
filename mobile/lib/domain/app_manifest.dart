// ============================================================================
// CONTRATO SEMANTICO P2 -> P3  (ADR-011, esquema en ADR-021)
//
// El backend generado emite `app-manifest.json` (lo sirve en
// GET /app-manifest.json). Esta app lo lee al arrancar y aprende el dominio en
// TIEMPO DE EJECUCION.
//
// Por eso el MISMO APK sirve para barberia, biblioteca o veterinaria: el dia
// de la presentacion el docente trae un modelo cualquiera y no hay que
// reescribir ni recompilar nada (requisito R18, escenario EC-10).
//
// Es generacion de SEGUNDO ORDEN: el generador no produce solo codigo,
// produce el contrato que permite que otro sistema se adapte solo.
//
// ESPEJO de backend/.../codegen/ManifestDeApp.java. Las claves se copian, no
// se interpretan. Los manifiestos de mobile/test/manifests/ los escribe el
// generador (ManifestDeAppTest), asi que una clave cambiada de un solo lado
// rompe una prueba.
// ============================================================================

/// Version mas alta del esquema que esta app sabe leer. Un manifiesto con una
/// version mayor se rechaza al cargar: leerlo a medias seria peor.
const int versionDeManifiestoSoportada = 1;

class ManifiestoIncompatible implements Exception {
  final String motivo;
  ManifiestoIncompatible(this.motivo);
  @override
  String toString() => 'ManifiestoIncompatible: $motivo';
}

class AppManifest {
  final int version;
  final String app;
  final List<EntityDescriptor> entities;

  AppManifest({required this.version, required this.app, required this.entities});

  factory AppManifest.fromJson(Map<String, dynamic> json) {
    final version = json['manifestVersion'] as int? ?? 0;
    if (version < 1 || version > versionDeManifiestoSoportada) {
      throw ManifiestoIncompatible('el manifiesto es version $version y esta app lee hasta la '
          '$versionDeManifiestoSoportada: hay que actualizar la app');
    }
    return AppManifest(
      version: version,
      app: json['app'] as String,
      entities: (json['entities'] as List<dynamic>)
          .map((e) => EntityDescriptor.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }

  EntityDescriptor? entidad(String name) {
    for (final e in entities) {
      if (e.name == name) return e;
    }
    return null;
  }

  /// Las entidades que se pueden crear en lugar de una abstracta: sus
  /// descendientes concretas, a cualquier profundidad.
  List<EntityDescriptor> concretasBajo(EntityDescriptor madre) {
    final resultado = <EntityDescriptor>[];
    for (final e in entities) {
      for (var p = e.parent; p != null; p = entidad(p)?.parent) {
        if (p == madre.name) {
          if (!e.isAbstract) resultado.add(e);
          break;
        }
      }
    }
    return resultado;
  }
}

class EntityDescriptor {
  /// Nombre de la clase Java generada: identificador estable.
  final String name;

  /// Como lo escribio la persona en el UML ("Año Académico").
  final String label;

  /// Ruta REST: GET lista, POST crea.
  final String path;
  final bool isAbstract;
  final String? parent;
  final List<String> actions;
  final List<String> synonyms;
  final List<FieldDescriptor> fields;
  final List<RelationDescriptor> relations;

  EntityDescriptor({
    required this.name,
    required this.label,
    required this.path,
    required this.isAbstract,
    required this.parent,
    required this.actions,
    required this.synonyms,
    required this.fields,
    required this.relations,
  });

  factory EntityDescriptor.fromJson(Map<String, dynamic> json) => EntityDescriptor(
        name: json['name'] as String,
        label: json['label'] as String? ?? json['name'] as String,
        path: json['path'] as String,
        isAbstract: json['abstract'] as bool? ?? false,
        parent: json['parent'] as String?,
        actions: List<String>.from(json['actions'] as List? ?? const []),
        synonyms: List<String>.from(json['synonyms'] as List? ?? const []),
        fields: (json['fields'] as List<dynamic>? ?? const [])
            .map((f) => FieldDescriptor.fromJson(f as Map<String, dynamic>))
            .toList(),
        relations: (json['relations'] as List<dynamic>? ?? const [])
            .map((r) => RelationDescriptor.fromJson(r as Map<String, dynamic>))
            .toList(),
      );

  bool permite(String accion) => actions.contains(accion);

  /// Como nombrarla en una respuesta: la primera forma de sus sinonimos.
  String get nombreHablado => synonyms.isNotEmpty ? synonyms.first : label.toLowerCase();

  List<FieldDescriptor> get obligatorios => fields.where((f) => f.required).toList();
}

/// El vocabulario de tipos del contrato. No es el de Java ni el de Dart.
enum FieldType {
  string,
  integer,
  long,
  decimal,
  boolean,
  date,
  datetime,
  uuid,
  enumeration,
  reference,
  embedded,
  list,

  /// Un tipo que agrego una version nueva del generador. No rompe la carga:
  /// el motor rechaza dictarlo, con motivo.
  desconocido;

  static FieldType de(String? t) => switch (t) {
        'string' => string,
        'integer' => integer,
        'long' => long,
        'decimal' => decimal,
        'boolean' => boolean,
        'date' => date,
        'datetime' => datetime,
        'uuid' => uuid,
        'enum' => enumeration,
        'reference' => reference,
        'embedded' => embedded,
        'list' => list,
        _ => desconocido,
      };
}

class FieldDescriptor {
  /// Clave JSON del DTO.
  final String name;
  final String label;
  final FieldType type;
  final String typeName;
  final bool required;
  final bool unique;
  final int? maxLength;

  /// Solo `enum`: los literales que acepta la API, tal cual ("EN_PRESTAMO").
  final List<String> literals;

  /// Limites de un numero, como texto decimal para no perder precision.
  final String? min;
  final String? max;

  /// `reference`: la entidad apuntada. `embedded`: la clase de valor.
  final String? target;

  /// Solo `list`: el tipo de cada elemento.
  final String? items;
  final List<String> synonyms;

  FieldDescriptor({
    required this.name,
    required this.label,
    required this.typeName,
    required this.required,
    required this.unique,
    required this.synonyms,
    this.maxLength,
    this.literals = const [],
    this.min,
    this.max,
    this.target,
    this.items,
  }) : type = FieldType.de(typeName);

  factory FieldDescriptor.fromJson(Map<String, dynamic> json) => FieldDescriptor(
        name: json['name'] as String,
        label: json['label'] as String? ?? json['name'] as String,
        typeName: json['type'] as String,
        required: json['required'] as bool? ?? false,
        unique: json['unique'] as bool? ?? false,
        maxLength: json['maxLength'] as int?,
        literals: List<String>.from(json['literals'] as List? ?? const []),
        min: json['min']?.toString(),
        max: json['max']?.toString(),
        target: json['target'] as String?,
        items: json['items'] as String?,
        synonyms: List<String>.from(json['synonyms'] as List? ?? const []),
      );

  String get nombreHablado => synonyms.isNotEmpty ? synonyms.first : label.toLowerCase();
}

class RelationDescriptor {
  final String name;
  final String target;
  final bool many;

  /// Lado duenio: la clave JSON con el id de `target`.
  final String? field;

  /// Lado inverso: el campo de `target` que apunta aca. La API no tiene rutas
  /// anidadas; "los prestamos de un socio" es GET de prestamos filtrado por el.
  final String? inverseOf;

  RelationDescriptor({required this.name, required this.target, required this.many, this.field, this.inverseOf});

  factory RelationDescriptor.fromJson(Map<String, dynamic> json) => RelationDescriptor(
        name: json['name'] as String,
        target: json['target'] as String,
        many: json['many'] as bool? ?? false,
        field: json['field'] as String?,
        inverseOf: json['inverseOf'] as String?,
      );
}
