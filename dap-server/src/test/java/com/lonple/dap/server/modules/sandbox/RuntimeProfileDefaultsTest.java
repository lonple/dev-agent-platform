package com.lonple.dap.server.modules.sandbox;

import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.sandbox.entity.SbRuntimeProfile;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeProfileDefaultsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SandboxRuntimeInstantator instantator = new SandboxRuntimeInstantator(mapper, new SandboxProperties());

    @Test
    void defaultDoesNotAssumeAnyProductsServicesOrDependencies() throws Exception {
        var config = mapper.readTree(RuntimeProfileDefaults.DEFAULT_CONFIG_JSON);
        assertTrue(config.path("ports").path("services").isEmpty());
        assertTrue(config.path("envVars").isEmpty());
        assertTrue(config.path("envTemplates").isEmpty());
        assertTrue(config.path("startTemplates").isEmpty());
        assertTrue(config.path("database").isMissingNode());
        SysProduct product = new SysProduct();
        product.setCode("dap");
        SbRuntimeProfile profile = new SbRuntimeProfile();
        profile.setProfileCode("default");
        profile.setConfigJson(RuntimeProfileDefaults.DEFAULT_CONFIG_JSON);
        var result = instantator.instantiate(product, profile, "REQ-1", null, 10L,
                (key, start, end) -> { fail("Empty template must not allocate a business port"); return 0; });
        assertTrue(result.portPublishes().isEmpty());
        assertFalse(result.env().containsKey("JDBC_URL"));
        assertFalse(result.env().containsKey("SERVER_PORT"));
        assertFalse(result.env().containsKey("SPRING_PROFILES_ACTIVE"));
    }

    @Test
    void explicitlyConfiguredProductPortIsUsedInsteadOfExamplePorts() {
        SysProduct product = new SysProduct();
        product.setCode("dap");
        SbRuntimeProfile profile = new SbRuntimeProfile();
        profile.setProfileCode("dap-dev");
        profile.setConfigJson("""
                {"ports":{"services":[{"key":"dap-api","containerPort":8200,"env":"SERVER_PORT"}]}}
                """);
        var result = instantator.instantiate(product, profile, "REQ-2", null, 10L, (key, start, end) -> 18023);
        assertEquals(1, result.portPublishes().size());
        assertEquals(8200, result.portPublishes().getFirst().get("container"));
        assertEquals(18023, result.portPublishes().getFirst().get("host"));
        assertEquals("8200", result.env().get("SERVER_PORT"));
        assertFalse(result.env().containsKey("WEB_PORT"));
        assertFalse(result.env().containsKey("ADMIN_PORT"));
    }
}
