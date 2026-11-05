package com.msmesh.seats.net;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

/** Small OkHttp wrapper used by the OIDC client and the SEAtS gateway. */
public final class Http {
	public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	public static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");
	public static final OkHttpClient CLIENT = new OkHttpClient.Builder()
			.connectTimeout(20, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.writeTimeout(30, TimeUnit.SECONDS)
			.build();
	private static final OkHttpClient POST_CLIENT = CLIENT.newBuilder()
			.retryOnConnectionFailure(false)
			.followRedirects(false)
			.followSslRedirects(false)
			.build();

	private Http() {
	}

	/** Status, body and the optional Date header (used to align the device clock). */
	public static final class Result {
		public final int code;
		public final String body;
		public final long serverTimeMillis;

		Result(int code, String body, long serverTimeMillis) {
			this.code = code;
			this.body = body;
			this.serverTimeMillis = serverTimeMillis;
		}

		public boolean ok() {
			return code >= 200 && code < 300;
		}
	}

	public static Result get(String url, String token) throws IOException {
		Request.Builder builder = new Request.Builder().url(url).get();
		if (token != null) builder.header("Authorization", "Bearer " + token);
		builder.header("Accept", "application/json");
		return execute(builder.build());
	}

	public static Result postJson(String url, String token, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		RequestBody body = new RequestBody() {
			@Override
			public MediaType contentType() {
				return JSON;
			}

			@Override
			public long contentLength() {
				return bytes.length;
			}

			@Override
			public void writeTo(BufferedSink sink) throws IOException {
				sink.write(bytes);
			}

			@Override
			public boolean isOneShot() {
				// Also prevents replay after HTTP 503 with Retry-After: 0.
				return true;
			}
		};
		Request.Builder builder = new Request.Builder().url(url).post(body);
		if (token != null) builder.header("Authorization", "Bearer " + token);
		builder.header("Accept", "application/json");
		return execute(POST_CLIENT, builder.build());
	}

	/** application/x-www-form-urlencoded POST, used by the OIDC token endpoint. */
	public static Result postForm(String url, Map<String, String> form) throws IOException {
		StringBuilder formBody = new StringBuilder();
		for (Map.Entry<String, String> entry : form.entrySet()) {
			if (formBody.length() > 0) formBody.append('&');
			String value = entry.getValue() == null ? "" : entry.getValue();
			formBody.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(value, "UTF-8"));
		}
		Request request = new Request.Builder()
				.url(url)
				.post(RequestBody.create(formBody.toString(), FORM))
				.header("Accept", "application/json")
				.build();
		return execute(request);
	}

	private static Result execute(Request request) throws IOException {
		return execute(CLIENT, request);
	}

	private static Result execute(OkHttpClient client, Request request) throws IOException {
		try (Response response = client.newCall(request).execute()) {
			ResponseBody body = response.body();
			long serverTime = 0L;
			String date = response.header("Date");
			if (date != null) {
				try {
					serverTime = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
				} catch (Exception ignored) {
				}
			}
			return new Result(response.code(), body == null ? "" : body.string(), serverTime);
		}
	}
}
