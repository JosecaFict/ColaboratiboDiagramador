// ============================================================================
// MOTOR DE INTENCIONES LOCAL - Nivel A  (ADR-012, diseno en ADR-021)
//
// Determinista, sin red, sin modelo neuronal: gramatica + coincidencia difusa
// sobre el manifiesto del dominio. Es la arquitectura de Alexa: intencion +
// slots. El SLM on-device (Nivel B) queda fuera del parcial.
//
// Resuelve "crear libro con título X" contra un dominio que nunca vio: la
// entidad, el campo y el valor salen SOLO del manifiesto.
//
// Tres reglas de conducta:
//   1. Un empate se PREGUNTA, nunca se elige (entidad, campo o literal).
//   2. Un valor que no encaja con su tipo, rango o enumeracion se RECHAZA con
//      motivo antes de pegarle a P2.
//   3. Un registro se nombra por lo que se ve de el ("el libro Rayuela"): el
//      motor no lo busca, devuelve el texto; la app lo busca en P2 y, si hay
//      varios, pregunta cual.
//
// Alcance (T-19): CREAR, LISTAR, VER, ACTUALIZAR y BORRAR. Sin cola offline.
// ============================================================================

import 'app_manifest.dart';
import 'lexico.dart';
import 'valores.dart';

enum Accion { crear, listar, ver, actualizar, borrar }

// ---------------------------------------------------------------- RESULTADOS

sealed class Interpretacion {
  final String texto;
  Interpretacion(this.texto);
}

/// Lista para ejecutar contra P2.
class Resuelta extends Interpretacion {
  final Accion accion;
  final EntityDescriptor entidad;

  /// Cuerpo del POST (vacio al listar). No incluye las referencias por resolver.
  final Map<String, Object> cuerpo;

  /// Referencias dichas por nombre ("el socio Ana"): la app las busca en P2 y
  /// agrega su id al cuerpo con la clave `campo.name`.
  final List<ReferenciaPendiente> referencias;

  /// Campos unicos que se van a enviar. P2 rechaza el duplicado igual (409);
  /// la app puede verificarlo antes para explicarlo mejor.
  final List<FieldDescriptor> unicos;

  /// VER, ACTUALIZAR y BORRAR: como se nombro el registro ("Rayuela"). La app
  /// lo busca en P2. Al actualizar, `cuerpo` y `referencias` son los cambios.
  final String? busqueda;

  Resuelta(super.texto, this.accion, this.entidad,
      {this.cuerpo = const {}, this.referencias = const [], this.unicos = const [], this.busqueda});

  String get metodo => switch (accion) {
        Accion.crear => 'POST',
        Accion.listar || Accion.ver => 'GET',
        Accion.actualizar => 'PATCH',
        Accion.borrar => 'DELETE',
      };
  String get ruta => entidad.path;
}

class ReferenciaPendiente {
  final FieldDescriptor campo;
  final String entidadDestino;
  final String texto;
  ReferenciaPendiente(this.campo, this.entidadDestino, this.texto);
}

/// Se entendio que crear, pero faltan datos obligatorios (o se nombro un
/// campo sin decir su valor).
class Incompleta extends Interpretacion {
  final EntityDescriptor entidad;
  final Map<String, Object> cuerpo;
  final List<ReferenciaPendiente> referencias;
  final List<FieldDescriptor> faltantes;
  final String pregunta;
  Incompleta(super.texto, this.entidad, this.cuerpo, this.referencias, this.faltantes, this.pregunta);
}

class Opcion {
  /// Lo que se devuelve en `aclaraciones[clave]`.
  final String valor;
  final String etiqueta;
  Opcion(this.valor, this.etiqueta);
  @override
  String toString() => valor;
}

/// Hay empate: se pregunta. Para seguir, volver a interpretar el MISMO texto
/// con `aclaraciones: {clave: opcion.valor}`.
class Ambigua extends Interpretacion {
  final String clave;
  final String pregunta;
  final List<Opcion> opciones;
  Ambigua(super.texto, this.clave, this.pregunta, this.opciones);
}

class Rechazo {
  final FieldDescriptor? campo;
  final String? valor;
  final String motivo;
  Rechazo(this.motivo, {this.campo, this.valor});
  @override
  String toString() => motivo;
}

/// Se entendio, pero no se puede hacer: valor invalido, entidad no creable.
class Rechazada extends Interpretacion {
  final EntityDescriptor? entidad;
  final List<Rechazo> motivos;
  Rechazada(super.texto, this.entidad, this.motivos);
  String get mensaje => motivos.map((m) => m.motivo).join('. ');
}

/// Se entendio el pedido, pero esta version no lo hace (o la API no lo permite).
class FueraDeAlcance extends Interpretacion {
  final String motivo;
  FueraDeAlcance(super.texto, this.motivo);
}

class NoEntendida extends Interpretacion {
  final String motivo;
  NoEntendida(super.texto, this.motivo);
}

// ------------------------------------------------------------------ GRAMATICA
//
//   enunciado := cortesia* verbo determinante* ENTIDAD resto
//   resto     := (lista)                          -> se ignora al listar
//              | mencion* ; mencion := [delim] articulo* CAMPO introductor* VALOR
//
// Un campo solo se reconoce DESPUES de un delimitador (o pegado a la entidad),
// asi "título El nombre del viento" no parte el valor en "nombre". Los
// delimitadores debiles (de, del, al, para...) solo introducen referencias:
// "préstamo del libro Rayuela al socio Ana".

const Set<String> _cortesia = {
  'hola', 'che', 'bueno', 'ok', 'okay', 'porfa', 'por', 'favor', 'quiero', 'quisiera', 'necesito',
  'podes', 'puedes', 'podrias', 'me', 'te', 'que', 'vamos', 'a', 'ahora', 'asistente', 'y',
};

const Set<String> _verbosCrear = {
  'crear', 'crea', 'creas', 'cree', 'crees', 'creemos', 'agregar', 'agrega', 'agregue', 'agregues', 'aniadir', 'aniade',
  'registrar', 'registra', 'registras', 'registre', 'registres', 'cargar', 'carga', 'cargue', 'anotar', 'anota', 'anote',
  'ingresar', 'ingresa', 'insertar', 'inserta', 'guardar', 'guarda', 'agendar', 'agenda', 'nuevo', 'nueva',
  'alta', 'haceme', 'hazme', 'generar', 'genera',
};

const Set<String> _verbosListar = {
  'listar', 'lista', 'listado', 'mostrar', 'muestra', 'mostra', 'ver', 'veo', 'dame', 'decime', 'dime',
  'traer', 'trae', 'consultar', 'consulta', 'buscar', 'busca', 'cuales', 'ensenia', 'que', 'todos', 'todas',
};

const Set<String> _listarDebil = {'que', 'cuales', 'todos', 'todas'};

const Set<String> _verbosActualizar = {
  'actualizar', 'actualiza', 'actualice', 'modificar', 'modifica', 'modifique', 'cambiar', 'cambia', 'cambie',
  'editar', 'edita', 'corregir', 'corregi', 'corrige', 'renombrar', 'renombra', 'reprogramar', 'reprograma',
  'poner', 'pone', 'pon', 'ponele', 'ponle', 'fijar', 'fija',
};

const Set<String> _verbosBorrar = {
  'borrar', 'borra', 'eliminar', 'elimina', 'quitar', 'quita', 'sacar', 'saca',
  'anular', 'anula', 'remover', 'remove', 'suprimir', 'suprime',
};

const List<String> _cliticos = ['melo', 'mela', 'nos', 'les', 'los', 'las', 'me', 'le', 'lo', 'la'];

const Set<String> _determinantes = {
  'un', 'una', 'unos', 'unas', 'el', 'la', 'los', 'las', 'lo', 'todos', 'todas', 'mis', 'mi', 'sus', 'su',
  'nuevo', 'nueva', 'nuevos', 'nuevas', 'otro', 'otra', 'de', 'del', 'al', 'por', 'favor', 'que', 'son', 'hay',
  'cuales', 'me',
};

const Set<String> _delimitadoresFuertes = {'con', 'y', 'e', 'tambien'};
const Set<String> _delimitadoresDebiles = {'de', 'del', 'al', 'a', 'para', 'en', 'sobre'};
const Set<String> _articulos = {'el', 'la', 'los', 'las', 'su', 'sus', 'un', 'una', 'mi'};

/// Lo que puede ir entre el nombre del campo y su valor: "título ES X",
/// "título: X", "título igual a X", "título que sea X".
const Set<String> _introductores = {'es', 'sea', 'igual', 'que', 'vale', 'seria', 'será', 'sera'};

enum _Delim { ninguno, fuerte, debil }

class _Verbo {
  final Accion? accion;
  final String? fuera;
  final int siguiente;

  /// "qué", "cuáles", "todos": solo piden una lista si despues viene una
  /// entidad. "¿qué tal?" no es un pedido.
  final bool debil;
  _Verbo(this.accion, this.fuera, this.siguiente, {this.debil = false});
}

class _Mencion {
  /// Primer token del nombre del campo.
  final int desde;

  /// Primer token despues del nombre.
  final int hasta;
  final List<FieldDescriptor> candidatos;
  final String dicho;
  _Mencion(this.desde, this.hasta, this.candidatos, this.dicho);
}

class MotorDeIntenciones {
  final AppManifest manifiesto;
  final DateTime Function() reloj;

  MotorDeIntenciones(this.manifiesto, {DateTime Function()? reloj}) : reloj = reloj ?? DateTime.now;

  /// `aclaraciones`: respuestas a preguntas anteriores sobre el mismo texto,
  /// `{Ambigua.clave: Opcion.valor}`.
  Interpretacion interpretar(String texto, {Map<String, String> aclaraciones = const {}}) {
    final tokens = tokenizar(texto);
    final verbo = _verbo(tokens);
    if (verbo == null) {
      return NoEntendida(texto, 'No entendí qué querés hacer. Puedo crear o listar, por ejemplo '
          '«crear ${_ejemplo()} con …» o «listar ${_ejemplo()}».');
    }
    if (verbo.fuera != null) return FueraDeAlcance(texto, verbo.fuera!);
    final accion = verbo.accion!;
    final verboAccion = _permiso[accion]!;

    // ---- entidad
    var p = verbo.siguiente;
    while (p < tokens.length && !tokens[p].signo && _determinantes.contains(tokens[p].norma)) {
      p++;
    }
    final sobreRegistro = accion == Accion.actualizar || accion == Accion.borrar;
    var encontradas = _mejores<EntityDescriptor>(tokens, p,
        manifiesto.entities.where((e) => sobreRegistro || e.permite(verboAccion) || e.isAbstract), (e) => e.synonyms);

    // "cambiá EL PRECIO DEL libro Rayuela a 50": el campo va antes de la entidad.
    if (accion == Accion.actualizar && encontradas.candidatos.isEmpty) {
      final r = _campoAntesDeEntidad(texto, tokens, p, aclaraciones);
      if (r != null) return r;
    }

    EntityDescriptor? entidad;
    var inicioResto = p;
    if (encontradas.candidatos.isNotEmpty) inicioResto = encontradas.hasta;

    final elegida = aclaraciones['entidad'];
    final elegibles = encontradas.candidatos.isNotEmpty
        ? encontradas.candidatos
        : manifiesto.entities.where((e) => e.permite(verboAccion) && !e.isAbstract).toList();
    if (elegida != null) {
      entidad = manifiesto.entidad(elegida);
      if (entidad == null) return NoEntendida(texto, 'No existe la entidad $elegida');
    } else if (encontradas.candidatos.length == 1) {
      entidad = encontradas.candidatos.single;
    } else if (encontradas.candidatos.length > 1) {
      final dicho = _dicho(tokens, p, encontradas.hasta);
      return Ambigua(texto, 'entidad',
          '«$dicho» puede ser ${enumerar(elegibles.map((e) => e.nombreHablado).toList())}: ¿cuál?',
          elegibles.map((e) => Opcion(e.name, e.nombreHablado)).toList());
    } else if (verbo.debil) {
      return NoEntendida(texto, 'No entendí qué querés hacer. Puedo crear o listar, por ejemplo '
          '«listar ${_ejemplo()}».');
    } else {
      final verboDicho = _verboDicho[accion]!;
      return Ambigua(texto, 'entidad',
          '¿Qué querés $verboDicho? Puedo: ${enumerar(elegibles.map((e) => e.nombreHablado).toList())}.',
          elegibles.map((e) => Opcion(e.name, e.nombreHablado)).toList());
    }

    if (accion == Accion.listar) {
      // "mostrame los libros" lista; "mostrame el libro Rayuela" muestra ese.
      final busqueda = _nombreDelRegistro(texto, tokens, inicioResto, tokens.length);
      return busqueda == null
          ? Resuelta(texto, Accion.listar, entidad)
          : Resuelta(texto, Accion.ver, entidad, busqueda: busqueda);
    }

    if (sobreRegistro) {
      if (!entidad.permite(verboAccion)) {
        return Rechazada(texto, entidad,
            [Rechazo('La API no permite ${_verboDicho[accion]} ${entidad.nombreHablado}')]);
      }
      if (accion == Accion.borrar) {
        final busqueda = _nombreDelRegistro(texto, tokens, inicioResto, tokens.length);
        if (busqueda == null) {
          return NoEntendida(texto, '¿Qué ${entidad.nombreHablado} querés borrar? Decime cuál, por ejemplo '
              '«borrar ${entidad.nombreHablado} …».');
        }
        return Resuelta(texto, Accion.borrar, entidad, busqueda: busqueda);
      }
      return _actualizar(texto, tokens, inicioResto, entidad, aclaraciones);
    }

    // ---- una entidad abstracta no se crea: se pregunta cual de sus hijas
    if (entidad.isAbstract || !entidad.permite('create')) {
      final hijas = manifiesto.concretasBajo(entidad);
      if (hijas.isEmpty) {
        return Rechazada(texto, entidad, [Rechazo('${entidad.nombreHablado} no se puede crear')]);
      }
      return Ambigua(texto, 'entidad',
          '${entidad.nombreHablado} es general: ¿qué querés crear, '
              '${enumerar(hijas.map((e) => e.nombreHablado).toList())}?',
          hijas.map((e) => Opcion(e.name, e.nombreHablado)).toList());
    }

    return _crear(texto, tokens, inicioResto, entidad, aclaraciones);
  }

  // ------------------------------------------------------------------ CREAR

  /// `parcial`: al actualizar se manda solo lo que cambia, asi que no faltan
  /// obligatorios.
  Interpretacion _crear(String texto, List<Token> tokens, int inicio, EntityDescriptor entidad,
      Map<String, String> aclaraciones, {bool parcial = false}) {
    final menciones = _menciones(tokens, inicio, entidad);

    // Empate entre campos: se pregunta, salvo que ya se haya contestado.
    final asignadas = <(FieldDescriptor, _Mencion)>[];
    for (final m in menciones) {
      final clave = 'campo:${normalizar(m.dicho)}';
      final elegido = aclaraciones[clave];
      if (elegido != null) {
        final campo = m.candidatos.where((c) => c.name == elegido).firstOrNull ??
            entidad.fields.where((c) => c.name == elegido).firstOrNull;
        if (campo == null) return NoEntendida(texto, '${entidad.nombreHablado} no tiene el campo $elegido');
        asignadas.add((campo, m));
      } else if (m.candidatos.length > 1) {
        return Ambigua(texto, clave,
            '«${m.dicho}» puede ser ${enumerar(m.candidatos.map((c) => c.nombreHablado).toList())}: ¿cuál?',
            m.candidatos.map((c) => Opcion(c.name, c.nombreHablado)).toList());
      } else {
        asignadas.add((m.candidatos.single, m));
      }
    }

    // Texto suelto antes del primer campo ("crear libro Rayuela"): no se
    // adivina a que campo va; se pregunta entre los que lo aceptarian.
    final finSuelto = menciones.isEmpty ? tokens.length : _inicioConDelimitador(tokens, menciones.first.desde);
    final suelto = _recortar(tokens, inicio, finSuelto, null);
    if (suelto != null) {
      final dicho = _dicho(tokens, suelto.$1, suelto.$2);
      final clave = 'campo:${normalizar(dicho)}';
      final elegido = aclaraciones[clave];
      if (elegido != null) {
        final campo = entidad.fields.where((c) => c.name == elegido).firstOrNull;
        if (campo == null) return NoEntendida(texto, '${entidad.nombreHablado} no tiene el campo $elegido');
        asignadas.insert(0, (campo, _Mencion(suelto.$1, suelto.$1, [campo], dicho)));
      } else {
        final hoy = reloj();
        final yaDichos = asignadas.map((a) => a.$1.name).toSet();
        final aceptan = entidad.fields
            .where((f) => !yaDichos.contains(f.name))
            .where((f) => switch (convertir(f, dicho, hoy)) { Invalido() => false, _ => true })
            .toList();
        if (aceptan.isEmpty) {
          return Rechazada(texto, entidad,
              [Rechazo('No sé a qué dato de ${entidad.nombreHablado} corresponde «$dicho»', valor: dicho)]);
        }
        return Ambigua(texto, clave,
            '¿«$dicho» es ${enumerar(aceptan.map((c) => c.nombreHablado).toList())}?',
            aceptan.map((c) => Opcion(c.name, c.nombreHablado)).toList());
      }
    }

    // Valores: de donde termina el nombre del campo hasta el delimitador de la mencion siguiente.
    final valores = <FieldDescriptor, String?>{};
    final rechazos = <Rechazo>[];
    for (var k = 0; k < asignadas.length; k++) {
      final (campo, m) = asignadas[k];
      if (valores.containsKey(campo)) {
        rechazos.add(Rechazo('Dijiste ${campo.nombreHablado} dos veces', campo: campo));
        continue;
      }
      // El texto suelto aclarado es su propio valor.
      if (m.desde == m.hasta) {
        valores[campo] = m.dicho;
        continue;
      }
      final fin = _finDeValor(tokens, asignadas, k, menciones);
      final rango = _recortar(tokens, m.hasta, fin, campo);
      valores[campo] = rango == null ? null : _valorDe(texto, tokens, rango.$1, rango.$2);
    }
    if (rechazos.isNotEmpty) return Rechazada(texto, entidad, rechazos);

    final hoy = reloj();
    final cuerpo = <String, Object>{};
    final referencias = <ReferenciaPendiente>[];
    final sinValor = <FieldDescriptor>[];
    for (final MapEntry(key: campo, value: valor) in valores.entries) {
      if (valor == null) {
        sinValor.add(campo);
        continue;
      }
      switch (convertir(campo, valor, hoy)) {
        case Convertido(:final json):
          cuerpo[campo.name] = json;
        case PorResolver(:final texto):
          referencias.add(ReferenciaPendiente(campo, campo.target!, texto));
        case Invalido(:final motivo):
          rechazos.add(Rechazo(motivo, campo: campo, valor: valor));
        case LiteralAmbiguo(:final literales):
          final clave = 'valor:${campo.name}';
          final elegido = aclaraciones[clave];
          if (elegido != null && literales.contains(elegido)) {
            cuerpo[campo.name] = elegido;
          } else {
            return Ambigua(texto, clave,
                '«$valor» puede ser ${enumerar(literales.map(literalHablado).toList())}: ¿cuál?',
                literales.map((l) => Opcion(l, literalHablado(l))).toList());
          }
      }
    }
    if (rechazos.isNotEmpty) return Rechazada(texto, entidad, rechazos);
    // Un cambio pedido sin valor no se descarta: mandar el resto seria hacer
    // menos de lo que se pidio sin avisar.
    if (parcial && sinValor.isNotEmpty) {
      final cuales = enumerar(sinValor.map((f) => f.nombreHablado).toList(), conjuncion: 'y');
      return NoEntendida(texto, '¿A qué valor cambio $cuales? No cambié nada: decilo de nuevo con el valor.');
    }
    if (parcial) {
      return Resuelta(texto, Accion.actualizar, entidad,
          cuerpo: cuerpo, referencias: referencias, unicos: valores.keys.where((f) => f.unique).toList());
    }

    final faltantes = [
      ...sinValor,
      ...entidad.fields.where((f) => f.required && !valores.containsKey(f)),
    ];
    // Un obligatorio que no se puede dictar no se va a poder completar
    // preguntando: se dice ahora, no despues de tres preguntas.
    final imposibles = faltantes.where((f) =>
        f.type == FieldType.embedded || f.type == FieldType.list || f.type == FieldType.desconocido);
    if (imposibles.isNotEmpty) {
      return Rechazada(texto, entidad, [
        for (final f in imposibles)
          Rechazo('${entidad.nombreHablado} necesita ${f.nombreHablado}, que es un valor compuesto y no se carga '
              'por voz en esta versión', campo: f),
      ]);
    }
    if (faltantes.isNotEmpty) {
      return Incompleta(texto, entidad, cuerpo, referencias, faltantes,
          'Para crear ${entidad.nombreHablado} falta ${enumerar(faltantes.map((f) => f.nombreHablado).toList(), conjuncion: 'y')}.');
    }

    return Resuelta(texto, Accion.crear, entidad,
        cuerpo: cuerpo,
        referencias: referencias,
        unicos: valores.keys.where((f) => f.unique).toList());
  }

  // ------------------------------------------------------------- AUXILIARES

  _Verbo? _verbo(List<Token> tokens) {
    for (var i = 0; i < tokens.length; i++) {
      final t = tokens[i];
      if (t.signo) continue;
      final w = t.norma;
      final siguiente = i + 1 < tokens.length ? tokens[i + 1].norma : '';
      final despues = i + 2 < tokens.length ? tokens[i + 2].norma : '';

      // "dar de alta" / "dar de baja"
      if ((w == 'dar' || w == 'da' || w == 'de') && siguiente == 'de' && despues == 'alta') {
        return _Verbo(Accion.crear, null, i + 3);
      }
      if ((w == 'dar' || w == 'da' || w == 'de') && siguiente == 'de' && despues == 'baja') {
        return _Verbo(Accion.borrar, null, i + 3);
      }
      var forma = _sinClitico(w);
      // "quiero QUE crees un libro": ese "que" no pregunta nada.
      if (forma == 'que' && _sinClitico(siguiente) != null) forma = null;
      if (forma != null) {
        if (_verbosBorrar.contains(forma)) return _Verbo(Accion.borrar, null, i + 1);
        if (_verbosActualizar.contains(forma)) return _Verbo(Accion.actualizar, null, i + 1);
        if (_verbosCrear.contains(forma)) return _Verbo(Accion.crear, null, i + 1);
        if (_verbosListar.contains(forma)) {
          return _Verbo(Accion.listar, null, i + 1, debil: _listarDebil.contains(forma));
        }
      }
      if (!_cortesia.contains(w)) return null;
    }
    return null;
  }

  /// "mostrame" -> "mostra", "creame" -> "crea". Null si ni con ni sin clitico es un verbo.
  String? _sinClitico(String w) {
    bool esVerbo(String x) =>
        _verbosCrear.contains(x) || _verbosListar.contains(x) || _verbosActualizar.contains(x) ||
        _verbosBorrar.contains(x);
    if (esVerbo(w)) return w;
    for (final c in _cliticos) {
      if (w.length > c.length + 2 && w.endsWith(c)) {
        final base = w.substring(0, w.length - c.length);
        if (esVerbo(base)) return base;
      }
    }
    return null;
  }

  static const Map<Accion, String> _permiso = {
    Accion.crear: 'create',
    Accion.listar: 'list',
    Accion.ver: 'list',
    Accion.actualizar: 'update',
    Accion.borrar: 'delete',
  };

  static const Map<Accion, String> _verboDicho = {
    Accion.crear: 'crear',
    Accion.listar: 'listar',
    Accion.ver: 'ver',
    Accion.actualizar: 'cambiar',
    Accion.borrar: 'borrar',
  };

  /// Lo que puede ir entre la entidad y el nombre del registro: "el socio
  /// LLAMADO Ana", "el libro QUE SE LLAMA Rayuela", "los libros DE García".
  static const Set<String> _antesDelNombre = {
    'llamado', 'llamada', 'llamados', 'llamadas', 'se', 'llama', 'nombre', 'nombrado', 'nombrada',
  };

  /// El texto con el que se nombra un registro, con su ortografia original.
  /// Null si no se nombro ninguno ("mostrame los libros").
  String? _nombreDelRegistro(String texto, List<Token> tokens, int desde, int hasta) {
    var a = desde, b = hasta;
    // "llamado", "nombre"... solo son relleno si despues viene el nombre: en
    // "borrá el producto Nombre" el registro SE LLAMA "Nombre".
    while (a < b &&
        (tokens[a].signo ||
            (!tokens[a].citado &&
                (_determinantes.contains(tokens[a].norma) ||
                    _delimitadoresDebiles.contains(tokens[a].norma) ||
                    (_antesDelNombre.contains(tokens[a].norma) && a < b - 1))))) {
      a++;
    }
    while (b > a && (tokens[b - 1].signo || (!tokens[b - 1].citado && _esDelimitador(tokens[b - 1])))) {
      b--;
    }
    return a < b ? _valorDe(texto, tokens, a, b) : null;
  }

  // -------------------------------------------------------------- ACTUALIZAR

  /// "modificar socio Ana, email ana@x.com" / "actualizar el libro Rayuela con
  /// precio 50": el registro es lo que va entre la entidad y el primer campo;
  /// los cambios se leen igual que al crear, sin exigir obligatorios.
  Interpretacion _actualizar(String texto, List<Token> tokens, int inicio, EntityDescriptor entidad,
      Map<String, String> aclaraciones) {
    final menciones = _menciones(tokens, inicio, entidad);
    final primera = menciones.where((m) => m.desde > inicio).firstOrNull;
    final finNombre = primera == null ? tokens.length : _inicioConDelimitador(tokens, primera.desde);
    final busqueda = _nombreDelRegistro(texto, tokens, inicio, finNombre);
    if (busqueda == null) {
      return NoEntendida(texto, '¿Qué ${entidad.nombreHablado} querés cambiar? Decime cuál y qué, por ejemplo '
          '«cambiar ${entidad.nombreHablado} …, ${entidad.fields.first.nombreHablado} …».');
    }
    if (primera == null) {
      return NoEntendida(texto, '¿Qué querés cambiar de ${entidad.nombreHablado} «$busqueda»? Decime el dato y '
          'el valor nuevo, por ejemplo «…, ${entidad.fields.first.nombreHablado} …».');
    }
    final cambios = _crear(texto, tokens, finNombre, entidad, aclaraciones, parcial: true);
    if (cambios is! Resuelta) return cambios;
    if (cambios.cuerpo.isEmpty && cambios.referencias.isEmpty) {
      return NoEntendida(texto, 'No entendí qué cambiar de ${entidad.nombreHablado} «$busqueda».');
    }
    return Resuelta(texto, Accion.actualizar, entidad,
        cuerpo: cambios.cuerpo, referencias: cambios.referencias, unicos: cambios.unicos, busqueda: busqueda);
  }

  /// "cambiá el precio del libro Rayuela a 50": CAMPO de ENTIDAD REGISTRO a VALOR.
  /// Null si no tiene esa forma (y entonces se sigue como siempre).
  Interpretacion? _campoAntesDeEntidad(
      String texto, List<Token> tokens, int p, Map<String, String> aclaraciones) {
    for (var j = p + 1; j < tokens.length; j++) {
      if (_delimitadorAntes(tokens, j) != _Delim.debil) continue;
      final ents = _mejores<EntityDescriptor>(tokens, j, manifiesto.entities, (e) => e.synonyms);
      if (ents.candidatos.length != 1) continue;
      final entidad = ents.candidatos.single;
      final campos = _mejores<FieldDescriptor>(tokens, p, entidad.fields, (f) => f.synonyms);
      if (campos.candidatos.isEmpty || campos.hasta > _inicioConDelimitador(tokens, j)) continue;
      if (!entidad.permite('update')) {
        return Rechazada(texto, entidad, [Rechazo('La API no permite cambiar ${entidad.nombreHablado}')]);
      }
      FieldDescriptor campo;
      final clave = 'campo:${normalizar(_dicho(tokens, p, campos.hasta))}';
      if (campos.candidatos.length > 1 && aclaraciones[clave] == null) {
        return Ambigua(texto, clave,
            '«${_dicho(tokens, p, campos.hasta)}» puede ser '
                '${enumerar(campos.candidatos.map((c) => c.nombreHablado).toList())}: ¿cuál?',
            campos.candidatos.map((c) => Opcion(c.name, c.nombreHablado)).toList());
      }
      campo = campos.candidatos.firstWhere((c) => c.name == aclaraciones[clave],
          orElse: () => campos.candidatos.first);

      // "a" (o "por", "en") separa el registro del valor nuevo. Vale un corte
      // que deja nombre y un valor valido; "a" manda sobre "por" y "en", que
      // tambien aparecen dentro de un titulo. Si aun quedan varios, se pregunta.
      bool sirve(int k) {
        if (_nombreDelRegistro(texto, tokens, ents.hasta, k) == null) return false;
        final r = _recortar(tokens, k + 1, tokens.length, campo);
        return r != null && convertir(campo, _valorDe(texto, tokens, r.$1, r.$2), reloj()) is! Invalido;
      }

      final separadores = [
        for (var k = ents.hasta + 1; k < tokens.length; k++)
          if (!tokens[k].citado && const {'a', 'por', 'en'}.contains(tokens[k].norma)) k,
      ];
      var validos = separadores.where(sirve).toList();
      if (validos.any((k) => tokens[k].norma == 'a')) {
        validos = validos.where((k) => tokens[k].norma == 'a').toList();
      }
      var corte = validos.isNotEmpty ? validos.last : (separadores.isEmpty ? -1 : separadores.last);
      if (validos.length > 1) {
        final elegido = int.tryParse(aclaraciones['corte'] ?? '');
        if (elegido == null || !validos.contains(elegido)) {
          return Ambigua(texto, 'corte',
              'No me queda claro dónde termina el nombre de ${entidad.nombreHablado}: ¿cuál es?',
              [for (final k in validos) Opcion('$k', '«${_nombreDelRegistro(texto, tokens, ents.hasta, k)}»')]);
        }
        corte = elegido;
      }
      final busqueda = _nombreDelRegistro(texto, tokens, ents.hasta, corte < 0 ? tokens.length : corte);
      if (busqueda == null) {
        return NoEntendida(texto, '¿De qué ${entidad.nombreHablado} querés cambiar ${campo.nombreHablado}?');
      }
      final rango = corte < 0 ? null : _recortar(tokens, corte + 1, tokens.length, campo);
      if (rango == null) {
        return NoEntendida(texto, '¿A qué valor cambio ${campo.nombreHablado} de ${entidad.nombreHablado} '
            '«$busqueda»? Por ejemplo «cambiá ${campo.nombreHablado} de ${entidad.nombreHablado} $busqueda a …».');
      }
      final valor = _valorDe(texto, tokens, rango.$1, rango.$2);
      switch (convertir(campo, valor, reloj())) {
        case Convertido(:final json):
          return Resuelta(texto, Accion.actualizar, entidad,
              cuerpo: {campo.name: json}, unicos: [if (campo.unique) campo], busqueda: busqueda);
        case PorResolver(texto: final dicho):
          return Resuelta(texto, Accion.actualizar, entidad,
              referencias: [ReferenciaPendiente(campo, campo.target!, dicho)], busqueda: busqueda);
        case Invalido(:final motivo):
          return Rechazada(texto, entidad, [Rechazo(motivo, campo: campo, valor: valor)]);
        case LiteralAmbiguo(:final literales):
          final claveValor = 'valor:${campo.name}';
          final elegido = aclaraciones[claveValor];
          if (elegido != null && literales.contains(elegido)) {
            return Resuelta(texto, Accion.actualizar, entidad, cuerpo: {campo.name: elegido}, busqueda: busqueda);
          }
          return Ambigua(texto, claveValor,
              '«$valor» puede ser ${enumerar(literales.map(literalHablado).toList())}: ¿cuál?',
              literales.map((l) => Opcion(l, literalHablado(l))).toList());
      }
    }
    return null;
  }

  String _ejemplo() {
    final e = manifiesto.entities.where((e) => e.permite('create')).firstOrNull ?? manifiesto.entities.firstOrNull;
    return e?.nombreHablado ?? 'algo';
  }

  /// Los mejores candidatos que empiezan exactamente en `desde`. Varios si empatan.
  ({List<T> candidatos, int hasta}) _mejores<T>(
      List<Token> tokens, int desde, Iterable<T> universo, List<String> Function(T) nombres) {
    var mejor = 0.0;
    var hasta = desde;
    final ganadores = <T>[];
    for (final item in universo) {
      Aparicion? propia;
      for (final n in nombres(item)) {
        final a = buscarNombre(tokens, desde, n);
        if (a == null) continue;
        if (propia == null || a.puntaje > propia.puntaje + 1e-9 ||
            ((a.puntaje - propia.puntaje).abs() <= 1e-9 && a.hasta > propia.hasta)) {
          propia = a;
        }
      }
      if (propia == null) continue;
      if (propia.puntaje > mejor + 1e-9 || ((propia.puntaje - mejor).abs() <= 1e-9 && propia.hasta > hasta)) {
        mejor = propia.puntaje;
        hasta = propia.hasta;
        ganadores
          ..clear()
          ..add(item);
      } else if ((propia.puntaje - mejor).abs() <= 1e-9 && propia.hasta == hasta) {
        ganadores.add(item);
      }
    }
    return (candidatos: ganadores, hasta: hasta);
  }

  List<_Mencion> _menciones(List<Token> tokens, int inicio, EntityDescriptor entidad) {
    final menciones = <_Mencion>[];
    var i = inicio;
    while (i < tokens.length) {
      final delim = i == inicio ? _Delim.fuerte : _delimitadorAntes(tokens, i);
      if (delim != _Delim.ninguno && !tokens[i].signo && !_esDelimitador(tokens[i])) {
        var j = i;
        while (j < tokens.length && _articulos.contains(tokens[j].norma)) {
          j++;
        }
        final campos = entidad.fields.where((f) => delim == _Delim.fuerte || f.type == FieldType.reference);
        final r = _mejores<FieldDescriptor>(tokens, j, campos, (f) => f.synonyms);
        if (r.candidatos.isNotEmpty) {
          menciones.add(_Mencion(j, r.hasta, r.candidatos, _dicho(tokens, j, r.hasta)));
          i = r.hasta;
          continue;
        }
      }
      i++;
    }
    return menciones;
  }

  bool _esDelimitador(Token t) =>
      _delimitadoresFuertes.contains(t.norma) || _delimitadoresDebiles.contains(t.norma);

  /// Que delimita a `tokens[i]` por la izquierda, salteando articulos.
  _Delim _delimitadorAntes(List<Token> tokens, int i) {
    var k = i - 1;
    while (k >= 0 && _articulos.contains(tokens[k].norma)) {
      k--;
    }
    if (k < 0) return _Delim.fuerte;
    final t = tokens[k];
    if (t.signo && t.texto != ':' && t.texto != '=') return _Delim.fuerte;
    if (t.citado) return _Delim.ninguno;
    if (_delimitadoresFuertes.contains(t.norma)) return _Delim.fuerte;
    if (_delimitadoresDebiles.contains(t.norma)) return _Delim.debil;
    return _Delim.ninguno;
  }

  /// Donde empieza el delimitador (y los articulos) que introducen la mencion en `desde`.
  int _inicioConDelimitador(List<Token> tokens, int desde) {
    var k = desde;
    while (k - 1 >= 0 && _articulos.contains(tokens[k - 1].norma)) {
      k--;
    }
    if (k - 1 >= 0) {
      final t = tokens[k - 1];
      if ((t.signo && t.texto != ':' && t.texto != '=') || _esDelimitador(t)) k--;
    }
    return k;
  }

  int _finDeValor(List<Token> tokens, List<(FieldDescriptor, _Mencion)> asignadas, int k, List<_Mencion> menciones) {
    final desde = asignadas[k].$2.hasta;
    for (final m in menciones) {
      if (m.desde >= desde) return _inicioConDelimitador(tokens, m.desde);
    }
    return tokens.length;
  }

  /// Quita de los bordes lo que no es valor: signos, delimitadores y, al
  /// principio, introductores ("es", "igual a", ":"). Null si no queda nada.
  (int, int)? _recortar(List<Token> tokens, int desde, int hasta, FieldDescriptor? campo) {
    var a = desde, b = hasta;
    bool sobraAdelante(Token t) =>
        t.signo ||
        (!t.citado &&
            (_introductores.contains(t.norma) ||
                _delimitadoresFuertes.contains(t.norma) ||
                (campo != null && t.norma == 'a' && a > desde && tokens[a - 1].norma == 'igual') ||
                (campo != null && _numericoOFecha(campo) && (t.norma == 'de' || t.norma == 'del')) ||
                (campo != null && campo.type == FieldType.reference && _articulos.contains(t.norma))));
    while (a < b && sobraAdelante(tokens[a])) {
      a++;
    }
    while (b > a && (tokens[b - 1].signo || (!tokens[b - 1].citado && _esDelimitador(tokens[b - 1])))) {
      b--;
    }
    return a < b ? (a, b) : null;
  }

  /// "precio de 50", "fecha de hoy": ese "de" no es parte del valor. En un
  /// texto o un literal si puede serlo ("de baja").
  bool _numericoOFecha(FieldDescriptor f) => const {
        FieldType.integer, FieldType.long, FieldType.decimal, FieldType.boolean, FieldType.date, FieldType.datetime,
      }.contains(f.type);

  /// El valor con su ortografia original: se corta del texto, no se rearma.
  String _valorDe(String texto, List<Token> tokens, int a, int b) {
    if (b - a == 1 && tokens[a].citado) return tokens[a].texto;
    return texto.substring(tokens[a].inicio, tokens[b - 1].fin);
  }

  String _dicho(List<Token> tokens, int a, int b) =>
      tokens.sublist(a, b).where((t) => !t.signo).map((t) => t.texto).join(' ');

}
