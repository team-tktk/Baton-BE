package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.baton.common.BusinessException;

class SafeWebSourceFetcherTest {
	private final SafeWebSourceFetcher fetcher = new SafeWebSourceFetcher();

	@Test
	void rejectsLoopbackAndPrivateAddresses() {
		assertThatThrownBy(() -> fetcher.parseAndValidate("http://127.0.0.1/admin"))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> fetcher.parseAndValidate("http://10.0.0.1/secret"))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> fetcher.parseAndValidate("http://[::1]/"))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void rejectsNonHttpSchemesAndEmbeddedCredentials() {
		assertThatThrownBy(() -> fetcher.parseAndValidate("file:///etc/passwd"))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> fetcher.parseAndValidate("https://user:pass@example.com/"))
				.isInstanceOf(BusinessException.class);
	}
}
