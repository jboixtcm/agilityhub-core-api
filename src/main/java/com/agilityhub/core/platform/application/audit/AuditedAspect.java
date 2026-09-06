package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.platform.domain.audit.AuditDiff;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(100)
public class AuditedAspect {
    private final AuditWriter writer;
    private final Map<String, AuditableLoader> loaders;
    private final SpelExpressionParser expressions = new SpelExpressionParser();

    public AuditedAspect(AuditWriter writer, List<AuditableLoader> loaders) {
        this.writer = writer;
        this.loaders = loaders.stream().collect(Collectors.toUnmodifiableMap(AuditableLoader::entityType, Function.identity()));
    }

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint invocation, Audited audited) throws Throwable {
        Method method = AopUtils.getMostSpecificMethod(((MethodSignature) invocation.getSignature()).getMethod(),
                invocation.getTarget().getClass());
        var context = new MethodBasedEvaluationContext(invocation.getTarget(), method, invocation.getArgs(),
                new DefaultParameterNameDiscoverer());
        Object before = null;
        if (!audited.before().isBlank()) {
            before = evaluate(audited.before(), context);
        } else if (!audited.entity().contains("#result") && !audited.entityType().contains("#result")) {
            String type = text(audited.entityType(), context);
            before = requireLoader(type).load(text(audited.entity(), context));
        }
        AuditDiff.Snapshot snapshot = AuditDiff.snapshot(before);
        Object result = invocation.proceed();
        context.setVariable("result", result);
        String type = text(audited.entityType(), context);
        String id = text(audited.entity(), context);
        AuditableLoader loader = loaders.get(type);
        Object after = loader == null ? result : loader.load(id);
        writer.write(audited.action(), type, id, text(audited.member(), context), text(audited.reason(), context),
                AuditDiff.between(snapshot, AuditDiff.snapshot(after)));
        return result;
    }

    private AuditableLoader requireLoader(String type) {
        AuditableLoader loader = loaders.get(type);
        if (loader == null) { throw new IllegalArgumentException("Missing AuditableLoader for " + type); }
        return loader;
    }

    private String text(String expression, MethodBasedEvaluationContext context) {
        Object value = evaluate(expression, context);
        return value == null ? null : value.toString();
    }

    private Object evaluate(String expression, MethodBasedEvaluationContext context) {
        return expression.isBlank() ? null : expressions.parseExpression(expression).getValue(context);
    }
}
