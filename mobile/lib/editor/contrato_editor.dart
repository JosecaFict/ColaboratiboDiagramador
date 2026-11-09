// ============================================================================
// CONTRATO ENTRE LAS DOS MITADES DEL EDITOR MOVIL (T-15)
//
// T-15a (Codex) IMPLEMENTA esto: canal de escritura, bloqueos, espejo del
// contrato Command y el controlador, sin widgets.
// T-15b (Claude) lo USA desde las pantallas: menu, paleta, hojas de
// propiedades y gestos.
//
// Ninguna de las dos mitades cambia este archivo por su cuenta: un cambio se
// acuerda en la ficha T-15 y se hace en la rama base `feat/editor-flutter`.
//
// Reglas que la implementacion respeta (las mismas que la web):
//   - El servidor manda: nada se aplica al modelo hasta que vuelve por
//     `/topic/diagram/{id}/ops` con su `seq` (no hay escritura optimista).
//   - Catalogo cerrado (ADR-014): cada metodo es UN comando del catalogo (o un
//     BATCH de ellos), con el mismo payload que arma la web.
//   - Un Observador no escribe: con `puedeEditar == false` los metodos de
//     escritura no envian nada.
//   - Seleccionar toma el lock del elemento (y lo renueva por latido mientras
//     siga seleccionado); deseleccionar lo suelta.
// ============================================================================

import 'package:flutter/foundation.dart';

import '../visor/modelo.dart';

/// Lo que crea la paleta. Todo es un CREATE_CLASS con otra marca.
enum TipoDeElemento {
  /// Clase comun.
  clase,

  /// `isAbstract: true`.
  abstracta,

  /// `stereotype: 'enumeration'`.
  enumeracion,

  /// `stereotype: 'embeddable'` (@Embeddable).
  valor,
}

/// Los cinco tipos de relacion del metamodelo (RelationKind en Java).
enum TipoRelacion {
  asociacion('ASSOCIATION'),
  agregacion('AGGREGATION'),
  composicion('COMPOSITION'),
  herencia('GENERALIZATION'),
  dependencia('DEPENDENCY');

  /// El valor que viaja en el payload (`kind`).
  final String clave;
  const TipoRelacion(this.clave);

  static TipoRelacion desdeClave(String clave) =>
      values.firstWhere((t) => t.clave == clave, orElse: () => asociacion);
}

/// Datos de un atributo. Al actualizar, los null no se envian.
@immutable
class DatosAtributo {
  final String? nombre;
  final String? tipoDato;
  final bool? pk;
  final bool? obligatorio;
  final bool? unico;

  /// PUBLIC, PRIVATE, PROTECTED o PACKAGE.
  final String? visibilidad;

  const DatosAtributo({
    this.nombre,
    this.tipoDato,
    this.pk,
    this.obligatorio,
    this.unico,
    this.visibilidad,
  });
}

/// Cambios de una relacion. Los null no se envian.
@immutable
class CambiosRelacion {
  final TipoRelacion? tipo;
  final String? nombre;

  /// Multiplicidad junto al origen y junto al destino: "1", "0..1", "1..*", "0..*".
  final String? multiplicidadOrigen;
  final String? multiplicidadDestino;
  final String? rolOrigen;
  final String? rolDestino;

  const CambiosRelacion({
    this.tipo,
    this.nombre,
    this.multiplicidadOrigen,
    this.multiplicidadDestino,
    this.rolOrigen,
    this.rolDestino,
  });
}

/// Un rechazo del servidor (o del propio editor) listo para mostrar.
@immutable
class RechazoVisible {
  /// LOCKED_BY_OTHER, STALE, INVALID, FORBIDDEN, NOT_FOUND o LOCAL
  /// (lo frena el propio editor antes de enviar: sin permiso, sin conexion,
  /// nombre vacio).
  final String codigo;

  /// En español, para el usuario: «La está editando Ana».
  final String mensaje;

  /// Elemento al que se refiere, si se sabe.
  final String? elementId;

  const RechazoVisible({required this.codigo, required this.mensaje, this.elementId});
}

/// El editor de UN diagrama. Avisa (Listenable) cada vez que cambia algo que
/// la pantalla muestra: modelo, seleccion, bloqueos, conexion o rechazo.
abstract class EditorDeDiagrama implements Listenable {
  /// El modelo confirmado por el servidor. null mientras carga.
  EstadoModelo? get modelo;

  bool get conectado;

  /// Rol MODELADOR o PROPIETARIO, y conectado. Si es false, la pantalla
  /// deshabilita todo lo que escribe.
  bool get puedeEditar;

  /// Clase o relacion seleccionada (y bloqueada por esta sesion).
  String? get seleccionada;

  /// Nombre de quien tiene tomado el elemento, o null si esta libre o es
  /// de esta sesion.
  String? bloqueadaPorOtro(String elementId);

  /// Hay comandos enviados que el servidor todavia no confirmo ni rechazo.
  bool get esperandoConfirmacion;

  /// Ultimo rechazo sin descartar, o null.
  RechazoVisible? get ultimoRechazo;
  void descartarRechazo();

  /// Selecciona (y bloquea) o, con null, suelta la seleccion actual.
  void seleccionar(String? elementId);

  // ------------------------------------------------------------- escritura
  // Los que crean devuelven el id nuevo (generado en el cliente, UUID v4),
  // o null si no se envio nada (ver ultimoRechazo).

  String? crearElemento(TipoDeElemento tipo, String nombre, {double? x, double? y});
  void renombrarClase(String classId, String nombre);
  void borrarClase(String classId);

  String? agregarAtributo(String classId, DatosAtributo datos);
  void actualizarAtributo(String attributeId, DatosAtributo cambios);
  void quitarAtributo(String attributeId);

  String? agregarMetodo(String classId, String nombre, {String retorno = 'void'});
  void quitarMetodo(String methodId);

  String? crearRelacion(String origenId, String destinoId, TipoRelacion tipo);
  void actualizarRelacion(String relationId, CambiosRelacion cambios);
  void borrarRelacion(String relationId);

  /// Mover es geometria (nivel 3, sin lock): se puede mover lo que otro edita.
  void moverClase(String classId, double x, double y);

  /// Suelta la seleccion, corta el latido y cierra el canal.
  void dispose();
}
