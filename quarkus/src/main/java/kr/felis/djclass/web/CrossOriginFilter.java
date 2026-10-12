package kr.felis.djclass.web;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import java.util.Set;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

/** Port of net/http.CrossOriginProtection for unsafe methods. */
public class CrossOriginFilter {
	private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

	@ServerRequestFilter(preMatching = true)
	public Response check(ContainerRequestContext ctx) {
		if (SAFE.contains(ctx.getMethod()) || allowed(ctx)) {
			return null; // continue
		}
		return Response.status(403).entity("교차 출처 요청이 거부되었습니다.").build();
	}

	private static boolean allowed(ContainerRequestContext ctx) {
		var site = ctx.getHeaderString("Sec-Fetch-Site");
		if (site != null) {
			return site.equals("same-origin") || site.equals("none");
		}
		var origin = ctx.getHeaderString("Origin");
		if (origin == null) {
			return true; // not a browser request
		}
		return origin.endsWith("://" + ctx.getHeaderString("Host"));
	}
}
