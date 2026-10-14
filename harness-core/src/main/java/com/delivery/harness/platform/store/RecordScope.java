package com.delivery.harness.platform.store;

import java.util.Set;

/**
 * Query scope supplied by an authorized caller; independent of any identity provider or role model.
 */
public interface RecordScope {
    String tenantId();

    Set<String> cityCodes();

    Set<String> stationIds();
}
