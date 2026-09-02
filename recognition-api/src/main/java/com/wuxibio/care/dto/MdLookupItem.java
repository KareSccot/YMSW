package com.wuxibio.care.dto;

public class MdLookupItem {

    private String externalCode;
    private String labelZh;
    private String labelEn;
    private String status;

    public MdLookupItem() {
    }

    public MdLookupItem(String externalCode, String labelZh, String labelEn, String status) {
        this.externalCode = externalCode;
        this.labelZh = labelZh;
        this.labelEn = labelEn;
        this.status = status;
    }

    public String getExternalCode() { return externalCode; }
    public void setExternalCode(String externalCode) { this.externalCode = externalCode; }
    public String getLabelZh() { return labelZh; }
    public void setLabelZh(String labelZh) { this.labelZh = labelZh; }
    public String getLabelEn() { return labelEn; }
    public void setLabelEn(String labelEn) { this.labelEn = labelEn; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
