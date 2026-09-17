package com.zzj.interview.infrastructure.persistence.mapper;

import com.zzj.interview.infrastructure.persistence.entity.OrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单Mapper（模拟业务数据源）
 *
 * 供 query_order / cancel_order / list_orders 工具读写真实 `order` 表。
 */
@Mapper
public interface OrderMapper {

    /**
     * 按订单号查询，不存在返回null
     */
    OrderEntity selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 按客户ID查询其全部订单（按下单时间升序），无订单返回空列表
     */
    List<OrderEntity> selectByCustomerId(@Param("customerId") String customerId);

    /**
     * 更新订单状态，返回受影响行数
     */
    int updateStatusByOrderNo(@Param("orderNo") String orderNo, @Param("status") String status);
}
