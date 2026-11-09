// Regresiones de la revision de Codex sobre T-19 (89f6d1f): un P1 y dos P2.
import 'dart:convert';

import 'package:colabmodel_app/asistente/controlador_asistente.dart';
import 'package:colabmodel_app/domain/app_manifest.dart';
import 'package:colabmodel_app/domain/intent.dart';
import 'package:flutter_test/flutter_test.dart';

import 'asistente_test.dart' show BackendFalso;

AppManifest _conCampoVersion(BackendFalso b, String entidad) {
  final json = jsonDecode(b.manifiestoJson) as Map<String, dynamic>;
  final e = (json['entities'] as List).firstWhere((e) => e['name'] == entidad) as Map;
  (e['fields'] as List).add({
    'name': 'version',
    'label': 'version',
    'type': 'integer',
    'required': false,
    'unique': false,
    'synonyms': ['version'],
  });
  return AppManifest.fromJson(json);
}

void main() {
  test('P1: el PATCH lleva versionRegistro cuando version es un campo del dominio', () async {
    final b = BackendFalso('biblioteca');
    final manifiesto = _conCampoVersion(b, 'Libro');
    b.sembrar('/api/libro', {'titulo': 'Rayuela', 'version': 7, 'versionRegistro': 3, 'precio': '20'});
    final c = ControladorAsistente(b.clienteBackend(), manifiesto, confirmarAntesDeCrear: false);
    await c.enviar('cambia el precio del libro Rayuela a 50');
    expect(b.parches.single, {'precio': '50', 'versionRegistro': 3});
  });

  test('P1: versionRegistro tambien cuando el choque de nombres esta en otra entidad', () async {
    final b = BackendFalso('biblioteca');
    final manifiesto = _conCampoVersion(b, 'Socio');
    b.sembrar('/api/libro', {'titulo': 'Rayuela', 'versionRegistro': 4, 'precio': '20'});
    final c = ControladorAsistente(b.clienteBackend(), manifiesto, confirmarAntesDeCrear: false);
    await c.enviar('cambia el precio del libro Rayuela a 50');
    expect(b.parches.single, {'precio': '50', 'versionRegistro': 4});
  });

  test('P2: un cambio sin valor no se descarta para mandar un PATCH parcial', () async {
    final b = BackendFalso('biblioteca');
    b.sembrar('/api/libro', {'titulo': 'Rayuela', 'version': 3, 'precio': '20', 'estado': 'DISPONIBLE'});
    final c = ControladorAsistente(b.clienteBackend(), b.manifiesto, confirmarAntesDeCrear: false);
    const frase = 'actualizar el libro Rayuela con precio 50 y estado';
    await c.enviar(frase);
    expect(b.parches, isEmpty, reason: 'falta el valor del estado');
    expect(MotorDeIntenciones(b.manifiesto).interpretar(frase), isA<NoEntendida>());
  });

  test('P2: el valor nuevo puede llevar una preposicion despues del separador «a»', () {
    final motor = MotorDeIntenciones(BackendFalso('biblioteca').manifiesto);
    final r = motor.interpretar('cambia el titulo del libro Rayuela a El amor en los tiempos del colera');
    expect(r, isA<Resuelta>());
    expect((r as Resuelta).busqueda, 'Rayuela');
    expect(r.cuerpo, {'titulo': 'El amor en los tiempos del colera'});
  });

  test('P2: el nombre del registro puede llevar «a» si el valor solo es valido despues del ultimo', () {
    final motor = MotorDeIntenciones(BackendFalso('biblioteca').manifiesto);
    final r = motor.interpretar('cambia el precio del libro Viaje a la luna a 50');
    expect(r, isA<Resuelta>());
    expect((r as Resuelta).busqueda, 'Viaje a la luna');
    expect(r.cuerpo, {'precio': '50'});
  });

  test('P2: con dos «a» posibles en un texto se pregunta, y la respuesta resuelve', () {
    final motor = MotorDeIntenciones(BackendFalso('biblioteca').manifiesto);
    const frase = 'cambia el titulo del libro Viaje a la luna a Regreso';
    final r = motor.interpretar(frase);
    expect(r, isA<Ambigua>());
    final a = r as Ambigua;
    expect(a.clave, 'corte');
    expect(a.opciones.length, 2);
    final resuelta = motor.interpretar(frase, aclaraciones: {'corte': a.opciones.last.valor}) as Resuelta;
    expect(resuelta.busqueda, 'Viaje a la luna');
    expect(resuelta.cuerpo, {'titulo': 'Regreso'});
  });
}
