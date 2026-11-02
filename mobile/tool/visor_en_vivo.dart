// Prueba manual del visor en vivo (ADR-036) SIN telefono: hace el mismo flujo
// que la app y escribe lo que va recibiendo.
//
//   dart run tool/visor_en_vivo.dart <host> <email> <password> [diagramId] [--segundos=60] [--pausa=0]
//
// Necesita la herramienta arriba. Mientras corre, edita el diagrama en la web
// (o con otro cliente): cada operacion aparece aca con su seq y el estado
// resultante. Es solo lectura: no envia nada a /app/...
import 'dart:async';

import 'package:colabmodel_app/visor/aplicar_operacion.dart';
import 'package:colabmodel_app/visor/cliente_herramienta.dart';
import 'package:colabmodel_app/visor/conexion_en_vivo.dart';
import 'package:colabmodel_app/visor/sincronizador.dart';

Future<void> main(List<String> args) async {
  final banderas = args.where((a) => a.startsWith('--')).toList();
  final pos = args.where((a) => !a.startsWith('--')).toList();
  if (pos.length < 3) {
    print('uso: dart run tool/visor_en_vivo.dart <host> <email> <password> [diagramId] [--segundos=60] [--pausa=0]');
    return;
  }
  int bandera(String nombre, int porDefecto) =>
      int.tryParse(banderas.firstWhere((b) => b.startsWith('--$nombre='), orElse: () => '--$nombre=').split('=')[1]) ??
      porDefecto;
  final segundos = bandera('segundos', 60);
  // --pausa=N: espera N segundos entre bajar el snapshot y abrir el canal. Lo que se
  // edite en ese lapso solo se puede recuperar con ?sinceSeq=, que es lo que se prueba.
  final pausa = bandera('pausa', 0);

  final cliente = ClienteHerramienta(pos[0]);
  final sesion = await cliente.iniciarSesion(pos[1], pos[2]);
  print('login ok: ${sesion.nombre}');

  var diagramId = pos.length > 3 ? pos[3] : null;
  if (diagramId == null) {
    final proyectos = await cliente.proyectos();
    print('proyectos: ${proyectos.map((p) => p.nombre).join(', ')}');
    for (final p in proyectos) {
      final ds = await cliente.diagramasDe(p.id);
      if (ds.isNotEmpty) {
        diagramId = ds.first.id;
        print('diagrama elegido: ${ds.first.nombre} (${p.nombre}) seq=${ds.first.currentSeq}');
        break;
      }
    }
  }
  if (diagramId == null) {
    print('no hay ningun diagrama para mirar');
    return;
  }

  final snapshot = await cliente.snapshot(diagramId);
  final sync = Sincronizador(snapshot);
  void resumen(String donde) => print('  [$donde] seq=${sync.ultimoSeq} clases=${sync.estado.classes.map((c) => c.name).toList()} '
      'relaciones=${sync.estado.relationships.length} bloqueos=${sync.bloqueos.length}');
  print('snapshot: seq=${snapshot.currentSeq}');
  resumen('inicio');

  if (pausa > 0) {
    print('pausa de $pausa s antes de abrir el canal (edita ahora)...');
    await Future<void>.delayed(Duration(seconds: pausa));
  }

  var recibidas = 0;
  var enCatchUp = false;
  Future<void> ponerseAlDia(String motivo) async {
    if (enCatchUp) return;
    enCatchUp = true;
    try {
      final ops = await cliente.operacionesDesde(diagramId!, sync.ultimoSeq);
      final n = sync.aplicarCatchUp(ops);
      print('catch-up ($motivo): ${ops.length} operaciones pedidas, $n aplicadas');
      resumen('catch-up');
    } finally {
      enCatchUp = false;
    }
  }

  final conexion = ConexionEnVivo(
    url: cliente.urlStomp,
    token: cliente.token!,
    diagramId: diagramId,
    alOperacion: (m) {
      final op = operacionDesdeJson(m);
      if (op == null) return;
      recibidas++;
      final r = sync.recibir(op);
      print('OP seq=${op.seq} ${op.type} -> ${r.name}');
      resumen('op');
      if (r == ResultadoOperacion.hueco) unawaited(ponerseAlDia('hueco'));
    },
    alBloqueo: (m) {
      sync.recibirBloqueo(m);
      print('LOCK ${m['held'] == true ? 'tomado' : 'liberado'} ${m['elementId']} por ${m['userName']}');
    },
    alPresencia: (m) {
      sync.recibirPresencia(m, DateTime.now());
      print('PRESENCIA ${m['userName']} (${sync.presentes(DateTime.now()).length} presentes)');
    },
    alConectar: () {
      print('canal STOMP (/ws, SockJS) conectado y suscrito');
      unawaited(ponerseAlDia('conexion'));
    },
    alCaer: (motivo) => print('canal caido: $motivo'),
  )..conectar();

  print('escuchando $segundos s... (edita el diagrama en la web)');
  await Future<void>.delayed(Duration(seconds: segundos));
  conexion.cerrar();
  print('fin: $recibidas operaciones por el canal, seq final ${sync.ultimoSeq}');
  resumen('final');
  cliente.cerrar();
}
