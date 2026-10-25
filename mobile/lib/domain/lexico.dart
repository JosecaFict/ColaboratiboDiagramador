// ============================================================================
// LEXICO DEL ESPANOL PARA EL MOTOR DE INTENCIONES  (ADR-021)
//
// El manifiesto trae el VOCABULARIO del dominio ("título", "fecha préstamo").
// Aca vive la MORFOLOGIA: como una persona dobla esas palabras al hablar
// ("títulos", "titulo", "alumna" por "alumno"). Separarlas permite que un
// sinonimo agregado a mano el dia de la demo se doble solo.
//
// Todo es determinista: mismas entradas, misma salida, sin modelo.
// ============================================================================

const Map<String, String> _sinTilde = {
  'á': 'a', 'à': 'a', 'ä': 'a', 'â': 'a',
  'é': 'e', 'è': 'e', 'ë': 'e', 'ê': 'e',
  'í': 'i', 'ì': 'i', 'ï': 'i', 'î': 'i',
  'ó': 'o', 'ò': 'o', 'ö': 'o', 'ô': 'o',
  'ú': 'u', 'ù': 'u', 'ü': 'u', 'û': 'u',
  'ç': 'c',
  // La enie como en la Regla 8 del generador ("Año" -> "anio"): asi un
  // nombre escrito sin enie en el UML sigue coincidiendo con el hablado.
  'ñ': 'ni',
};

/// Minusculas, sin tildes, solo letras y numeros separados por un espacio.
String normalizar(String s) {
  final b = StringBuffer();
  for (final r in s.toLowerCase().runes) {
    final c = String.fromCharCode(r);
    b.write(_sinTilde[c] ?? c);
  }
  return b
      .toString()
      .replaceAll(RegExp(r'[^a-z0-9]+'), ' ')
      .trim();
}

List<String> palabras(String s) => normalizar(s).split(' ').where((p) => p.isNotEmpty).toList();

/// Palabras que no nombran nada: se saltean DENTRO de un nombre de varias
/// palabras ("fecha de préstamo" = "fecha préstamo").
const Set<String> palabrasVacias = {'de', 'del', 'la', 'el', 'los', 'las', 'lo', 'al', 'a'};

/// Formas de una palabra normalizada que se consideran la misma: singular y
/// plural (-s, -es, -ces -> -z) y genero (vocal final o/a/e).
///
/// Dos palabras coinciden por morfologia si comparten una forma de 3 letras o
/// mas. Es deliberadamente grueso: "caso" y "casa" coinciden. En un dominio
/// eso casi no pasa, y si pasa el empate se PREGUNTA, no se adivina.
Set<String> formas(String p) {
  final base = <String>{p};
  if (p.length > 4 && p.endsWith('ces')) base.add('${p.substring(0, p.length - 3)}z');
  if (p.length > 4 && p.endsWith('es')) base.add(p.substring(0, p.length - 2));
  if (p.length > 3 && p.endsWith('s')) base.add(p.substring(0, p.length - 1));
  final todas = <String>{...base};
  for (final b in base) {
    if (b.length > 3 && (b.endsWith('a') || b.endsWith('o') || b.endsWith('e'))) {
      todas.add(b.substring(0, b.length - 1));
    }
  }
  return todas.where((f) => f.length >= 3 || f == p).toSet();
}

/// Cuantas ediciones se toleran segun el largo: nada en palabras cortas
/// ("mes" y "mas" no son lo mismo), una en medianas, dos en largas.
int toleranciaDe(int largo) => largo <= 4 ? 0 : (largo <= 7 ? 1 : 2);

/// Que tanto se parecen dos palabras YA normalizadas.
///   1.00  iguales
///   0.90  la misma por plural o genero
///   0.80  una letra de diferencia (error de dictado o de tipeo); 0.75 con dos
///   0     distintas
double parecido(String a, String b) {
  if (a == b) return 1.0;
  final fa = formas(a), fb = formas(b);
  if (fa.intersection(fb).isNotEmpty) return 0.9;
  final tolerancia = toleranciaDe(a.length < b.length ? a.length : b.length);
  if (tolerancia == 0) return 0;
  var mejor = 1 << 30;
  for (final x in fa) {
    for (final y in fb) {
      final d = levenshtein(x, y, tope: tolerancia);
      if (d < mejor) mejor = d;
    }
  }
  if (mejor > tolerancia) return 0;
  return 0.8 - 0.05 * (mejor - 1);
}

/// Distancia de edicion. Con `tope`, corta apenas se sabe que lo supera.
int levenshtein(String a, String b, {int? tope}) {
  if ((a.length - b.length).abs() > (tope ?? 1 << 30)) return (tope ?? 0) + 1;
  var previa = List<int>.generate(b.length + 1, (i) => i);
  for (var i = 1; i <= a.length; i++) {
    final actual = List<int>.filled(b.length + 1, 0)..[0] = i;
    var minimoFila = i;
    for (var j = 1; j <= b.length; j++) {
      final costo = a.codeUnitAt(i - 1) == b.codeUnitAt(j - 1) ? 0 : 1;
      final v = [previa[j] + 1, actual[j - 1] + 1, previa[j - 1] + costo].reduce((x, y) => x < y ? x : y);
      actual[j] = v;
      if (v < minimoFila) minimoFila = v;
    }
    if (tope != null && minimoFila > tope) return tope + 1;
    previa = actual;
  }
  return previa[b.length];
}

// ---------------------------------------------------------------- TOKENS

class Token {
  /// Tal como se escribio o dicto: de aca salen los valores, con sus tildes y
  /// mayusculas ("Cien años de soledad").
  final String texto;

  /// Normalizado, para comparar.
  final String norma;
  final int inicio;
  final int fin;

  /// Entre comillas: es un valor opaco, nunca el nombre de un campo.
  final bool citado;

  /// `,` `;` `:` `=`
  final bool signo;

  Token(this.texto, this.norma, this.inicio, this.fin, {this.citado = false, this.signo = false});

  @override
  String toString() => texto;
}

final RegExp _patronToken = RegExp(
  r'"([^"]*)"|“([^”]*)”|«([^»]*)»'
  // El signo solo si no esta pegado a una palabra: "-3" si, "AA-100" no.
  r'|(?<![\p{L}\p{N}])[+-]?\d[\d.,:/\-]*\d|(?<![\p{L}\p{N}])[+-]?\d'
  r'|[\p{L}\p{N}][\p{L}\p{N}_@.\-]*[\p{L}\p{N}]|[\p{L}\p{N}]'
  r'|[,;:=]',
  unicode: true,
);

List<Token> tokenizar(String texto) {
  final tokens = <Token>[];
  for (final m in _patronToken.allMatches(texto)) {
    final citado = m.group(1) ?? m.group(2) ?? m.group(3);
    final crudo = m.group(0)!;
    if (citado != null) {
      tokens.add(Token(citado, normalizar(citado), m.start, m.end, citado: true));
    } else if (RegExp(r'^[,;:=]$').hasMatch(crudo)) {
      tokens.add(Token(crudo, crudo, m.start, m.end, signo: true));
    } else {
      tokens.add(Token(crudo, normalizar(crudo), m.start, m.end));
    }
  }
  return tokens;
}

// ------------------------------------------------------ NOMBRES EN EL TEXTO

/// Donde y que tan bien aparece un nombre (de entidad o de campo) en el texto.
class Aparicion {
  final int desde;

  /// Primer token despues del nombre.
  final int hasta;
  final double puntaje;

  Aparicion(this.desde, this.hasta, this.puntaje);
}

/// Busca `nombre` (un sinonimo del manifiesto) empezando exactamente en
/// `tokens[desde]`.
///
/// Acepta un PREFIJO del nombre ("fecha" por "fecha préstamo"), con puntaje
/// proporcional a lo cubierto: asi "fecha" empata entre "fecha préstamo" y
/// "fecha devolución", y ese empate es una pregunta, no una eleccion.
Aparicion? buscarNombre(List<Token> tokens, int desde, String nombre) {
  var partes = palabras(nombre);
  final llenas = partes.where((p) => !palabrasVacias.contains(p)).toList();
  if (llenas.isNotEmpty) partes = llenas;
  if (partes.isEmpty || desde >= tokens.length) return null;

  var i = desde;
  var suma = 0.0;
  var cubiertas = 0;
  for (final parte in partes) {
    // Dentro del nombre se saltea un "de"/"la"; nunca antes de la primera palabra.
    var j = i;
    if (cubiertas > 0) {
      while (j < tokens.length && !tokens[j].signo && palabrasVacias.contains(tokens[j].norma)) {
        j++;
      }
    }
    if (j >= tokens.length || tokens[j].signo || tokens[j].citado) break;
    final p = parecido(tokens[j].norma, parte);
    if (p == 0) break;
    suma += p;
    cubiertas++;
    i = j + 1;
  }
  if (cubiertas == 0) return null;
  return Aparicion(desde, i, (suma / cubiertas) * (cubiertas / partes.length));
}

/// "a", "a o b", "a, b o c".
String enumerar(List<String> items, {String conjuncion = 'o'}) {
  if (items.isEmpty) return '';
  if (items.length == 1) return items.first;
  return '${items.sublist(0, items.length - 1).join(', ')} $conjuncion ${items.last}';
}
