package com.rigour.integration.api.v1.model;

import java.time.Instant;

public record ShippingAddressQueryCommand(Integer begin, Integer step, String addressAbout,
                                          String clientGuid, String isDefault,
                                          Instant updatedFrom, Instant updatedTo) {
}
