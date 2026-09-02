package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("cfg_template_channel")
public class TemplateChannelVariant {

    @TableId(value = "channel_variant_id", type = IdType.AUTO)
    private Long id;

    @TableField("template_id")
    private Long templateHeaderId;

    @TableField("channel_code")
    private String channel;

    @TableField("message_type")
    private String messageType;

    @TableField("title")
    private String subject;

    @TableField("richtext_content")
    private String content;

    @TableField("background_assetid")
    private String backgroundImageUrl;

    @TableField("design_json")
    private String designJson;

    @TableField("channel_payload_json")
    private String channelPayloadJson;

    @TableField("tokens_json")
    private String tokensJson;

    private String status;

    @TableLogic
    private Integer deleted;

    @TableField("effective_start_date")
    private LocalDate effectiveStartDate;

    @TableField("effective_end_date")
    private LocalDate effectiveEndDate;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTemplateHeaderId() { return templateHeaderId; }
    public void setTemplateHeaderId(Long templateHeaderId) { this.templateHeaderId = templateHeaderId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getBackgroundImageUrl() { return backgroundImageUrl; }
    public void setBackgroundImageUrl(String backgroundImageUrl) { this.backgroundImageUrl = backgroundImageUrl; }
    public String getDesignJson() { return designJson; }
    public void setDesignJson(String designJson) { this.designJson = designJson; }
    public String getChannelPayloadJson() { return channelPayloadJson; }
    public void setChannelPayloadJson(String channelPayloadJson) { this.channelPayloadJson = channelPayloadJson; }
    public String getTokensJson() { return tokensJson; }
    public void setTokensJson(String tokensJson) { this.tokensJson = tokensJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public LocalDate getEffectiveStartDate() { return effectiveStartDate; }
    public void setEffectiveStartDate(LocalDate effectiveStartDate) { this.effectiveStartDate = effectiveStartDate; }
    public LocalDate getEffectiveEndDate() { return effectiveEndDate; }
    public void setEffectiveEndDate(LocalDate effectiveEndDate) { this.effectiveEndDate = effectiveEndDate; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
