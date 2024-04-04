package com.group10.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PayInfoVO {

    /**
     * Order number
     */
    private String outTradeNo;

    /**
     * Total amount of the order
     */
    private BigDecimal payFee;

    /**
     * Payment type: WeChat - Alipay - Bank - Other
     */
    private String payType;

    /**
     * Client type: APP/H5/PC
     */
    private String clientType;

    /**
     * Title
     */
    private String title;

    /**
     * Description
     */
    private String description;

    /**
     * Order payment timeout, in milliseconds
     */
    private long orderPayTimeoutMills;
}
