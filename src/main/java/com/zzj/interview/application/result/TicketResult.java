package com.zzj.interview.application.result;

import com.zzj.interview.domain.model.ticket.Ticket;
import com.zzj.interview.domain.model.ticket.TicketIntent;
import com.zzj.interview.domain.model.ticket.TicketStatus;

/**
 * 工单处理结果
 * Application层输出，用于Interfaces层转换为DTO
 */
public class TicketResult {

    private String ticketId;
    private TicketIntent intent;
    private TicketStatus status;
    private String response;

    public static TicketResult from(Ticket ticket) {
        TicketResult result = new TicketResult();
        result.ticketId = ticket.getId();
        result.intent = ticket.getIntent();
        result.status = ticket.getStatus();
        result.response = ticket.getResponse();
        return result;
    }

    public String getTicketId() { return ticketId; }
    public TicketIntent getIntent() { return intent; }
    public TicketStatus getStatus() { return status; }
    public String getResponse() { return response; }
}
