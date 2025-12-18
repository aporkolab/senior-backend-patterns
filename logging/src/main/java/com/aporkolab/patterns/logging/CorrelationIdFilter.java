package com.aporkolab.patterns.logging;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet filter that extracts or generates correlation ID for incoming HTTP requests.
 * 
 * The correlation ID is:
 * 1. Extracted from X-Correlation-ID header if present
 * 2. Generated if not present
 * 3. Added to MDC for logging
 * 4. Added to response headers
 * 
 * Register as a Spring bean or in web.xml:
 * <pre>
 * @Bean
 * public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
 *     FilterRegistrationBean<CorrelationIdFilter> bean = new FilterRegistrationBean<>();
 *     bean.setFilter(new CorrelationIdFilter("my-service"));
 *     bean.addUrlPatterns("/*");
 *     bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
 *     return bean;
 * }
 * </pre>
 */
public class CorrelationIdFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    private final String serviceName;

    public CorrelationIdFilter() {
        this("unknown-service");
    }

    public CorrelationIdFilter(String serviceName) {
        this.serviceName = serviceName;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Extract or generate correlation ID
        String correlationId = httpRequest.getHeader(CorrelationContext.CORRELATION_ID_HEADER);
        
        try (CorrelationContext ctx = CorrelationContext.continueOrCreate(correlationId)) {
            ctx.withService(serviceName);

            // Extract user info if available
            String userId = httpRequest.getHeader("X-User-ID");
            if (userId != null) {
                ctx.withUserId(userId);
            }

            // Add correlation ID to response
            httpResponse.setHeader(
                    CorrelationContext.CORRELATION_ID_HEADER, 
                    CorrelationContext.getCurrentCorrelationId()
            );
            httpResponse.setHeader(
                    CorrelationContext.REQUEST_ID_HEADER,
                    CorrelationContext.getCurrentRequestId()
            );

            // Log request
            if (log.isDebugEnabled()) {
                log.debug("Incoming request: {} {} (correlationId={})",
                        httpRequest.getMethod(),
                        httpRequest.getRequestURI(),
                        CorrelationContext.getCurrentCorrelationId());
            }

            chain.doFilter(request, response);

        } finally {
            // MDC is cleared by CorrelationContext.close()
        }
    }
}
