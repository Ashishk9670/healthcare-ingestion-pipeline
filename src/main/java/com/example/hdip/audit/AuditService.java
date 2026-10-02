package com.example.hdip.audit;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/** Persists an audit entry for every ingest / data access. Never put PHI in {@code detail}. */
@Service
public class AuditService {
    private final AuditEntryRepository repo;

    public AuditService(AuditEntryRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, String resourceId, String detail) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actor = "system:pipeline";
        String roles = "";
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            actor = auth.getPrincipal() instanceof Jwt jwt
                    ? String.valueOf(jwt.getClaimAsString("preferred_username") != null ? jwt.getClaimAsString("preferred_username") : jwt.getSubject())
                    : auth.getName();
            roles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().collect(Collectors.joining(","));
        }
        repo.save(new AuditEntry(actor, roles, action, resourceType, resourceId, detail));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> latest(int limit) {
        return repo.findAllByOrderByTimestampDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }
}
