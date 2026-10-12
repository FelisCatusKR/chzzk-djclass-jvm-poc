package kr.felis.djclass.web;

import java.util.Map;
import kr.felis.djclass.realtime.ChatMessage;
import kr.felis.djclass.realtime.Hub;
import kr.felis.djclass.store.Store;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * dev.go: demo viewers + chat injection when DEV=1. Checked at runtime, not with
 * @ConditionalOnProperty: Spring AOT (native image) evaluates conditions at build
 * time and would freeze the bean in or out.
 */
@RestController
public class DevController implements ApplicationRunner {
	private final Hub hub;
	private final Store store;
	private final boolean dev;

	DevController(Hub hub, Store store, @Value("${djclass.dev}") String dev) {
		this.hub = hub;
		this.store = store;
		this.dev = dev.equals("1") || dev.equals("true");
	}

	@PostMapping("/dev/chat")
	ResponseEntity<String> chat(@RequestParam String channel, @RequestParam String sender,
			@RequestParam String text) {
		if (!dev) {
			return ResponseEntity.notFound().build();
		}
		boolean ok = hub.inject(channel, new ChatMessage(sender, sender, text, Map.of()));
		return ok ? ResponseEntity.ok("ok") : ResponseEntity.status(404).body("위젯이 열려 있지 않은 채널입니다.");
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!dev) {
			return;
		}
		store.writeTx(q -> {
			for (var id : new String[] {"dev-showstopper", "dev-theory", "dev-unsynced", "dev-streamer"}) {
				q.sql("INSERT INTO users (chzzk_id, chzzk_nickname) VALUES (?, ?) ON CONFLICT DO NOTHING")
						.params(id, id).update();
			}
			q.sql("""
					INSERT INTO varchive_links (user_id, varchive_nickname)
					SELECT id, chzzk_id FROM users WHERE chzzk_id IN ('dev-showstopper','dev-theory','dev-unsynced')
					ON CONFLICT DO NOTHING""").update();
			q.sql("""
					INSERT INTO dj_classes (user_id, button, dj_class, dj_power_conversion)
					SELECT id, 4, 'SHOWSTOPPER II', 9810 FROM users WHERE chzzk_id = 'dev-showstopper'
					UNION ALL SELECT id, 5, 'THE LORD OF DJMAX', 9999.98 FROM users WHERE chzzk_id = 'dev-theory'
					ON CONFLICT DO NOTHING""").update();
			q.sql("""
					INSERT INTO channels (user_id, chzzk_channel_id)
					SELECT id, 'dev-channel' FROM users WHERE chzzk_id = 'dev-streamer' ON CONFLICT DO NOTHING""")
					.update();
			return null;
		});
	}
}
