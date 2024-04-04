package com.group10.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ProductOrderAddressVO {

    private Long id;

    /**
     * User ID
     */
    private Long userId;

    /**
     * Whether it is the default shipping address: 0->No; 1->Yes
     */
    @JsonProperty("default_status")
    private Integer defaultStatus;

    /**
     * Recipient's name
     */
    @JsonProperty("receiver_name")
    private String receiverName;

    /**
     * Recipient's phone number
     */
    private String phone;

    /**
     * Province/city directly under the central government
     */
    private String state;

    /**
     * City
     */
    private String city;

    /**
     * District
     */
    private String district;

    /**
     * Detailed address
     */
    @JsonProperty("detailed_address")
    private String detailedAddress;
}
