package com.rigour.merchant.infrastructure.persistence.repository;

import com.rigour.merchant.api.v1.model.CustomerShippingAddressCommand;
import com.rigour.merchant.api.v1.model.CustomerShippingAddressView;
import com.rigour.merchant.application.port.out.CustomerShippingAddressStore;
import com.rigour.merchant.infrastructure.persistence.CrmUuidCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Repository
public class JdbcCustomerShippingAddressRepository implements CustomerShippingAddressStore {
    private final JdbcTemplate jdbc;
    private final CrmDataScope scopes;
    public JdbcCustomerShippingAddressRepository(JdbcTemplate jdbc, CrmDataScope scopes) {
        this.jdbc=jdbc; this.scopes=scopes;
    }
    @Override public List<CustomerShippingAddressView> addresses(String tenant,long customer) {
        scopes.requireCustomer(tenant,customer,"crm:customer:read");
        var c=customer(tenant,customer,false);
        return rows(tenant,(byte[])c.get("party_id"));
    }
    private List<CustomerShippingAddressView> rows(String tenant,byte[] party) {
        if(party==null) return List.of();
        return jdbc.query("""
            SELECT a.*,c.contact_name,c.phone FROM crm_address a
            LEFT JOIN crm_contact c ON c.tenant_id=a.tenant_id AND c.id=a.contact_id AND c.deleted=0
            WHERE a.tenant_id=UUID_TO_BIN(?) AND a.party_id=? AND a.deleted=0 AND a.address_type='SHIPPING' AND a.status='ACTIVE'
            ORDER BY a.is_default DESC,(a.ownership_state='INTERNAL_PRIMARY') DESC,a.created_time,a.id
            """,(r,n)->new CustomerShippingAddressView(CrmUuidCodec.decode(r.getBytes("id")),r.getString("consignee"),r.getString("contact_name"),r.getString("phone"),r.getString("region_text"),r.getString("address_detail"),r.getString("full_address"),r.getBoolean("is_default"),r.getLong("revision")),tenant,party);
    }
    @Override @Transactional public void createInitial(String tenant,long customer,CustomerShippingAddressCommand command,String actor) {
        if(command!=null) saveInternal(tenant,customer,null,command,actor);
    }
    @Override @Transactional public CustomerShippingAddressView save(String tenant,long customer,UUID id,CustomerShippingAddressCommand command,String actor) {
        scopes.requireCustomer(tenant,customer,"crm:customer:update");
        return saveInternal(tenant,customer,id,command,actor);
    }
    private CustomerShippingAddressView saveInternal(String tenant,long customer,UUID id,CustomerShippingAddressCommand v,String actor) {
        if(v==null) throw new IllegalArgumentException("收货信息不能为空");
        String name=required(v.contact(),160,"收货人"),phone=required(v.phone(),128,"联系电话"),region=required(v.regionText(),500,"省市区"),detail=required(v.addressDetail(),1000,"详细地址"),company=text(v.consignee(),240);
        var c=customer(tenant,customer,true);byte[] party=(byte[])c.get("party_id");
        if(party==null) {
            party=CrmUuidCodec.encode(UUID.randomUUID());
            jdbc.update("INSERT INTO crm_party(id,tenant_id,party_code,display_name,party_kind,internal_status,ownership_state,record_origin,revision,created_by,created_time,updated_by,updated_time,deleted) VALUES(?,UUID_TO_BIN(?),?,?,'ORGANIZATION','ACTIVE','INTERNAL_PRIMARY','MANUAL',1,?,UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6),0)",party,tenant,c.get("customer_code"),c.get("customer_name"),actor,actor);
            jdbc.update("INSERT INTO crm_party_role(tenant_id,party_id,role_code,status,effective_from,revision,created_by,created_time,updated_by,updated_time,deleted) VALUES(UUID_TO_BIN(?),?,'CUSTOMER','ACTIVE',UTC_TIMESTAMP(6),1,?,UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6),0)",tenant,party,actor,actor);
            jdbc.update("UPDATE crm_customer SET party_id=?,updated_time=updated_time WHERE tenant_id=? AND id=?",party,tenant,customer);
        }
        List<CustomerShippingAddressView> previous=rows(tenant,party);
        boolean create=id==null;UUID target=create?UUID.randomUUID():id;
        CustomerShippingAddressView old=create?null:previous.stream().filter(x->x.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("收货地址不存在或不属于该客户"));
        if(!create && !Objects.equals(v.revision(),old.revision())) throw new IllegalArgumentException("收货地址已被修改，请刷新后重试");
        boolean isDefault=previous.isEmpty()||Boolean.TRUE.equals(v.defaultAddress())||(!create&&old.defaultAddress());
        byte[] contact;
        if(create) {
            contact=CrmUuidCodec.encode(UUID.randomUUID());
            jdbc.update("INSERT INTO crm_contact(id,tenant_id,party_id,contact_type,contact_name,phone,is_primary,status,ownership_state,record_origin,revision,created_by,created_time,updated_by,updated_time,deleted) VALUES(?,UUID_TO_BIN(?),?,'SHIPPING',?,?,?,'ACTIVE','INTERNAL_PRIMARY','MANUAL',1,?,UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6),0)",contact,tenant,party,name,phone,isDefault,actor,actor);
        } else {
            contact=jdbc.queryForObject("SELECT contact_id FROM crm_address WHERE tenant_id=UUID_TO_BIN(?) AND party_id=? AND id=?",byte[].class,tenant,party,CrmUuidCodec.encode(target));
            if(contact==null) {
                contact=CrmUuidCodec.encode(UUID.randomUUID());
                jdbc.update("INSERT INTO crm_contact(id,tenant_id,party_id,contact_type,contact_name,phone,is_primary,status,ownership_state,record_origin,revision,created_by,created_time,updated_by,updated_time,deleted) VALUES(?,UUID_TO_BIN(?),?,'SHIPPING',?,?,?,'ACTIVE','INTERNAL_PRIMARY','MANUAL',1,?,UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6),0)",contact,tenant,party,name,phone,isDefault,actor,actor);
            } else jdbc.update("UPDATE crm_contact SET contact_name=?,phone=?,is_primary=?,ownership_state='INTERNAL_PRIMARY',updated_by=?,updated_time=UTC_TIMESTAMP(6),revision=revision+1 WHERE tenant_id=UUID_TO_BIN(?) AND party_id=? AND id=?",name,phone,isDefault,actor,tenant,party,contact);
        }
        if(isDefault) jdbc.update("UPDATE crm_address SET is_default=0,revision=revision+1,updated_by=?,updated_time=UTC_TIMESTAMP(6) WHERE tenant_id=UUID_TO_BIN(?) AND party_id=? AND address_type='SHIPPING' AND deleted=0 AND is_default=1 AND id<>?",actor,tenant,party,CrmUuidCodec.encode(target));
        if(create) jdbc.update("INSERT INTO crm_address(id,tenant_id,party_id,contact_id,address_type,consignee,region_text,address_detail,full_address,is_default,status,ownership_state,record_origin,revision,created_by,created_time,updated_by,updated_time,deleted) VALUES(?,UUID_TO_BIN(?),?,?,'SHIPPING',?,?,?,?,?,'ACTIVE','INTERNAL_PRIMARY','MANUAL',1,?,UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6),0)",CrmUuidCodec.encode(target),tenant,party,contact,company,region,detail,region+detail,isDefault,actor,actor);
        else {
            int n=jdbc.update("UPDATE crm_address SET contact_id=?,consignee=?,region_text=?,address_detail=?,full_address=?,is_default=?,ownership_state='INTERNAL_PRIMARY',revision=revision+1,updated_by=?,updated_time=UTC_TIMESTAMP(6) WHERE tenant_id=UUID_TO_BIN(?) AND party_id=? AND id=? AND revision=? AND deleted=0",contact,company,region,detail,region+detail,isDefault,actor,tenant,party,CrmUuidCodec.encode(target),v.revision());
            if(n!=1) throw new IllegalArgumentException("收货地址已被修改，请刷新后重试");
        }
        project(tenant,customer,party,actor);
        return rows(tenant,party).stream().filter(x->x.id().equals(target)).findFirst().orElseThrow();
    }
    @Override @Transactional public void delete(String tenant,long customer,UUID id,long revision,String actor) {
        scopes.requireCustomer(tenant,customer,"crm:customer:update");var c=customer(tenant,customer,true);byte[] party=(byte[])c.get("party_id");
        if(party==null) throw new IllegalArgumentException("收货地址不存在");
        int n=jdbc.update("UPDATE crm_address SET deleted=1,is_default=0,status='INACTIVE',ownership_state='INTERNAL_PRIMARY',revision=revision+1,updated_by=?,updated_time=UTC_TIMESTAMP(6) WHERE tenant_id=UUID_TO_BIN(?) AND party_id=? AND id=? AND address_type='SHIPPING' AND revision=? AND deleted=0",actor,tenant,party,CrmUuidCodec.encode(id),revision);
        if(n!=1) throw new IllegalArgumentException("收货地址不存在或已被修改");
        var remaining=rows(tenant,party);
        if(!remaining.isEmpty()&&remaining.stream().noneMatch(CustomerShippingAddressView::defaultAddress)) jdbc.update("UPDATE crm_address SET is_default=1,revision=revision+1,updated_by=?,updated_time=UTC_TIMESTAMP(6) WHERE tenant_id=UUID_TO_BIN(?) AND id=?",actor,tenant,CrmUuidCodec.encode(remaining.getFirst().id()));
        project(tenant,customer,party,actor);
    }
    private void project(String tenant,long customer,byte[] party,String actor) {
        var all=rows(tenant,party);var a=all.isEmpty()?null:all.getFirst();
        jdbc.update("UPDATE crm_customer SET contact_name=?,contact_phone=?,address=?,revision=revision+1,updated_by=?,updated_time=UTC_TIMESTAMP(6) WHERE tenant_id=? AND id=?",a==null?null:a.contact(),a==null?null:a.phone(),a==null?null:a.fullAddress(),actor,tenant,customer);
    }
    private Map<String,Object> customer(String tenant,long id,boolean lock) {
        var values=jdbc.queryForList("SELECT * FROM crm_customer WHERE tenant_id=? AND id=? AND deleted=0"+(lock?" FOR UPDATE":""),tenant,id);
        if(values.size()!=1) throw new IllegalArgumentException("客户不存在");return values.getFirst();
    }
    private static String required(String s,int max,String name) { String v=text(s,max);if(v==null)throw new IllegalArgumentException(name+"不能为空");return v; }
    private static String text(String s,int max) { if(s==null||s.isBlank())return null;String v=s.strip();if(v.length()>max)throw new IllegalArgumentException("收货信息字段过长");return v; }
}
