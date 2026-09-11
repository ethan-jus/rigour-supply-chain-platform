package com.rigour.order.api.v1.model;

/** 资金收付款单附件视图；objectKey 为我方私有 COS 对象键，url 为短时访问地址。 */
public record FundDocumentAttachmentView(String objectKey, String fileName, String url) {
}
