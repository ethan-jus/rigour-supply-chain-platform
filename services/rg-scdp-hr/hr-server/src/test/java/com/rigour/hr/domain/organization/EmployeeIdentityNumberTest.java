package com.rigour.hr.domain.organization;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class EmployeeIdentityNumberTest {
 @Test void validatesBirthDateAndChecksum(){
   assertThat(EmployeeIdentityNumber.normalize("11010519491231002x")).isEqualTo("11010519491231002X");
   assertThatThrownBy(()->EmployeeIdentityNumber.normalize("11010519490230002X")).hasMessageContaining("出生日期");
   assertThatThrownBy(()->EmployeeIdentityNumber.normalize("110105194912310020")).hasMessageContaining("校验位");
 }
}
