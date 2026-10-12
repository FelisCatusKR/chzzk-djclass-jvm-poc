package kr.felis.djclass.web;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/")
public class PageResource {
	private final Template index;

	PageResource(Template index) {
		this.index = index;
	}

	@GET
	@Produces(MediaType.TEXT_HTML)
	public TemplateInstance get() {
		return index.data("title", "치지직 DJ CLASS 채팅 위젯");
	}
}
