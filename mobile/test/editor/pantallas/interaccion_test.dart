import 'dart:ui';

import 'package:colabmodel_app/editor/contrato_editor.dart';
import 'package:colabmodel_app/editor/pantallas/entrada.dart';
import 'package:colabmodel_app/editor/pantallas/interaccion.dart';
import 'package:colabmodel_app/visor/modelo.dart';
import 'package:flutter_test/flutter_test.dart';

import 'editor_falso.dart';

void main() {
  final modelo = modeloDePrueba();

  group('que se toco', () {
    test('una clase por su caja, y nada en el fondo', () {
      expect(claseEn(modelo, const Offset(150, 120)), 'pedido');
      expect(claseEn(modelo, const Offset(560, 130)), 'cliente');
      expect(claseEn(modelo, const Offset(380, 400)), isNull);
    });

    test('una relacion cerca de su linea, con margen para el dedo', () {
      // La linea va del centro de Pedido al de Cliente, a la altura ~y=125.
      final y = claseEnCentroY(modelo, 'pedido');
      expect(relacionCerca(modelo, Offset(380, y + 10)), 'r1');
      expect(relacionCerca(modelo, Offset(380, y + 40)), isNull);
    });

    test('distancia a un segmento', () {
      expect(distanciaASegmento(const Offset(5, 3), const Offset(0, 0), const Offset(10, 0)), 3);
      expect(distanciaASegmento(const Offset(-4, 3), const Offset(0, 0), const Offset(10, 0)), 5);
    });
  });

  group('herramienta', () {
    test('con Seleccionar, tocar una clase la selecciona y el fondo suelta', () {
      final t = tocar(modelo, const Seleccionar(), const Offset(150, 120));
      expect((t.efecto as SeleccionarElemento).id, 'pedido');
      final fondo = tocar(modelo, const Seleccionar(), const Offset(380, 400));
      expect((fondo.efecto as SeleccionarElemento).id, isNull);
    });

    test('con una relacion armada: origen, destino, y vuelve a Seleccionar', () {
      final primero = tocar(modelo, const ArmarRelacion(TipoRelacion.herencia), const Offset(150, 120));
      expect(primero.efecto, isA<EsperarDestino>());
      expect((primero.herramienta as ArmarRelacion).origenId, 'pedido');

      final segundo = tocar(modelo, primero.herramienta, const Offset(560, 130));
      final crear = segundo.efecto as CrearRelacion;
      expect([crear.origenId, crear.destinoId, crear.tipo], ['pedido', 'cliente', TipoRelacion.herencia]);
      expect(segundo.herramienta, isA<Seleccionar>());
    });

    test('tocar el fondo con un origen elegido lo descarta pero deja la relacion armada', () {
      final t = tocar(modelo, const ArmarRelacion(TipoRelacion.asociacion, 'pedido'), const Offset(380, 400));
      expect(t.efecto, isA<Nada>());
      expect((t.herramienta as ArmarRelacion).origenId, isNull);
    });

    test('una relacion reflexiva se puede (la web la permite)', () {
      final t = tocar(modelo, const ArmarRelacion(TipoRelacion.asociacion, 'pedido'), const Offset(150, 120));
      final crear = t.efecto as CrearRelacion;
      expect([crear.origenId, crear.destinoId], ['pedido', 'pedido']);
    });
  });

  group('clase nueva', () {
    test('nace en el centro de lo visible', () {
      final p = posicionNueva(modelo, const Rect.fromLTWH(0, 300, 400, 400));
      expect(p, const Offset(115, 470));
    });

    test('si choca con otra, se corre en escalera hasta quedar libre', () {
      // El centro cae justo sobre Pedido.
      final p = posicionNueva(modelo, Rect.fromCenter(center: const Offset(185, 130), width: 400, height: 400));
      expect(claseEn(modelo, p.translate(1, 1)), isNull);
      expect(p.dx, greaterThan(100));
    });

    test('el arrastre se redondea y uno minimo no cuenta', () {
      expect(posicionArrastrada(const Offset(100, 100), const Offset(10.4, -3.6)), const Offset(110, 96));
      expect(esMovimiento(const Offset(2, 2)), isFalse);
      expect(esMovimiento(const Offset(8, 0)), isTrue);
    });

    test('el modelo para dibujar el arrastre mueve solo esa clase', () {
      final m = conClaseEn(modelo, 'pedido', const Offset(300, 250));
      expect([m.layout['pedido']!.x, m.layout['pedido']!.y], [300, 250]);
      expect(m.layout['cliente']!.x, 500);
      expect(modelo.layout['pedido']!.x, 100, reason: 'no toca el modelo original');
    });
  });

  group('textos', () {
    test('multiplicidades como en la web', () {
      for (final ok in ['', '1', '0..1', '1..*', '0..*', '2..5', '*']) {
        expect(multiplicidadValida(ok), isTrue, reason: ok);
      }
      for (final mal in ['l..*', '1..', 'muchos', '0...1']) {
        expect(multiplicidadValida(mal), isFalse, reason: mal);
      }
    });

    test('un nombre vacio no es un nombre', () {
      expect(nombreLimpio('  Pedido '), 'Pedido');
      expect(nombreLimpio('   '), isNull);
    });

    test('los tipos de un atributo suman las enumeraciones, menos la propia', () {
      final conEnum = modelo.copiar(classes: [
        ...modelo.classes,
        const UmlClass(id: 'estado', diagramId: 'd', name: 'EstadoPedido', stereotype: 'enumeration'),
      ]);
      expect(tiposDeAtributo(conEnum), contains('EstadoPedido'));
      expect(tiposDeAtributo(conEnum, excepto: 'estado'), isNot(contains('EstadoPedido')));
      expect(tiposDeAtributo(conEnum).take(9), tiposBase);
    });
  });

  group('zoom', () {
    test('lo visible en coordenadas del modelo', () {
      final r = visibleEnModelo(const Size(400, 800), 0.5, const Offset(-100, -50));
      expect(r, const Rect.fromLTWH(200, 100, 800, 1600));
    });

    test('encuadrar nunca agranda mas del 100 % ni achica menos del 20 %', () {
      expect(escalaParaEncuadrar(const Size(400, 800), const Rect.fromLTWH(0, 0, 200, 200)), 1);
      expect(escalaParaEncuadrar(const Size(400, 800), const Rect.fromLTWH(0, 0, 800, 200)), 0.5);
      expect(escalaParaEncuadrar(const Size(400, 800), const Rect.fromLTWH(0, 0, 8000, 200)), 0.2);
    });
  });

  test('solo Modelador y Propietario editan', () {
    expect(rolPuedeEditar('OWNER'), isTrue);
    expect(rolPuedeEditar('EDITOR'), isTrue);
    expect(rolPuedeEditar('VIEWER'), isFalse);
    expect(rolPuedeEditar(null), isFalse);
  });
}

/// El centro vertical de una clase de una linea (alto 60).
double claseEnCentroY(EstadoModelo modelo, String id) => modelo.layout[id]!.y + 30;
