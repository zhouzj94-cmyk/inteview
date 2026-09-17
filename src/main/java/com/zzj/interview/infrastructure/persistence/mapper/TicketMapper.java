package com.zzj.interview.infrastructure.persistence.mapper;

import com.zzj.interview.domain.model.ticket.Ticket;
import com.zzj.interview.domain.model.ticket.TicketAiRecord;
import com.zzj.interview.domain.model.ticket.TicketStatus;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * MyBatis Mapper接口
 * 仅定义数据库操作，SQL在XML中编写
 */
@Mapper
public interface TicketMapper {

    void insertTicket(Ticket ticket);

    void updateTicket(Ticket ticket);

    Ticket selectById(String id);

    List<Ticket> selectByStatus(TicketStatus status);

    void insertAiRecord(TicketAiRecord record);
}
