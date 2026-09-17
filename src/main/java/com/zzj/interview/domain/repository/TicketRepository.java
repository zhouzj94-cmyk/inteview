package com.zzj.interview.domain.repository;

import com.zzj.interview.domain.model.ticket.Ticket;
import com.zzj.interview.domain.model.ticket.TicketAiRecord;
import com.zzj.interview.domain.model.ticket.TicketStatus;

import java.util.List;

/**
 * 工单仓储接口（Domain层定义）
 *
 * 关键设计：
 * - 接口定义在Domain层，实现在Infrastructure层
 * - 这就是"依赖倒置"：Domain不依赖MyBatis/MySQL，而是Infrastructure依赖Domain
 * - Domain只关心"我需要保存和查询工单"，不关心"怎么存"
 *
 * 面试话术：
 * "仓储接口在领域层定义，基础设施层实现，这样切换存储引擎不影响领域模型"
 */
public interface TicketRepository {

    void save(Ticket ticket);

    void update(Ticket ticket);

    Ticket findById(String id);

    List<Ticket> findByStatus(TicketStatus status);

    void saveAiRecord(TicketAiRecord record);
}
