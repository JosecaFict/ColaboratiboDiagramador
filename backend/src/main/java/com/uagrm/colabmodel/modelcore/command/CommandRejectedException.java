package com.uagrm.colabmodel.modelcore.command;

import java.util.UUID;

public class CommandRejectedException extends RuntimeException {

    private final UUID opId;
    private final RejectionReason reason;

    public CommandRejectedException(UUID opId, RejectionReason reason, String message) {
        super(message);
        this.opId = opId;
        this.reason = reason;
    }

    public UUID getOpId() {
        return opId;
    }

    public RejectionReason getReason() {
        return reason;
    }

    public CommandRejection toRejection() {
        return new CommandRejection(opId, reason, getMessage());
    }
}
