// Logica de la pantalla del editor, sin widgets: que se toco, que hace un
// toque segun la herramienta armada, donde nace una clase nueva y que textos
// se aceptan. Todo en coordenadas del MODELO (las del layout). El hijo del
// InteractiveViewer arranca en la esquina de la escena ([escenaDe]), que puede
// ser negativa: la pantalla suma esa esquina antes de llamar a estas funciones.

import 'dart:math' as math;
import 'dart:ui';

import '../../visor/dibujo_clases.dart';
import '../../visor/modelo.dart';
import '../contrato_editor.dart';

// ------------------------------------------------------------- herramienta

/// Lo que esta armado en la paleta.
sealed class Herramienta {
  const Herramienta();
}

/// Tocar selecciona.
class Seleccionar extends Herramienta {
  const Seleccionar();
}

/// Tocar origen y despues destino crea una relacion de [tipo].
class ArmarRelacion extends Herramienta {
  final TipoRelacion tipo;

  /// La clase de origen ya tocada, o null si todavia no.
  final String? origenId;
  const ArmarRelacion(this.tipo, [this.origenId]);
}

/// Que tiene que hacer la pantalla despues de un toque.
sealed class Efecto {
  const Efecto();
}

class SeleccionarElemento extends Efecto {
  /// null = soltar la seleccion (se toco el fondo).
  final String? id;
  const SeleccionarElemento(this.id);
}

class CrearRelacion extends Efecto {
  final String origenId;
  final String destinoId;
  final TipoRelacion tipo;
  const CrearRelacion(this.origenId, this.destinoId, this.tipo);
}

/// Se eligio el origen: la pantalla lo resalta y pide el destino.
class EsperarDestino extends Efecto {
  const EsperarDestino();
}

class Nada extends Efecto {
  const Nada();
}

/// El resultado de tocar: la herramienta que queda y lo que hay que hacer.
class Toque {
  final Herramienta herramienta;
  final Efecto efecto;
  const Toque(this.herramienta, this.efecto);
}

/// Un toque en [punto] con [herramienta] armada.
///
/// Con una relacion armada: el primer toque sobre una clase es el origen; el
/// segundo, sobre otra o la misma (relacion reflexiva, la web la permite), la
/// crea y la paleta vuelve a Seleccionar, como en la web. Tocar el fondo con un
/// origen ya elegido lo descarta, pero deja la relacion armada.
Toque tocar(EstadoModelo modelo, Herramienta herramienta, Offset punto) {
  final clase = claseEn(modelo, punto);
  switch (herramienta) {
    case Seleccionar():
      if (clase != null) return Toque(herramienta, SeleccionarElemento(clase));
      final relacion = relacionCerca(modelo, punto);
      return Toque(herramienta, SeleccionarElemento(relacion));
    case ArmarRelacion(:final tipo, :final origenId):
      if (clase == null) return Toque(ArmarRelacion(tipo), const Nada());
      if (origenId == null) return Toque(ArmarRelacion(tipo, clase), const EsperarDestino());
      return Toque(const Seleccionar(), CrearRelacion(origenId, clase, tipo));
  }
}

// --------------------------------------------------------------- impactos

/// La clase bajo [punto], la de arriba si se superponen (la ultima dibujada).
String? claseEn(EstadoModelo modelo, Offset punto) {
  for (final c in modelo.classes.reversed) {
    if (rectDe(c, modelo.layout[c.id]).contains(punto)) return c.id;
  }
  return null;
}

/// La relacion cuya linea pasa a menos de [tolerancia] de [punto], la mas cercana.
///
/// La tolerancia es generosa a proposito: un dedo no es un mouse.
String? relacionCerca(EstadoModelo modelo, Offset punto, {double tolerancia = 14}) {
  final rects = {for (final c in modelo.classes) c.id: rectDe(c, modelo.layout[c.id])};
  String? mejor;
  var distanciaMejor = tolerancia;
  for (final r in modelo.relationships) {
    final a = rects[r.sourceClassId];
    final b = rects[r.targetClassId];
    if (a == null || b == null) continue;
    final quiebres = modelo.layout[r.id]?.waypoints ?? const <Punto>[];
    final puntos = [a.center, for (final q in quiebres) Offset(q.x, q.y), b.center];
    for (var i = 0; i + 1 < puntos.length; i++) {
      final d = distanciaASegmento(punto, puntos[i], puntos[i + 1]);
      if (d <= distanciaMejor) {
        distanciaMejor = d;
        mejor = r.id;
      }
    }
  }
  return mejor;
}

double distanciaASegmento(Offset p, Offset a, Offset b) {
  final ab = b - a;
  final largo2 = ab.dx * ab.dx + ab.dy * ab.dy;
  if (largo2 == 0) return (p - a).distance;
  final t = (((p.dx - a.dx) * ab.dx + (p.dy - a.dy) * ab.dy) / largo2).clamp(0.0, 1.0);
  return (p - (a + ab * t)).distance;
}

// ------------------------------------------------------- clase nueva

/// Donde nace una clase nueva: en el centro de lo que se esta viendo, corrida
/// en escalera mientras choque con otra (si no, la nueva tapa a una existente
/// y parece que no paso nada).
Offset posicionNueva(EstadoModelo modelo, Rect visible) {
  const ancho = 170.0, alto = 60.0, paso = 28.0;
  var origen = Offset(visible.center.dx - ancho / 2, visible.center.dy - alto / 2);
  final ocupados = [for (final c in modelo.classes) rectDe(c, modelo.layout[c.id])];
  for (var i = 0; i < 30; i++) {
    final caja = Rect.fromLTWH(origen.dx, origen.dy, ancho, alto);
    if (!ocupados.any((r) => r.overlaps(caja))) break;
    origen = origen.translate(paso, paso);
  }
  return Offset(origen.dx.roundToDouble(), origen.dy.roundToDouble());
}

/// Donde queda una clase arrastrada: redondeada, como la web (media unidad
/// corre el dibujo y el texto sale borroso).
Offset posicionArrastrada(Offset inicio, Offset desplazamiento) =>
    Offset((inicio.dx + desplazamiento.dx).roundToDouble(), (inicio.dy + desplazamiento.dy).roundToDouble());

/// El modelo con una clase en otro lugar, SOLO para dibujar el arrastre en
/// curso. No es un cambio: el de verdad sale al soltar y vuelve por el servidor.
EstadoModelo conClaseEn(EstadoModelo modelo, String classId, Offset posicion) {
  final previo = modelo.layout[classId];
  return modelo.copiar(layout: {
    ...modelo.layout,
    classId: ElementLayout(
      elementId: classId,
      x: posicion.dx,
      y: posicion.dy,
      width: previo?.width ?? 170,
      height: previo?.height ?? 60,
      waypoints: previo?.waypoints,
    ),
  });
}

/// Un arrastre mas corto que esto es un toque que tembló, no un movimiento.
bool esMovimiento(Offset desplazamiento) => desplazamiento.distance > 4;

// ------------------------------------------------------------- textos

/// Los tipos que ofrece la web (`PanelPropiedades.tsx`).
const tiposBase = ['string', 'text', 'int', 'long', 'decimal', 'boolean', 'date', 'datetime', 'uuid'];

bool _conEstereotipo(UmlClass c, String estereotipo) => (c.stereotype ?? '').toLowerCase() == estereotipo;

/// Las clases de un estereotipo, menos la que se esta editando, sin repetir nombres.
List<String> _nombresDe(EstadoModelo modelo, String estereotipo, String? excepto) => {
      for (final c in modelo.classes)
        if (_conEstereotipo(c, estereotipo) && c.id != excepto) c.name,
    }.toList();

/// Tipos de un atributo de la clase [excepto], como en la web: los base, las
/// enumeraciones y los valores embebidos. Un valor no puede contener otro valor
/// (anidar @Embeddable no esta soportado), asi que dentro de uno no se ofrecen.
List<String> tiposDeAtributo(EstadoModelo modelo, {String? excepto}) {
  final propia = modelo.classes.where((c) => c.id == excepto).firstOrNull;
  final esValor = propia != null && _conEstereotipo(propia, 'embeddable');
  return {
    ...tiposBase,
    ..._nombresDe(modelo, 'enumeration', excepto),
    if (!esValor) ..._nombresDe(modelo, 'embeddable', excepto),
  }.toList();
}

/// Tipos de retorno de un metodo, como en la web: `void`, los base y las
/// enumeraciones. Los valores embebidos no: tienen otro catalogo.
List<String> tiposDeRetorno(EstadoModelo modelo, {String? excepto}) =>
    {'void', ...tiposBase, ..._nombresDe(modelo, 'enumeration', excepto)}.toList();

/// Las multiplicidades que ofrece la web (`PanelRelacion.tsx`).
const multiplicidades = ['1', '0..1', '1..*', '0..*'];

/// Lo que ofrece el selector de una multiplicidad que hoy vale [actual] (`''` es
/// sin definir). Una rara que vino de la web o de un XMI se muestra igual.
/// «sin definir» aparece solo si ya esta asi: el contrato del editor no tiene
/// como borrar una multiplicidad (un null en [CambiosRelacion] es «no cambia»).
List<String> opcionesDeMultiplicidad(String actual) =>
    {if (actual.isEmpty) '', ...multiplicidades, if (actual.isNotEmpty) actual}.toList();

/// Una multiplicidad escrita a mano: `1`, `0..1`, `2..5`, `*`, `1..*`. Vacia vale ("no se sabe").
bool multiplicidadValida(String texto) {
  final t = texto.replaceAll(RegExp(r'\s+'), '');
  return t.isEmpty || RegExp(r'^(\d+|\*)(\.\.(\d+|\*))?$').hasMatch(t);
}

/// Un nombre de clase, atributo o metodo: sin espacios en los bordes y no vacio.
String? nombreLimpio(String texto) {
  final t = texto.trim();
  return t.isEmpty ? null : t;
}

/// Que esta mirando el usuario, en coordenadas del modelo, a partir del zoom.
Rect visibleEnModelo(Size pantalla, double escala, Offset traslacion) {
  final s = escala == 0 ? 1.0 : escala;
  return Rect.fromLTWH(-traslacion.dx / s, -traslacion.dy / s, pantalla.width / s, pantalla.height / s);
}

/// El rectangulo que ocupa el lienzo: todo el diagrama y tambien el origen.
/// Las coordenadas pueden ser negativas (la web deja mover una clase arriba a la
/// izquierda del origen); incluir siempre el (0,0) hace que un diagrama comun
/// tenga la esquina fija en el origen y no se corra al editar.
Rect escenaDe(Rect limites) => Rect.fromLTRB(
    math.min(limites.left, 0), math.min(limites.top, 0), math.max(limites.right, 0), math.max(limites.bottom, 0));

/// Escala para que entre todo el diagrama a lo ancho, sin agrandar de mas.
double escalaParaEncuadrar(Size pantalla, Rect limites) => math.min(1.0, math.max(0.2, pantalla.width / limites.width));
