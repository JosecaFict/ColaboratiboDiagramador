package com.uagrm.colabmodel.collab;

import java.util.UUID;

public record LockMessage(UUID elementId, UUID userId, String userName, boolean held) {}
