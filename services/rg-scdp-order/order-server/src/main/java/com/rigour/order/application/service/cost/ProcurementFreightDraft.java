package com.rigour.order.application.service.cost;

import com.rigour.erp.api.v1.model.ProcurementCostSource;
import com.rigour.order.api.v1.model.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 约定运费仅产生待核实草稿，不作为已发生运输事实。 */
public final class ProcurementFreightDraft {
    private ProcurementFreightDraft(){}
    public static LogisticsCostCommand from(ProcurementCostSource s){
        if(s.cityCode()==null || s.cityCode().isBlank())throw new IllegalArgumentException("采购收货仓库缺少归属城市");
        String key="ERP:PROCUREMENT_ORDER:"+s.procurementNo()+":FREIGHT";
        String content=String.join("|",s.procurementNo(),Objects.toString(s.purchaseDate(),""),Objects.toString(s.supplierCode(),""),Objects.toString(s.warehouseCode(),""),Objects.toString(s.receiverName(),""),Objects.toString(s.receiverPhone(),""),Objects.toString(s.receivingAddress(),""),Objects.toString(s.freightBearerCode(),""),s.freightAmount()==null?"?":s.freightAmount().stripTrailingZeros().toPlainString());
        String hash;
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        boolean external=Set.of("SUPPLIER","INCLUDED").contains(Objects.toString(s.freightBearerCode(),""));
        BigDecimal amount=external?BigDecimal.ZERO.setScale(2):s.freightAmount()==null || s.freightAmount().signum()==0?null:s.freightAmount().setScale(2,java.math.RoundingMode.HALF_UP);
        var sender=new LogisticsCostLedger.Party("sender","SUPPLIER",s.supplierCode(),null,null,s.supplierContact(),s.supplierPhone(),s.supplierAddress(),null);
        var receiver=new LogisticsCostLedger.Party("receiver","WAREHOUSE",s.warehouseCode(),null,null,s.receiverName(),s.receiverPhone(),s.receivingAddress(),s.cityCode());
        var fee=new LogisticsCostLedger.Fee("freight","FREIGHT",external?null:amount,external?"SUPPLIER":"COMPANY",amount,"MANUAL",external?("INCLUDED".equals(s.freightBearerCode())?"采购报价已含运费":"采购约定由厂家承担运费"):null,"采购约定金额，须核实实际费用");
        var source=new LogisticsCostLedger.Source("PURCHASE_ORDER",s.procurementNo(),"COST",List.of(new LogisticsCostLedger.Allocation("freight","receiver",s.cityCode(),null,amount==null?BigDecimal.ZERO:amount)),null);
        var sync=new LogisticsCostLedger.SourceSync(key,s.revision(),hash,false,s.revision(),hash,false);
        var ledger=new LogisticsCostLedger(2,"PURCHASE","THIRD_PARTY",null,s.purchaseDate().toString().substring(0,7),null,null,sender,List.of(receiver),List.of(fee),List.of(source),null,null,null,List.of(s.warehouseId()),List.of(s.cityCode()),sync);
        return new LogisticsCostCommand(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString(),null,"采购约定运费待核实-"+s.procurementNo(),"PURCHASE",s.cityCode(),null,null,null,s.purchaseDate(),amount,"UNKNOWN",null,"请核实运输日期、承运方、运单及实际费用后确认。",null,ledger);
    }
}
