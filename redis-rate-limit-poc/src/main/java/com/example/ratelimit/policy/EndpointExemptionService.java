package com.example.ratelimit.policy;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

@Component
public class EndpointExemptionService {

    private static final Logger log = LoggerFactory.getLogger(EndpointExemptionService.class);

    private final ManagedPolicyStore store;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public EndpointExemptionService(ManagedPolicyStore store) {
        this.store = store;
    }

    public boolean isExempt(String method, String path) {
        try {
            List<PolicyGroup> groups = store.findAllGroups();
            for (var group : groups) {
                if (!group.enabled()) {
                    continue;
                }
                for (var endpoint : group.endpoints()) {
                    if (!endpoint.exempt()) {
                        continue;
                    }
                    if (endpoint.method() != null && !"ANY".equalsIgnoreCase(endpoint.method())
                            && !endpoint.method().equalsIgnoreCase(method)) {
                        continue;
                    }
                    if (matcher.match(endpoint.path(), path)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("endpoint exemption lookup failed for {} {}: {}", method, path, e.getMessage());
            return false;
        }
    }
}
