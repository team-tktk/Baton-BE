package com.baton.ai;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;

@Component
public class SafeWebSourceFetcher {
	private static final int MAX_BYTES = 2 * 1024 * 1024;
	private static final int MAX_REDIRECTS = 3;
	private final HttpClient client;

	public SafeWebSourceFetcher() {
		this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
				.followRedirects(HttpClient.Redirect.NEVER).build());
	}

	SafeWebSourceFetcher(HttpClient client) {
		this.client = client;
	}

	public FetchResult fetch(String rawUrl) {
		URI uri = parseAndValidate(rawUrl);
		for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
			try {
				HttpRequest request = HttpRequest.newBuilder(uri)
						.timeout(Duration.ofSeconds(7))
						.header("User-Agent", "BATON-SourceFetcher/1.0")
						.header("Accept", "text/html,text/plain,application/xhtml+xml")
						.GET().build();
				HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
				int status = response.statusCode();
				if (status >= 300 && status < 400) {
					if (redirects == MAX_REDIRECTS) {
						throw new BusinessException(ErrorCode.AI_EXTERNAL_SOURCE_FETCH_FAILED, "리다이렉트가 너무 많습니다.");
					}
					String location = response.headers().firstValue("location").orElseThrow(() ->
							new BusinessException(ErrorCode.AI_EXTERNAL_SOURCE_FETCH_FAILED));
					uri = parseAndValidate(uri.resolve(location).toString());
					continue;
				}
				if (status == 401 || status == 403) {
					return new FetchResult(uri.toString(), null, null, false);
				}
				if (status < 200 || status >= 300) {
					throw new BusinessException(ErrorCode.AI_EXTERNAL_SOURCE_FETCH_FAILED,
							"웹 서버가 HTTP %d로 응답했습니다.".formatted(status));
				}
				String contentType = response.headers().firstValue("content-type").orElse("text/plain")
						.toLowerCase(Locale.ROOT);
				if (!(contentType.startsWith("text/html") || contentType.startsWith("text/plain")
						|| contentType.startsWith("application/xhtml+xml"))) {
					throw new BusinessException(ErrorCode.AI_EXTERNAL_SOURCE_FETCH_FAILED, "HTML 또는 일반 텍스트만 가져올 수 있습니다.");
				}
				byte[] body = readLimited(response.body());
				String raw = new String(body, StandardCharsets.UTF_8);
				if (contentType.startsWith("text/plain")) {
					return new FetchResult(uri.toString(), null, raw.strip(), true);
				}
				Document html = Jsoup.parse(raw, uri.toString());
				html.select("script,style,noscript,svg").remove();
				return new FetchResult(uri.toString(), html.title(), html.body().text().strip(), true);
			} catch (BusinessException e) {
				throw e;
			} catch (Exception e) {
				throw new BusinessException(ErrorCode.AI_EXTERNAL_SOURCE_FETCH_FAILED, "웹 자료를 가져오는 중 오류가 발생했습니다.");
			}
		}
		throw new BusinessException(ErrorCode.AI_EXTERNAL_SOURCE_FETCH_FAILED);
	}

	URI parseAndValidate(String rawUrl) {
		try {
			URI uri = URI.create(rawUrl == null ? "" : rawUrl.trim());
			if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
					|| uri.getHost() == null || uri.getUserInfo() != null) {
				throw new BusinessException(ErrorCode.AI_EXTERNAL_SOURCE_INVALID_URL);
			}
			for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
				if (isBlocked(address)) {
					throw new BusinessException(ErrorCode.AI_EXTERNAL_SOURCE_INVALID_URL,
							"내부망 또는 로컬 주소는 등록할 수 없습니다.");
				}
			}
			return uri;
		} catch (BusinessException e) {
			throw e;
		} catch (Exception e) {
			throw new BusinessException(ErrorCode.AI_EXTERNAL_SOURCE_INVALID_URL);
		}
	}

	private boolean isBlocked(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return true;
		}
		byte[] bytes = address.getAddress();
		if (address instanceof Inet4Address) {
			int first = bytes[0] & 0xff;
			int second = bytes[1] & 0xff;
			return first == 0 || first == 127 || (first == 100 && second >= 64 && second <= 127);
		}
		return address instanceof Inet6Address && (bytes[0] & 0xfe) == 0xfc;
	}

	private byte[] readLimited(InputStream input) throws Exception {
		try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			int total = 0;
			for (int read; (read = input.read(buffer)) >= 0;) {
				total += read;
				if (total > MAX_BYTES) {
					throw new BusinessException(ErrorCode.AI_EXTERNAL_SOURCE_FETCH_FAILED, "웹 자료는 2MB를 넘을 수 없습니다.");
				}
				output.write(buffer, 0, read);
			}
			return output.toByteArray();
		}
	}

	public record FetchResult(String finalUrl, String title, String text, boolean fetched) {}
}
