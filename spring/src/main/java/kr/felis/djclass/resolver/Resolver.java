package kr.felis.djclass.resolver;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.felis.djclass.store.Store;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.stereotype.Component;

/**
 * internal/resolver: per-sender cache, linked → 5 min, linked without classes →
 * 15 s, unlinked → 10 s. Reads don't extend the TTL (expireAfterRead keeps it).
 * Badge selection (internal/djclass) is left out of this prototype.
 */
@Component
@ImportRuntimeHints(CaffeineHints.class)
public class Resolver {
	public enum Status { linked, unlinked }

	public record ClassRow(int button, String djClass, Double power) {}

	public record Result(Status status, List<ClassRow> classes) {}

	private static final Result UNLINKED = new Result(Status.unlinked, List.of());

	private final Store store;
	private final Cache<String, Result> cache = Caffeine.newBuilder()
			.maximumSize(50_000)
			.expireAfter(new Expiry<String, Result>() {
				public long expireAfterCreate(String k, Result r, long now) {
					Duration d = r.status() == Status.unlinked ? Duration.ofSeconds(10)
							: r.classes().isEmpty() ? Duration.ofSeconds(15) : Duration.ofMinutes(5);
					return d.toNanos();
				}

				public long expireAfterUpdate(String k, Result r, long now, long left) {
					return expireAfterCreate(k, r, now);
				}

				public long expireAfterRead(String k, Result r, long now, long left) {
					return left;
				}
			})
			.build();

	Resolver(Store store) {
		this.store = store;
	}

	public Result resolve(String senderChannelId) {
		if (senderChannelId == null || senderChannelId.isEmpty()) {
			return UNLINKED; // no nickname fallback
		}
		return cache.get(senderChannelId, this::load);
	}

	/** Called after a link/sync/unlink commit. */
	public void invalidate(String senderChannelId) {
		cache.invalidate(senderChannelId);
	}

	private Result load(String id) {
		var linked = store.read().sql("""
				SELECT 1 FROM users u JOIN varchive_links v ON v.user_id = u.id
				WHERE u.chzzk_id = ? AND v.is_active = 1""").param(id).query(Integer.class).optional();
		if (linked.isEmpty()) {
			return UNLINKED;
		}
		var rows = store.read().sql("""
				SELECT d.button, d.dj_class, d.dj_power_conversion FROM dj_classes d
				JOIN users u ON u.id = d.user_id WHERE u.chzzk_id = ? ORDER BY d.button""")
				.param(id)
				.query((rs, i) -> new ClassRow(rs.getInt(1), rs.getString(2), rs.getObject(3, Double.class)))
				.list();
		return new Result(Status.linked, rows);
	}

	public Map<String, Object> badgeJson(Result r) {
		return r.classes().isEmpty() ? null : Map.of("classes", r.classes());
	}
}
