package com.ainative.conversation.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class PlatformTurnVO {
    private Long id;
    private String direction;
    private String content;
    private java.util.List<com.ainative.conversation.PlatformConversationImageService.Image> images;
    private String status;
    private String error;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private com.ainative.conversation.ConversationTurnDisplay display;
    private Date gmtCreate;
}
