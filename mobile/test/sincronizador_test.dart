import 'package:colabmodel_app/visor/modelo.dart';
import 'package:colabmodel_app/visor/sincronizador.dart';
import 'package:flutter_test/flutter_test.dart';

SnapshotDiagrama snapshot({int seq = 10}) => SnapshotDiagrama(crudo: {
      'id': 'd',
      'name': 'Biblioteca',
      'currentSeq': seq,
      'defaultView': 'CLASS',
      'classes': [
        {
          'id': 'libro',
          'diagramId': 'd',
          'name': 'Libro',
          'stereotype': null,
          'isAbstract': false,
          'weak': false,
          'erKind': 'STRONG',
          'inheritanceStrategy': 'JOINED',
          'attributes': [
            {'id': 'a1', 'classId': 'libro', 'name': 'titulo', 'dataType': 'string', 'visibility': 'PRIVATE'},
          ],
          'methods': [],
        },
      ],
      'relationships': [],
      'layout': [
        {'elementId': 'libro', 'x': 10, 'y': 20, 'width': 170, 'height': 60, 'waypoints': null},
      ],
    });

OperacionAplicada renombrar(int seq, String nombre) => OperacionAplicada(
      opId: 'o$seq',
      seq: seq,
      type: 'RENAME_CLASS',
      payload: {'classId': 'libro', 'name': nombre},
    );

void main() {
  test('arranca del snapshot: estado y seq', () {
    final s = Sincronizador(snapshot());
    expect(s.ultimoSeq, 10);
    expect(s.estado.classes.single.name, 'Libro');
    expect(s.estado.layout['libro']!.x, 10);
  });

  test('la operacion siguiente se aplica', () {
    final s = Sincronizador(snapshot());
    expect(s.recibir(renombrar(11, 'Libros')), ResultadoOperacion.aplicada);
    expect(s.ultimoSeq, 11);
    expect(s.estado.classes.single.name, 'Libros');
  });

  test('una repetida o vieja se ignora', () {
    final s = Sincronizador(snapshot());
    s.recibir(renombrar(11, 'A'));
    expect(s.recibir(renombrar(11, 'B')), ResultadoOperacion.duplicada);
    expect(s.recibir(renombrar(5, 'C')), ResultadoOperacion.duplicada);
    expect(s.estado.classes.single.name, 'A');
  });

  test('un hueco no se aplica, se guarda y avisa que hay que pedir lo que falta', () {
    final s = Sincronizador(snapshot());
    expect(s.recibir(renombrar(13, 'Tres')), ResultadoOperacion.hueco);
    expect(s.hayHueco, isTrue);
    expect(s.ultimoSeq, 10);
    expect(s.estado.classes.single.name, 'Libro');
  });

  test('el catch-up llena el hueco y despues aplica lo que estaba guardado', () {
    final s = Sincronizador(snapshot());
    s.recibir(renombrar(13, 'Tres'));
    final n = s.aplicarCatchUp([renombrar(11, 'Uno'), renombrar(12, 'Dos')]);
    expect(n, 3);
    expect(s.ultimoSeq, 13);
    expect(s.estado.classes.single.name, 'Tres');
    expect(s.hayHueco, isFalse);
  });

  test('el catch-up llega desordenado y con repetidas: igual queda bien', () {
    final s = Sincronizador(snapshot());
    s.aplicarCatchUp([renombrar(12, 'Dos'), renombrar(5, 'viejo'), renombrar(11, 'Uno'), renombrar(11, 'Uno')]);
    expect(s.ultimoSeq, 12);
    expect(s.estado.classes.single.name, 'Dos');
  });

  test('un catch-up incompleto deja el hueco a la vista', () {
    final s = Sincronizador(snapshot());
    s.aplicarCatchUp([renombrar(12, 'Dos')]);
    expect(s.ultimoSeq, 10);
    expect(s.hayHueco, isTrue);
  });

  test('reiniciar descarta todo y vuelve al snapshot nuevo', () {
    final s = Sincronizador(snapshot());
    s.recibir(renombrar(13, 'Tres'));
    s.recibirBloqueo({'elementId': 'libro', 'userId': 'u', 'userName': 'Ana', 'held': true});
    s.reiniciar(snapshot(seq: 20));
    expect(s.ultimoSeq, 20);
    expect(s.hayHueco, isFalse);
    expect(s.bloqueos, isEmpty);
  });

  test('los bloqueos se toman y se sueltan', () {
    final s = Sincronizador(snapshot());
    s.recibirBloqueo({'elementId': 'libro', 'userId': 'u', 'userName': 'Ana', 'held': true});
    expect(s.quienEdita('libro'), 'Ana');
    s.recibirBloqueo({'elementId': 'libro', 'userId': null, 'userName': null, 'held': false});
    expect(s.quienEdita('libro'), isNull);
  });

  test('la presencia vence a los 30 segundos sin noticias', () {
    final s = Sincronizador(snapshot());
    final t0 = DateTime(2026, 1, 1, 12);
    s.recibirPresencia({'userId': 'u1', 'userName': 'Ana', 'x': 1, 'y': 2, 'selectedElementId': 'libro'}, t0);
    expect(s.presentes(t0.add(const Duration(seconds: 10))).map((p) => p.userName), ['Ana']);
    expect(s.presentes(t0.add(const Duration(seconds: 31))), isEmpty);
  });
}
