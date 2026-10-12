package kr.felis.djclass.web;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import kr.felis.djclass.realtime.ChatMessage;
import kr.felis.djclass.realtime.Hub;
import kr.felis.djclass.store.Store;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;

/**
 * dev.go: demo viewers + chat injection when DEV=1. A runtime check: Quarkus'
 * @IfBuildProperty would fix the bean in or out at build time.
 */
@Path("/dev")
public class DevResource {
	private final Hub hub;
	private final Store store;
	private final boolean dev;

	DevResource(Hub hub, Store store, @ConfigProperty(name = "djclass.dev") String dev) {
		this.hub = hub;
		this.store = store;
		this.dev = dev.equals("1") || dev.equals("true");
	}

	@POST
	@Path("chat")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response chat(@RestForm String channel, @RestForm String sender, @RestForm String text) {
		if (!dev) {
			return Response.status(404).build();
		}
		boolean ok = hub.inject(channel, new ChatMessage(sender, sender, text, Map.of()));
		return ok ? Response.ok("ok").build() : Response.status(404).entity("위젯이 열려 있지 않은 채널입니다.").build();
	}

	void seed(@Observes StartupEvent ev) {
		if (!dev) {
			return;
		}
		store.writeTx(c -> {
			for (var id : new String[] {"dev-showstopper", "dev-theory", "dev-unsynced", "dev-streamer"}) {
				Store.exec(c, "INSERT INTO users (chzzk_id, chzzk_nickname) VALUES (?, ?) ON CONFLICT DO NOTHING", id, id);
			}
			Store.exec(c, """
					INSERT INTO varchive_links (user_id, varchive_nickname)
					SELECT id, chzzk_id FROM users WHERE chzzk_id IN ('dev-showstopper','dev-theory','dev-unsynced')
					ON CONFLICT DO NOTHING""");
			Store.exec(c, """
					INSERT INTO dj_classes (user_id, button, dj_class, dj_power_conversion)
					SELECT id, 4, 'SHOWSTOPPER II', 9810 FROM users WHERE chzzk_id = 'dev-showstopper'
					UNION ALL SELECT id, 5, 'THE LORD OF DJMAX', 9999.98 FROM users WHERE chzzk_id = 'dev-theory'
					ON CONFLICT DO NOTHING""");
			Store.exec(c, """
					INSERT INTO channels (user_id, chzzk_channel_id)
					SELECT id, 'dev-channel' FROM users WHERE chzzk_id = 'dev-streamer' ON CONFLICT DO NOTHING""");
			return null;
		});
	}
}
