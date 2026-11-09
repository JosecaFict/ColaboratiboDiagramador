// Un EditorDeDiagrama de mentira para probar las pantallas sin T-15a: anota
// cada llamada y deja fijar a mano el modelo, el rol, los locks y los rechazos.

import 'package:colabmodel_app/editor/contrato_editor.dart';
import 'package:colabmodel_app/visor/modelo.dart';
import 'package:flutter/foundation.dart';

class EditorFalso extends ChangeNotifier implements EditorDeDiagrama {
  EditorFalso({EstadoModelo? modelo, this.puedeEditar = true, this.conectado = true}) : _modelo = modelo;

  EstadoModelo? _modelo;
  @override
  EstadoModelo? get modelo => _modelo;
  set modelo(EstadoModelo? m) {
    _modelo = m;
    notifyListeners();
  }

  @override
  bool conectado;
  @override
  bool puedeEditar;
  @override
  bool esperandoConfirmacion = false;

  final Map<String, String> tomados = {};
  @override
  String? bloqueadaPorOtro(String elementId) => tomados[elementId];

  RechazoVisible? _rechazo;
  @override
  RechazoVisible? get ultimoRechazo => _rechazo;
  void rechazar(RechazoVisible r) {
    _rechazo = r;
    notifyListeners();
  }

  @override
  void descartarRechazo() {
    _rechazo = null;
    llamadas.add('descartarRechazo');
    notifyListeners();
  }

  String? _seleccionada;
  @override
  String? get seleccionada => _seleccionada;
  @override
  void seleccionar(String? elementId) {
    _seleccionada = elementId;
    llamadas.add('seleccionar:$elementId');
    notifyListeners();
  }

  /// Todo lo que pidio la pantalla, en orden: «crearElemento:clase:Pedido».
  final List<String> llamadas = [];
  bool cerrado = false;

  @override
  String? crearElemento(TipoDeElemento tipo, String nombre, {double? x, double? y}) {
    llamadas.add('crearElemento:${tipo.name}:$nombre');
    return 'nuevo';
  }

  @override
  void renombrarClase(String classId, String nombre) => llamadas.add('renombrarClase:$classId:$nombre');
  @override
  void borrarClase(String classId) => llamadas.add('borrarClase:$classId');
  @override
  String? agregarAtributo(String classId, DatosAtributo datos) {
    llamadas.add('agregarAtributo:$classId:${datos.nombre}:${datos.tipoDato}');
    return 'attr';
  }

  @override
  void actualizarAtributo(String attributeId, DatosAtributo cambios) => llamadas.add(
      'actualizarAtributo:$attributeId:${cambios.nombre}:${cambios.tipoDato}:${cambios.pk}:${cambios.obligatorio}:${cambios.unico}');
  @override
  void quitarAtributo(String attributeId) => llamadas.add('quitarAtributo:$attributeId');
  @override
  String? agregarMetodo(String classId, String nombre, {String retorno = 'void'}) {
    llamadas.add('agregarMetodo:$classId:$nombre:$retorno');
    return 'met';
  }

  @override
  void quitarMetodo(String methodId) => llamadas.add('quitarMetodo:$methodId');
  @override
  String? crearRelacion(String origenId, String destinoId, TipoRelacion tipo) {
    llamadas.add('crearRelacion:$origenId:$destinoId:${tipo.clave}');
    return 'rel';
  }

  @override
  void actualizarRelacion(String relationId, CambiosRelacion cambios) => llamadas.add(
      'actualizarRelacion:$relationId:${cambios.tipo?.clave}:${cambios.multiplicidadOrigen}:${cambios.multiplicidadDestino}');
  @override
  void borrarRelacion(String relationId) => llamadas.add('borrarRelacion:$relationId');
  @override
  void moverClase(String classId, double x, double y) => llamadas.add('moverClase:$classId:${x.round()}:${y.round()}');

  @override
  void dispose() {
    cerrado = true;
    super.dispose();
  }
}

/// Un diagrama chico: Pedido (100,100) con un atributo, Cliente (500,100) y una asociacion.
EstadoModelo modeloDePrueba() => const EstadoModelo(
      diagramId: 'd',
      classes: [
        UmlClass(id: 'pedido', diagramId: 'd', name: 'Pedido', attributes: [
          UmlAttribute({'id': 'total', 'name': 'total', 'dataType': 'decimal', 'visibility': 'PRIVATE'}),
        ]),
        UmlClass(id: 'cliente', diagramId: 'd', name: 'Cliente'),
      ],
      relationships: [
        UmlRelationship({
          'id': 'r1',
          'sourceClassId': 'pedido',
          'targetClassId': 'cliente',
          'kind': 'ASSOCIATION',
          'sourceMultiplicity': '0..*',
          'targetMultiplicity': '1',
        }),
      ],
      layout: {
        'pedido': ElementLayout(elementId: 'pedido', x: 100, y: 100, width: 170, height: 60),
        'cliente': ElementLayout(elementId: 'cliente', x: 500, y: 100, width: 170, height: 60),
      },
    );
