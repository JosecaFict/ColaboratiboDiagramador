// Verificador del contrato CRUD de un backend generado (docs/DISENO-FASE-2.md §A, ADR-034).
//
//   dart run tool/verificar_contrato_crud.dart <urlBackend> [app-manifest.json] [--solo=Pedido,Cliente]
//
// Sin el segundo argumento baja el manifiesto de <urlBackend>/app-manifest.json.
// Por cada entidad con `create` en el manifiesto recorre el contrato, imprime
// una tabla PASA / FALLA / OMITE por punto y termina con codigo 1 si algo FALLA
// (2 si no pudo ni empezar). Deja la base como la encontro: borra lo que crea.
//
// Es SOLO una sonda HTTP: no conoce el generador, solo el contrato.
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:http/http.dart' as http;

enum Resultado { pasa, falla, omite }

class Punto {
  final String entidad;
  final String punto;
  final Resultado resultado;
  final String detalle;
  const Punto(this.entidad, this.punto, this.resultado, [this.detalle = '']);
}

class _Resp {
  final int estado;
  final Map<String, String> cabeceras;
  final String cuerpo;
  final dynamic json;
  _Resp(this.estado, this.cabeceras, this.cuerpo, this.json);

  Map<String, dynamic>? get objeto => json is Map ? Map<String, dynamic>.from(json as Map) : null;
}

class _Fila {
  final String id;
  final Map<String, dynamic> enviado;
  final Map<String, dynamic> recibido;
  _Fila(this.id, this.enviado, this.recibido);
}

class _Entidad {
  final Map<String, dynamic> j;
  _Entidad(this.j);
  String get nombre => j['name'] as String;
  String get ruta => j['path'] as String;
  bool get esAbstracta => j['abstract'] == true;
  List<String> get acciones => List<String>.from(j['actions'] as List? ?? const []);
  List<Map<String, dynamic>> get campos =>
      [for (final f in (j['fields'] as List? ?? const [])) Map<String, dynamic>.from(f as Map)];
  bool permite(String a) => acciones.contains(a);
  bool get creable => !esAbstracta && permite('create');
}

/// Recorre el contrato contra un backend vivo (o simulado, con un `http.Client` falso).
class VerificadorCrud {
  final String base;
  final List<_Entidad> _entidades;
  final http.Client _http;
  final Set<String>? solo;
  final _azar = Random(7);
  final List<Punto> puntos = [];
  var _n = 0;
  final List<(String ruta, String id)> _creados = [];

  VerificadorCrud(String base, Map<String, dynamic> manifiesto, {http.Client? cliente, this.solo})
      : base = base.replaceAll(RegExp(r'/+$'), ''),
        _http = cliente ?? http.Client(),
        _entidades = [for (final e in (manifiesto['entities'] as List)) _Entidad(Map<String, dynamic>.from(e as Map))];

  _Entidad? _entidad(String nombre) {
    for (final e in _entidades) {
      if (e.nombre == nombre) return e;
    }
    return null;
  }

  // ------------------------------------------------------------------ HTTP

  Future<_Resp> _pedir(String metodo, String ruta, {Object? cuerpo, Map<String, String>? query}) async {
    final uri = Uri.parse('$base$ruta').replace(queryParameters: query);
    final req = http.Request(metodo, uri)..headers['Accept'] = 'application/json';
    if (cuerpo != null) {
      req.headers['Content-Type'] = 'application/json';
      req.body = jsonEncode(cuerpo);
    }
    final r = await http.Response.fromStream(await _http.send(req).timeout(const Duration(seconds: 20)));
    final texto = utf8.decode(r.bodyBytes);
    dynamic j;
    try {
      j = texto.isEmpty ? null : jsonDecode(texto);
    } on FormatException {
      j = null;
    }
    return _Resp(r.statusCode, {for (final e in r.headers.entries) e.key.toLowerCase(): e.value}, texto, j);
  }

  // -------------------------------------------------------------- DATOS

  dynamic _valor(Map<String, dynamic> f, int n) {
    final max = (f['maxLength'] as num?)?.toInt();
    switch (f['type']) {
      case 'string':
        final s = 'v${n}x${_azar.nextInt(99999)}';
        return max != null && s.length > max ? s.substring(0, max) : s;
      case 'integer':
      case 'long':
        final min = num.tryParse('${f['min'] ?? ''}')?.toInt() ?? 1;
        return min + n;
      case 'decimal':
        final min = num.tryParse('${f['min'] ?? ''}') ?? 1;
        return min + n + 0.5;
      case 'boolean':
        return n.isEven;
      case 'date':
        return '2026-11-${(1 + n % 27).toString().padLeft(2, '0')}';
      case 'datetime':
        return '2026-11-${(1 + n % 27).toString().padLeft(2, '0')}T10:00:00';
      case 'uuid':
        return '00000000-0000-4000-8000-${n.toString().padLeft(12, '0')}';
      case 'enum':
        final l = List<String>.from(f['literals'] as List? ?? const []);
        return l.isEmpty ? null : l[n % l.length];
      default:
        return null;
    }
  }

  /// Cuerpo valido de [e]. Las referencias obligatorias se resuelven creando antes lo apuntado.
  Future<Map<String, dynamic>?> _cuerpo(_Entidad e, {Set<String> enCurso = const {}}) async {
    final c = <String, dynamic>{};
    final n = ++_n;
    for (final f in e.campos) {
      final tipo = f['type'];
      if (tipo == 'reference') {
        if (f['required'] != true) continue;
        final destino = _entidad(f['target'] as String);
        if (destino == null || enCurso.contains(destino.nombre)) return null;
        final fila = await _crear(destino, enCurso: {...enCurso, e.nombre});
        if (fila == null) return null;
        c[f['name'] as String] = fila.id;
      } else if (tipo == 'embedded' || tipo == 'list') {
        if (f['required'] == true) return null; // no se sabe armar
      } else {
        final v = _valor(f, n);
        if (v != null) c[f['name'] as String] = v;
      }
    }
    return c;
  }

  Future<_Fila?> _crear(_Entidad e, {Set<String> enCurso = const {}}) async {
    if (!e.creable) return null;
    final cuerpo = await _cuerpo(e, enCurso: enCurso);
    if (cuerpo == null) return null;
    final r = await _pedir('POST', e.ruta, cuerpo: cuerpo);
    final o = r.objeto;
    if (r.estado < 200 || r.estado >= 300 || o == null || o['id'] == null) return null;
    _creados.add((e.ruta, '${o['id']}'));
    return _Fila('${o['id']}', cuerpo, o);
  }

  // ------------------------------------------------------------- PUNTOS

  void _ok(String ent, String punto, [String d = '']) => puntos.add(Punto(ent, punto, Resultado.pasa, d));
  void _mal(String ent, String punto, String d) => puntos.add(Punto(ent, punto, Resultado.falla, d));
  void _omite(String ent, String punto, String d) => puntos.add(Punto(ent, punto, Resultado.omite, d));

  /// Corre [f]; cualquier excepcion es una FALLA de ese punto, no el fin de la corrida.
  Future<void> _guarda(String ent, String punto, Future<void> Function() f) async {
    try {
      await f();
    } on Object catch (e) {
      _mal(ent, punto, 'error al probar: $e');
    }
  }

  static bool _formaDeError(_Resp r) {
    final o = r.objeto;
    return o != null && o['error'] is String && (o['error'] as String).isNotEmpty && (o['errores'] == null || o['errores'] is Map);
  }

  Future<List<Punto>> verificar() async {
    await _guarda('(global)', '/actuator/health', () async {
      final r = await _pedir('GET', '/actuator/health');
      final estado = r.objeto?['status'];
      if (r.estado == 200 && estado == 'UP') {
        _ok('(global)', '/actuator/health');
      } else {
        _mal('(global)', '/actuator/health', 'GET /actuator/health -> ${r.estado} ${_corto(r.cuerpo)}');
      }
    });

    for (final e in _entidades) {
      if (solo != null && !solo!.contains(e.nombre)) continue;
      if (e.permite('list') && !e.creable) {
        await _guarda(e.nombre, 'lista sin page', () async {
          final r = await _pedir('GET', e.ruta);
          r.estado == 200 && r.json is List
              ? _ok(e.nombre, 'lista sin page', '(abstracta/sin create: solo lectura)')
              : _mal(e.nombre, 'lista sin page', 'GET ${e.ruta} -> ${r.estado}');
        });
        continue;
      }
      if (!e.creable) {
        _omite(e.nombre, '(todo)', 'sin accion create en el manifiesto');
        continue;
      }
      await _entidadCompleta(e);
    }

    // Deja la base como estaba: lo ultimo creado se borra primero (las FK).
    for (final (ruta, id) in _creados.reversed) {
      try {
        await _pedir('DELETE', '$ruta/$id');
      } on Object {
        // limpieza de mejor esfuerzo
      }
    }
    return puntos;
  }

  /// Compara lo que devolvio el servidor con lo que se pidio, segun el tipo del
  /// campo en el manifiesto. Solo se toleran equivalencias del propio tipo:
  /// numeros (3.5 == 3.50) y fechas con otra forma de escribir el mismo instante
  /// (`10:00` == `10:00:00.000`, con o sin `Z`). El texto se compara completo:
  /// un vacio o un valor truncado no cuentan como iguales.
  static bool valoresIguales(String? tipo, dynamic recibido, dynamic pedido) {
    if (recibido == null || pedido == null) return recibido == pedido;
    switch (tipo) {
      case 'integer':
      case 'long':
      case 'decimal':
        final x = recibido is num ? recibido : num.tryParse('$recibido');
        final y = pedido is num ? pedido : num.tryParse('$pedido');
        return x != null && y != null && x == y;
      case 'boolean':
        return '$recibido' == '$pedido';
      case 'date':
        final x = _fecha('$recibido');
        final y = _fecha('$pedido');
        return x != null && y != null && x.year == y.year && x.month == y.month && x.day == y.day;
      case 'datetime':
        final x = _fecha('$recibido');
        final y = _fecha('$pedido');
        return x != null &&
            y != null &&
            x.year == y.year &&
            x.month == y.month &&
            x.day == y.day &&
            x.hour == y.hour &&
            x.minute == y.minute &&
            x.second == y.second &&
            x.millisecond == y.millisecond;
      default:
        return recibido == pedido || '$recibido' == '$pedido';
    }
  }

  /// Lee la fecha tal como se escribio, sin pasarla a otra zona: `...Z` y sin zona
  /// valen lo mismo, porque el backend generado no guarda zona horaria.
  static DateTime? _fecha(String s) {
    final sinZona = s.endsWith('Z') ? s.substring(0, s.length - 1) : s;
    return DateTime.tryParse(sinZona);
  }

  static String? _tipoDe(_Entidad e, String campo) =>
      e.campos.firstWhere((f) => f['name'] == campo, orElse: () => const {})['type'] as String?;

  static String _corto(String s) => s.length > 90 ? '${s.substring(0, 90)}...' : s.replaceAll('\n', ' ');

  Future<void> _entidadCompleta(_Entidad e) async {
    final n = e.nombre;
    final filas = <_Fila>[];

    // ---- POST
    await _guarda(n, 'POST 201 + Location', () async {
      final cuerpo = await _cuerpo(e);
      if (cuerpo == null) {
        _omite(n, 'POST 201 + Location', 'no se pudo armar un cuerpo valido desde el manifiesto');
        return;
      }
      final r = await _pedir('POST', e.ruta, cuerpo: cuerpo);
      final o = r.objeto;
      if (o != null && o['id'] != null) _creados.add((e.ruta, '${o['id']}'));
      if (r.estado != 201) {
        _mal(n, 'POST 201 + Location', 'POST ${e.ruta} -> ${r.estado} ${_corto(r.cuerpo)}');
      } else {
        final loc = r.cabeceras['location'];
        loc != null && o != null && loc.endsWith('${o['id']}')
            ? _ok(n, 'POST 201 + Location', loc)
            : _mal(n, 'POST 201 + Location', 'responde 201 pero Location=${loc ?? 'ausente'}');
      }
      if (o != null && o['id'] != null) filas.add(_Fila('${o['id']}', cuerpo, o));
    });
    for (var i = 0; i < 2; i++) {
      final f = await _crear(e);
      if (f != null) filas.add(f);
    }
    if (filas.isEmpty) {
      _omite(n, '(resto)', 'no se pudo crear ninguna fila: no se prueba lo demas');
      return;
    }
    final a = filas.first;

    // ---- GET por id
    await _guarda(n, 'GET id 200', () async {
      final r = await _pedir('GET', '${e.ruta}/${a.id}');
      r.estado == 200 && r.objeto?['id'] == a.id
          ? _ok(n, 'GET id 200')
          : _mal(n, 'GET id 200', 'GET ${e.ruta}/${a.id} -> ${r.estado}');
    });
    await _guarda(n, 'GET id 404 + forma de error', () async {
      final r = await _pedir('GET', '${e.ruta}/00000000-0000-4000-8000-00000000dead');
      if (r.estado != 404) {
        _mal(n, 'GET id 404 + forma de error', 'esperaba 404, dio ${r.estado}');
      } else if (!_formaDeError(r)) {
        _mal(n, 'GET id 404 + forma de error', '404 sin {"error": "..."}: ${_corto(r.cuerpo)}');
      } else {
        _ok(n, 'GET id 404 + forma de error');
      }
    });

    // ---- lista
    List<dynamic>? lista;
    await _guarda(n, 'lista sin page = todo', () async {
      final r = await _pedir('GET', e.ruta);
      if (r.estado != 200 || r.json is! List) {
        _mal(n, 'lista sin page = todo', 'GET ${e.ruta} -> ${r.estado}, ${r.json is List ? 'arreglo' : 'no es un arreglo'}');
        return;
      }
      lista = r.json as List;
      final ids = {for (final x in lista!) if (x is Map) '${x['id']}'};
      final faltan = [for (final f in filas) if (!ids.contains(f.id)) f.id];
      faltan.isEmpty
          ? _ok(n, 'lista sin page = todo', '${lista!.length} filas')
          : _mal(n, 'lista sin page = todo', 'faltan ${faltan.length} de las filas creadas');
      final total = r.cabeceras['x-total-count'];
      if (total == null) {
        _mal(n, 'X-Total-Count', 'cabecera ausente');
      } else if (int.tryParse(total) != lista!.length) {
        _mal(n, 'X-Total-Count', 'dice $total y el arreglo trae ${lista!.length}');
      } else {
        _ok(n, 'X-Total-Count', total);
      }
    });

    await _guarda(n, 'page/size', () async {
      final p0 = await _pedir('GET', e.ruta, query: {'page': '0', 'size': '1', 'sort': 'id,asc'});
      final p1 = await _pedir('GET', e.ruta, query: {'page': '1', 'size': '1', 'sort': 'id,asc'});
      if (p0.estado != 200 || p0.json is! List) {
        _mal(n, 'page/size', '?page=0&size=1 -> ${p0.estado}');
        return;
      }
      final l0 = p0.json as List;
      final l1 = p1.json is List ? p1.json as List : const [];
      final total = int.tryParse(p0.cabeceras['x-total-count'] ?? '');
      if (l0.length != 1) {
        _mal(n, 'page/size', 'size=1 devolvio ${l0.length} filas');
      } else if (l1.length != 1 || (l0.first as Map)['id'] == (l1.first as Map)['id']) {
        _mal(n, 'page/size', 'page=1 no trae otra fila distinta de page=0');
      } else if (total == null || total < filas.length) {
        _mal(n, 'page/size', 'X-Total-Count de una pagina debe ser el total (dio ${p0.cabeceras['x-total-count']})');
      } else {
        _ok(n, 'page/size', 'total $total');
      }
    });

    await _guarda(n, 'sort=campo,asc|desc', () async {
      final campo = [
        for (final f in e.campos)
          if (f['type'] == 'string' || f['type'] == 'integer' || f['type'] == 'long') f['name'] as String,
      ];
      if (campo.isEmpty) {
        _omite(n, 'sort=campo,asc|desc', 'sin campo string/entero para ordenar');
        return;
      }
      final c = campo.first;
      Future<List<dynamic>> orden(String d) async {
        final r = await _pedir('GET', e.ruta, query: {'page': '0', 'size': '100', 'sort': '$c,$d'});
        if (r.estado != 200 || r.json is! List) throw 'sort=$c,$d -> ${r.estado}';
        return [for (final x in (r.json as List)) (x as Map)[c]];
      }

      final asc = await orden('asc');
      final desc = await orden('desc');
      // La base puede ordenar texto distinguiendo mayusculas o no: se acepta cualquiera de las dos.
      int cmpSensible(dynamic x, dynamic y) => x is num && y is num ? x.compareTo(y) : '$x'.compareTo('$y');
      int cmpIgnora(dynamic x, dynamic y) =>
          x is num && y is num ? x.compareTo(y) : '$x'.toLowerCase().compareTo('$y'.toLowerCase());
      bool ordenado(List<dynamic> l, int Function(dynamic, dynamic) cmp, bool desc) {
        for (var i = 1; i < l.length; i++) {
          final r = cmp(l[i - 1], l[i]);
          if (desc ? r < 0 : r > 0) return false;
        }
        return true;
      }

      bool ok(List<dynamic> l, bool desc) => ordenado(l, cmpSensible, desc) || ordenado(l, cmpIgnora, desc);
      ok(asc, false) && ok(desc, true) && asc.length == desc.length && '$asc' != '$desc'
          ? _ok(n, 'sort=campo,asc|desc', 'por $c')
          : _mal(n, 'sort=campo,asc|desc', 'el orden por $c no es el pedido (asc: $asc, desc: $desc)');
    });

    // ---- PUT / PATCH
    // Campo para probar cambios: un string si hay, si no cualquier escalar (numero, fecha, booleano).
    const escalares = ['string', 'integer', 'long', 'decimal', 'boolean', 'date', 'datetime'];
    final candidatos = [for (final f in e.campos) if (escalares.contains(f['type'])) f];
    candidatos.sort((x, y) => (x['type'] == 'string' ? 0 : 1).compareTo(y['type'] == 'string' ? 0 : 1));
    final campoTexto = [for (final f in candidatos) f['name'] as String];
    await _guarda(n, 'PUT 200 (reemplazo)', () async {
      final nuevo = {...a.enviado};
      if (campoTexto.isNotEmpty) nuevo[campoTexto.first] = _valor(e.campos.firstWhere((f) => f['name'] == campoTexto.first), ++_n);
      final r = await _pedir('PUT', '${e.ruta}/${a.id}', cuerpo: nuevo);
      if (r.estado != 200) {
        _mal(n, 'PUT 200 (reemplazo)', 'PUT ${e.ruta}/${a.id} -> ${r.estado} ${_corto(r.cuerpo)}');
      } else if (campoTexto.isNotEmpty &&
          !valoresIguales(_tipoDe(e, campoTexto.first), r.objeto?[campoTexto.first], nuevo[campoTexto.first])) {
        _mal(n, 'PUT 200 (reemplazo)', 'el campo ${campoTexto.first} no cambio');
      } else {
        _ok(n, 'PUT 200 (reemplazo)');
      }
    });
    await _guarda(n, 'PUT 404', () async {
      final r = await _pedir('PUT', '${e.ruta}/00000000-0000-4000-8000-00000000dead', cuerpo: a.enviado);
      r.estado == 404 ? _ok(n, 'PUT 404') : _mal(n, 'PUT 404', 'esperaba 404, dio ${r.estado}');
    });

    await _guarda(n, 'PATCH parcial 200', () async {
      if (campoTexto.isEmpty) {
        _omite(n, 'PATCH parcial 200', 'sin campo escalar para cambiar');
        return;
      }
      final antes = (await _pedir('GET', '${e.ruta}/${a.id}')).objeto ?? {};
      final c = campoTexto.first;
      final valor = _valor(e.campos.firstWhere((f) => f['name'] == c), ++_n);
      final r = await _pedir('PATCH', '${e.ruta}/${a.id}', cuerpo: {c: valor});
      if (r.estado != 200) {
        _mal(n, 'PATCH parcial 200', 'PATCH ${e.ruta}/${a.id} -> ${r.estado} ${_corto(r.cuerpo)}');
        return;
      }
      final despues = (await _pedir('GET', '${e.ruta}/${a.id}')).objeto ?? {};
      final tocados = [
        for (final k in antes.keys)
          if (k != c && k != 'updatedAt' && k != 'version' && '${antes[k]}' != '${despues[k]}') k,
      ];
      if (!valoresIguales(_tipoDe(e, c), despues[c], valor)) {
        _mal(n, 'PATCH parcial 200', 'el campo $c no cambio');
      } else if (tocados.isNotEmpty) {
        _mal(n, 'PATCH parcial 200', 'PATCH de $c tambien cambio: $tocados');
      } else {
        _ok(n, 'PATCH parcial 200', 'solo $c');
      }
    });
    await _guarda(n, 'PATCH 404', () async {
      final r = await _pedir('PATCH', '${e.ruta}/00000000-0000-4000-8000-00000000dead', cuerpo: {'x': 1});
      r.estado == 404 ? _ok(n, 'PATCH 404') : _mal(n, 'PATCH 404', 'esperaba 404, dio ${r.estado}');
    });

    await _guarda(n, 'version vieja = 409', () async {
      final actual = (await _pedir('GET', '${e.ruta}/${a.id}')).objeto ?? {};
      if (!actual.containsKey('version')) {
        _mal(n, 'version vieja = 409', 'la respuesta no trae el campo "version": no hay bloqueo optimista');
        return;
      }
      if (campoTexto.isEmpty) {
        _omite(n, 'version vieja = 409', 'sin campo escalar para cambiar');
        return;
      }
      final c = campoTexto.first;
      final vieja = actual['version'];
      final campo = e.campos.firstWhere((f) => f['name'] == c);
      final u1 = await _pedir('PATCH', '${e.ruta}/${a.id}', cuerpo: {c: _valor(campo, ++_n), 'version': vieja});
      if (u1.estado != 200) {
        _mal(n, 'version vieja = 409', 'PATCH con la version vigente dio ${u1.estado}');
        return;
      }
      final u2 = await _pedir('PATCH', '${e.ruta}/${a.id}', cuerpo: {c: _valor(campo, ++_n), 'version': vieja});
      u2.estado == 409
          ? _ok(n, 'version vieja = 409')
          : _mal(n, 'version vieja = 409', 'PATCH con version vieja dio ${u2.estado}, esperaba 409');
    });

    // ---- validacion
    await _guarda(n, 'POST invalido = 400 + forma', () async {
      final tieneObligatorio = e.campos.any((f) => f['required'] == true && f['type'] != 'reference');
      if (!tieneObligatorio) {
        _omite(n, 'POST invalido = 400 + forma', 'sin campos obligatorios que violar');
        return;
      }
      final r = await _pedir('POST', e.ruta, cuerpo: <String, dynamic>{});
      if (r.estado != 400) {
        _mal(n, 'POST invalido = 400 + forma', 'POST {} -> ${r.estado}, esperaba 400');
      } else if (!_formaDeError(r) || r.objeto?['errores'] is! Map) {
        _mal(n, 'POST invalido = 400 + forma', 'falta {"error","errores":{campo:...}}: ${_corto(r.cuerpo)}');
      } else {
        _ok(n, 'POST invalido = 400 + forma');
      }
    });

    // ---- DELETE
    final borrable = filas.length > 1 ? filas.last : null;
    await _guarda(n, 'DELETE 204', () async {
      if (borrable == null) {
        _omite(n, 'DELETE 204', 'no hay una fila de sobra');
        return;
      }
      final r = await _pedir('DELETE', '${e.ruta}/${borrable.id}');
      final g = await _pedir('GET', '${e.ruta}/${borrable.id}');
      r.estado == 204 && g.estado == 404
          ? _ok(n, 'DELETE 204')
          : _mal(n, 'DELETE 204', 'DELETE -> ${r.estado} (esperaba 204), GET despues -> ${g.estado} (esperaba 404)');
    });
    await _guarda(n, 'DELETE 404', () async {
      final r = await _pedir('DELETE', '${e.ruta}/00000000-0000-4000-8000-00000000dead');
      r.estado == 404 ? _ok(n, 'DELETE 404') : _mal(n, 'DELETE 404', 'esperaba 404, dio ${r.estado}');
    });

    // ---- DELETE de algo referenciado: se prueba sobre la entidad APUNTADA
    for (final otra in _entidades) {
      if (!otra.creable) continue;
      final apunta = [
        for (final f in otra.campos)
          if (f['type'] == 'reference' && f['required'] == true && f['target'] == n) f,
      ];
      if (apunta.isEmpty || otra.nombre == n) continue;
      await _guarda(n, 'DELETE referenciado = 409 (por ${otra.nombre})', () async {
        final punto = 'DELETE referenciado = 409 (por ${otra.nombre})';
        final hija = await _crear(otra); // crea su propia fila de ${n}
        final id = hija == null ? null : '${hija.enviado[apunta.first['name']]}';
        if (id == null) {
          _omite(n, punto, 'no se pudo crear una fila de ${otra.nombre} que lo referencie');
          return;
        }
        final r = await _pedir('DELETE', '${e.ruta}/$id');
        final msg = r.objeto?['error'];
        if (r.estado == 204 && (await _pedir('GET', '${otra.ruta}/${hija!.id}')).estado == 404) {
          // Composicion: la parte vive y muere con el todo (ADR-034/035), el borrado en cascada es lo correcto.
          // El 409 tambien lo es: en MongoDB un todo con partes en otra coleccion se rechaza hasta que se
          // borren las partes (ADR-035, apendice D-02). Lo unico incorrecto es un 204 que deje partes colgando.
          _ok(n, punto, 'borrado en cascada de ${otra.nombre} (composicion)');
          _creados.removeWhere((c) => c.$2 == hija.id);
        } else if (r.estado != 409) {
          _mal(n, punto, 'DELETE ${e.ruta}/$id con ${otra.nombre} apuntandole -> ${r.estado}, esperaba 409');
        } else if (msg is! String || msg.isEmpty) {
          _mal(n, punto, '409 sin mensaje legible en "error": ${_corto(r.cuerpo)}');
        } else {
          _ok(n, punto, _corto(msg));
        }
      });
    }
  }
}

// -------------------------------------------------------------------- tabla

String tabla(List<Punto> puntos) {
  final b = StringBuffer();
  final anchoEnt = puntos.fold<int>(8, (m, p) => max(m, p.entidad.length));
  final anchoPunto = puntos.fold<int>(5, (m, p) => max(m, p.punto.length));
  String etiqueta(Resultado r) => switch (r) {
        Resultado.pasa => 'PASA ',
        Resultado.falla => 'FALLA',
        Resultado.omite => 'OMITE',
      };
  for (final p in puntos) {
    b.writeln('${p.entidad.padRight(anchoEnt)}  ${p.punto.padRight(anchoPunto)}  ${etiqueta(p.resultado)}'
        '${p.detalle.isEmpty ? '' : '  ${p.detalle}'}');
  }
  final pasa = puntos.where((p) => p.resultado == Resultado.pasa).length;
  final falla = puntos.where((p) => p.resultado == Resultado.falla).length;
  final omite = puntos.where((p) => p.resultado == Resultado.omite).length;
  b.writeln('');
  b.write('TOTAL: $pasa PASA, $falla FALLA, $omite OMITE');
  return b.toString();
}

Future<void> main(List<String> args) async {
  final banderas = args.where((a) => a.startsWith('--')).toList();
  final pos = args.where((a) => !a.startsWith('--')).toList();
  if (pos.isEmpty) {
    stderr.writeln('uso: dart run tool/verificar_contrato_crud.dart <urlBackend> [app-manifest.json] [--solo=A,B]');
    exit(2);
  }
  final soloTexto = banderas.where((b) => b.startsWith('--solo=')).map((b) => b.substring(7)).firstOrNull;
  final base = pos[0].startsWith('http') ? pos[0] : 'http://${pos[0]}';
  try {
    final String texto;
    if (pos.length > 1) {
      texto = File(pos[1]).readAsStringSync();
    } else {
      final r = await http.get(Uri.parse('${base.replaceAll(RegExp(r'/+$'), '')}/app-manifest.json'));
      if (r.statusCode != 200) throw 'GET /app-manifest.json -> ${r.statusCode}';
      texto = utf8.decode(r.bodyBytes);
    }
    final v = VerificadorCrud(base, jsonDecode(texto) as Map<String, dynamic>,
        solo: soloTexto?.split(',').toSet());
    final puntos = await v.verificar();
    print(tabla(puntos));
    exit(puntos.any((p) => p.resultado == Resultado.falla) ? 1 : 0);
  } on Object catch (e) {
    stderr.writeln('No se pudo verificar: $e');
    exit(2);
  }
}
