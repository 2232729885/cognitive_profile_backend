package com.idata.profile.common.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class StableUuidUtil {

    private StableUuidUtil() {
    }

    public static String fromSeed(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
