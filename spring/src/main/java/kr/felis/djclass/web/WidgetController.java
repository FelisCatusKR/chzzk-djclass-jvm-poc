package kr.felis.djclass.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import kr.felis.djclass.realtime.Hub;
import kr.felis.djclass.store.Store;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * widgetStream in widget.go. With virtual threads this is the Go shape: a
 * blocking loop per connection (no SseEmitter / async dispatch needed).
 */
@RestController
public class WidgetController {
	static final Duration KEEPALIVE = Duration.ofSeconds(15);
	private static final byte[] KEEPALIVE_LINE = ": keepalive\n\n".getBytes(StandardCharsets.UTF_8);
	private static final byte[] EVENT_PREFIX = "event: chat\ndata: ".getBytes(StandardCharsets.UTF_8);
	private static final byte[] EVENT_SUFFIX = "\n\n".getBytes(StandardCharsets.UTF_8);

	private final Store store;
	private final Hub hub;

	WidgetController(Store store, Hub hub) {
		this.store = store;
		this.hub = hub;
	}

	@GetMapping("/widget/{channelId}/stream")
	void stream(@PathVariable String channelId, HttpServletResponse res) throws IOException {
		boolean known = store.read().sql("SELECT 1 FROM channels WHERE chzzk_channel_id = ?")
				.param(channelId).query(Integer.class).optional().isPresent();
		if (!known) {
			res.sendError(404);
			return;
		}
		Hub.Subscription sub;
		try {
			sub = hub.subscribe(channelId);
		} catch (Hub.TooManySubscribersException e) {
			res.sendError(503, "위젯 연결이 너무 많습니다.");
			return;
		} catch (Hub.ClosedException e) {
			res.sendError(503, "서버가 종료 중입니다");
			return;
		}
		try {
			res.setContentType("text/event-stream");
			res.setCharacterEncoding("UTF-8");
			res.setHeader("Cache-Control", "no-cache");
			res.setHeader("X-Accel-Buffering", "no");
			var out = res.getOutputStream();
			out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
			out.flush();
			while (true) {
				byte[] data = sub.poll(KEEPALIVE);
				if (data == Hub.CLOSED) {
					return;
				}
				if (data == null) {
					out.write(KEEPALIVE_LINE);
				} else {
					out.write(EVENT_PREFIX);
					out.write(data);
					out.write(EVENT_SUFFIX);
				}
				out.flush(); // IOException = client gone (or the 10 s write timeout hit)
			}
		} catch (IOException | InterruptedException e) {
			// client disconnected / shutting down
		} finally {
			hub.unsubscribe(sub);
		}
	}
}
