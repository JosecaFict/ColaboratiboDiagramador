import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import 'modelo.dart';
import 'sincronizador.dart';

const _anchoMinimo = 170.0;
const _altoLinea = 16.0;
const _altoCabecera = 30.0;

/// Rectangulo de una clase: el del layout, pero nunca mas chico que su contenido
/// (una caja del web de 60 de alto con 5 atributos se veria cortada).
Rect rectDe(UmlClass c, ElementLayout? l) {
  final lineas = c.attributes.length + c.methods.length;
  final contenido = _altoCabecera + math.max(1, lineas) * _altoLinea + 12;
  final x = l?.x ?? 0;
  final y = l?.y ?? 0;
  return Rect.fromLTWH(x, y, math.max(l?.width ?? _anchoMinimo, _anchoMinimo), math.max(l?.height ?? 0, contenido));
}

/// Area que ocupa todo el diagrama, con margen, para centrarlo y dimensionar el lienzo.
Rect limitesDe(EstadoModelo e) {
  Rect? r;
  for (final c in e.classes) {
    final rc = rectDe(c, e.layout[c.id]);
    r = r == null ? rc : r.expandToInclude(rc);
  }
  return (r ?? const Rect.fromLTWH(0, 0, 400, 300)).inflate(60);
}

String _firma(UmlAttribute a) {
  final signo = switch (a.visibility) {
    'PUBLIC' => '+',
    'PROTECTED' => '#',
    'PACKAGE' => '~',
    _ => '-',
  };
  return '${a.pk ? '(PK) ' : ''}$signo ${a.name}: ${a.dataType}${a.readOnly ? ' {readOnly}' : ''}';
}

String _firmaMetodo(UmlMethod m) {
  final signo = switch (m.visibility) {
    'PRIVATE' => '-',
    'PROTECTED' => '#',
    'PACKAGE' => '~',
    _ => '+',
  };
  final ps = [
    for (final p in m.params)
      if (p is Map) '${p['name']}: ${p['type']}',
  ].join(', ');
  return '$signo ${m.name}($ps): ${m.returnType}';
}

/// Dibuja la vista de Clases. Solo mira: no hay ningun gesto de edicion.
class DibujoClases extends CustomPainter {
  final EstadoModelo estado;
  final Map<String, Bloqueo> bloqueos;
  final Set<String> seleccionadosPorOtros;
  final ColorScheme colores;

  DibujoClases({
    required this.estado,
    required this.bloqueos,
    required this.seleccionadosPorOtros,
    required this.colores,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final rects = {for (final c in estado.classes) c.id: rectDe(c, estado.layout[c.id])};

    for (final r in estado.relationships) {
      final a = rects[r.sourceClassId];
      final b = rects[r.targetClassId];
      if (a == null || b == null) continue;
      _relacion(canvas, r, a, b, estado.layout[r.id]?.waypoints);
    }
    for (final c in estado.classes) {
      _clase(canvas, c, rects[c.id]!);
    }
  }

  void _clase(Canvas canvas, UmlClass c, Rect rect) {
    final bloqueo = bloqueos[c.id];
    final marcada = seleccionadosPorOtros.contains(c.id);
    final fondo = Paint()..color = colores.surface;
    final borde = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = bloqueo != null ? 3 : 1.5
      ..color = bloqueo != null
          ? Colors.orange.shade700
          : marcada
              ? colores.primary
              : colores.outline;
    final caja = RRect.fromRectAndRadius(rect, const Radius.circular(6));
    canvas.drawRRect(caja, fondo);

    // Cabecera
    canvas.save();
    canvas.clipRRect(caja);
    canvas.drawRect(
      Rect.fromLTWH(rect.left, rect.top, rect.width, _altoCabecera),
      Paint()..color = colores.primaryContainer,
    );
    canvas.restore();

    final nombre = c.stereotype != null && c.stereotype!.isNotEmpty ? '«${c.stereotype}»\n${c.name}' : c.name;
    _texto(canvas, nombre, Offset(rect.left + 6, rect.top + 3), rect.width - 12,
        estilo: TextStyle(
          fontWeight: FontWeight.bold,
          fontStyle: c.isAbstract ? FontStyle.italic : FontStyle.normal,
          fontSize: 12,
          color: colores.onPrimaryContainer,
          height: 1.1,
        ),
        centrado: true);

    var y = rect.top + _altoCabecera + 2;
    final estiloLinea = TextStyle(fontSize: 11, color: colores.onSurface);
    for (final a in c.attributes) {
      _texto(canvas, _firma(a), Offset(rect.left + 6, y), rect.width - 12,
          // UML 2.5.1: miembro estatico subrayado.
          estilo: a.isStatic ? estiloLinea.copyWith(decoration: TextDecoration.underline) : estiloLinea);
      y += _altoLinea;
    }
    if (c.attributes.isEmpty) y += _altoLinea;
    canvas.drawLine(Offset(rect.left, y + 2), Offset(rect.right, y + 2), Paint()..color = colores.outlineVariant);
    y += 4;
    for (final m in c.methods) {
      _texto(canvas, _firmaMetodo(m), Offset(rect.left + 6, y), rect.width - 12,
          // UML 2.5.1: estatico subrayado, abstracto en cursiva.
          estilo: estiloLinea.copyWith(
              decoration: m.isStatic ? TextDecoration.underline : null,
              fontStyle: m.isAbstract ? FontStyle.italic : null));
      y += _altoLinea;
    }

    canvas.drawRRect(caja, borde);

    // "editando: Ana" sobre el elemento tomado
    if (bloqueo != null) {
      final etiqueta = 'editando: ${bloqueo.userName ?? 'alguien'}';
      final tp = _medir(etiqueta, TextStyle(fontSize: 10, color: Colors.white, fontWeight: FontWeight.w600), 220);
      final pastilla = Rect.fromLTWH(rect.left, rect.top - tp.height - 8, tp.width + 12, tp.height + 4);
      canvas.drawRRect(RRect.fromRectAndRadius(pastilla, const Radius.circular(4)), Paint()..color = Colors.orange.shade700);
      tp.paint(canvas, Offset(pastilla.left + 6, pastilla.top + 2));
    }
  }

  void _relacion(Canvas canvas, UmlRelationship r, Rect a, Rect b, List<Punto>? quiebres) {
    final linea = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5
      ..color = colores.onSurfaceVariant;

    // Camino: centro -> quiebres -> centro, recortado en el borde de cada caja.
    final puntos = <Offset>[a.center, for (final p in quiebres ?? const <Punto>[]) Offset(p.x, p.y), b.center];
    puntos[0] = _salida(a, puntos[1]);
    puntos[puntos.length - 1] = _salida(b, puntos[puntos.length - 2]);

    final camino = Path()..moveTo(puntos.first.dx, puntos.first.dy);
    for (final p in puntos.skip(1)) {
      camino.lineTo(p.dx, p.dy);
    }
    final punteada = r.kind == 'DEPENDENCY' || r.kind == 'REALIZATION';
    canvas.drawPath(punteada ? _punteado(camino) : camino, linea);

    final inicio = puntos.first;
    final fin = puntos.last;
    final haciaInicio = (puntos[1] - inicio);
    final haciaFin = (puntos[puntos.length - 2] - fin);

    switch (r.kind) {
      case 'AGGREGATION':
        _rombo(canvas, inicio, haciaInicio, relleno: false);
      case 'COMPOSITION':
        _rombo(canvas, inicio, haciaInicio, relleno: true);
      case 'GENERALIZATION' || 'REALIZATION':
        _triangulo(canvas, fin, haciaFin);
      case 'DEPENDENCY':
        _flecha(canvas, fin, haciaFin);
    }

    // Multiplicidades junto a cada extremo y nombre al medio.
    final estilo = TextStyle(fontSize: 10, color: colores.onSurfaceVariant);
    if (r.sourceMultiplicity != null) {
      _texto(canvas, r.sourceMultiplicity!, inicio + _lado(haciaInicio, 12, 8), 60, estilo: estilo);
    }
    if (r.targetMultiplicity != null) {
      _texto(canvas, r.targetMultiplicity!, fin + _lado(haciaFin, 12, 8), 60, estilo: estilo);
    }
    final etiqueta = r.name;
    if (etiqueta != null && etiqueta.isNotEmpty) {
      final medio = Offset.lerp(inicio, fin, 0.5)!;
      _texto(canvas, etiqueta, medio + const Offset(4, -14), 120,
          estilo: estilo.copyWith(fontStyle: FontStyle.italic));
    }
  }

  /// Donde la recta desde el centro de [caja] hacia [hacia] cruza el borde.
  Offset _salida(Rect caja, Offset hacia) {
    final c = caja.center;
    final d = hacia - c;
    if (d == Offset.zero) return c;
    final tx = d.dx == 0 ? double.infinity : (caja.width / 2) / d.dx.abs();
    final ty = d.dy == 0 ? double.infinity : (caja.height / 2) / d.dy.abs();
    return c + d * math.min(tx, ty);
  }

  Offset _lado(Offset direccion, double a, double b) {
    final n = direccion.distance == 0 ? const Offset(1, 0) : direccion / direccion.distance;
    return n * a + Offset(-n.dy, n.dx) * b;
  }

  void _rombo(Canvas canvas, Offset punta, Offset direccion, {required bool relleno}) {
    final n = direccion / (direccion.distance == 0 ? 1 : direccion.distance);
    final p = Offset(-n.dy, n.dx);
    final a = punta + n * 9 + p * 5;
    final b = punta + n * 18;
    final c = punta + n * 9 - p * 5;
    final path = Path()
      ..moveTo(punta.dx, punta.dy)
      ..lineTo(a.dx, a.dy)
      ..lineTo(b.dx, b.dy)
      ..lineTo(c.dx, c.dy)
      ..close();
    canvas.drawPath(path, Paint()..color = relleno ? colores.onSurfaceVariant : colores.surface);
    canvas.drawPath(path, Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5
      ..color = colores.onSurfaceVariant);
  }

  void _triangulo(Canvas canvas, Offset punta, Offset direccion) {
    final n = direccion / (direccion.distance == 0 ? 1 : direccion.distance);
    final p = Offset(-n.dy, n.dx);
    final a = punta + n * 13 + p * 7;
    final b = punta + n * 13 - p * 7;
    final path = Path()
      ..moveTo(punta.dx, punta.dy)
      ..lineTo(a.dx, a.dy)
      ..lineTo(b.dx, b.dy)
      ..close();
    canvas.drawPath(path, Paint()..color = colores.surface);
    canvas.drawPath(path, Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5
      ..color = colores.onSurfaceVariant);
  }

  void _flecha(Canvas canvas, Offset punta, Offset direccion) {
    final n = direccion / (direccion.distance == 0 ? 1 : direccion.distance);
    final p = Offset(-n.dy, n.dx);
    final path = Path()
      ..moveTo((punta + n * 11 + p * 6).dx, (punta + n * 11 + p * 6).dy)
      ..lineTo(punta.dx, punta.dy)
      ..lineTo((punta + n * 11 - p * 6).dx, (punta + n * 11 - p * 6).dy);
    canvas.drawPath(path, Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5
      ..color = colores.onSurfaceVariant);
  }

  Path _punteado(Path origen) {
    final salida = Path();
    for (final m in origen.computeMetrics()) {
      var d = 0.0;
      while (d < m.length) {
        salida.addPath(m.extractPath(d, math.min(d + 6, m.length)), Offset.zero);
        d += 10;
      }
    }
    return salida;
  }

  TextPainter _medir(String texto, TextStyle estilo, double ancho, {bool centrado = false}) {
    final tp = TextPainter(
      text: TextSpan(text: texto, style: estilo),
      textDirection: ui.TextDirection.ltr,
      textAlign: centrado ? TextAlign.center : TextAlign.left,
      maxLines: centrado ? 2 : 1,
      ellipsis: '…',
    )..layout(maxWidth: ancho, minWidth: centrado ? ancho : 0);
    return tp;
  }

  void _texto(Canvas canvas, String texto, Offset donde, double ancho,
      {required TextStyle estilo, bool centrado = false}) {
    _medir(texto, estilo, ancho, centrado: centrado).paint(canvas, donde);
  }

  @override
  bool shouldRepaint(DibujoClases viejo) =>
      !identical(viejo.estado, estado) ||
      viejo.bloqueos != bloqueos ||
      viejo.seleccionadosPorOtros != seleccionadosPorOtros ||
      viejo.colores != colores;
}
