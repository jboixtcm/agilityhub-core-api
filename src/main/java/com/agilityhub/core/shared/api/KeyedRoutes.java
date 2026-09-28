package com.agilityhub.core.shared.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * CONVENCIONS_API §7 (E5-T27, ruling E46): the key applies to every route that declares it, whatever the method. The routes are
 * read from the api's own handlers — a handler parameter `@RequestHeader("Idempotency-Key")` is what the published contract lists
 * as the operation's `Idempotency-Key` header — so the filter never keeps a list by hand. A GET, HEAD or OPTIONS route is never
 * keyed. The set is read once, at the first request, when every handler is registered.
 */
public final class KeyedRoutes {
    static final String HEADER = "Idempotency-Key";
    private static final Set<RequestMethod> READS = Set.of(RequestMethod.GET, RequestMethod.HEAD, RequestMethod.OPTIONS);
    private final Supplier<RequestMappingHandlerMapping> mappings;
    private volatile List<Route> routes;

    public KeyedRoutes(Supplier<RequestMappingHandlerMapping> mappings) { this.mappings = mappings; }

    private record Route(RequestMethod method, String template, PathPattern pattern) { }

    /** Whether `method path` is a route whose handler declares the header (never a read). */
    public boolean declares(String method, String path) {
        var container = PathContainer.parsePath(path);
        return routes().stream().anyMatch(route -> route.method().name().equals(method) && route.pattern().matches(container));
    }

    /** `METHOD /template` of every keyed route, as the OpenAPI snapshot names its operations. */
    public Set<String> operations() {
        var operations = new TreeSet<String>();
        routes().forEach(route -> operations.add(route.method().name() + " " + route.template()));
        return operations;
    }

    private List<Route> routes() {
        var known = routes;
        if (known == null) {
            var found = new ArrayList<Route>();
            mappings.get().getHandlerMethods().forEach((info, handler) -> { if (keyed(handler)) { add(found, info); } });
            routes = known = List.copyOf(found);
        }
        return known;
    }

    private static boolean keyed(HandlerMethod handler) {
        for (var parameter : handler.getMethodParameters()) {
            var header = parameter.getParameterAnnotation(RequestHeader.class);
            if (header != null && (HEADER.equalsIgnoreCase(header.value()) || HEADER.equalsIgnoreCase(header.name()))) { return true; }
        }
        return false;
    }

    private static void add(List<Route> found, RequestMappingInfo info) {
        for (var method : info.getMethodsCondition().getMethods()) {
            if (READS.contains(method)) { continue; }
            for (String template : info.getPatternValues()) { found.add(new Route(method, template, PathPatternParser.defaultInstance.parse(template))); }
        }
    }
}
