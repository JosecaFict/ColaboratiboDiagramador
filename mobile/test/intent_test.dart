// Motor de intenciones contra manifiestos REALES: los de test/manifests/ los
// escribe el generador (backend: ManifestDeAppTest), no una persona. Ninguna
// prueba ni linea del motor conoce los dominios: cambiar de biblioteca a
// vuelos es cambiar de archivo.

import 'dart:convert';
import 'dart:io';

import 'package:colabmodel_app/domain/app_manifest.dart';
import 'package:colabmodel_app/domain/intent.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> _json(String nombre) =>
    jsonDecode(File('test/manifests/$nombre.json').readAsStringSync()) as Map<String, dynamic>;

AppManifest _manifiesto(String nombre) => AppManifest.fromJson(_json(nombre));

/// Miercoles 16 de septiembre de 2026, 10:00: "hoy" tiene que dar siempre lo mismo.
MotorDeIntenciones _motor(String nombre) =>
    MotorDeIntenciones(_manifiesto(nombre), reloj: () => DateTime(2026, 9, 16, 10));

T _es<T>(Interpretacion i) {
  expect(i, isA<T>(), reason: _describir(i));
  return i as T;
}

String _describir(Interpretacion i) => switch (i) {
      Resuelta r => 'Resuelta ${r.entidad.name} ${r.cuerpo} refs=${r.referencias.map((x) => '${x.campo.name}:${x.texto}')}',
      Incompleta x => 'Incompleta ${x.entidad.name} ${x.cuerpo} falta=${x.faltantes.map((f) => f.name)}',
      Ambigua a => 'Ambigua ${a.clave}: ${a.pregunta} ${a.opciones}',
      Rechazada r => 'Rechazada: ${r.mensaje}',
      FueraDeAlcance f => 'FueraDeAlcance: ${f.motivo}',
      NoEntendida n => 'NoEntendida: ${n.motivo}',
    };

const _todos = [
  '01-uno-a-uno', '02-uno-a-muchos', '03-muchos-a-muchos', '04-herencia',
  '05-clase-asociativa', '06-nombres-acentos', '07-reflexiva-doble', 'biblioteca',
];

void main() {
  // ===================================================== DOMINIO QUE NUNCA VIO

  group('biblioteca: un dominio que ningun canonico cubre', () {
    final motor = _motor('biblioteca');

    test('"crear libro con título X" descubre entidad, campo y valor solo del manifiesto', () {
      final r = _es<Incompleta>(motor.interpretar('crear libro con título Cien años de soledad'));
      expect(r.entidad.name, 'Libro');
      expect(r.cuerpo, {'titulo': 'Cien años de soledad'});
      // Lo obligatorio que falta se pide; no se inventa.
      expect(r.faltantes.map((f) => f.name), ['isbn', 'estado']);
      expect(r.pregunta, 'Para crear libro falta isbn y estado.');
    });

    test('con todo lo obligatorio queda lista para el POST', () {
      final r = _es<Resuelta>(motor.interpretar(
          'Creá un libro con título Cien años de soledad, isbn 978-84-376-0494-7 y estado disponible.'));
      expect(r.metodo, 'POST');
      expect(r.ruta, '/api/libro');
      expect(r.cuerpo, {
        'titulo': 'Cien años de soledad',
        'isbn': '978-84-376-0494-7',
        'estado': 'DISPONIBLE',
      });
      expect(r.unicos.map((f) => f.name), ['isbn']);
    });

    test('plural, sin tilde, errores de dictado y clitico dan lo mismo', () {
      for (final frase in [
        'crear libros con titulo Rayuela, isbn 1 y estado disponible',
        'nuevo libro con títlo Rayuela, isbn 1 y estado disponible',
        'registrame un libro con TÍTULO Rayuela, ISBN 1, estado Disponible',
        'quiero que crees un libro con título es Rayuela, isbn: 1 y estado igual a disponible',
      ]) {
        final r = _es<Resuelta>(motor.interpretar(frase));
        expect(r.cuerpo, {'titulo': 'Rayuela', 'isbn': '1', 'estado': 'DISPONIBLE'}, reason: frase);
      }
    });

    test('un literal de varias palabras y un nombre de campo de varias palabras', () {
      final r = _es<Resuelta>(motor.interpretar(
          'crear libro con título Rayuela, isbn 1, estado en préstamo y año de publicación 1963'));
      expect(r.cuerpo['estado'], 'EN_PRESTAMO');
      expect(r.cuerpo['anioPublicacion'], 1963);
    });

    test('una palabra que es nombre de campo no parte el valor si no hay delimitador', () {
      final r = _es<Resuelta>(motor.interpretar('crear libro con título El autor y estado de baja, isbn 7'));
      expect(r.cuerpo['titulo'], 'El autor');
      expect(r.cuerpo['estado'], 'DE_BAJA');
    });

    test('entre comillas el valor es opaco', () {
      final r = _es<Resuelta>(motor.interpretar('crear libro con título "El autor y estado" e isbn 9 y estado disponible'));
      expect(r.cuerpo['titulo'], 'El autor y estado');
    });

    test('referencias dichas por nombre quedan pendientes para resolver contra P2', () {
      final r = _es<Resuelta>(motor.interpretar(
          'registrá un préstamo del libro Rayuela al socio Ana Pérez con fecha de préstamo hoy'));
      expect(r.entidad.name, 'Prestamo');
      expect(r.cuerpo, {'fechaPrestamo': '2026-09-16'});
      expect(r.referencias.map((x) => '${x.campo.name}->${x.entidadDestino}:${x.texto}'),
          ['libroId->Libro:Rayuela', 'socioId->Socio:Ana Pérez']);
    });

    test('una referencia dicha por id va directo al cuerpo', () {
      final r = _es<Resuelta>(motor.interpretar('crear préstamo con libro 3f1c2b9e-0d4a-4c7e-9a41-2b6f0c1d8e55, '
          'socio 3f1c2b9e-0d4a-4c7e-9a41-2b6f0c1d8e56 y fecha préstamo 1 de marzo'));
      expect(r.referencias, isEmpty);
      expect(r.cuerpo['libroId'], '3f1c2b9e-0d4a-4c7e-9a41-2b6f0c1d8e55');
      expect(r.cuerpo['fechaPrestamo'], '2026-03-01');
    });

    test('fechas y horas', () {
      final r = _es<Resuelta>(motor.interpretar('crear préstamo con libro X, socio Ana, fecha préstamo 15/09/2026, '
          'fecha devolución pasado mañana y hora retiro mañana a las 9:30'));
      expect(r.cuerpo['fechaPrestamo'], '2026-09-15');
      expect(r.cuerpo['fechaDevolucion'], '2026-09-18');
      expect(r.cuerpo['horaRetiro'], '2026-09-17T09:30:00');
    });

    test('listar', () {
      for (final (frase, ruta) in [
        ('listar libros', '/api/libro'),
        ('mostrame los socios', '/api/socio'),
        ('¿qué préstamos hay?', '/api/prestamo'),
        ('cuáles son los libros', '/api/libro'),
      ]) {
        final r = _es<Resuelta>(motor.interpretar(frase));
        expect(r.accion, Accion.listar, reason: frase);
        expect(r.metodo, 'GET');
        expect(r.ruta, ruta, reason: frase);
      }
    });
  });

  // =================================================================== EMPATES

  group('empates: se pregunta, no se adivina', () {
    test('dos campos que empatan (vuelo: origen y destino son aeropuertos)', () {
      final motor = _motor('07-reflexiva-doble');
      const frase = 'crear vuelo con código AA100 y aeropuerto VVI';
      final a = _es<Ambigua>(motor.interpretar(frase));
      expect(a.clave, 'campo:aeropuerto');
      expect(a.opciones.map((o) => o.valor), ['origenId', 'destinoId']);
      expect(a.pregunta, '«aeropuerto» puede ser origen o destino: ¿cuál?');

      // Contestada la pregunta, sigue: ahora falta el otro extremo.
      final i = _es<Incompleta>(motor.interpretar(frase, aclaraciones: {a.clave: 'origenId'}));
      expect(i.referencias.single.campo.name, 'origenId');
      expect(i.faltantes.map((f) => f.name), ['destinoId']);
    });

    test('un prefijo que empata entre dos campos (fecha préstamo / fecha devolución)', () {
      final motor = _motor('biblioteca');
      const frase = 'crear préstamo del libro X al socio Ana con fecha 31/02/2026';
      final a = _es<Ambigua>(motor.interpretar(frase));
      expect(a.opciones.map((o) => o.valor), ['fechaPrestamo', 'fechaDevolucion']);

      // Y aclarado el campo, el valor igual se rechaza: el 31 de febrero no existe.
      final r = _es<Rechazada>(motor.interpretar(frase, aclaraciones: {a.clave: 'fechaPrestamo'}));
      expect(r.mensaje, contains('no existe en el calendario'));
    });

    test('dos entidades que empatan (un sinonimo agregado a mano el dia de la demo)', () {
      final json = _json('biblioteca');
      for (final e in json['entities'] as List) {
        if (e['name'] == 'Libro' || e['name'] == 'Prestamo') (e['synonyms'] as List).add('registro');
      }
      final motor = MotorDeIntenciones(AppManifest.fromJson(json), reloj: () => DateTime(2026, 9, 16));
      const frase = 'crear registro con título Rayuela, isbn 1 y estado disponible';

      final a = _es<Ambigua>(motor.interpretar(frase));
      expect(a.clave, 'entidad');
      expect(a.opciones.map((o) => o.valor), ['Libro', 'Prestamo']);

      final r = _es<Resuelta>(motor.interpretar(frase, aclaraciones: {'entidad': 'Libro'}));
      expect(r.cuerpo['titulo'], 'Rayuela');
    });

    test('una entidad abstracta: se pregunta cual de sus hijas', () {
      final motor = _motor('04-herencia');
      const frase = 'crear persona con nombre Ana Díaz y ci 1234567';
      final a = _es<Ambigua>(motor.interpretar(frase));
      expect(a.opciones.map((o) => o.valor), ['Empleado', 'Cliente']);
      expect(a.pregunta, 'persona es general: ¿qué querés crear, empleado o cliente?');

      final r = _es<Resuelta>(motor.interpretar(frase, aclaraciones: {'entidad': 'Empleado'}));
      expect(r.ruta, '/api/empleado');
      // Lo heredado de Persona se carga en la hija.
      expect(r.cuerpo, {'nombre': 'Ana Díaz', 'ci': '1234567'});
    });

    test('sin entidad: se pregunta que crear', () {
      final a = _es<Ambigua>(_motor('02-uno-a-muchos').interpretar('crear con nombre Juan'));
      expect(a.clave, 'entidad');
      expect(a.pregunta, '¿Qué querés crear? Puedo: cliente o pedido.');
    });

    test('texto sin campo: se pregunta a que campo va, entre los que lo aceptan', () {
      final motor = _motor('biblioteca');
      const frase = 'crear libro Rayuela';
      final a = _es<Ambigua>(motor.interpretar(frase));
      // "estado" no acepta "Rayuela" y el año tampoco: no se ofrecen.
      expect(a.opciones.map((o) => o.valor), ['titulo', 'autor', 'isbn']);

      final i = _es<Incompleta>(motor.interpretar(frase, aclaraciones: {a.clave: 'titulo'}));
      expect(i.cuerpo, {'titulo': 'Rayuela'});
    });

    test('dos literales que empatan', () {
      final json = _json('biblioteca');
      final libro = (json['entities'] as List).firstWhere((e) => e['name'] == 'Libro');
      final estado = (libro['fields'] as List).firstWhere((f) => f['name'] == 'estado');
      estado['literals'] = ['RESERVADO', 'RESERVADA'];
      final motor = MotorDeIntenciones(AppManifest.fromJson(json));
      const frase = 'crear libro con título X, isbn 1 y estado reservados';

      final a = _es<Ambigua>(motor.interpretar(frase));
      expect(a.clave, 'valor:estado');
      final r = _es<Resuelta>(motor.interpretar(frase, aclaraciones: {'valor:estado': 'RESERVADA'}));
      expect(r.cuerpo['estado'], 'RESERVADA');
    });
  });

  // ========================================================== VALORES INVALIDOS

  group('valores invalidos: rechazo con motivo, antes de P2', () {
    final biblioteca = _motor('biblioteca');

    test('fuera de rango', () {
      final r = _es<Rechazada>(biblioteca.interpretar(
          'crear libro con título X, isbn 1, estado disponible y año de publicación 1200'));
      expect(r.motivos.single.campo!.name, 'anioPublicacion');
      expect(r.mensaje, 'año publicación tiene que ser al menos 1450 y como mucho 2100, y dijiste 1200');
    });

    test('bajo el minimo de un decimal', () {
      final r = _es<Rechazada>(biblioteca.interpretar('crear libro con título X, isbn 1, estado disponible y precio -0,01'));
      expect(r.mensaje, contains('al menos 0'));
    });

    test('un literal que la enumeracion no tiene', () {
      final r = _es<Rechazada>(biblioteca.interpretar('crear libro con título X, isbn 1 y estado perdido'));
      expect(r.mensaje, '«perdido» no es un valor de estado; puede ser disponible, en prestamo o de baja');
    });

    test('tipo que no encaja: se juntan todos los motivos', () {
      final r = _es<Rechazada>(biblioteca.interpretar(
          'crear libro con título X, isbn 1, estado disponible, año de publicación muchos y precio caro'));
      expect(r.motivos.map((m) => m.campo!.name), ['anioPublicacion', 'precio']);
    });

    test('mas largo de lo que admite la columna', () {
      final r = _es<Rechazada>(biblioteca.interpretar('crear socio con nombre ${'a' * 256}'));
      expect(r.mensaje, contains('hasta 255 caracteres'));
    });

    test('booleano', () {
      expect(_es<Resuelta>(biblioteca.interpretar('crear socio con nombre Ana y activo sí')).cuerpo['activo'], true);
      _es<Rechazada>(biblioteca.interpretar('crear socio con nombre Ana y activo quizás'));
    });

    test('fecha y hora sin hora', () {
      final r = _es<Rechazada>(biblioteca.interpretar(
          'crear préstamo con libro X, socio Ana, fecha préstamo hoy y hora retiro mañana'));
      expect(r.mensaje, contains('lleva fecha y hora'));
    });

    test('el mismo campo dos veces', () {
      final r = _es<Rechazada>(biblioteca.interpretar('crear libro con título A, isbn 1, estado disponible y título B'));
      expect(r.mensaje, 'Dijiste título dos veces');
    });

    test('pedido: decimal, fecha y hora', () {
      final motor = _motor('02-uno-a-muchos');
      final ok = _es<Resuelta>(motor.interpretar('crear pedido para el cliente Juan con total 1.500,50 y fecha hoy a las 10'));
      expect(ok.cuerpo, {'total': '1500.5', 'fecha': '2026-09-16T10:00:00'});
      expect(ok.referencias.single.texto, 'Juan');

      final mal = _es<Rechazada>(motor.interpretar('crear pedido para el cliente Juan con total abc y fecha 2026-13-01 a las 10'));
      expect(mal.motivos.map((m) => m.campo!.name), ['total', 'fecha']);
    });

    test('un entero que no es entero', () {
      final r = _es<Rechazada>(_motor('06-nombres-acentos').interpretar('crear año académico con descripción X y orden 1,5'));
      expect(r.mensaje, 'orden es un número entero y «1,5» no lo es');
    });
  });

  // ================================================================ CANONICOS

  group('canonicos', () {
    test('nombres hostiles: enie, tildes, plural en -ces y numeros dichos en palabras', () {
      final motor = _motor('06-nombres-acentos');
      final r = _es<Resuelta>(motor.interpretar('Creá un año académico con descripción Gestión 2026 y orden cinco'));
      expect(r.entidad.name, 'AnioAcademico');
      expect(r.ruta, '/api/anio-academico');
      expect(r.cuerpo, {'descripcion': 'Gestión 2026', 'orden': 5});

      expect(_es<Resuelta>(motor.interpretar('listar peces')).entidad.name, 'Pez');
      expect(_es<Resuelta>(motor.interpretar('mostrame los niños')).entidad.name, 'Ninio');
    });

    test('clase asociativa con dos referencias por delimitadores debiles', () {
      final r = _es<Resuelta>(_motor('05-clase-asociativa').interpretar(
          'registrá una inscripción del estudiante 2020-01 a la materia INF-110 '
          'con fecha 1 de marzo de 2026 y nota 85,5'));
      expect(r.entidad.name, 'Inscripcion');
      expect(r.cuerpo, {'fecha': '2026-03-01', 'nota': '85.5'});
      expect(r.referencias.map((x) => '${x.campo.name}:${x.texto}'), ['estudianteId:2020-01', 'materiaId:INF-110']);
    });

    test('reflexiva: el jefe es un empleado', () {
      final r = _es<Resuelta>(_motor('07-reflexiva-doble').interpretar('crear empleado con nombre Ana y jefe Luis'));
      expect(r.cuerpo, {'nombre': 'Ana'});
      expect(r.referencias.single.entidadDestino, 'Empleado');
    });

    test('listar una abstracta es valido: la API lista polimorficamente', () {
      expect(_es<Resuelta>(_motor('04-herencia').interpretar('listar personas')).ruta, '/api/persona');
    });

    test('en todos los dominios, cada entidad se lista y se crea por su propio nombre', () {
      for (final nombre in _todos) {
        final manifiesto = _manifiesto(nombre);
        final motor = MotorDeIntenciones(manifiesto);
        for (final e in manifiesto.entities) {
          final lista = _es<Resuelta>(motor.interpretar('listar ${e.nombreHablado}'));
          expect(lista.entidad.name, e.name, reason: '$nombre: listar ${e.nombreHablado}');

          final crear = motor.interpretar('crear ${e.nombreHablado}');
          if (e.isAbstract) {
            expect(crear, isA<Ambigua>(), reason: '$nombre: ${e.name} es abstracta');
          } else if (e.obligatorios.isEmpty) {
            expect((crear as Resuelta).entidad.name, e.name, reason: '$nombre: crear ${e.name}');
          } else {
            expect((crear as Incompleta).faltantes, e.obligatorios, reason: '$nombre: crear ${e.name}');
          }
        }
      }
    });
  });

  // ================================================================== ALCANCE

  group('alcance: solo crear y listar', () {
    final motor = _motor('biblioteca');

    test('actualizar y borrar se reconocen para decir que no estan', () {
      for (final frase in ['borrá el libro Rayuela', 'eliminar socio Ana', 'cambiá el título del libro',
        'dar de baja al socio Ana', 'actualizar préstamo']) {
        expect(_es<FueraDeAlcance>(motor.interpretar(frase)).motivo, contains('solo creo y listo'), reason: frase);
      }
    });

    test('lo que no es un pedido', () {
      _es<NoEntendida>(motor.interpretar('hola, qué tal'));
      _es<NoEntendida>(motor.interpretar(''));
    });

    test('un manifiesto de una version que la app no conoce se rechaza al cargar', () {
      final json = _json('biblioteca')..['manifestVersion'] = 2;
      expect(() => AppManifest.fromJson(json), throwsA(isA<ManifiestoIncompatible>()));
    });
  });
}
