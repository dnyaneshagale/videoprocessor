package com.vidprocessor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
	"API_KEY=test-api-key-must-be-at-least-32-characters",
	"CLOUDFLARE_R2_ACCESS_KEY=test-access-key",
	"CLOUDFLARE_R2_SECRET_KEY=test-secret-key",
	"CLOUDFLARE_R2_ENDPOINT=https://test.r2.cloudflarestorage.com",
	"CLOUDFLARE_R2_BUCKET=test-bucket"
})
class VidprocessorApplicationTests {

	@Test
	void contextLoads() {
	}

}
