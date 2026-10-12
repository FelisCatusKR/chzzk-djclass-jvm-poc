package kr.felis.djclass.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * internal/web/middleware.go in Spring Security terms. Login itself (Chzzk OAuth)
 * is a custom flow in the real port; the prototype permits everything.
 */
@Configuration
public class SecurityConfig {
	static final String CSP = "default-src 'self'; img-src 'self' data: https://*.pstatic.net https://*.naver.net; "
			+ "frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		return http
				.authorizeHttpRequests(a -> a.anyRequest().permitAll())
				// Spring's default is a synchroniser token; the Go server uses
				// http.CrossOriginProtection (Sec-Fetch-Site / Origin) + SameSite=Lax.
				.csrf(c -> c.disable())
				.addFilterBefore(new CrossOriginProtection(), CsrfFilter.class)
				.headers(h -> h
						.contentSecurityPolicy(c -> c.policyDirectives(CSP))
						.frameOptions(f -> f.deny())
						.referrerPolicy(r -> r.policy(ReferrerPolicy.SAME_ORIGIN))
						.crossOriginOpenerPolicy(c -> {})
						.permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=()")))
				.requestCache(c -> c.disable())
				.securityContext(c -> c.disable())
				.anonymous(a -> a.disable())
				.build();
	}

	/** Port of net/http.CrossOriginProtection for unsafe methods. */
	static final class CrossOriginProtection extends OncePerRequestFilter {
		private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

		@Override
		protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
				throws ServletException, IOException {
			if (!SAFE.contains(req.getMethod()) && !allowed(req)) {
				res.sendError(HttpServletResponse.SC_FORBIDDEN, "교차 출처 요청이 거부되었습니다.");
				return;
			}
			chain.doFilter(req, res);
		}

		private static boolean allowed(HttpServletRequest req) {
			var site = req.getHeader("Sec-Fetch-Site");
			if (site != null) {
				return site.equals("same-origin") || site.equals("none");
			}
			var origin = req.getHeader("Origin");
			if (origin == null) {
				return true; // not a browser request
			}
			var host = req.getHeader("Host");
			return origin.endsWith("://" + host);
		}
	}
}
