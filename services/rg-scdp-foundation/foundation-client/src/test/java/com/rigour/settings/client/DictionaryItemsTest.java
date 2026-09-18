package com.rigour.settings.client;
import com.rigour.settings.api.v1.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
/** 停用不抹去历史标签，但父级停用、断链和环都不得继续用于业务选择。 */
class DictionaryItemsTest {
 @Test void rejectsDisabledAncestryAndMalformedParentChains(){
  var items=List.of(item("PCS",null,true),item("PACK",null,false),item("BOX","PACK",true),item("LOST","MISSING",true),item("A","B",true),item("B","A",true));
  var view=new EffectiveDictView(new DictView(1L,"PRODUCT_UNIT","单位","COMMON",null,1),items);
  assertThat(DictionaryItems.activeCodes(view)).containsExactly("PCS");
  assertThat(view.items()).hasSize(6);
 }
 private DictItemView item(String code,String parent,boolean enabled){return new DictItemView(1L,"PRODUCT_UNIT",1,parent,code,code,null,0,1,enabled);}
}
