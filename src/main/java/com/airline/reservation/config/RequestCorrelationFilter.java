package com.airline.reservation.config;

import com.airline.reservation.exception.BusinessException;
import com.airline.reservation.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestCorrelationFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Correlation-ID";
  private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private final HandlerExceptionResolver exceptionResolver;
  private final String instanceId;

  public RequestCorrelationFilter(
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
      @Value("${airline.instance-id:${HOSTNAME:local}}") String instanceId) {
    this.exceptionResolver = exceptionResolver;
    this.instanceId = instanceId.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    List<String> suppliedIds = Collections.list(request.getHeaders(HEADER));
    boolean valid =
        suppliedIds.isEmpty()
            || suppliedIds.size() == 1 && VALID_ID.matcher(suppliedIds.getFirst()).matches();
    String correlationId =
        valid && !suppliedIds.isEmpty() ? suppliedIds.getFirst() : UUID.randomUUID().toString();
    long started = System.nanoTime();
    MDC.put("correlationId", correlationId);
    MDC.put("instanceId", instanceId);
    response.setHeader(HEADER, correlationId);
    try {
      if (!valid) {
        exceptionResolver.resolveException(
            request,
            response,
            null,
            new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "X-Correlation-ID must be one identifier containing 1-64 letters, digits, dots, underscores or hyphens"));
        return;
      }
      chain.doFilter(request, response);
    } finally {
      log.debug(
          "request_completed method={} status={} elapsedMs={}",
          request.getMethod(),
          response.getStatus(),
          (System.nanoTime() - started) / 1_000_000);
      MDC.remove("correlationId");
      MDC.remove("instanceId");
    }
  }
}
