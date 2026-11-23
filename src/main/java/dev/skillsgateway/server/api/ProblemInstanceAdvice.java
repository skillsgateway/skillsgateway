package dev.skillsgateway.server.api;

import io.github.reqstool.annotations.Requirements;
import java.net.URI;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Keeps {@code instance} on the problem documents Spring MVC writes (GW_API_0007 — Every refusal is a
 * problem document carrying its reason).
 *
 * <p>Up to Spring Framework 7.0 the message-writing return-value handlers filled a problem's {@code
 * instance} with the request path when the handler left it empty; 7.1 (Spring Boot 4.2) stopped
 * doing so. The published contract describes {@code instance} as "the request path this problem
 * occurred on", and the contract is additive within a major, so the field is put back here, as the
 * framework used to: only where nothing set it, and from the same request URI.
 */
@ControllerAdvice
public class ProblemInstanceAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    @Requirements({"GW_API_0007"})
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof ProblemDetail problem
                && problem.getInstance() == null
                && request instanceof ServletServerHttpRequest servlet) {
            problem.setInstance(URI.create(servlet.getServletRequest().getRequestURI()));
        }
        return body;
    }
}
