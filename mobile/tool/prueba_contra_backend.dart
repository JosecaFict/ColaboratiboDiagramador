// Prueba manual de P3 contra un backend GENERADO de verdad.
//
//   dart run tool/prueba_contra_backend.dart http://localhost:8081
//
// No es parte de la suite: necesita un servidor arriba. Es lo que verifica el
// contrato P2 -> P3 (ADR-011) de punta a punta, que es justo lo que los tests
// con un cliente falso no pueden demostrar.
import 'package:colabmodel_app/data/cliente_backend.dart';

Future<void> main(List<String> args) async {
  final host = args.isEmpty ? 'http://localhost:8081' : args.first;
  final cliente = ClienteBackend(host);

  final manifiesto = await cliente.cargarManifiesto();
  print('app: ' + manifiesto.app + '  (manifiesto v' + manifiesto.version.toString() + ')');

  for (final entidad in manifiesto.entities) {
    print('');
    print(entidad.label + '  ->  ' + entidad.path + '   [' + entidad.actions.join(', ') + ']');

    if (!entidad.actions.contains('list')) {
      final motivo = entidad.isAbstract ? 'raiz abstracta' : 'sin accion de listado';
      print('  (no se lista: ' + motivo + ')');
      continue;
    }
    try {
      final filas = await cliente.listar(manifiesto, entidad);
      print('  ' + filas.length.toString() + ' registros');
      for (final fila in filas.take(3)) {
        print('    - ' + fila.toString());
      }
    } catch (e) {
      print('  ERROR: ' + e.toString());
    }
  }
  cliente.cerrar();
}
