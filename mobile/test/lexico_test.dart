import 'package:colabmodel_app/domain/app_manifest.dart';
import 'package:colabmodel_app/domain/lexico.dart';
import 'package:colabmodel_app/domain/valores.dart';
import 'package:flutter_test/flutter_test.dart';

double _p(String a, String b) => parecido(normalizar(a), normalizar(b));

void main() {
  group('sinonimos en espanol', () {
    test('tildes y mayusculas no cuentan; la enie sigue a la Regla 8', () {
      expect(normalizar('TÍTULO'), 'titulo');
      expect(normalizar('Año Académico'), 'anio academico');
      expect(_p('título', 'titulo'), 1.0);
    });

    test('singular y plural', () {
      for (final (a, b) in [('libro', 'libros'), ('autor', 'autores'), ('ciudad', 'ciudades'),
        ('pez', 'peces'), ('país', 'países'), ('mes', 'meses'), ('cliente', 'clientes')]) {
        expect(_p(a, b), 0.9, reason: '$a / $b');
      }
    });

    test('genero', () {
      for (final (a, b) in [('alumno', 'alumna'), ('doctor', 'doctora'), ('socios', 'socias'), ('cliente', 'clienta')]) {
        expect(_p(a, b), 0.9, reason: '$a / $b');
      }
    });

    test('errores de dictado: una letra en palabras medianas, dos en largas, ninguna en cortas', () {
      expect(_p('titulo', 'titlo'), 0.8);
      expect(_p('devolucion', 'debolusion'), 0.75);
      expect(_p('mes', 'mas'), 0);
      expect(_p('libro', 'socio'), 0);
    });
  });

  group('valores', () {
    FieldDescriptor campo(String tipo, {String? min, String? max}) =>
        FieldDescriptor(name: 'x', label: 'x', typeName: tipo, required: false, unique: false, synonyms: const ['x'],
            min: min, max: max);

    test('decimales con coma o punto, con miles', () {
      expect(leerDecimal('150,50'), '150.5');
      expect(leerDecimal('150.50'), '150.5');
      expect(leerDecimal('1.500,50'), '1500.5');
      expect(leerDecimal('1,500.50'), '1500.5');
      expect(leerDecimal('-0,01'), '-0.01');
      expect(leerDecimal('1,2,3'), isNull);
      expect(leerDecimal('caro'), isNull);
    });

    test('enteros en cifras y en palabras', () {
      expect(leerEntero('1.500'), BigInt.from(1500));
      expect(leerEntero('treinta y cinco'), BigInt.from(35));
      expect(leerEntero('veintidós'), BigInt.from(22));
      expect(leerEntero('1,5'), isNull);
    });

    test('rangos sin pasar por double', () {
      expect(compararDecimales('0.1', '0.10'), 0);
      expect(compararDecimales('99999999999999999.01', '99999999999999999'), 1);
      expect(convertir(campo('integer', min: '1', max: '10'), '11', DateTime(2026)), isA<Invalido>());
      expect(convertir(campo('integer'), '3000000000', DateTime(2026)), isA<Invalido>());
      expect((convertir(campo('long'), '3000000000', DateTime(2026)) as Convertido).json, 3000000000);
    });

    test('un tipo que esta version no conoce se rechaza con motivo, no rompe', () {
      final r = convertir(campo('geometria'), 'algo', DateTime(2026));
      expect((r as Invalido).motivo, contains('no se carga por voz'));
    });
  });
}
