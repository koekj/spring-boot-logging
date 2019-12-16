package pl.piomin.logging.filter;

import static net.logstash.logback.argument.StructuredArguments.value;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import pl.piomin.logging.util.UniqueIDGenerator;

public class SpringLoggingFilter extends OncePerRequestFilter {

	private static final Logger LOGGER = LoggerFactory.getLogger(SpringLoggingFilter.class);
	private UniqueIDGenerator generator;
	private String ignorePatterns;
	private boolean logHeaders;

	@Autowired
	ApplicationContext context;

	public SpringLoggingFilter(UniqueIDGenerator generator, String ignorePatterns, boolean logHeaders) {
		this.generator = generator;
		this.ignorePatterns = ignorePatterns;
		this.logHeaders = logHeaders;
	}

	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (ignorePatterns != null && request.getRequestURI().matches(ignorePatterns)) {
			chain.doFilter(request, response);
		} else {
			generator.generateAndSetMDC(request);
			try {
				getHandlerMethod(request);
			} catch (Exception e) {
				LOGGER.trace("Cannot get handler method");
			}
			final long startTime = System.currentTimeMillis();
			final ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
			if (logHeaders)
				LOGGER.info("Request: method={}, uri={}, payload={}, headers={}, audit={}", wrappedRequest.getMethod(),
						wrappedRequest.getRequestURI(),
						new String(wrappedRequest.getContentAsByteArray(), wrappedRequest.getCharacterEncoding()),
						getAllHeaders(wrappedRequest), value("audit", true));
			else
				LOGGER.info("Request: method={}, uri={}, payload={}, audit={}", wrappedRequest.getMethod(),
						wrappedRequest.getRequestURI(),
						new String(wrappedRequest.getContentAsByteArray(), wrappedRequest.getCharacterEncoding()),
						value("audit", true));
			final ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
			wrappedResponse.setHeader("X-Request-ID", MDC.get("X-Request-ID"));
			wrappedResponse.setHeader("X-Correlation-ID", MDC.get("X-Correlation-ID"));

			try {
				chain.doFilter(wrappedRequest, wrappedResponse);
			} catch (Exception e) {
				logResponse(startTime, wrappedResponse, 500);
				throw e;
			}
			logResponse(startTime, wrappedResponse, wrappedResponse.getStatus());
			wrappedResponse.copyBodyToResponse();
		}
	}

	private Object getAllHeaders(ContentCachingRequestWrapper wrappedRequest) {
		final Map<String, String> headers = new HashMap<>();
		wrappedRequest.getHeaderNames().asIterator()
				.forEachRemaining(it -> headers.put(it, wrappedRequest.getHeader(it)));
		return headers;
	}

	private void logResponse(long startTime, ContentCachingResponseWrapper wrappedResponse, int overriddenStatus)
			throws IOException {
		final long duration = System.currentTimeMillis() - startTime;
		if (logHeaders)
			LOGGER.info("Response({} ms): status={}, payload={}, headers={}, audit={}",
					value("X-Response-Time", duration), value("X-Response-Status", overriddenStatus),
					IOUtils.toString(wrappedResponse.getContentAsByteArray(), wrappedResponse.getCharacterEncoding()),
					getAllHeaders(wrappedResponse), value("audit", true));
		else
			LOGGER.info("Response({} ms): status={}, payload={}, audit={}", value("X-Response-Time", duration),
					value("X-Response-Status", overriddenStatus),
					IOUtils.toString(wrappedResponse.getContentAsByteArray(), wrappedResponse.getCharacterEncoding()),
					value("audit", true));
	}

	private Object getAllHeaders(ContentCachingResponseWrapper wrappedResponse) {
		final Map<String, String> headers = new HashMap<>();
		wrappedResponse.getHeaderNames().stream().forEach((header) -> {
			headers.put(header, wrappedResponse.getHeader(header));
		});
		return headers;
	}

	private void getHandlerMethod(HttpServletRequest request) throws Exception {
		RequestMappingHandlerMapping mappings1 = (RequestMappingHandlerMapping) context
				.getBean("requestMappingHandlerMapping");
		HandlerExecutionChain handler = mappings1.getHandler(request);
		if (Objects.nonNull(handler)) {
			HandlerMethod handler1 = (HandlerMethod) handler.getHandler();
			MDC.put("X-Operation-Name", handler1.getBeanType().getSimpleName() + "." + handler1.getMethod().getName());
		}
	}

}
