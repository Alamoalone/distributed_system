package com.group10.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.group10.constants.CacheKey;
import com.group10.request.*;
import com.group10.component.PayFactory;
import com.group10.config.RabbitMQConfig;
import com.group10.vo.*;
import com.group10.constants.TimeConstant;
import com.group10.enums.*;
import com.group10.exception.BizException;
import com.group10.feign.CouponFeignSerivce;
import com.group10.feign.ProductFeignService;
import com.group10.feign.UserFeignService;
import com.group10.interceptor.LoginInterceptor;
import com.group10.mapper.ProductOrderItemMapper;
import com.group10.mapper.ProductOrderMapper;
import com.group10.model.LoginUser;
import com.group10.model.OrderMessage;
import com.group10.model.ProductOrderDO;
import com.group10.model.ProductOrderItemDO;
import com.group10.service.ProductOrderService;
import com.group10.util.CommonUtil;
import com.group10.util.JsonData;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ProductOrderServiceImpl implements ProductOrderService {

    @Autowired
    private ProductOrderMapper productOrderMapper;

    @Autowired
    private UserFeignService userFeignService;

    @Autowired
    private ProductFeignService productFeignService;

    @Autowired
    private CouponFeignSerivce couponFeignSerivce;

    @Autowired
    private ProductOrderItemMapper productOrderItemMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitMQConfig rabbitMQConfig;

    @Autowired
    private PayFactory payFactory;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    @Transactional
    public JsonData confirmOrder(ConfirmOrderRequest orderRequest) {
        LoginUser loginUser = LoginInterceptor.threadLocal.get();
        String orderToken = orderRequest.getToken();
        if (StringUtils.isBlank(orderToken)) {
            throw new BizException(BizCodeEnum.ORDER_CONFIRM_TOKEN_NOT_EXIST);
        }
        String script = "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end";
        Long result = redisTemplate.execute(new DefaultRedisScript<>(script, Long.class),
                Arrays.asList(String.format(CacheKey.SUBMIT_ORDER_TOKEN_KEY, loginUser.getId())), orderToken);
        if (result == 0L) {
            throw new BizException(BizCodeEnum.ORDER_CONFIRM_TOKEN_EQUAL_FAIL);
        }
        String orderOutTradeNo = CommonUtil.getStringNumRandom(32);
        ProductOrderAddressVO addressVO = this.getUserAddress(orderRequest.getAddressId());
        log.info("收货地址信息:{}", addressVO);
        List<Long> productIdList = orderRequest.getProductIdList();
        JsonData cartItemDate = productFeignService.confirmOrderCartItem(productIdList);
        List<OrderItemVO> orderItemList = cartItemDate.getData(new TypeReference<>() {});
        log.info("获取的商品:{}", orderItemList);
        if (orderItemList == null) {
            throw new BizException(BizCodeEnum.ORDER_CONFIRM_CART_ITEM_NOT_EXIST);
        }
        this.checkPrice(orderItemList, orderRequest);
        if (orderRequest.getCouponRecordId() != null) {
            this.lockCouponRecords(orderRequest, orderOutTradeNo);
        }
        this.lockProductStocks(orderItemList, orderOutTradeNo);
        ProductOrderDO productOrderDO = this.saveProductOrder(orderRequest, loginUser, orderOutTradeNo, addressVO);
        this.saveProductOrderItems(orderOutTradeNo, productOrderDO.getId(), orderItemList);
        OrderMessage orderMessage = new OrderMessage();
        orderMessage.setOutTradeNo(orderOutTradeNo);
        rabbitTemplate.convertAndSend(rabbitMQConfig.getEventExchange(), rabbitMQConfig.getOrderCloseDelayRoutingKey(), orderMessage);
        PayInfoVO payInfoVO = new PayInfoVO(orderOutTradeNo, productOrderDO.getPayAmount(), orderRequest.getPayType(), orderRequest.getClientType(), orderItemList.get(0).getProductTitle(), "", TimeConstant.ORDER_PAY_TIMEOUT_MILLS);
        String payResult = payFactory.pay(payInfoVO);
        if (StringUtils.isNotBlank(payResult)) {
            log.info("Create Payment Order Successfully:payInfoVO={},payResult={}", payInfoVO, payResult);
            return JsonData.buildSuccess(payResult);
        } else {
            log.error("Failed to create payment order:payInfoVO={},payResult={}", payInfoVO, payResult);
            return JsonData.buildResult(BizCodeEnum.PAY_ORDER_FAIL);
        }
    }

    private void saveProductOrderItems(String orderOutTradeNo, Long orderId, List<OrderItemVO> orderItemList) {
        List<ProductOrderItemDO> list = orderItemList.stream().map(
                obj -> {
                    ProductOrderItemDO itemDO = new ProductOrderItemDO();
                    itemDO.setBuyNum(obj.getBuyNum());
                    itemDO.setProductId(obj.getProductId());
                    itemDO.setProductImg(obj.getProductImg());
                    itemDO.setProductName(obj.getProductTitle());
                    itemDO.setOutTradeNo(orderOutTradeNo);
                    itemDO.setCreateTime(new Date());
                    itemDO.setAmount(obj.getAmount());
                    itemDO.setTotalAmount(obj.getTotalAmount());
                    itemDO.setProductOrderId(orderId);
                    return itemDO;
                }
        ).collect(Collectors.toList());
        productOrderItemMapper.insertBatch(list);
    }

    private ProductOrderDO saveProductOrder(ConfirmOrderRequest orderRequest, LoginUser loginUser, String orderOutTradeNo, ProductOrderAddressVO addressVO) {
        ProductOrderDO productOrderDO = new ProductOrderDO();
        productOrderDO.setUserId(loginUser.getId());
        productOrderDO.setHeadImg(loginUser.getAvatar());
        productOrderDO.setNickname(loginUser.getName());
        productOrderDO.setOutTradeNo(orderOutTradeNo);
        productOrderDO.setCreateTime(new Date());
        productOrderDO.setDel(0);
        productOrderDO.setOrderType(ProductOrderTypeEnum.DAILY.name());
        productOrderDO.setPayAmount(orderRequest.getRealPayAmount());
        productOrderDO.setTotalAmount(orderRequest.getTotalAmount());
        productOrderDO.setState(ProductOrderStateEnum.NEW.name());
        productOrderDO.setPayType(ProductOrderPayTypeEnum.valueOf(orderRequest.getPayType()).name());
        productOrderDO.setReceiverAddress(JSON.toJSONString(addressVO));
        productOrderMapper.insert(productOrderDO);
        return productOrderDO;
    }

    private void lockProductStocks(List<OrderItemVO> orderItemList, String orderOutTradeNo) {
        List<OrderItemRequest> itemRequestList = orderItemList.stream().map(obj -> {
            OrderItemRequest request = new OrderItemRequest();
            request.setBuyNum(obj.getBuyNum());
            request.setProductId(obj.getProductId());
            return request;
        }).collect(Collectors.toList());
        LockProductRequest lockProductRequest = new LockProductRequest();
        lockProductRequest.setOrderOutTradeNo(orderOutTradeNo);
        lockProductRequest.setOrderItemList(itemRequestList);
        JsonData jsonData = productFeignService.lockProductStock(lockProductRequest);
        if (jsonData.getCode() != 0) {
            log.error("Failed to lock item inventory：{}", lockProductRequest);
            throw new BizException(BizCodeEnum.ORDER_CONFIRM_LOCK_PRODUCT_FAIL);
        }
    }

    private void lockCouponRecords(ConfirmOrderRequest orderRequest, String orderOutTradeNo) {
        List<Long> lockCouponRecordIds = new ArrayList<>();
        if (orderRequest.getCouponRecordId() > 0) {
            lockCouponRecordIds.add(orderRequest.getCouponRecordId());
            LockCouponRecordRequest lockCouponRecordRequest = new LockCouponRecordRequest();
            lockCouponRecordRequest.setOrderOutTradeNo(orderOutTradeNo);
            lockCouponRecordRequest.setLockCouponRecordIds(lockCouponRecordIds);
            JsonData jsonData = couponFeignSerivce.lockCouponRecords(lockCouponRecordRequest);
            if (jsonData.getCode() != 0) {
                throw new BizException(BizCodeEnum.COUPON_RECORD_LOCK_FAIL);
            }
        }
    }

    private void checkPrice(List<OrderItemVO> orderItemList, ConfirmOrderRequest orderRequest) {
        BigDecimal realPayAmount = new BigDecimal("0");
        if (orderItemList != null) {
            for (OrderItemVO orderItemVO : orderItemList) {
                BigDecimal itemRealPayAmount = orderItemVO.getTotalAmount();
                realPayAmount = realPayAmount.add(itemRealPayAmount);
            }
        }
        CouponRecordVO couponRecordVO = getCartCouponRecord(orderRequest.getCouponRecordId());
        if (couponRecordVO != null) {
            if (realPayAmount.compareTo(couponRecordVO.getConditionPrice()) < 0) {
                throw new BizException(BizCodeEnum.ORDER_CONFIRM_COUPON_FAIL);
            }
            if (couponRecordVO.getPriceDeducted().compareTo(realPayAmount) > 0) {
                realPayAmount = BigDecimal.ZERO;
            } else {
                realPayAmount = realPayAmount.subtract(couponRecordVO.getPriceDeducted());
            }
        }
        if (realPayAmount.compareTo(orderRequest.getRealPayAmount()) != 0) {
            log.error("订单验价失败：{}", orderRequest);
            throw new BizException(BizCodeEnum.ORDER_CONFIRM_PRICE_FAIL);
        }
    }

    private CouponRecordVO getCartCouponRecord(Long couponRecordId) {
        if (couponRecordId == null || couponRecordId < 0) {
            return null;
        }
        JsonData couponData = couponFeignSerivce.findUserCouponRecordById(couponRecordId);
        if (couponData.getCode() != 0) {
            throw new BizException(BizCodeEnum.ORDER_CONFIRM_COUPON_FAIL);
        }
        ObjectMapper mapper = new ObjectMapper();
        CouponRecordVO couponRecordVO = mapper.convertValue(couponData.getData(), CouponRecordVO.class);
        if (!couponAvailable(couponRecordVO)) {
            log.error("Failed to use coupon");
            throw new BizException(BizCodeEnum.COUPON_UNAVAILABLE);
        }
        return couponRecordVO;
    }

    private boolean couponAvailable(CouponRecordVO couponRecordVO) {
        if (couponRecordVO.getUsageState().equalsIgnoreCase(CouponStateEnum.NEW.name())) {
            long currentTimestamp = CommonUtil.getCurrentTimestamp();
            long end = couponRecordVO.getValidUntil().getTime();
            long start = couponRecordVO.getValidFrom().getTime();
            if (currentTimestamp >= start && currentTimestamp <= end) {
                return true;
            }
        }
        return false;
    }

    private ProductOrderAddressVO getUserAddress(long addressId) {
        JsonData addressData = userFeignService.detail(addressId);
        if (addressData.getCode() != 0) {
            log.error("Failed to get harvest address,msg:{}", addressData);
            throw new BizException(BizCodeEnum.ADDRESS_NO_EXITS);
        }
        ObjectMapper mapper = new ObjectMapper();
        return mapper.convertValue(addressData.getData(), ProductOrderAddressVO.class);
    }

    @Override
    public String queryProductOrderState(String outTradeNo) {
        ProductOrderDO productOrderDO = productOrderMapper.selectOne(new QueryWrapper<ProductOrderDO>().eq("out_trade_no", outTradeNo));
        if (productOrderDO == null) {
            return "";
        } else {
            return productOrderDO.getState();
        }
    }

    @Override
    public boolean closeProductOrder(OrderMessage orderMessage) {
        ProductOrderDO productOrderDO = productOrderMapper.selectOne(new QueryWrapper<ProductOrderDO>().eq("out_trade_no", orderMessage.getOutTradeNo()));
        if (productOrderDO == null) {
            log.warn("Direct confirmation message, order does not exist:{}", orderMessage);
            return true;
        }
        if (productOrderDO.getState().equalsIgnoreCase(ProductOrderStateEnum.PAY.name())) {
            log.info("Direct confirmation message that the order has been paid:{}", orderMessage);
            return true;
        }
        PayInfoVO payInfoVO = new PayInfoVO();
        payInfoVO.setPayType(productOrderDO.getPayType());
        payInfoVO.setOutTradeNo(orderMessage.getOutTradeNo());
        String payResult = "success";
        if (StringUtils.isBlank(payResult)) {
            productOrderMapper.updateOrderPayState(productOrderDO.getOutTradeNo(), ProductOrderStateEnum.CANCEL.name(), ProductOrderStateEnum.NEW.name());
            log.info("If the result is null, the payment is not successful and the order is canceled locally:{}", orderMessage);
            return true;
        } else {
            log.warn("Payment is successful, the initiative to change the order status to UI on the payment, the cause of the situation may be a problem with the payment channel callbacks:{}", orderMessage);
            productOrderMapper.updateOrderPayState(productOrderDO.getOutTradeNo(), ProductOrderStateEnum.PAY.name(), ProductOrderStateEnum.NEW.name());
            return true;
        }
    }

    @Override
    public JsonData handlerOrderCallbackMsg(ProductOrderPayTypeEnum payType, Map<String, String> paramsMap) {
        if (payType.name().equalsIgnoreCase(ProductOrderPayTypeEnum.ALIPAY.name())) {
            String outTradeNo = paramsMap.get("out_trade_no");
            String tradeStatus = paramsMap.get("trade_status");
            if ("TRADE_SUCCESS".equalsIgnoreCase(tradeStatus) || "TRADE_FINISHED".equalsIgnoreCase(tradeStatus)) {
                productOrderMapper.updateOrderPayState(outTradeNo, ProductOrderStateEnum.PAY.name(), ProductOrderStateEnum.NEW.name());
                return JsonData.buildSuccess();
            }
        } else if (payType.name().equalsIgnoreCase(ProductOrderPayTypeEnum.WECHAT.name())) {
        }
        return JsonData.buildResult(BizCodeEnum.PAY_ORDER_CALLBACK_NOT_SUCCESS);
    }

    @Override
    public Map<String, Object> page(int page, int size, String state) {
        LoginUser loginUser = LoginInterceptor.threadLocal.get();
        Page<ProductOrderDO> pageInfo = new Page<>(page, size);
        IPage<ProductOrderDO> orderDOPage = null;
        if (StringUtils.isBlank(state)) {
            orderDOPage = productOrderMapper.selectPage(pageInfo, new QueryWrapper<ProductOrderDO>().eq("user_id", loginUser.getId()));
        } else {
            orderDOPage = productOrderMapper.selectPage(pageInfo, new QueryWrapper<ProductOrderDO>().eq("user_id", loginUser.getId()).eq("state", state));
        }
        List<ProductOrderDO> productOrderDOList = orderDOPage.getRecords();
        List<ProductOrderVO> productOrderVOList = productOrderDOList.stream().map(orderDO -> {
            List<ProductOrderItemDO> itemDOList = productOrderItemMapper.selectList(new QueryWrapper<ProductOrderItemDO>().eq("product_order_id", orderDO.getId()));
            List<OrderItemVO> itemVOList = itemDOList.stream().map(item -> {
                OrderItemVO itemVO = new OrderItemVO();
                BeanUtils.copyProperties(item, itemVO);
                return itemVO;
            }).collect(Collectors.toList());
            ProductOrderVO productOrderVO = new ProductOrderVO();
            BeanUtils.copyProperties(orderDO, productOrderVO);
            productOrderVO.setOrderItemList(itemVOList);
            return productOrderVO;
        }).collect(Collectors.toList());
        Map<String, Object> pageMap = new HashMap<>(3);
        pageMap.put("total_record", orderDOPage.getTotal());
        pageMap.put("total_page", orderDOPage.getPages());
        pageMap.put("current_data", productOrderVOList);
        return pageMap;
    }

    @Override
    @Transactional
    public JsonData repay(RepayOrderRequest repayOrderRequest) {
        LoginUser loginUser = LoginInterceptor.threadLocal.get();
        ProductOrderDO productOrderDO = productOrderMapper.selectOne(new QueryWrapper<ProductOrderDO>().eq("out_trade_no", repayOrderRequest.getOutTradeNo()).eq("user_id", loginUser.getId()));
        log.info("订单状态:{}", productOrderDO);
        if (productOrderDO == null) {
            return JsonData.buildResult(BizCodeEnum.PAY_ORDER_NOT_EXIST);
        }
        if (!productOrderDO.getState().equalsIgnoreCase(ProductOrderStateEnum.NEW.name())) {
            return JsonData.buildResult(BizCodeEnum.PAY_ORDER_STATE_ERROR);
        } else {
            long orderLiveTime = CommonUtil.getCurrentTimestamp() - productOrderDO.getCreateTime().getTime();
            orderLiveTime = orderLiveTime + 70 * 1000;
            if (orderLiveTime > TimeConstant.ORDER_PAY_TIMEOUT_MILLS) {
                return JsonData.buildResult(BizCodeEnum.PAY_ORDER_PAY_TIMEOUT);
            } else {
                long timeout = TimeConstant.ORDER_PAY_TIMEOUT_MILLS - orderLiveTime;
                PayInfoVO payInfoVO = new PayInfoVO(productOrderDO.getOutTradeNo(),
                        productOrderDO.getPayAmount(), repayOrderRequest.getPayType(),
                        repayOrderRequest.getClientType(), productOrderDO.getOutTradeNo(), "", timeout);
                log.info("payInfoVO={}", payInfoVO);
                String payResult = payFactory.pay(payInfoVO);
                if (StringUtils.isNotBlank(payResult)) {
                    log.info("Create secondary payment order successfully:payInfoVO={},payResult={}", payInfoVO, payResult);
                    return JsonData.buildSuccess(payResult);
                } else {
                    log.error("Create secondary payment order fail:payInfoVO={},payResult={}", payInfoVO, payResult);
                    return JsonData.buildResult(BizCodeEnum.PAY_ORDER_FAIL);
                }
            }
        }
    }
}
