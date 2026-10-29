package com.ims.common.events;

import java.util.UUID;

public record OrderLine(UUID productId, int quantity) {
}
