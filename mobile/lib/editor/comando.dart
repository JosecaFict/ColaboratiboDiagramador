/// Espejo del catálogo Java y TypeScript. Las claves viajan sin renombrar.
enum CommandType {
  CREATE_CLASS,
  RENAME_CLASS,
  DELETE_CLASS,
  ADD_ATTRIBUTE,
  UPDATE_ATTRIBUTE,
  REMOVE_ATTRIBUTE,
  ADD_METHOD,
  REMOVE_METHOD,
  UPDATE_METHOD,
  CREATE_RELATION,
  UPDATE_RELATION,
  DELETE_RELATION,
  SET_ER_FLAG,
  SET_MODELING_DECISION,
  CONFIRM_SCHEMA,
  MOVE_ELEMENT,
  RESIZE_ELEMENT,
  BATCH,
}

enum Origin { MOUSE, TEXT, VOICE, IMAGE, XMI_IMPORT }

enum RejectionReason { LOCKED_BY_OTHER, STALE, INVALID, FORBIDDEN, NOT_FOUND }

class Comando {
  final String opId;
  final String diagramId;
  final int baseSeq;
  final CommandType type;
  final Origin origin;
  final Map<String, dynamic> payload;
  Comando(
      {required this.opId,
      required this.diagramId,
      required this.baseSeq,
      required this.type,
      required this.origin,
      required Map<String, dynamic> payload})
      : payload = Map.unmodifiable(payload);
  Map<String, dynamic> aJson() => {
        'opId': opId,
        'diagramId': diagramId,
        'baseSeq': baseSeq,
        'type': type.name,
        'origin': origin.name,
        'payload': payload,
      };
}

class RechazoComando {
  final String opId;
  final RejectionReason reason;
  final String message;
  const RechazoComando(
      {required this.opId, required this.reason, required this.message});
  factory RechazoComando.desdeJson(Map<String, dynamic> json) => RechazoComando(
        opId: json['opId'] as String,
        reason: RejectionReason.values.byName(json['reason'] as String),
        message: json['message'] as String,
      );
}
