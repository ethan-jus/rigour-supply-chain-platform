package com.rigour.merchant.api.v1.model;
import java.util.UUID;
public record CustomerShippingAddressView(UUID id, String consignee, String contact, String phone,
        String regionText, String addressDetail, String fullAddress, boolean defaultAddress, Long revision) {}
