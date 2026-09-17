package com.zzj.interview.infrastructure.persistence.mapper;

import com.zzj.interview.infrastructure.persistence.entity.AfterSaleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 售后Mapper
 *
 * 供 create_after_sale / query_after_sale / process_refund 工具读写真实 `after_sale` 表。
 */
@Mapper
public interface AfterSaleMapper {

    /**
     * 插入一条售后记录，返回受影响行数
     */
    int insert(AfterSaleEntity entity);

    /**
     * 按订单号查询最近一条售后记录（按创建时间倒序取第一条），不存在返回null
     */
    AfterSaleEntity selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 按订单号更新售后状态，返回受影响行数
     */
    int updateStatusByOrderNo(@Param("orderNo") String orderNo, @Param("status") String status);
}
