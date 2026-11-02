// DictadoContinuo (T-03c): las pausas cortas del reconocedor de Android no
// cortan la frase. TODO SIMULADO: los eventos del sistema los dispara la
// prueba (en el orden en que los manda speech_to_text en Android: listening,
// parciales, final, done) y el reloj es falso.
//
// Las tres regresiones del final salieron del telefono real con el APK
// 94080d0: la frase partida durante el reinicio, la frase que no se enviaba
// sola y el "juan Perez" que terminaba en "No te entendi".

import 'package:colabmodel_app/asistente/reconocedor_voz.dart';
import 'package:fake_async/fake_async.dart';
import 'package:flutter_test/flutter_test.dart';

const silencio = Duration(seconds: 2);
const reinicio = Duration(seconds: 6);

class _Registro {
  int sesiones = 0;
  int paradas = 0;
  int cortes = 0;
  int terminados = 0;
  final parciales = <String>[];
  final finales = <String>[];
  final errores = <ErrorVoz>[];

  DictadoContinuo dictado() => DictadoContinuo(
        iniciarSesion: () => sesiones++,
        pararSesion: () => paradas++,
        cortarSesion: () => cortes++,
        alParcial: parciales.add,
        alFinal: finales.add,
        alError: errores.add,
        alTerminar: () => terminados++,
        silencio: silencio,
        esperaReinicio: reinicio,
      );
}

void main() {
  test('varios pedazos con pausas cortas salen como una sola frase', () {
    fakeAsync((t) {
      final r = _Registro();
      final d = r.dictado()..empezar();
      expect(r.sesiones, 1);
      d.sistemaEscuchando();

      d.parcial('crear');
      d.finalDeSesion('crear el');
      d.sesionTerminada(); // Android corto por la pausa
      expect(r.sesiones, 2, reason: 'vuelve a escuchar enseguida');
      d.sistemaEscuchando();
      t.elapse(const Duration(milliseconds: 900)); // pausa corta: no se envia
      expect(r.finales, isEmpty);

      d.parcial('cliente con');
      t.elapse(const Duration(milliseconds: 1500));
      d.parcial('cliente con nombre Ana Pérez'); // sigue hablando: el silencio vuelve a empezar
      t.elapse(const Duration(milliseconds: 1500));
      expect(r.finales, isEmpty);
      d.finalDeSesion('cliente con nombre Ana Pérez');
      expect(r.parciales.last, 'crear el cliente con nombre Ana Pérez');
      d.sesionTerminada();
      d.sistemaEscuchando();
      expect(r.sesiones, 3);

      t.elapse(silencio); // silencio de verdad, con el sistema escuchando
      expect(r.finales, ['crear el cliente con nombre Ana Pérez']);
      expect(r.cortes, 1, reason: 'corta la sesion que quedo abierta');
      expect(r.terminados, 1);
      expect(d.activo, isFalse);

      // Lo que llegue tarde del sistema ya no hace nada.
      d.finalDeSesion('basura');
      d.sesionTerminada();
      expect(r.finales, hasLength(1));
      expect(r.sesiones, 3);
    });
  });

  test('tocar el microfono corta y envia lo acumulado mas lo que se estaba diciendo', () {
    fakeAsync((t) {
      final r = _Registro();
      final d = r.dictado()..empezar();
      d.sistemaEscuchando();
      d.finalDeSesion('crear cliente');
      d.sesionTerminada();
      d.sistemaEscuchando();
      d.parcial('con nombre');

      d.detener();
      expect(r.paradas, 1);
      expect(r.finales, isEmpty, reason: 'espera el final de la sesion parada');
      d.finalDeSesion('con nombre Ana');
      expect(r.finales, ['crear cliente con nombre Ana']);
      expect(r.terminados, 1);
    });
  });

  test('si al tocar el microfono el sistema no contesta, se envia igual', () {
    fakeAsync((t) {
      final r = _Registro();
      final d = r.dictado()..empezar();
      d.sistemaEscuchando();
      d.finalDeSesion('listar');
      d.sesionTerminada();
      d.sistemaEscuchando();
      d.parcial('clientes');
      d.detener();
      t.elapse(silencio);
      expect(r.finales, ['listar clientes']);
    });
  });

  test('una frase vacia entrega texto vacio (la pantalla no envia nada)', () {
    fakeAsync((t) {
      final r = _Registro();
      r.dictado()
        ..empezar()
        ..sistemaEscuchando()
        ..error('error_no_match');
      expect(r.finales, ['']);
      expect(r.errores, isEmpty);

      final r2 = _Registro();
      r2.dictado()
        ..empezar()
        ..sistemaEscuchando()
        ..sesionTerminada();
      expect(r2.finales, ['']);
      expect(r2.sesiones, 1, reason: 'sin nada dicho no se queda escuchando para siempre');
    });
  });

  test('el silencio despues de una frase (no_match) la entrega', () {
    fakeAsync((t) {
      final r = _Registro();
      final d = r.dictado()..empezar();
      d.sistemaEscuchando();
      d.finalDeSesion('listar productos');
      d.sesionTerminada();
      d.error('error_speech_timeout');
      expect(r.finales, ['listar productos']);
    });
  });

  test('un error de verdad sin nada dicho se avisa; con algo dicho se entrega', () {
    fakeAsync((t) {
      final r = _Registro();
      r.dictado()
        ..empezar()
        ..error('error_network');
      expect(r.finales, isEmpty);
      expect(r.errores.single.mensaje, contains('conexión'));
      expect(r.terminados, 1);

      final r2 = _Registro();
      final d2 = r2.dictado()..empezar();
      d2.finalDeSesion('crear producto');
      d2.sesionTerminada();
      d2.error('error_network');
      expect(r2.finales, ['crear producto']);
      expect(r2.errores, isEmpty);
    });
  });

  test('abandonar (se envio a mano o se cerro la pantalla) corta sin entregar', () {
    fakeAsync((t) {
      final r = _Registro();
      final d = r.dictado()..empezar();
      d.finalDeSesion('crear');
      d.abandonar();
      t.elapse(const Duration(seconds: 10));
      expect(r.finales, isEmpty);
      expect(r.cortes, 1);
      expect(r.terminados, 0);
    });
  });

  // ------------------------------------------------ regresiones del telefono (APK 94080d0)

  test('telefono: el silencio no corre mientras Android reinicia la escucha (frase partida)', () {
    fakeAsync((t) {
      final r = _Registro();
      final d = r.dictado()..empezar();
      d.sistemaEscuchando();
      d.parcial('crear');
      d.finalDeSesion('crear');
      d.sesionTerminada(); // pausa corta: reinicio
      // El reinicio tarda (pitido de Samsung). Antes, a los 1,8 s se mandaba "crear" solo.
      t.elapse(const Duration(seconds: 3));
      expect(r.finales, isEmpty);
      d.sistemaEscuchando();
      d.parcial('cliente con nombre Ana');
      d.finalDeSesion('cliente con nombre Ana');
      d.sesionTerminada();
      d.sistemaEscuchando();
      t.elapse(silencio);
      expect(r.finales, ['crear cliente con nombre Ana'], reason: 'una sola frase, un solo envio');
    });
  });

  // Guardia (no reproduce el sintoma con la clase vieja): con ruido vacio en la sesion
  // reiniciada, la frase se envia sola a los 2 s de que el sistema volvio a escuchar.
  test('guardia: parciales vacios en la sesion reiniciada no dejan la frase sin enviar', () {
    fakeAsync((t) {
      final r = _Registro();
      final d = r.dictado()..empezar();
      d.sistemaEscuchando();
      d.parcial('listar clientes');
      d.finalDeSesion('listar clientes');
      d.sesionTerminada();
      d.sistemaEscuchando();
      // La sesion reiniciada solo trae ruido: parciales vacios o el mismo texto.
      t.elapse(const Duration(milliseconds: 800));
      d.parcial('');
      t.elapse(const Duration(milliseconds: 800));
      d.parcial('');
      t.elapse(const Duration(milliseconds: 500)); // 2,1 s desde que volvio a escuchar
      expect(r.finales, ['listar clientes'], reason: 'se envia solo tras el silencio');
    });
  });

  test('telefono: un final vacio no borra lo que se vio como parcial ("juan Perez")', () {
    fakeAsync((t) {
      final r = _Registro();
      final d = r.dictado()..empezar();
      d.sistemaEscuchando();
      d.parcial('juan');
      d.parcial('juan Pérez');
      expect(r.parciales.last, 'juan Pérez');
      d.finalDeSesion(''); // Android entrega el final vacio
      d.sesionTerminada();
      d.sistemaEscuchando();
      d.error('error_speech_timeout'); // y despues nadie habla
      expect(r.finales, ['juan Pérez'], reason: 'antes: "" y el aviso "No te entendi"');
    });
  });

  test('telefono: una sesion que termina sin final conserva el parcial', () {
    fakeAsync((t) {
      final r = _Registro();
      final d = r.dictado()..empezar();
      d.sistemaEscuchando();
      d.parcial('crear producto');
      d.sesionTerminada(); // sin final
      expect(r.sesiones, 2, reason: 'hay algo dicho: sigue escuchando');
      d.sistemaEscuchando();
      t.elapse(silencio);
      expect(r.finales, ['crear producto']);
    });
  });

  test('telefono: si el sistema no vuelve a escuchar tras el reinicio, se entrega igual', () {
    fakeAsync((t) {
      final r = _Registro();
      final d = r.dictado()..empezar();
      d.sistemaEscuchando();
      d.finalDeSesion('listar pedidos');
      d.sesionTerminada(); // reinicia, pero nunca llega "listening"
      t.elapse(const Duration(seconds: 5));
      expect(r.finales, isEmpty);
      t.elapse(const Duration(seconds: 1));
      expect(r.finales, ['listar pedidos']);
    });
  });
}
