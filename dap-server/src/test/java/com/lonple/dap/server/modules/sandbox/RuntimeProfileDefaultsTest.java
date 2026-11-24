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
                {"ports":{"services":[{"key":"dap-api","containerPort":8200}]}}
                """);
        var result = instantator.instantiate(product, profile, "REQ-2", null, 10L, (key, start, end) -> 18023);
        assertEquals(1, result.portPublishes().size());
        assertEquals(8200, result.portPublishes().getFirst().get("container"));
        assertEquals(18023, result.portPublishes().getFirst().get("host"));
        assertFalse(result.env().containsKey("SERVER_PORT"));
        assertFalse(result.env().containsKey("WEB_PORT"));
        assertFalse(result.env().containsKey("ADMIN_PORT"));
    }

    @Test
    void genericInputsOnlyProvidePlaceholdersAndNeverNameProductVariables() {
        SysProduct product = new SysProduct();
        product.setCode("other");
        SbRuntimeProfile profile = new SbRuntimeProfile();
        profile.setProfileCode("generic");
        profile.setConfigJson("""
                {"database":{"host":"10.0.0.12","port":5433,"name":"dap_dev","username":"dap_dev","password":"secret"},
                 "ports":{"services":[{"key":"api","containerPort":8200}]},
                 "envVars":[
                   {"name":"SPRING_DATASOURCE_URL",
                    "value":"jdbc:postgresql://{database.host}:{database.port}/{database.name}?currentSchema={schema}"},
                   {"name":"SPRING_DATASOURCE_USERNAME","value":"{database.username}"},
                   {"name":"SPRING_DATASOURCE_PASSWORD","value":"{database.password}"},
                   {"name":"SPRING_FLYWAY_SCHEMAS","value":"{database.schema}"},
                   {"name":"DAP_DATA_ROOT","value":"/data/dap-dev/"},
                   {"name":"SERVICE_URL","value":"http://127.0.0.1:{ports.api}"},
                   {"name":"SERVICE_PUBLIC_URL","value":"http://127.0.0.1:{ports.api.host}"},
                   {"name":"UNKNOWN_TOKEN","value":"{no.such.placeholder}"}]}
                """);
        var env = instantator.instantiate(product, profile, "REQ-8", null, 10L, (k, s, e) -> 18500).env();
        assertEquals("jdbc:postgresql://10.0.0.12:5433/dap_dev?currentSchema=it_other_REQ_8", env.get("SPRING_DATASOURCE_URL"));
        assertEquals("dap_dev", env.get("SPRING_DATASOURCE_USERNAME"));
        assertEquals("secret", env.get("SPRING_DATASOURCE_PASSWORD"));
        assertEquals("it_other_REQ_8", env.get("SPRING_FLYWAY_SCHEMAS"));
        assertEquals("/data/dap-dev/", env.get("DAP_DATA_ROOT"));
        assertEquals("http://127.0.0.1:8200", env.get("SERVICE_URL"));
        assertEquals("http://127.0.0.1:18500", env.get("SERVICE_PUBLIC_URL"));
        assertEquals("{no.such.placeholder}", env.get("UNKNOWN_TOKEN"));
        assertFalse(env.containsKey("SPRING_FLYWAY_DEFAULTSCHEMA"));
        assertFalse(env.containsKey("DAP_REVIEW_DATA_DIR"));
        assertFalse(env.containsKey("DAP_TEST_API_URL"));
        assertFalse(env.containsKey("SERVER_PORT"));
    }

    @Test
    void servicePortsAreMappedOnceWithoutSeparatePortConfig() {
        SysProduct product = new SysProduct();
        product.setCode("DAP");
        SbRuntimeProfile profile = new SbRuntimeProfile();
        profile.setProfileCode("dap-dev");
        profile.setConfigJson("""
                {"ports":{"services":[{"key":"api","containerPort":8200},
                                       {"key":"api","containerPort":8200}]}}
                """);
        int[] next = {18000};
        var result = instantator.instantiate(product, profile, "REQ-5", null, 10L, (k, s, e) -> next[0]++);
        var containers = result.portPublishes().stream().map(p -> p.get("container")).toList();
        assertEquals(java.util.List.of(8200), containers);
        assertEquals(1, result.portPublishes().size());
    }

    @Test
    void mockTestingRedirectsExternalServicesToMock() {
        SysProduct product = new SysProduct();
        product.setCode("DAP");
        SbRuntimeProfile profile = new SbRuntimeProfile();
        profile.setProfileCode("dap-mock");
        profile.setConfigJson("""
                {"ports":{"services":[{"key":"api","containerPort":8200},{"key":"agent","containerPort":8202}]},
                 "testing":{"modelMode":"mock","mockPort":18300}}
                """);
        var env = instantator.instantiate(product, profile, "REQ-4", null, 10L, (k, s, e) -> 18000).env();
        assertEquals("18300", env.get("DAP_TEST_MOCK_PORT"));
        assertEquals("http://127.0.0.1:18300", env.get("DAP_AGENT_BASEURL"));
        assertEquals("http://127.0.0.1:18300/v1", env.get("DAP_REVIEW_LLM_BASE_URL"));
        assertEquals("http://127.0.0.1:18300", env.get("DAP_SANDBOX_MANAGERBASEURL"));
    }

    @Test
    void genericServiceListDrivesPortsAndVariablesWithoutProductSpecificFields() {
        SysProduct product = new SysProduct();
        product.setCode("other");
        SbRuntimeProfile profile = new SbRuntimeProfile();
        profile.setProfileCode("generic");
        profile.setConfigJson("""
                {"ports":{"services":[
                    {"key":"api","containerPort":9000},
                    {"key":"web","containerPort":9001},
                    {"key":"agent","containerPort":9002},
                    {"key":"api","containerPort":9003}
                 ]},
                 "envVars":[
                    {"name":"SERVER_PORT","value":"{ports.api}"},
                    {"name":"DAP_UI_PORT","value":"{ports.web}"},
                    {"name":"PORT","value":"{ports.agent}"},
                    {"name":"DAP_SERVER_BASE_URL","value":"http://127.0.0.1:{ports.api}"},
                    {"name":"DAP_API_PROXY_TARGET","value":"http://127.0.0.1:{ports.api}"},
                    {"name":"DAP_AGENT_BASEURL","value":"http://127.0.0.1:{ports.agent}"}],
                 "testing":{"apiService":"api","uiService":"web","username":"admin","password":"admin"}}
                """);
        int[] next = {18000};
        var result = instantator.instantiate(product, profile, "REQ-6", null, 10L, (k, s, e) -> next[0]++);
        var env = result.env();
        assertEquals(java.util.List.of(9000, 9001, 9002, 9003),
                result.portPublishes().stream().map(p -> p.get("container")).toList());
        assertEquals(java.util.List.of(18000, 18001, 18002, 18003),
                result.portPublishes().stream().map(p -> p.get("host")).toList());
        assertEquals("9000", env.get("SERVER_PORT"));
        assertEquals("http://127.0.0.1:9000", env.get("DAP_SERVER_BASE_URL"));
        assertEquals("http://127.0.0.1:9000", env.get("DAP_API_PROXY_TARGET"));
        assertEquals("9001", env.get("DAP_UI_PORT"));
        assertEquals("9002", env.get("PORT"));
        assertEquals("http://127.0.0.1:9002", env.get("DAP_AGENT_BASEURL"));
        assertEquals("http://127.0.0.1:9000", env.get("DAP_TEST_API_URL"));
        assertEquals("http://127.0.0.1:9001", env.get("DAP_TEST_UI_URL"));
        @SuppressWarnings("unchecked")
        var ports = (java.util.Map<String, Object>) result.runtimeInstance().get("ports");
        assertEquals(java.util.Set.of("api", "web", "agent", "api-9003"), ports.keySet());
    }

    @Test
    void serviceWithoutDeclaredVariablesInjectsNothing() {
        SysProduct product = new SysProduct();
        product.setCode("other");
        SbRuntimeProfile profile = new SbRuntimeProfile();
        profile.setProfileCode("admin-only");
        profile.setConfigJson("""
                {"ports":{"services":[{"key":"admin","containerPort":8081}]}}
                """);
        var result = instantator.instantiate(product, profile, "REQ-7", null, 10L, (k, s, e) -> 18000);
        assertEquals(java.util.List.of(8081),
                result.portPublishes().stream().map(p -> p.get("container")).toList());
        assertFalse(result.env().containsKey("SERVER_PORT"));
        assertFalse(result.env().containsKey("DAP_SERVER_BASE_URL"));
        assertFalse(result.env().containsKey("DAP_AGENT_BASEURL"));
        assertFalse(result.env().containsKey("DAP_TEST_API_URL"));
    }
}
