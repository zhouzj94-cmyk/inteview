package com.zzj.interview.interfaces.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 工单响应DTO
 */
@Getter
@Setter
public class TicketResponse {

    private String ticketId;
    private String status;
    private String intent;
    private String response;
}
