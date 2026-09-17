package com.zzj.interview.infrastructure.persistence.repository;

import com.zzj.interview.domain.model.ticket.Ticket;
import com.zzj.interview.domain.model.ticket.TicketAiRecord;
import com.zzj.interview.domain.model.ticket.TicketStatus;
import com.zzj.interview.domain.repository.TicketRepository;
import com.zzj.interview.infrastructure.persistence.mapper.TicketMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 仓储实现（Infrastructure层）
 *
 * 实现Domain层定义的TicketRepository接口
 * 这就是"依赖倒置"的具体体现：
 * - Domain定义接口（我不关心你怎么存）
 * - Infrastructure实现接口（我来用MyBatis/MySQL实现）
 */
@Repository
public class TicketRepositoryImpl implements TicketRepository {

    private final TicketMapper ticketMapper;

    public TicketRepositoryImpl(TicketMapper ticketMapper) {
        this.ticketMapper = ticketMapper;
    }

    @Override
    @Transactional
    public void save(Ticket ticket) {
        Ticket existing = ticketMapper.selectById(ticket.getId());
        if (existing == null) {
            ticketMapper.insertTicket(ticket);
        } else {
            ticketMapper.updateTicket(ticket);
        }
    }

    @Override
    @Transactional
    public void update(Ticket ticket) {
        ticketMapper.updateTicket(ticket);
    }

    @Override
    public Ticket findById(String id) {
        return ticketMapper.selectById(id);
    }

    @Override
    public List<Ticket> findByStatus(TicketStatus status) {
        return ticketMapper.selectByStatus(status);
    }

    @Override
    @Transactional
    public void saveAiRecord(TicketAiRecord record) {
        ticketMapper.insertAiRecord(record);
    }
}
