package com.agilityhub.core.configuration;

import com.agilityhub.core.shared.application.contract.ListContract;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;
import static org.assertj.core.api.Assertions.*;

/**
 * E5-T24 step 5 (CONVENCIONS_API §4, amended 26-09; review E5-T22 #2 and nit #7): a misdeclared list contract stops the
 * application at startup instead of reaching the OpenAPI document. Every Spring context of the build runs the same check on
 * the real controllers ({@link ListContractValidation#listContracts}).
 */
class ListContractValidationTest {
    static class Examples {
        @ListContract(paged = true, fields = {"id", "name"}) public void list() { }
        @ListContract(paged = true, acceptsFields = false) public void contractOnly() { }
        @ListContract(paged = false) public void filterValues() { }
        @ListContract(paged = true, acceptsFields = false, fields = {"id"}) public void refusedWithFields() { }
        @ListContract(paged = true) public void fieldsWithoutKeys() { }
        @ListContract(paged = false, fields = {"id"}) public void keysWithoutParameter() { }
        public void notAList() { }
    }
    static HandlerMethod handler(String name) throws Exception { return new HandlerMethod(new Examples(), Examples.class.getMethod(name)); }

    @Test void CONVENCIONS_API_4_wellDeclaredListContractsStart() throws Exception {
        assertThatCode(() -> ListContractValidation.check(List.of(handler("list"), handler("contractOnly"), handler("filterValues"), handler("notAList"))))
                .doesNotThrowAnyException();
    }

    @Test void CONVENCIONS_API_4_aMisdeclaredListContractStopsTheStartup() throws Exception {
        assertThatThrownBy(() -> ListContractValidation.check(List.of(handler("list"), handler("refusedWithFields"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Examples.refusedWithFields: acceptsFields = false with fields");
        assertThatThrownBy(() -> ListContractValidation.check(List.of(handler("fieldsWithoutKeys"))))
                .hasMessageContaining("Examples.fieldsWithoutKeys: publishes fields without x-fields");
        assertThatThrownBy(() -> ListContractValidation.check(List.of(handler("keysWithoutParameter"))))
                .hasMessageContaining("Examples.keysWithoutParameter: publishes x-fields without the fields parameter");
    }
}
