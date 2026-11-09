import 'package:flutter/material.dart';

/// Paleta de la app movil: rojo de marca sobre neutros, al estilo de YouTube.
/// El rojo queda para lo que se toca (boton principal, seleccion, marca); los
/// fondos y contenedores son grises, sin tinte de color.
const rojoMarca = Color(0xFFFF0000);

ThemeData temaClaro() => _tema(
      brillo: Brightness.light,
      fondo: const Color(0xFFFFFFFF),
      texto: const Color(0xFF0F0F0F),
      textoSuave: const Color(0xFF606060),
      capas: const [Color(0xFFF9F9F9), Color(0xFFF2F2F2), Color(0xFFE5E5E5)],
      borde: const Color(0xFF909090),
      enlace: const Color(0xFF065FD4),
      error: const Color(0xFFCC0000),
    );

ThemeData temaOscuro() => _tema(
      brillo: Brightness.dark,
      fondo: const Color(0xFF0F0F0F),
      texto: const Color(0xFFF1F1F1),
      textoSuave: const Color(0xFFAAAAAA),
      capas: const [Color(0xFF212121), Color(0xFF272727), Color(0xFF3F3F3F)],
      borde: const Color(0xFF717171),
      enlace: const Color(0xFF3EA6FF),
      error: const Color(0xFFFF4E45),
    );

ThemeData _tema({
  required Brightness brillo,
  required Color fondo,
  required Color texto,
  required Color textoSuave,
  required List<Color> capas,
  required Color borde,
  required Color enlace,
  required Color error,
}) {
  final oscuro = brillo == Brightness.dark;
  final colores = ColorScheme.fromSeed(seedColor: rojoMarca, brightness: brillo).copyWith(
    primary: rojoMarca,
    onPrimary: Colors.white,
    primaryContainer: capas[1],
    onPrimaryContainer: texto,
    secondary: enlace,
    onSecondary: oscuro ? const Color(0xFF0F0F0F) : Colors.white,
    secondaryContainer: capas[2],
    onSecondaryContainer: texto,
    tertiary: enlace,
    onTertiary: oscuro ? const Color(0xFF0F0F0F) : Colors.white,
    tertiaryContainer: capas[1],
    onTertiaryContainer: texto,
    error: error,
    onError: Colors.white,
    errorContainer: oscuro ? const Color(0xFF4A1512) : const Color(0xFFFDE7E6),
    onErrorContainer: oscuro ? const Color(0xFFFFDAD6) : const Color(0xFF7A0000),
    surface: fondo,
    onSurface: texto,
    onSurfaceVariant: textoSuave,
    surfaceDim: capas[1],
    surfaceBright: fondo,
    surfaceContainerLowest: fondo,
    surfaceContainerLow: capas[0],
    surfaceContainer: capas[0],
    surfaceContainerHigh: capas[1],
    surfaceContainerHighest: capas[2],
    surfaceTint: Colors.transparent,
    outline: borde,
    outlineVariant: capas[2],
    inverseSurface: texto,
    onInverseSurface: fondo,
    inversePrimary: rojoMarca,
  );
  return ThemeData(
    useMaterial3: true,
    colorScheme: colores,
    scaffoldBackgroundColor: fondo,
    appBarTheme: AppBarTheme(
      backgroundColor: fondo,
      foregroundColor: texto,
      elevation: 0,
      scrolledUnderElevation: 0,
    ),
    floatingActionButtonTheme: const FloatingActionButtonThemeData(
      backgroundColor: rojoMarca,
      foregroundColor: Colors.white,
    ),
    dividerTheme: DividerThemeData(color: capas[2]),
  );
}
