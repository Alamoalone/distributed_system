package com.group10.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Data
public class ProductOrderVO {

    private Long id;

    /**
     * Unique identifier for the order
     */
    private String outTradeNo;

    /**
     * NEW: unpaid order, PAY: paid order, CANCEL: order cancelled due to timeout
     */
    private String state;

    /**
     * Order generation time
     */
    private Date createTime;

    /**
     * Total amount of the order
     */
    private BigDecimal totalAmount;

    /**
     * Actual amount paid for the order
     */
    private BigDecimal payAmount;

    /**
     * Payment type: WeChat - Bank - Alipay
     */
    private String payType;

    /**
     * Nickname
     */
    private String nickname;

    /**
     * Avatar
     */
    private String headImg;

    /**
     * User ID
     */
    private Long userId;

    /**
     * 0 indicates not deleted, 1 indicates deleted
     */
    private Integer del;

    /**
     * Update time
     */
    private Date updateTime;

    /**
     * Order type: DAILY for regular order, PROMOTION for promotional order
     */
    private String orderType;

    /**
     * Shipping address stored in JSON format
     */
    private String receiverAddress;

    /**
     * Order items
     */
    private List<OrderItemVO> orderItemList;
}
