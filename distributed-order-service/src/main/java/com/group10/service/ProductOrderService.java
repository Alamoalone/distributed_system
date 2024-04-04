package com.group10.service;

import com.group10.request.ConfirmOrderRequest;
import com.group10.request.RepayOrderRequest;
import com.group10.enums.ProductOrderPayTypeEnum;
import com.group10.model.OrderMessage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.group10.util.JsonData;

import java.util.Map;

/**
 * <p>
 * Service class
 * </p>
 *
 * @author zzf
 * @since 2023-09-16
 */
public interface ProductOrderService {
    /**
     * Create order
     *
     * @param orderRequest
     * @return
     */
    JsonData confirmOrder(ConfirmOrderRequest orderRequest);

    /**
     * Query order status
     *
     * @param outTradeNo
     * @return
     */
    String queryProductOrderState(String outTradeNo);

    /**
     * Queue listener, close order regularly
     *
     * @param orderMessage
     * @return
     */
    boolean closeProductOrder(OrderMessage orderMessage);

    /**
     * Payment result callback notification
     *
     * @param alipay
     * @param paramsMap
     * @return
     */
    JsonData handlerOrderCallbackMsg(ProductOrderPayTypeEnum alipay, Map<String, String> paramsMap);


    /**
     * Pagination query for my order list
     *
     * @param page
     * @param size
     * @param state
     * @return
     */
    Map<String, Object> page(int page, int size, String state);


    /**
     * Second payment for orders
     *
     * @param repayOrderRequest
     * @return
     */
    JsonData repay(RepayOrderRequest repayOrderRequest);
}
