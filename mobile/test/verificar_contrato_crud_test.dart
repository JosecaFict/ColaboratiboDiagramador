import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import '../tool/verificar_contrato_crud.dart';

/// Manifiesto minimo con una referencia obligatoria (Pedido -> Cliente).
final manifiesto = {
  'manifestVersion': 1,
  'app': 'prueba',
  'entities': [
    {
      'name': 'Cliente',
      'path': '/api/cliente',
      'abstract': false,
      'actions': ['list', 'get', 'create', 'update', 'delete'],
      'fields': [
        {'name': 'nombre', 'type': 'string', 'required': true, 'maxLength': 255},
      ],
    },
    {
      'name': 'Pedido',
      'path': '/api/pedido',
      'abstract': false,
      'actions': ['list', 'get', 'create', 'update', 'delete'],
      'fields': [
        {'name': 'numero', 'type': 'string', 'required': true},
        {'name': 'total', 'type': 'decimal', 'required': true},
        {'name': 'clienteId', 'type': 'reference', 'required': true, 'target': 'Cliente'},
      ],
    },
  ],
};

/// Servidor en memoria. [moderno] cumple el contrato de §A; si no, se comporta
/// como el generador anterior: sin PATCH, sin paginacion, sin X-Total-Count,
/// POST con 200, sin version y sin 409 al borrar algo referenciado.
class ServidorFalso {
  final bool moderno;

  /// Que hace DELETE del todo (Cliente) cuando todavia tiene partes (Pedido):
  /// 'restrictivo' = 409 (PostgreSQL con FK), 'partes409' = 409 que pide borrar
  /// las partes primero (MongoDB, T-01/D-02), 'cascada' = borra las partes y
  /// responde 204, 'huerfano' = 204 y deja las partes colgando (incorrecto).
  final String borrado;

  /// Si no es null, despues de un PUT o PATCH exitoso de Cliente guarda y devuelve
  /// un `nombre` corrupto: 'vacio' (""), 'prefijo' (le faltan las dos ultimas
  /// letras) o 'sufijo' (le sobra una). El verificador tiene que fallar.
  final String? corrompe;
  final Map<String, Map<String, Map<String, dynamic>>> tablas = {
    '/api/cliente': {},
    '/api/pedido': {},
    '/api/medida': {},
  };
  var _id = 0;
  ServidorFalso({required this.moderno, this.borrado = 'restrictivo', this.corrompe});

  Map<String, dynamic> _corromper(String ruta, Map<String, dynamic> fila) {
    if (corrompe == null || ruta != '/api/cliente' || fila['nombre'] is! String) return fila;
    final n = fila['nombre'] as String;
    fila['nombre'] = switch (corrompe) {
      'vacio' => '',
      'prefijo' => n.substring(0, n.length - 2),
      _ => '${n}x',
    };
    return fila;
  }

  http.Response _json(Object o, int estado, [Map<String, String> h = const {}]) =>
      http.Response(jsonEncode(o), estado, headers: {'content-type': 'application/json', ...h});

  http.Response _error(int estado, String msg, [Map<String, String>? errores]) =>
      _json({'error': msg, if (errores != null) 'errores': errores}, estado);

  Future<http.Response> atender(http.Request r) async {
    final p = r.url.path;
    if (p == '/actuator/health') return moderno ? _json({'status': 'UP'}, 200) : http.Response('no', 404);
    final partes = p.split('/'); // ['', 'api', 'cliente', id?]
    final ruta = '/${partes[1]}/${partes[2]}';
    final tabla = tablas[ruta];
    if (tabla == null) return _error(404, 'no existe');
    final id = partes.length > 3 ? partes[3] : null;
    final cuerpo = r.body.isEmpty ? <String, dynamic>{} : jsonDecode(r.body) as Map<String, dynamic>;

    if (id == null) {
      if (r.method == 'GET') {
        var filas = tabla.values.toList();
        final sort = r.url.queryParameters['sort'];
        if (moderno && sort != null) {
          final campo = sort.split(',')[0];
          final desc = sort.endsWith('desc');
          filas.sort((a, b) => '${a[campo]}'.toLowerCase().compareTo('${b[campo]}'.toLowerCase()) * (desc ? -1 : 1));
        }
        final total = filas.length;
        if (moderno && r.url.queryParameters.containsKey('page')) {
          final page = int.parse(r.url.queryParameters['page']!);
          final size = int.parse(r.url.queryParameters['size'] ?? '20');
          filas = filas.skip(page * size).take(size).toList();
        }
        return _json(filas, 200, moderno ? {'X-Total-Count': '$total'} : {});
      }
      if (r.method == 'POST') {
        final faltan = <String, String>{};
        if (ruta == '/api/cliente' && cuerpo['nombre'] == null) faltan['nombre'] = 'es obligatorio';
        if (ruta == '/api/pedido' && cuerpo['numero'] == null) faltan['numero'] = 'es obligatorio';
        if (faltan.isNotEmpty) {
          return moderno ? _error(400, 'Validacion', faltan) : _json({'mensaje': 'mal'}, 400);
        }
        final nuevo = {...cuerpo, 'id': 'id-${++_id}', if (moderno) 'version': 0};
        tabla[nuevo['id'] as String] = nuevo;
        return _json(nuevo, moderno ? 201 : 200, moderno ? {'Location': '$p/${nuevo['id']}'} : {});
      }
    } else {
      final fila = tabla[id];
      if (fila == null) return moderno ? _error(404, 'No existe') : http.Response('', 404);
      switch (r.method) {
        case 'GET':
          return _json(fila, 200);
        case 'PUT':
          if (moderno && cuerpo['version'] != null && cuerpo['version'] != fila['version']) return _error(409, 'version vieja');
          tabla[id] = _corromper(ruta, {...cuerpo, 'id': id, if (moderno) 'version': (fila['version'] as int) + 1});
          return _json(tabla[id]!, 200);
        case 'PATCH':
          if (!moderno) return http.Response('', 405);
          if (cuerpo['version'] != null && cuerpo['version'] != fila['version']) return _error(409, 'version vieja');
          tabla[id] = _corromper(ruta, {...fila, ...cuerpo..remove('version'), 'version': (fila['version'] as int) + 1});
          return _json(tabla[id]!, 200);
        case 'DELETE':
          if (moderno && ruta == '/api/cliente' && tablas['/api/pedido']!.values.any((x) => x['clienteId'] == id)) {
            switch (borrado) {
              case 'restrictivo':
                return _error(409, 'No se puede borrar el cliente: tiene pedidos');
              case 'partes409':
                return _error(409, 'Elimina primero las partes Pedido.cliente: la cascada entre colecciones requiere una transaccion atomica');
              case 'cascada':
                tablas['/api/pedido']!.removeWhere((_, x) => x['clienteId'] == id);
            }
          }
          tabla.remove(id);
          return http.Response('', 204);
      }
    }
    return http.Response('', 405);
  }
}

/// Una entidad sin campos de texto: PUT y PATCH tienen que probar con un numero o una fecha.
final manifiestoEscalar = {
  'manifestVersion': 1,
  'app': 'prueba',
  'entities': [
    {
      'name': 'Medida',
      'path': '/api/medida',
      'abstract': false,
      'actions': ['list', 'get', 'create', 'update', 'delete'],
      'fields': [
        {'name': 'valor', 'type': 'integer', 'required': true},
        {'name': 'tomada', 'type': 'date', 'required': true},
      ],
    },
  ],
};

Future<List<Punto>> correr(ServidorFalso s, {Map<String, dynamic>? con}) =>
    VerificadorCrud('http://falso', con ?? manifiesto, cliente: MockClient.streaming((req, cuerpo) async {
      final r = await s.atender(http.Request(req.method, req.url)
        ..headers.addAll(req.headers)
        ..body = await cuerpo.bytesToString());
      return http.StreamedResponse(Stream.value(r.bodyBytes), r.statusCode, headers: r.headers);
    })).verificar();

void main() {
  test('un servidor que cumple el contrato pasa todo', () async {
    final s = ServidorFalso(moderno: true);
    final puntos = await correr(s);
    final fallas = [for (final p in puntos) if (p.resultado == Resultado.falla) '${p.entidad} ${p.punto}: ${p.detalle}'];
    expect(fallas, isEmpty);
    // y cubre los puntos del contrato en cada entidad
    final de = (String e) => {for (final p in puntos) if (p.entidad == e) p.punto};
    expect(de('Cliente'), containsAll(['POST 201 + Location', 'GET id 200', 'page/size', 'X-Total-Count', 'PATCH parcial 200', 'version vieja = 409', 'DELETE 204']));
    expect(de('Cliente').any((p) => p.startsWith('DELETE referenciado = 409')), isTrue);
    expect(de('(global)'), {'/actuator/health'});
  });

  group('DELETE de un todo con partes (T-02, C2 de T-01)', () {
    Future<Punto> punto(String borrado) async {
      final puntos = await correr(ServidorFalso(moderno: true, borrado: borrado));
      return puntos.firstWhere((p) => p.entidad == 'Cliente' && p.punto.startsWith('DELETE referenciado'));
    }

    test('409 restrictivo (PostgreSQL) pasa', () async {
      expect((await punto('restrictivo')).resultado, Resultado.pasa);
    });

    test('409 que pide borrar las partes primero (MongoDB) pasa', () async {
      final p = await punto('partes409');
      expect(p.resultado, Resultado.pasa);
      expect(p.detalle, contains('Elimina primero las partes'));
    });

    test('cascada que borra las partes pasa', () async {
      final p = await punto('cascada');
      expect(p.resultado, Resultado.pasa);
      expect(p.detalle, contains('cascada'));
    });

    test('204 que deja partes huerfanas falla', () async {
      expect((await punto('huerfano')).resultado, Resultado.falla);
    });

    test('las variantes aceptadas dejan la base limpia', () async {
      for (final b in ['restrictivo', 'partes409', 'cascada']) {
        final s = ServidorFalso(moderno: true, borrado: b);
        await correr(s);
        expect(s.tablas['/api/cliente'], isEmpty, reason: b);
        expect(s.tablas['/api/pedido'], isEmpty, reason: b);
      }
    });
  });

  test('deja la base como la encontro', () async {
    final s = ServidorFalso(moderno: true);
    await correr(s);
    expect(s.tablas['/api/cliente'], isEmpty);
    expect(s.tablas['/api/pedido'], isEmpty);
  });

  test('un servidor del estilo anterior falla justo donde se espera', () async {
    final puntos = await correr(ServidorFalso(moderno: false));
    final fallan = {for (final p in puntos) if (p.resultado == Resultado.falla) '${p.entidad}|${p.punto}'};
    for (final e in ['Cliente', 'Pedido']) {
      expect(fallan, contains('$e|POST 201 + Location')); // responde 200
      expect(fallan, contains('$e|X-Total-Count'));
      expect(fallan, contains('$e|page/size'));
      expect(fallan, contains('$e|PATCH parcial 200'));
      expect(fallan, contains('$e|version vieja = 409'));
      expect(fallan, contains('$e|POST invalido = 400 + forma'));
      expect(fallan, contains('$e|GET id 404 + forma de error'));
    }
    expect(fallan.any((f) => f.startsWith('Cliente|DELETE referenciado')), isTrue);
    expect(fallan, contains('(global)|/actuator/health'));
    // lo que el estilo anterior si cumplia sigue pasando
    final pasan = {for (final p in puntos) if (p.resultado == Resultado.pasa) '${p.entidad}|${p.punto}'};
    expect(pasan, containsAll(['Cliente|GET id 200', 'Cliente|lista sin page = todo', 'Cliente|DELETE 204']));
  });

  test('la tabla resume y marca cada punto', () {
    final t = tabla(const [
      Punto('Cliente', 'GET id 200', Resultado.pasa),
      Punto('Cliente', 'PATCH parcial 200', Resultado.falla, 'PATCH -> 405'),
      Punto('Cliente', 'sort', Resultado.omite, 'sin campo'),
    ]);
    expect(t, contains('PASA'));
    expect(t, contains('FALLA'));
    expect(t, contains('PATCH -> 405'));
    expect(t, contains('TOTAL: 1 PASA, 1 FALLA, 1 OMITE'));
  });

  test('--solo limita las entidades', () async {
    final s = ServidorFalso(moderno: true);
    final v = VerificadorCrud('http://falso', manifiesto,
        solo: {'Cliente'},
        cliente: MockClient.streaming((req, cuerpo) async {
          final r = await s.atender(http.Request(req.method, req.url)..body = await cuerpo.bytesToString());
          return http.StreamedResponse(Stream.value(r.bodyBytes), r.statusCode, headers: r.headers);
        }));
    final puntos = await v.verificar();
    expect(puntos.where((p) => p.entidad == 'Pedido'), isEmpty);
  });

  group('PUT y PATCH comparan el valor completo, segun su tipo (dictamen de Codex en T-02)', () {
    for (final modo in ['vacio', 'prefijo', 'sufijo']) {
      test('un servidor que devuelve el nombre $modo falla en PUT y en PATCH', () async {
        final r = await correr(ServidorFalso(moderno: true, corrompe: modo));
        final fallan = {for (final p in r) if (p.resultado == Resultado.falla) '${p.entidad}|${p.punto}'};
        expect(fallan, containsAll(['Cliente|PUT 200 (reemplazo)', 'Cliente|PATCH parcial 200']));
      });
    }

    test('una entidad sin texto prueba PUT y PATCH con un escalar', () async {
      final r = await correr(ServidorFalso(moderno: true), con: manifiestoEscalar);
      final de = {for (final p in r) if (p.entidad == 'Medida') p.punto: p};
      expect(de['PUT 200 (reemplazo)']?.resultado, Resultado.pasa);
      expect(de['PATCH parcial 200']?.resultado, Resultado.pasa);
      expect(de['PATCH parcial 200']?.detalle, contains('solo'));
    });

    test('texto: solo el valor exacto', () {
      expect(VerificadorCrud.valoresIguales('string', 'abc', 'abc'), isTrue);
      expect(VerificadorCrud.valoresIguales('string', '', 'abc'), isFalse);
      expect(VerificadorCrud.valoresIguales('string', 'ab', 'abc'), isFalse);
      expect(VerificadorCrud.valoresIguales('string', 'abcd', 'abc'), isFalse);
      expect(VerificadorCrud.valoresIguales('string', null, 'abc'), isFalse);
      expect(VerificadorCrud.valoresIguales('string', '12', '12.0'), isFalse);
    });

    test('numeros, booleanos y fechas: solo equivalencias del tipo', () {
      expect(VerificadorCrud.valoresIguales('decimal', 3.5, '3.50'), isTrue);
      expect(VerificadorCrud.valoresIguales('integer', 7, 7.0), isTrue);
      expect(VerificadorCrud.valoresIguales('integer', 7, 70), isFalse);
      expect(VerificadorCrud.valoresIguales('boolean', true, 'true'), isTrue);
      expect(VerificadorCrud.valoresIguales('boolean', false, true), isFalse);
      expect(VerificadorCrud.valoresIguales('date', '2026-11-05', '2026-11-05'), isTrue);
      expect(VerificadorCrud.valoresIguales('date', '2026-11-05', '2026-11-06'), isFalse);
      expect(VerificadorCrud.valoresIguales('date', '2026-11', '2026-11-05'), isFalse);
      expect(VerificadorCrud.valoresIguales('datetime', '2026-11-05T10:00:00.000Z', '2026-11-05T10:00:00'), isTrue);
      expect(VerificadorCrud.valoresIguales('datetime', '2026-11-05T10:00', '2026-11-05T10:00:00'), isTrue);
      expect(VerificadorCrud.valoresIguales('datetime', '2026-11-05T11:00:00', '2026-11-05T10:00:00'), isFalse);
      expect(VerificadorCrud.valoresIguales('datetime', '2026-11-05', '2026-11-05T10:00:00'), isFalse);
    });
  });
}
