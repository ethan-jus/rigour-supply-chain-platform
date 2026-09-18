package com.rigour.merchant.api.v1.model;
/** One customer-owned shipping address. Existing records require their own revision. */
public record CustomerShippingAddressCommand(String consignee, String contact, String phone,
        String regionText, String addressDetail, Boolean defaultAddress, Long revision) {}
