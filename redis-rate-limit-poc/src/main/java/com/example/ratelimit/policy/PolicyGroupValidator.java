package com.example.ratelimit.policy;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class PolicyGroupValidator {

    public void validateGroup(PolicyGroup group, GlobalScopeRules globalRules) {
        var problems = new ArrayList<String>();

        for (var endpoint : group.endpoints()) {
            if (endpoint.exempt()) {
                continue;
            }
            validateEndpointHierarchy(endpoint, globalRules, problems);
        }

        if (!problems.isEmpty()) {
            throw new PolicyValidationException(problems);
        }
    }

    public void validateGlobalRules(GlobalScopeRules globalRules, List<PolicyGroup> groups) {
        var problems = new ArrayList<String>();

        for (var group : groups) {
            for (var endpoint : group.endpoints()) {
                if (endpoint.exempt()) {
                    continue;
                }
                validateEndpointHierarchy(endpoint, globalRules, problems);
            }
        }

        if (!problems.isEmpty()) {
            throw new PolicyValidationException(problems);
        }
    }

    private void validateEndpointHierarchy(EndpointRule endpoint, GlobalScopeRules globalRules,
            List<String> problems) {
        var endpointRule = findRule(endpoint, Scope.ENDPOINT);
        var ipRule = findRule(endpoint, Scope.IP);
        var userRule = findRule(endpoint, Scope.USER);
        var appRule = findGlobalRule(globalRules, Scope.APPLICATION);
        var globalRule = findGlobalRule(globalRules, Scope.GLOBAL);

        if (endpointRule != null && ipRule != null) {
            requireStrictlyLess(endpointRule, ipRule, "ENDPOINT", "IP", problems);
        }
        if (endpointRule != null && userRule != null) {
            requireStrictlyLess(endpointRule, userRule, "ENDPOINT", "USER", problems);
        }
        if (ipRule != null && appRule != null) {
            requireStrictlyLess(ipRule, appRule, "IP", "APPLICATION", problems);
        }
        if (appRule != null && globalRule != null) {
            requireStrictlyLess(appRule, globalRule, "APPLICATION", "GLOBAL", problems);
        }
    }

    private static ScopeRule findRule(EndpointRule endpoint, Scope scope) {
        return endpoint.scopeRules().stream()
                .filter(r -> r.scope() == scope)
                .findFirst()
                .orElse(null);
    }

    private static ScopeRule findGlobalRule(GlobalScopeRules globalRules, Scope scope) {
        if (globalRules == null || globalRules.rules() == null) {
            return null;
        }
        return globalRules.rules().stream()
                .filter(r -> r.scope() == scope)
                .findFirst()
                .orElse(null);
    }

    private static void requireStrictlyLess(ScopeRule child, ScopeRule parent,
            String childName, String parentName, List<String> problems) {
        if (!child.isComparable() || !parent.isComparable()) {
            return;
        }
        double childRps = child.normalizedRps();
        double parentRps = parent.normalizedRps();
        if (childRps >= parentRps) {
            problems.add("%s RPS (%.4f) must be strictly less than %s RPS (%.4f)"
                    .formatted(childName, childRps, parentName, parentRps));
        }
    }
}
