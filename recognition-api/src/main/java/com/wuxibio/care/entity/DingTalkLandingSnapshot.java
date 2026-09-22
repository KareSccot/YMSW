package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("msg_dingtalk_landing_snapshot")
public class DingTalkLandingSnapshot {

    @TableId(value = "snapshot_id", type = IdType.AUTO)
    private Long id;

    @TableField("access_token")
    private String accessToken;

    @TableField("source_key")
    private String sourceKey;

    @TableField("template_header_id")
    private Long templateHeaderId;

    @TableField("channel_variant_id")
    private Long channelVariantId;

    @TableField("recipient")
    private String recipient;

    private String title;

    @TableField("content_mode")
    private String contentMode;

    @TableField("rendered_html")
    private String renderedHtml;

    @TableField("rendered_image_url")
    private String renderedImageUrl;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getSourceKey() { return sourceKey; }
    public void setSourceKey(String sourceKey) { this.sourceKey = sourceKey; }
    public Long getTemplateHeaderId() { return templateHeaderId; }
    public void setTemplateHeaderId(Long templateHeaderId) { this.templateHeaderId = templateHeaderId; }
    public Long getChannelVariantId() { return channelVariantId; }
    public void setChannelVariantId(Long channelVariantId) { this.channelVariantId = channelVariantId; }
    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContentMode() { return contentMode; }
    public void setContentMode(String contentMode) { this.contentMode = contentMode; }
    public String getRenderedHtml() { return renderedHtml; }
    public void setRenderedHtml(String renderedHtml) { this.renderedHtml = renderedHtml; }
    public String getRenderedImageUrl() { return renderedImageUrl; }
    public void setRenderedImageUrl(String renderedImageUrl) { this.renderedImageUrl = renderedImageUrl; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
