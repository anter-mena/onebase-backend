package com.onebase;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"onebase.docs.username=test-user",
	"onebase.docs.password=test-password",
})
@AutoConfigureMockMvc
class OnebaseBackendApplicationTests {

	@Autowired
	MockMvc mvc;

	@Test
	void healthIsPublic() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void swaggerNeedsTheDocsLogin() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
		mvc.perform(get("/v3/api-docs").with(httpBasic("test-user", "wrong"))).andExpect(status().isUnauthorized());
		mvc.perform(get("/v3/api-docs").with(httpBasic("test-user", "test-password"))).andExpect(status().isOk());
	}
}
