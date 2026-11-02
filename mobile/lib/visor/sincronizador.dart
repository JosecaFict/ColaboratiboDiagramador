import 'aplicar_operacion.dart';
import 'modelo.dart';

enum ResultadoOperacion {
  /// Era la siguiente: se aplico.
  aplicada,

  /// Ya estaba aplicada (seq <= ultimo): se ignora.
  duplicada,

  /// Falta alguna anterior: se guardo y hay que pedir `?sinceSeq=`.
  hueco,
}

class Bloqueo {
  final String elementId;
  final String? userId;
  final String? userName;
  const Bloqueo(this.elementId, this.userId, this.userName);
}

class Presencia {
  final String userId;
  final String userName;
  final String? elementoSeleccionado;
  final DateTime visto;
  const Presencia(this.userId, this.userName, this.elementoSeleccionado, this.visto);
}

/// Cabeza de lectura del visor: el estado del diagrama, el ultimo `seq`
/// aplicado, los bloqueos y la presencia. Sin red ni widgets: se prueba solo.
///
/// Regla del orden (ADR-005): el `seq` lo asigna el servidor y es contiguo.
/// Una operacion con `seq` mayor que `ultimoSeq + 1` NO se aplica: se guarda y
/// se pide lo que falta, porque aplicarla saltandose una daria un modelo que el
/// servidor nunca tuvo.
class Sincronizador {
  EstadoModelo _estado;
  int _ultimoSeq;
  final Map<int, OperacionAplicada> _pendientes = {};
  final Map<String, Bloqueo> _bloqueos = {};
  final Map<String, Presencia> _presentes = {};

  /// Cuanto tiempo se considera "conectado" a quien mando presencia por ultima vez.
  /// El servidor no avisa cuando alguien se va: solo difunde cursores.
  static const ventanaPresencia = Duration(seconds: 30);

  Sincronizador(SnapshotDiagrama snapshot)
      : _estado = snapshot.aEstado(),
        _ultimoSeq = snapshot.currentSeq;

  EstadoModelo get estado => _estado;
  int get ultimoSeq => _ultimoSeq;
  bool get hayHueco => _pendientes.isNotEmpty;
  Map<String, Bloqueo> get bloqueos => Map.unmodifiable(_bloqueos);

  List<Presencia> presentes(DateTime ahora) => [
        for (final p in _presentes.values)
          if (ahora.difference(p.visto) <= ventanaPresencia) p,
      ];

  /// Reemplaza todo por un snapshot nuevo (resincronizacion completa).
  void reiniciar(SnapshotDiagrama snapshot) {
    _estado = snapshot.aEstado();
    _ultimoSeq = snapshot.currentSeq;
    _pendientes.clear();
    _bloqueos.clear();
  }

  ResultadoOperacion recibir(OperacionAplicada op) {
    if (op.seq <= _ultimoSeq) return ResultadoOperacion.duplicada;
    if (op.seq > _ultimoSeq + 1) {
      _pendientes[op.seq] = op;
      return ResultadoOperacion.hueco;
    }
    _aplicar(op);
    _vaciarPendientes();
    return ResultadoOperacion.aplicada;
  }

  /// Aplica lo que devolvio `?sinceSeq=`. Devuelve cuantas operaciones aplico.
  /// Si despues de esto todavia falta alguna, [hayHueco] sigue en true.
  int aplicarCatchUp(List<OperacionAplicada> ops) {
    final inicio = _ultimoSeq;
    final ordenadas = [...ops]..sort((a, b) => a.seq.compareTo(b.seq));
    for (final op in ordenadas) {
      if (op.seq > _ultimoSeq) _pendientes[op.seq] = op;
    }
    _vaciarPendientes();
    return _ultimoSeq - inicio;
  }

  void _aplicar(OperacionAplicada op) {
    _estado = aplicarOperacion(_estado, op);
    _ultimoSeq = op.seq;
    _pendientes.remove(op.seq);
  }

  void _vaciarPendientes() {
    _pendientes.removeWhere((seq, _) => seq <= _ultimoSeq);
    while (_pendientes.containsKey(_ultimoSeq + 1)) {
      _aplicar(_pendientes[_ultimoSeq + 1]!);
    }
  }

  /// `/topic/diagram/{id}/locks`: `held=false` libera el elemento.
  void recibirBloqueo(Map<String, dynamic> m) {
    final elementId = m['elementId'] as String?;
    if (elementId == null) return;
    if (m['held'] == true) {
      _bloqueos[elementId] = Bloqueo(elementId, m['userId'] as String?, m['userName'] as String?);
    } else {
      _bloqueos.remove(elementId);
    }
  }

  /// `/topic/diagram/{id}/presence`.
  void recibirPresencia(Map<String, dynamic> m, DateTime ahora) {
    final userId = m['userId'] as String?;
    if (userId == null) return;
    _presentes[userId] = Presencia(
      userId,
      (m['userName'] ?? 'Alguien') as String,
      m['selectedElementId'] as String?,
      ahora,
    );
  }

  /// Quien tiene tomado un elemento, o null.
  String? quienEdita(String elementId) => _bloqueos[elementId]?.userName;
}
