package com.planb.emotionfriend.api.learning;

import java.util.UUID;

/** Adapter must return actual bytes; registry hash is verified again before HTTP delivery.
 * No default provider, public URL, filesystem path or synthetic production fallback. */
public interface CoreMediaDelivery {
    byte[] read(UUID artifact);
}
