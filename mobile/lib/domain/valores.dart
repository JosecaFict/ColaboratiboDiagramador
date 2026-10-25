// ============================================================================
// VALORES DICTADOS -> VALORES DE LA API  (ADR-021)
//
// Convierte lo que dijo la persona al tipo que declara el manifiesto, y lo
// RECHAZA con un motivo legible si no encaja: tipo, largo, rango o literal de
// una enumeracion. Todo antes de pegarle a P2, que igual valida (Regla 9):
// aca se valida para poder explicarlo en la conversacion, no para confiar.
// ============================================================================

import 'app_manifest.dart';
import 'lexico.dart';

sealed class Conversion {}

/// `json` va tal cual al cuerpo del POST.
class Convertido extends Conversion {
  final Object json;
  Convertido(this.json);
}

class Invalido extends Conversion {
  final String motivo;
  Invalido(this.motivo);
}

/// Mas de un literal de la enumeracion encaja igual de bien.
class LiteralAmbiguo extends Conversion {
  final List<String> literales;
  LiteralAmbiguo(this.literales);
}

/// Una referencia dicha por su nombre ("el socio Ana"). Para pasarla a id hay
/// que consultar P2, y eso no es trabajo del motor.
class PorResolver extends Conversion {
  final String texto;
  PorResolver(this.texto);
}

Conversion convertir(FieldDescriptor campo, String texto, DateTime hoy) {
  final nombre = campo.nombreHablado;
  switch (campo.type) {
    case FieldType.string:
      final largo = texto.runes.length;
      if (campo.maxLength != null && largo > campo.maxLength!) {
        return Invalido('$nombre admite hasta ${campo.maxLength} caracteres y «${_corto(texto)}» tiene $largo');
      }
      return Convertido(texto);

    case FieldType.integer:
    case FieldType.long:
      final n = leerEntero(texto);
      if (n == null) return Invalido('$nombre es un número entero y «$texto» no lo es');
      final limite = campo.type == FieldType.integer ? BigInt.from(2147483647) : BigInt.parse('9223372036854775807');
      if (n > limite || n < -limite - BigInt.one) {
        return Invalido('$nombre no admite un número tan grande: $n');
      }
      return _enRango(campo, n.toString()) ?? Convertido(n.toInt());

    case FieldType.decimal:
      final d = leerDecimal(texto);
      if (d == null) return Invalido('$nombre es un número y «$texto» no lo es');
      // Como texto: un double perderia centavos. Jackson lo acepta como BigDecimal.
      return _enRango(campo, d) ?? Convertido(d);

    case FieldType.boolean:
      final b = leerBooleano(texto);
      if (b == null) return Invalido('$nombre se contesta con sí o no, y «$texto» no es ninguno');
      return Convertido(b);

    case FieldType.date:
      final f = leerFecha(texto, hoy);
      return switch (f) {
        _Fecha(:final valor) => Convertido(_iso(valor)),
        _SinFecha(:final motivo) => Invalido('$nombre: $motivo'),
      };

    case FieldType.datetime:
      return _fechaHora(campo, texto, hoy);

    case FieldType.enumeration:
      return _literal(campo, texto);

    case FieldType.uuid:
      return _esUuid(texto) ? Convertido(texto.toLowerCase()) : Invalido('$nombre es un identificador y «$texto» no lo es');

    case FieldType.reference:
      return _esUuid(texto) ? Convertido(texto.toLowerCase()) : PorResolver(texto);

    case FieldType.embedded:
    case FieldType.list:
    case FieldType.desconocido:
      return Invalido('$nombre es un valor compuesto (${campo.typeName}) y no se carga por voz en esta versión');
  }
}

String _corto(String s) => s.length <= 30 ? s : '${s.substring(0, 27)}...';

bool _esUuid(String s) =>
    RegExp(r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$').hasMatch(s.trim());

// ------------------------------------------------------------------ NUMEROS

const Map<String, int> _numeros = {
  'cero': 0, 'uno': 1, 'una': 1, 'un': 1, 'dos': 2, 'tres': 3, 'cuatro': 4, 'cinco': 5, 'seis': 6,
  'siete': 7, 'ocho': 8, 'nueve': 9, 'diez': 10, 'once': 11, 'doce': 12, 'trece': 13, 'catorce': 14,
  'quince': 15, 'dieciseis': 16, 'diecisiete': 17, 'dieciocho': 18, 'diecinueve': 19, 'veinte': 20,
  'veintiuno': 21, 'veintiun': 21, 'veintiuna': 21, 'veintidos': 22, 'veintitres': 23, 'veinticuatro': 24,
  'veinticinco': 25, 'veintiseis': 26, 'veintisiete': 27, 'veintiocho': 28, 'veintinueve': 29,
  'treinta': 30, 'cuarenta': 40, 'cincuenta': 50, 'sesenta': 60, 'setenta': 70, 'ochenta': 80,
  'noventa': 90, 'cien': 100,
};

/// "15", "-3", "1.500" (miles), "treinta y cinco". Null si no es un entero.
BigInt? leerEntero(String texto) {
  final t = texto.trim();
  if (RegExp(r'^[+-]?\d+$').hasMatch(t)) return BigInt.parse(t.replaceFirst('+', ''));
  if (RegExp(r'^\d{1,3}(\.\d{3})+$').hasMatch(t)) return BigInt.parse(t.replaceAll('.', ''));
  final p = palabras(t);
  if (p.length == 1 && _numeros.containsKey(p[0])) return BigInt.from(_numeros[p[0]]!);
  if (p.length == 3 && p[1] == 'y') {
    final decena = _numeros[p[0]], unidad = _numeros[p[2]];
    if (decena != null && unidad != null && decena >= 30 && decena % 10 == 0 && unidad >= 1 && unidad <= 9) {
      return BigInt.from(decena + unidad);
    }
  }
  return null;
}

/// Decimal canonico ("150.5") o null. Coma o punto decimal: con un solo
/// separador, ese es el decimal ("150,50" en Bolivia, "150.50" en un teclado);
/// con los dos, el ultimo ("1.500,50" y "1,500.50").
String? leerDecimal(String texto) {
  var t = texto.trim().replaceAll(' ', '');
  final entero = leerEntero(t);
  if (entero != null && !t.contains(',')) return entero.toString();
  if (!RegExp(r'^[+-]?[\d.,]+$').hasMatch(t) || !RegExp(r'\d').hasMatch(t)) return null;
  final signo = t.startsWith('-') ? '-' : '';
  t = t.replaceFirst(RegExp(r'^[+-]'), '');
  final ultimoPunto = t.lastIndexOf('.'), ultimaComa = t.lastIndexOf(',');
  String enteros, decimales;
  if (ultimoPunto >= 0 && ultimaComa >= 0) {
    final sep = ultimoPunto > ultimaComa ? '.' : ',';
    final miles = sep == '.' ? ',' : '.';
    final corte = t.lastIndexOf(sep);
    enteros = t.substring(0, corte);
    decimales = t.substring(corte + 1);
    if (!RegExp('^\\d{1,3}(\\${miles}\\d{3})*\$').hasMatch(enteros)) return null;
    enteros = enteros.replaceAll(miles, '');
  } else {
    final sep = ultimoPunto >= 0 ? '.' : ',';
    final partes = t.split(sep);
    if (partes.length != 2) return null;
    enteros = partes[0];
    decimales = partes[1];
  }
  if (!RegExp(r'^\d+$').hasMatch(enteros) || !RegExp(r'^\d+$').hasMatch(decimales)) return null;
  enteros = enteros.replaceFirst(RegExp(r'^0+(?=\d)'), '');
  decimales = decimales.replaceFirst(RegExp(r'0+$'), '');
  return '$signo$enteros${decimales.isEmpty ? '' : '.$decimales'}';
}

Invalido? _enRango(FieldDescriptor campo, String valor) {
  final nombre = campo.nombreHablado;
  if (campo.min != null && compararDecimales(valor, campo.min!) < 0) {
    return Invalido('$nombre tiene que ser al menos ${campo.min}${campo.max != null ? ' y como mucho ${campo.max}' : ''}, y dijiste $valor');
  }
  if (campo.max != null && compararDecimales(valor, campo.max!) > 0) {
    return Invalido('$nombre tiene que ser como mucho ${campo.max}${campo.min != null ? ' y al menos ${campo.min}' : ''}, y dijiste $valor');
  }
  return null;
}

/// Compara dos decimales escritos como texto sin pasar por double.
int compararDecimales(String a, String b) {
  (BigInt, int) partir(String s) {
    if (s.contains('e') || s.contains('E')) {
      // Un limite que JSON trajo en notacion cientifica: lo expande double.
      s = double.parse(s).toStringAsFixed(12);
    }
    final negativo = s.startsWith('-');
    final sinSigno = s.replaceFirst(RegExp(r'^[+-]'), '');
    final partes = sinSigno.split('.');
    final frac = partes.length > 1 ? partes[1] : '';
    final n = BigInt.parse('${partes[0]}$frac');
    return (negativo ? -n : n, frac.length);
  }

  final (na, ea) = partir(a);
  final (nb, eb) = partir(b);
  final escala = ea > eb ? ea : eb;
  final x = na * BigInt.from(10).pow(escala - ea);
  final y = nb * BigInt.from(10).pow(escala - eb);
  return x.compareTo(y);
}

bool? leerBooleano(String texto) {
  final t = normalizar(texto);
  const si = {'si', 'verdadero', 'true', 'cierto', 'activo', 'activa', 'habilitado', 'habilitada'};
  const no = {'no', 'falso', 'false', 'inactivo', 'inactiva', 'deshabilitado', 'deshabilitada'};
  if (si.contains(t)) return true;
  if (no.contains(t)) return false;
  return null;
}

// ------------------------------------------------------------------ FECHAS

sealed class _LecturaFecha {}

class _Fecha extends _LecturaFecha {
  final DateTime valor;
  _Fecha(this.valor);
}

class _SinFecha extends _LecturaFecha {
  final String motivo;
  _SinFecha(this.motivo);
}

const Map<String, int> _meses = {
  'enero': 1, 'febrero': 2, 'marzo': 3, 'abril': 4, 'mayo': 5, 'junio': 6, 'julio': 7,
  'agosto': 8, 'septiembre': 9, 'setiembre': 9, 'octubre': 10, 'noviembre': 11, 'diciembre': 12,
};

/// "2024-05-01", "1/5/2024" (dia primero), "1 de mayo de 2024", "1 de mayo"
/// (este anio), "hoy", "mañana", "ayer", "pasado mañana".
_LecturaFecha leerFecha(String texto, DateTime hoy) {
  final t = texto.trim();
  final n = normalizar(t);
  final base = DateTime(hoy.year, hoy.month, hoy.day);
  switch (n) {
    case 'hoy':
      return _Fecha(base);
    case 'maniana':
      return _Fecha(DateTime(base.year, base.month, base.day + 1));
    case 'pasado maniana':
      return _Fecha(DateTime(base.year, base.month, base.day + 2));
    case 'ayer':
      return _Fecha(DateTime(base.year, base.month, base.day - 1));
  }

  int? anio, mes, dia;
  var m = RegExp(r'^(\d{4})-(\d{1,2})-(\d{1,2})$').firstMatch(t);
  if (m != null) {
    anio = int.parse(m.group(1)!);
    mes = int.parse(m.group(2)!);
    dia = int.parse(m.group(3)!);
  } else if ((m = RegExp(r'^(\d{1,2})[/-](\d{1,2})[/-](\d{4})$').firstMatch(t)) != null) {
    dia = int.parse(m!.group(1)!);
    mes = int.parse(m.group(2)!);
    anio = int.parse(m.group(3)!);
  } else if ((m = RegExp(r'^(\d{1,2}) de ([a-z]+)(?: (?:de|del) (\d{4}))?$').firstMatch(n)) != null) {
    dia = int.parse(m!.group(1)!);
    mes = _meses[m.group(2)!];
    anio = m.group(3) != null ? int.parse(m.group(3)!) : hoy.year;
    if (mes == null) return _SinFecha('«${m.group(2)}» no es un mes');
  } else {
    return _SinFecha('«$t» no es una fecha (por ejemplo «15 de mayo de 2024», «15/05/2024» u «hoy»)');
  }
  final f = DateTime(anio, mes, dia);
  if (mes < 1 || mes > 12 || f.year != anio || f.month != mes || f.day != dia) {
    return _SinFecha('«$t» no existe en el calendario');
  }
  return _Fecha(f);
}

Conversion _fechaHora(FieldDescriptor campo, String texto, DateTime hoy) {
  final nombre = campo.nombreHablado;
  final t = texto.trim().replaceFirst(RegExp(r'(?<=\d)T(?=\d)'), ' ');
  // La hora va al final: "hoy a las 15:30", "2024-05-01 10:00", "mañana a las 9".
  final m = RegExp(r'^(.*?)\s*(?:(?:a\s+las?\s+)(\d{1,2})(?::(\d{2}))?|(\d{1,2}):(\d{2}))\s*(?:hs|h|horas)?$',
          caseSensitive: false)
      .firstMatch(t);
  if (m == null) {
    return Invalido('$nombre lleva fecha y hora: decí por ejemplo «hoy a las 15:30»');
  }
  final hora = int.parse(m.group(2) ?? m.group(4)!);
  final minuto = int.parse(m.group(3) ?? m.group(5) ?? '0');
  if (hora > 23 || minuto > 59) return Invalido('$nombre: «$t» no es una hora válida');
  final fecha = leerFecha(m.group(1)!, hoy);
  return switch (fecha) {
    _SinFecha(:final motivo) => Invalido('$nombre: $motivo'),
    _Fecha(:final valor) => Convertido('${_iso(valor)}T${_dos(hora)}:${_dos(minuto)}:00'),
  };
}

String _dos(int n) => n.toString().padLeft(2, '0');
String _iso(DateTime f) => '${f.year.toString().padLeft(4, '0')}-${_dos(f.month)}-${_dos(f.day)}';

// ------------------------------------------------------------ ENUMERACIONES

/// "EN_PRESTAMO" -> "en prestamo": como se dice un literal.
String literalHablado(String literal) => literal.toLowerCase().replaceAll('_', ' ');

Conversion _literal(FieldDescriptor campo, String texto) {
  final dicho = palabras(texto);
  var mejor = 0.0;
  var ganadores = <String>[];
  for (final literal in campo.literals) {
    final esperado = palabras(literalHablado(literal));
    double puntaje;
    if (esperado.length == dicho.length) {
      var suma = 0.0;
      for (var i = 0; i < esperado.length; i++) {
        final p = parecido(dicho[i], esperado[i]);
        if (p == 0) {
          suma = 0;
          break;
        }
        suma += p;
      }
      puntaje = esperado.isEmpty ? 0 : suma / esperado.length;
    } else {
      puntaje = 0;
    }
    if (puntaje == 0) continue;
    if (puntaje > mejor + 1e-9) {
      mejor = puntaje;
      ganadores = [literal];
    } else if ((puntaje - mejor).abs() <= 1e-9) {
      ganadores.add(literal);
    }
  }
  if (ganadores.length == 1) return Convertido(ganadores.single);
  if (ganadores.length > 1) return LiteralAmbiguo(ganadores);
  return Invalido('«$texto» no es un valor de ${campo.nombreHablado}; puede ser '
      '${enumerar(campo.literals.map(literalHablado).toList())}');
}
