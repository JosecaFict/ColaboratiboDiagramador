import 'dart:io';
import 'package:colabmodel_app/editor/comando.dart';
import 'package:flutter_test/flutter_test.dart';

List<String> valoresJava(String nombre) {
  final archivo = File(
      '../backend/src/main/java/com/uagrm/colabmodel/modelcore/command/$nombre.java');
  final texto = archivo
      .readAsStringSync()
      .replaceAll(RegExp(r'/\*[\s\S]*?\*/|//[^\n]*'), '');
  final cuerpo =
      RegExp('enum $nombre\\s*\\{([^}]*)\\}').firstMatch(texto)!.group(1)!;
  return cuerpo
      .split(',')
      .map((s) => s.trim())
      .where((s) => s.isNotEmpty)
      .toList();
}

void main() {
  test('CommandType es espejo exacto de Java', () {
    expect(CommandType.values.map((v) => v.name), valoresJava('CommandType'));
  });
  test('RejectionReason es espejo exacto de Java', () {
    expect(RejectionReason.values.map((v) => v.name),
        valoresJava('RejectionReason'));
  });
  test('Origin es espejo exacto de Java', () {
    expect(Origin.values.map((v) => v.name), valoresJava('Origin'));
  });
  test('serializa el sobre sin identidad aportada por el cliente', () {
    final c = Comando(
        opId: 'o',
        diagramId: 'd',
        baseSeq: 42,
        type: CommandType.RENAME_CLASS,
        origin: Origin.TEXT,
        payload: {'classId': 'c', 'name': 'Libro'});
    expect(c.aJson(), {
      'opId': 'o',
      'diagramId': 'd',
      'baseSeq': 42,
      'type': 'RENAME_CLASS',
      'origin': 'TEXT',
      'payload': {'classId': 'c', 'name': 'Libro'}
    });
    expect(() => c.payload['name'] = 'Otro', throwsUnsupportedError);
  });
  test('interpreta todos los rechazos con su opId', () {
    for (final r in RejectionReason.values) {
      final rechazo = RechazoComando.desdeJson(
          {'opId': 'o', 'reason': r.name, 'message': 'detalle'});
      expect(rechazo.opId, 'o');
      expect(rechazo.reason, r);
      expect(rechazo.message, 'detalle');
    }
  });
}
