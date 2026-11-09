// Dictado para el asistente (T-03c). La pantalla habla con [ReconocedorVoz],
// no con el plugin: asi se prueba con un reconocedor falso y el texto dictado
// entra por el mismo `ControladorAsistente.enviar` que el teclado.

import 'dart:async';

import 'package:flutter/foundation.dart';

import 'package:speech_to_text/speech_recognition_error.dart';
import 'package:speech_to_text/speech_recognition_result.dart';
import 'package:speech_to_text/speech_to_text.dart';

/// Un error del microfono, ya traducido para mostrarlo.
class ErrorVoz {
  final String mensaje;

  /// Si es true no tiene sentido reintentar (sin permiso, sin reconocedor):
  /// la pantalla deshabilita el microfono y deja solo el teclado.
  final bool definitivo;

  const ErrorVoz(this.mensaje, {this.definitivo = false});
}

abstract class ReconocedorVoz {
  /// Pide el permiso y prepara el reconocedor. Devuelve null si quedo listo o
  /// el error que impide dictar.
  Future<ErrorVoz?> preparar();

  /// Empieza a escuchar. [alParcial] recibe lo que se va entendiendo,
  /// [alFinal] la frase completa (puede venir vacia), [alError] lo que salio
  /// mal y [alTerminar] avisa que dejo de escuchar, por cualquier motivo.
  Future<void> escuchar({
    required void Function(String texto) alParcial,
    required void Function(String texto) alFinal,
    required void Function(ErrorVoz error) alError,
    required void Function() alTerminar,
  });

  /// Deja de escuchar. Lo dicho hasta ahi llega como final.
  Future<void> detener();

  /// Corta la escucha SIN entregar nada (el usuario envio a mano lo que
  /// tenia en el campo).
  Future<void> cancelar();
}

const _sinPermiso = ErrorVoz(
    'No tengo permiso para usar el micrófono. Activalo en Ajustes > Apps > ColabModel. '
    'Mientras tanto podés escribir.',
    definitivo: true);

const _sinReconocedor = ErrorVoz(
    'Este teléfono no tiene reconocimiento de voz disponible. Podés escribir.',
    definitivo: true);

/// Traduce los codigos de error del reconocedor de Android a algo que se
/// pueda mostrar. Publico para probarlo sin plugin.
ErrorVoz errorVozDesdeCodigo(String codigo) {
  switch (codigo) {
    case 'error_no_match':
    case 'error_speech_timeout':
      return const ErrorVoz('No te entendí. Tocá el micrófono y probá de nuevo.');
    case 'error_permission':
    case 'error_insufficient_permissions':
      return _sinPermiso;
    case 'error_busy':
      return const ErrorVoz('El micrófono está ocupado. Esperá un momento y probá de nuevo.');
    case 'error_network':
    case 'error_network_timeout':
    case 'error_server':
    case 'error_server_disconnected':
    case 'error_too_many_requests':
      return const ErrorVoz('El reconocedor necesita conexión y no la tiene. Podés escribir.');
    case 'error_language_not_supported':
    case 'error_language_unavailable':
      return const ErrorVoz('El reconocedor no tiene español disponible. Podés escribir.');
    case 'error_audio_error':
      return const ErrorVoz('No pude leer el micrófono. Probá de nuevo.');
    default:
      return ErrorVoz('No pude usar el micrófono ($codigo). Podés escribir.');
  }
}

/// Une los pedazos de una frase dictada en una sola.
///
/// El reconocedor de Android termina la sesion con cualquier pausa corta
/// ("crear el" ... "cliente" ... "ana perez"). Aca cada pedazo se acumula, se
/// vuelve a escuchar enseguida, y la frase se entrega entera cuando:
///   - pasa [silencio] SIN CAMBIOS en el texto MIENTRAS el sistema escucha, o
///   - el usuario toca el microfono para terminar.
///
/// Reglas que salieron del telefono real (T-03c, APK 94080d0):
///   - El silencio no corre mientras Android reinicia la escucha (pitido
///     incluido): si corriera, una frase dicha durante el reinicio se partia
///     ("crear" / "cliente" / la frase completa). Empieza a contar recien con
///     [sistemaEscuchando]. Si el sistema nunca vuelve a escuchar, igual se
///     entrega a los [esperaReinicio].
///   - Solo un CAMBIO real del texto reinicia el silencio: Android repite
///     parciales iguales (o ruido vacio) y eso dejaba la frase sin enviar.
///   - Un final vacio, o una sesion que termina sin final, NO borran lo que se
///     vio como parcial: antes "juan Perez" se veia en el campo y terminaba en
///     "No te entendi".
///
/// No conoce al plugin: recibe los eventos y llama a [iniciarSesion],
/// [pararSesion] (corta y entrega lo dicho) y [cortarSesion] (corta y
/// descarta). Asi se prueba con un reloj falso.
class DictadoContinuo {
  final void Function() iniciarSesion;
  final void Function() pararSesion;
  final void Function() cortarSesion;
  final Duration silencio;
  final Duration esperaReinicio;

  final void Function(String texto) alParcial;
  final void Function(String texto) alFinal;
  final void Function(ErrorVoz error) alError;
  final void Function() alTerminar;

  DictadoContinuo({
    required this.iniciarSesion,
    required this.pararSesion,
    required this.cortarSesion,
    required this.alParcial,
    required this.alFinal,
    required this.alError,
    required this.alTerminar,
    this.silencio = const Duration(seconds: 2),
    this.esperaReinicio = const Duration(seconds: 6),
  });

  bool _activo = false;
  bool _sesionViva = false;
  bool _escuchando = false;
  bool _detener = false;
  String _acumulado = '';
  String _parcial = '';
  Timer? _reloj;

  bool get activo => _activo;

  /// Lo que se entiende hasta ahora: lo ya cerrado mas el parcial de la sesion actual.
  String get texto => _unir(_acumulado, _parcial);

  static String _unir(String a, String b) {
    final x = a.trim(), y = b.trim();
    if (x.isEmpty) return y;
    if (y.isEmpty) return x;
    return '$x $y';
  }

  void empezar() {
    _activo = true;
    _detener = false;
    _escuchando = false;
    _acumulado = '';
    _parcial = '';
    _sesionViva = true;
    iniciarSesion();
  }

  /// El sistema empezo (o volvio) a escuchar: desde aca cuenta el silencio, si
  /// ya se dijo algo. Si todavia no se dijo nada, espera a que se hable (el
  /// propio Android corta con "no te entendi" si nadie habla).
  void sistemaEscuchando() {
    if (!_activo) return;
    _escuchando = true;
    if (texto.isNotEmpty) _armar(silencio);
  }

  /// Lo que se va entendiendo en la sesion actual.
  void parcial(String t) {
    if (!_activo) return;
    final antes = texto;
    _parcial = t;
    if (texto == antes) return; // repetido o ruido vacio: no reinicia el silencio
    alParcial(texto);
    if (_escuchando && texto.isNotEmpty) _armar(silencio);
  }

  /// La sesion actual entrego su final. Vacio = "lo que se vio como parcial".
  void finalDeSesion(String t) {
    if (!_activo) return;
    final pedazo = t.trim().isEmpty ? _parcial : t;
    _acumulado = _unir(_acumulado, pedazo);
    _parcial = '';
    alParcial(texto);
    if (_detener) _cerrar();
    // Si no, se espera el fin de la sesion para volver a escuchar.
  }

  /// La sesion del sistema termino (por una pausa o porque se la paro).
  void sesionTerminada() {
    if (!_activo) return;
    _sesionViva = false;
    _escuchando = false;
    _reloj?.cancel();
    _guardarParcial();
    if (_detener || texto.isEmpty) {
      // Se pidio terminar, o termino sin que se dijera nada.
      _cerrar();
      return;
    }
    // Hay una frase a medias: se vuelve a escuchar. El silencio cuenta cuando
    // el sistema avise que escucha; si no avisa, se entrega igual.
    _sesionViva = true;
    iniciarSesion();
    _armar(esperaReinicio);
  }

  void error(String codigo) {
    if (!_activo) return;
    final noHablo = codigo == 'error_no_match' || codigo == 'error_speech_timeout';
    _sesionViva = false;
    _escuchando = false;
    _guardarParcial();
    if (noHablo || texto.isNotEmpty) {
      // Silencio despues de la frase, o un error con algo ya dicho: se entrega lo que hay.
      _cerrar();
      return;
    }
    _activo = false;
    _reloj?.cancel();
    alError(errorVozDesdeCodigo(codigo));
    alTerminar();
  }

  /// El usuario toco el microfono para terminar.
  void detener() {
    if (!_activo) return;
    if (!_sesionViva) {
      _cerrar();
      return;
    }
    _detener = true;
    pararSesion();
    _armar(silencio); // si el sistema no contesta, se entrega igual
  }

  /// Corta sin entregar nada (se envio a mano, o la pantalla se cerro).
  void abandonar() {
    if (!_activo) return;
    _activo = false;
    _reloj?.cancel();
    if (_sesionViva) cortarSesion();
    _sesionViva = false;
  }

  void _guardarParcial() {
    if (_parcial.trim().isNotEmpty) _acumulado = _unir(_acumulado, _parcial);
    _parcial = '';
  }

  void _armar(Duration d) {
    _reloj?.cancel();
    _reloj = Timer(d, _cerrar);
  }

  void _cerrar() {
    if (!_activo) return;
    _activo = false;
    _reloj?.cancel();
    if (_sesionViva) cortarSesion();
    _sesionViva = false;
    _escuchando = false;
    alFinal(texto);
    alTerminar();
  }
}

/// Reconocedor del sistema (speech_to_text). Primero intenta reconocer en el
/// dispositivo, sin red; si el telefono no tiene el idioma descargado, repite
/// una vez con el reconocedor normal (que puede usar la red). Las pausas
/// cortas no cortan la frase: ver [DictadoContinuo].
class ReconocedorSistema implements ReconocedorVoz {
  final SpeechToText _stt;

  ReconocedorSistema({SpeechToText? stt}) : _stt = stt ?? SpeechToText();

  bool _listo = false;
  bool _enDispositivo = true;
  String? _idioma;
  DictadoContinuo? _dictado;

  @override
  Future<ErrorVoz?> preparar() async {
    if (_listo) return null;
    final bool ok;
    try {
      ok = await _stt.initialize(onError: _onError, onStatus: _onStatus);
    } on Exception {
      return _sinReconocedor;
    }
    if (!ok) {
      final permiso = await _stt.hasPermission;
      return permiso ? _sinReconocedor : _sinPermiso;
    }
    _idioma = await _elegirIdioma();
    _listo = true;
    return null;
  }

  /// El idioma del sistema si es español; si no, el primer español que haya.
  Future<String?> _elegirIdioma() async {
    try {
      final sistema = await _stt.systemLocale();
      if (sistema != null && sistema.localeId.toLowerCase().startsWith('es')) return sistema.localeId;
      for (final l in await _stt.locales()) {
        if (l.localeId.toLowerCase().startsWith('es')) return l.localeId;
      }
    } on Exception {
      // Sin lista de idiomas: se usa el del sistema.
    }
    return null;
  }

  @override
  Future<void> escuchar({
    required void Function(String texto) alParcial,
    required void Function(String texto) alFinal,
    required void Function(ErrorVoz error) alError,
    required void Function() alTerminar,
  }) async {
    _dictado?.abandonar();
    final d = DictadoContinuo(
      iniciarSesion: _escuchar,
      pararSesion: () => _stt.stop(),
      cortarSesion: () => _stt.cancel(),
      alParcial: alParcial,
      alFinal: alFinal,
      alError: alError,
      alTerminar: alTerminar,
    );
    _dictado = d;
    d.empezar();
  }

  void _escuchar() {
    _stt.listen(
      onResult: _onResult,
      listenOptions: SpeechListenOptions(
        partialResults: true,
        onDevice: _enDispositivo,
        cancelOnError: true,
        listenMode: ListenMode.dictation,
        localeId: _idioma,
        // Corta el plugin de este lado; las pausas del sistema las cubre DictadoContinuo.
        pauseFor: const Duration(seconds: 5),
        listenFor: const Duration(seconds: 30),
      ),
    );
  }

  /// Rastro de los eventos del sistema, solo en el APK de depuracion
  /// (`adb logcat -s flutter`): para ver en el telefono real en que orden llegan.
  static void _rastro(String m) {
    if (kDebugMode) debugPrint('[voz] $m');
  }

  void _onResult(SpeechRecognitionResult r) {
    _rastro('${r.finalResult ? 'final' : 'parcial'} "${r.recognizedWords}"');
    final d = _dictado;
    if (d == null) return;
    if (r.finalResult) {
      d.finalDeSesion(r.recognizedWords);
    } else {
      d.parcial(r.recognizedWords);
    }
  }

  void _onError(SpeechRecognitionError e) {
    _rastro('error ${e.errorMsg} permanente=${e.permanent}');
    final sinIdiomaLocal =
        e.errorMsg == 'error_language_unavailable' || e.errorMsg == 'error_language_not_supported';
    if (_enDispositivo && sinIdiomaLocal) {
      // No hay paquete de español para reconocer sin red: se repite con el
      // reconocedor normal, sin molestar al usuario.
      _enDispositivo = false;
      _reintentando = true;
      _escuchar();
      return;
    }
    _reintentando = false;
    _dictado?.error(e.errorMsg);
  }

  /// Mientras se repite sin el modo en el dispositivo, el "termine" de la
  /// sesion que fallo no cuenta: el dictado sigue.
  bool _reintentando = false;

  void _onStatus(String estado) {
    _rastro('estado $estado');
    if (estado == SpeechToText.listeningStatus) {
      _reintentando = false;
      _dictado?.sistemaEscuchando(); // recien ahora cuenta el silencio
      return;
    }
    if (_reintentando) return;
    if (estado == SpeechToText.doneStatus) _dictado?.sesionTerminada();
  }

  @override
  Future<void> detener() async => _dictado?.detener();

  @override
  Future<void> cancelar() async => _dictado?.abandonar();
}
