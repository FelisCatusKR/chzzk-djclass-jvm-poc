package kr.felis.djclass.web;

import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.core.http.HttpServerResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import kr.felis.djclass.realtime.Hub;
import kr.felis.djclass.store.Store;

/**
 * widgetStream in widget.go. @RunOnVirtualThread gives the Go shape: a blocking
 * loop per connection. Each send waits for the write to complete with a 10 s
 * timeout, which is Go's per-write deadline.
 */
@Path("/widget")
public class WidgetResource {
	static final Duration KEEPALIVE = Duration.ofSeconds(15);
	static final long WRITE_TIMEOUT_SECONDS = 10;

	private final Store store;
	private final Hub hub;

	WidgetResource(Store store, Hub hub) {
		this.store = store;
		this.hub = hub;
	}

	@GET
	@Path("{channelId}/stream")
	@Produces(MediaType.SERVER_SENT_EVENTS)
	@RunOnVirtualThread
	public void stream(@PathParam("channelId") String channelId, @Context SseEventSink sink, @Context Sse sse,
			@Context HttpServerResponse res) {
		if (!store.exists("SELECT 1 FROM channels WHERE chzzk_channel_id = ?", channelId)) {
			throw new NotFoundException();
		}
		Hub.Subscription sub;
		try {
			sub = hub.subscribe(channelId);
		} catch (Hub.TooManySubscribersException e) {
			throw new ServiceUnavailableException(Response.status(503).entity("위젯 연결이 너무 많습니다.").build());
		} catch (Hub.ClosedException e) {
			throw new ServiceUnavailableException(Response.status(503).entity("서버가 종료 중입니다").build());
		}
		try (sink) {
			// @ResponseHeader is not applied to SSE responses; set them before the first event.
			res.putHeader("Cache-Control", "no-cache").putHeader("X-Accel-Buffering", "no");
			send(sink, sse.newEventBuilder().comment("connected").build());
			while (true) {
				byte[] data = sub.poll(KEEPALIVE);
				if (data == Hub.CLOSED) {
					return;
				}
				send(sink, data == null
						? sse.newEventBuilder().comment("keepalive").build()
						: sse.newEventBuilder().name("chat")
								.data(String.class, new String(data, StandardCharsets.UTF_8)).build());
			}
		} catch (Exception e) {
			// client gone, write timed out, or shutting down
		} finally {
			hub.unsubscribe(sub);
		}
	}

	private static void send(SseEventSink sink, OutboundSseEvent ev) throws Exception {
		sink.send(ev).toCompletableFuture().get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}
}
