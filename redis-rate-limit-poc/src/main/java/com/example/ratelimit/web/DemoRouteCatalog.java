package com.example.ratelimit.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.ratelimit.admin.AdminDtos.PolicyResponse;
import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.policy.ExemptionDocument;
import com.example.ratelimit.policy.ExemptionStore;
import com.example.ratelimit.policy.ManagedPolicyStore;
import com.example.ratelimit.policy.PolicyDocument;
import com.example.ratelimit.policy.PolicyMatcher;
import com.example.ratelimit.policy.Scope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Builds the request-demo catalog from the managed policies, because a policy is what the operator
 * configured and wants to prove.
 *
 * <p>The catalog is policy-centric, not route-centric. Each managed policy produces exactly one
 * {@link PolicyTarget}: an entry in the console's dropdown plus a concrete request the demo may send
 * to exercise it. A policy is never silently dropped, so a typo, a wildcard, a pathless scope or a
 * disabled policy is all still visible.
 *
 * <ul>
 *   <li>The target path is always concrete. Wildcard and {@code {template}} syntax is resolved, never
 *       sent literally: the demo would otherwise get a 404 from its own request text and prove
 *       nothing.
 *   <li>A policy whose path no handler serves still gets a target. The rate-limit filter runs before
 *       routing, so the request reaches the limiter and can still be rejected with 429; the 404 from
 *       downstream routing is reported separately rather than treated as a limiter answer.
 *   <li>Replay safety comes from {@link DemoCallable} when a handler does serve the path. A route the
 *       console may not call keeps its reason so the operator can see why.
 *   <li>Applicable policies come from {@link PolicyMatcher#matching}, the very call the filter uses,
 *       so method rules, {@code ANY}, wildcard paths, GLOBAL/APPLICATION scopes and exemptions cannot
 *       disagree with enforcement.
 * </ul>
 */
@Component
public class DemoRouteCatalog {

    /**
     * One dropdown entry: the operator's policy, and the concrete request that exercises it.
     *
     * @param policyId the managed policy this entry exists for
     * @param method concrete HTTP method to send, never {@code ANY}
     * @param concretePath concrete path to request, free of wildcard and template syntax
     * @param sampleQuery query string appended to the request, possibly empty
     * @param matchedHandler whether a registered handler serves this exact method and path
     * @param testable false when the request cannot be sent; {@code reason} then says why
     * @param enforcedWith every policy the filter will charge for this exact request, including this
     *        one. Selecting one policy's entry does not isolate it.
     */
    public record PolicyTarget(
            String policyId,
            boolean enabled,
            String algorithm,
            Scope scope,
            String parameterSummary,
            String configuredMethod,
            String configuredPath,
            String method,
            String concretePath,
            String sampleQuery,
            boolean matchedHandler,
            boolean testable,
            boolean requiresCredentials,
            String note,
            String reason,
            List<PolicyResponse> enforcedWith,
            List<String> exemptions) {
    }

    public record Catalog(List<PolicyTarget> targets, List<PolicyResponse> policies) {
    }

    private record RegisteredRoute(String pattern, Set<String> methods, boolean demoCallable,
            boolean repeatable, boolean requiresCredentials, String sampleQuery, String samplePath,
            String note, String reason) {

        String concretePath() {
            return !samplePath.isBlank() ? samplePath : pattern;
        }

        boolean templated() {
            return pattern.contains("{");
        }
    }

    /** {@code {name}} segment in a configured policy path. */
    private static final java.util.regex.Pattern TEMPLATE = java.util.regex.Pattern.compile("\\{[^{}]*}");
    /** Prefer a repeatable route with no credential prompt, then GET, then a stable path order. */
    private static final Comparator<RegisteredRoute> STAND_IN_ORDER = Comparator
            .comparing(RegisteredRoute::requiresCredentials)
            .thenComparing(route -> !route.methods().contains("GET"))
            .thenComparing(RegisteredRoute::concretePath);

    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private final PolicyMatcher matcher;
    private final ExemptionStore exemptions;
    private final ManagedPolicyStore store;
    private final RateLimitProperties yamlProperties;
    private final AntPathMatcher ant = new AntPathMatcher();

    public DemoRouteCatalog(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mappings,
            PolicyMatcher matcher,
            ExemptionStore exemptions,
            ManagedPolicyStore store,
            RateLimitProperties yamlProperties) {
        this.mappings = mappings;
        this.matcher = matcher;
        this.exemptions = exemptions;
        this.store = store;
        this.yamlProperties = yamlProperties;
    }

    public Catalog catalog() {
        List<RegisteredRoute> registered = registeredRoutes();
        List<PolicyDocument> policies = effectivePolicies();

        var targets = new ArrayList<PolicyTarget>();
        for (PolicyDocument policy : policies) {
            targets.add(describe(policy, registered));
        }
        targets.sort((a, b) -> a.policyId().compareTo(b.policyId()));

        return new Catalog(targets, policies.stream().map(PolicyResponse::from).toList());
    }

    private PolicyTarget describe(PolicyDocument policy, List<RegisteredRoute> registered) {
        boolean pathless = policy.path() == null || policy.path().isBlank()
                || policy.scope() == Scope.GLOBAL || policy.scope() == Scope.APPLICATION;

        String policyId = policy.id();
        boolean enabled = policy.enabled();

        // Disabled policies still get an entry: the operator configured them and needs to see them,
        // but the console must not claim they are enforced.
        if (!enabled) {
            return new PolicyTarget(policyId, false, algorithmName(policy), policy.scope(),
                    summary(policy), policy.method(), policy.path(), null, null, null, false, false, false,
                    "", "This policy is disabled, so it is not enforced. Enable it in the Policies "
                            + "workspace to test it.",
                    List.of(), matchingExemptions(policy));
        }

        RegisteredRoute route = pathless
                ? safeSampleRoute(registered)
                : resolve(policy, registered);

        if (route == null) {
            return new PolicyTarget(policyId, false, algorithmName(policy), policy.scope(), summary(policy),
                    policy.method(), policy.path(), null, null, null, false, false, false, "",
                    pathless
                            ? "This policy has no path of its own. It applies to every request, but this "
                                    + "API registers no repeatable route the console may call."
                            : "No registered handler serves " + policy.method() + " " + policy.path()
                                    + ", and no repeatable route can stand in for it.",
                    List.of(), matchingExemptions(policy));
        }

        String concretePath = route.concretePath();
        String method = requestMethod(route, policy);
        boolean testable = route.repeatable();
        String reason = route.repeatable()
                ? ""
                : route.reason().isBlank()
                        ? "This route must not be replayed automatically."
                        : route.reason();

        List<PolicyResponse> enforcedWith = matcher.matching(method, concretePath).stream()
                .map(PolicyResponse::from)
                // A disabled co-policy is not enforced, so it is not listed as enforcing this request.
                .collect(Collectors.toList());

        // A note from @DemoCallable describes the handler. Without one, the note has to explain the
        // target the console derived: a pathless policy's sample route, or a path nothing serves.
        String note;
        if (pathless) {
            // Keep the global-policy explanation even when the sampled route supplies its own
            // @DemoCallable note; operators need both the policy scope and replay-safety context.
            note = defaultNote(policy, true, route);
            if (!route.note().isBlank()) {
                note += " " + route.note();
            }
        } else {
            note = route.note().isBlank()
                    ? (route.demoCallable ? "" : defaultNote(policy, false, route))
                    : route.note();
        }

        return new PolicyTarget(policyId, true, algorithmName(policy), policy.scope(), summary(policy),
                policy.method(), policy.path(), method, concretePath, route.sampleQuery(),
                route.demoCallable(), testable, route.requiresCredentials(), note, reason,
                enforcedWith, matchingExemptions(method, concretePath));
    }

    /**
     * A pathless GLOBAL/APPLICATION policy applies to every request, so it is exercised through a
     * documented repeatable route rather than by inventing a URL. Only repeatable routes qualify:
     * a non-repeatable stand-in would make the policy look untestable for the wrong reason.
     */
    private RegisteredRoute safeSampleRoute(List<RegisteredRoute> registered) {
        return registered.stream()
                .filter(RegisteredRoute::repeatable)
                .filter(r -> !r.templated())
                .sorted((a, b) -> a.concretePath().compareTo(b.concretePath()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Finds the route that serves this policy.
     *
     * <p>Wildcard and template syntax is resolved by asking which registered routes the policy pattern
     * actually matches, so the demo sends a real concrete path. When the policy path is literal and no
     * handler serves it, the policy still gets a target: the limiter runs before routing, so a bounded
     * request can be rejected with 429 while the downstream 404 is reported on its own.
     */
    private RegisteredRoute resolve(PolicyDocument policy, List<RegisteredRoute> registered) {
        var served = registered.stream()
                .filter(r -> methodCompatible(r, policy.method()) && ant.match(policy.path(), r.concretePath()))
                // Alphabetical, so a wildcard always resolves to the same concrete route: the demo
                // must be reproducible, not dependent on handler registration order.
                .sorted((a, b) -> a.concretePath().compareTo(b.concretePath()))
                .toList();

        RegisteredRoute exact = served.stream()
                .filter(r -> r.pattern().equals(policy.path()))
                .findFirst()
                .orElse(null);
        if (exact != null) {
            return exact;
        }
        // Wildcard or template policy: stand in for it with a real registered route it covers. The
        // stand-in must stay testable and free of credential prompts, so a repeatable GET route
        // wins over a POST that would need Basic auth. Otherwise the first match is still used and
        // keeps its own reason.
        RegisteredRoute sample = served.stream()
                .filter(r -> !r.templated() && r.repeatable)
                .min(STAND_IN_ORDER)
                .orElseGet(() -> served.stream().filter(r -> !r.templated()).findFirst().orElse(null));
        if (sample != null) {
            return sample;
        }

        // No handler. Still testable, but the path must become concrete: the demo must never send
        // wildcard or {template} syntax literally, because that would only 404 on its own text.
        return new RegisteredRoute(concretize(policy.path()), Set.of(requestMethodFor(policy.method())),
                false, true, false, "", "", "", null);
    }

    /**
     * Turns pattern syntax into a sendable path: {@code {name}} becomes {@code 1}, and a wildcard
     * contributes the longest literal prefix that can stand on its own. Purely textual, so it never
     * guesses a segment that could reach an unintended handler.
     */
    static String concretize(String path) {
        String concrete = TEMPLATE.matcher(path).replaceAll("1");
        int wildcard = concrete.indexOf('*');
        if (wildcard < 0) {
            return concrete;
        }
        String prefix = concrete.substring(0, wildcard);
        int lastSlash = prefix.lastIndexOf('/');
        String trimmed = lastSlash <= 0 ? "/" : prefix.substring(0, lastSlash);
        return trimmed.isEmpty() ? "/" : trimmed;
    }

    private static String sampleMethod(RegisteredRoute route) {
        return route.methods().isEmpty() ? "GET" : route.methods().iterator().next();
    }

    /** The concrete verb to send: the policy's own, or the route's when the policy says {@code ANY}. */
    private static String requestMethod(RegisteredRoute route, PolicyDocument policy) {
        String configured = policy.method();
        if (configured != null && !"ANY".equalsIgnoreCase(configured)) {
            return configured.toUpperCase();
        }
        return sampleMethod(route);
    }

    private static String requestMethodFor(String configured) {
        return configured == null || "ANY".equalsIgnoreCase(configured) ? "GET" : configured.toUpperCase();
    }

    private List<RegisteredRoute> registeredRoutes() {
        var out = new ArrayList<RegisteredRoute>();
        for (var entry : mappings.getObject().getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            Set<String> methods = info.getMethodsCondition().getMethods().stream()
                    .map(RequestMethod::name)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            DemoCallable meta = demoMetadata(handler);
            for (String pattern : patterns(info)) {
                out.add(new RegisteredRoute(pattern, methods, meta != null, meta != null && meta.repeatable(),
                        meta != null && meta.requiresCredentials(), meta == null ? "" : meta.sampleQuery(),
                        meta == null ? "" : meta.samplePath(), meta == null ? "" : meta.note(),
                        meta == null ? "" : meta.reason()));
            }
        }
        return out;
    }

    private static boolean methodCompatible(RegisteredRoute route, String policyMethod) {
        if (route.methods().isEmpty() || policyMethod == null || "ANY".equalsIgnoreCase(policyMethod)) {
            return true;
        }
        return route.methods().stream().anyMatch(m -> m.equalsIgnoreCase(policyMethod));
    }

    private List<String> matchingExemptions(PolicyDocument policy) {
        if (policy.path() == null || policy.path().isBlank()) {
            return List.of();
        }
        return matchingExemptions(policy.method(), policy.path());
    }

    /** Same method and AntPathMatcher test the filter applies, so exemptions cannot disagree. */
    private List<String> matchingExemptions(String method, String path) {
        List<String> out = new ArrayList<>();
        for (ExemptionDocument exemption : exemptions.findAll()) {
            if (!exemption.enabled()) {
                continue;
            }
            if (exemption.method() != null && !"ANY".equalsIgnoreCase(exemption.method())
                    && !exemption.method().equalsIgnoreCase(method)) {
                continue;
            }
            if (exemption.path() != null && ant.match(exemption.path(), path)) {
                out.add(exemption.id());
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    private static String defaultNote(PolicyDocument policy, boolean pathless, RegisteredRoute route) {
        if (pathless) {
            return "This policy has no path of its own, so it applies to every request. The console "
                    + "exercises it through " + sampleMethod(route) + " " + route.concretePath()
                    + ", a route documented as safe to replay.";
        }
        return "No handler serves " + policy.method() + " " + policy.path() + ". The request still "
                + "reaches the limiter, which runs before routing, so it can be rejected with 429; the "
                + "404 comes from downstream routing and is reported separately.";
    }

    private static String algorithmName(PolicyDocument policy) {
        return policy.algorithm() == null ? "UNSPECIFIED" : policy.algorithm().name();
    }

    private static String summary(PolicyDocument policy) {
        try {
            return policy.describeParameters();
        } catch (RuntimeException e) {
            return "incomplete";
        }
    }

    private List<PolicyDocument> effectivePolicies() {
        try {
            List<PolicyDocument> managed = store.findAll();
            return managed.isEmpty() ? PolicyMatcher.fromYamlProperties(yamlProperties) : managed;
        } catch (RuntimeException e) {
            return PolicyMatcher.fromYamlProperties(yamlProperties);
        }
    }

    private static DemoCallable demoMetadata(HandlerMethod handler) {
        DemoCallable onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), DemoCallable.class);
        return onMethod != null ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), DemoCallable.class);
    }

    private static Set<String> patterns(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return new LinkedHashSet<>(info.getPathPatternsCondition().getPatternValues());
        }
        return info.getPatternsCondition() == null ? Set.of()
                : new LinkedHashSet<>(info.getPatternsCondition().getPatterns());
    }
}
