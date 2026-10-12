package kr.felis.djclass.realtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import kr.felis.djclass.resolver.Resolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * internal/realtime.Hub, ported 1:1. Goroutines → virtual threads, context
 * cancellation → Thread.interrupt, buffered chan → ArrayBlockingQueue (offer =
 * non-blocking send), time.AfterFunc → ScheduledExecutorService.
 *
 * Stopped on ShutdownEvent, so open SSE streams end instead of holding the
 * graceful shutdown open.
 */
@ApplicationScoped
public class Hub {
	static final Duration FLUSH_INTERVAL = Duration.ofMillis(250);
	static final Duration TEARDOWN_DELAY = Duration.ofSeconds(30);
	static final int MAX_BATCH = 200;
	static final int SUBSCRIBER_BUFFER = 64;
	static final int MAX_PER_CHANNEL = 10;
	static final int MAX_TOTAL = 1000;

	/** Sentinel: "channel closed" (Go's close(sub.c)). */
	public static final byte[] CLOSED = new byte[0];

	public static final class TooManySubscribersException extends Exception {}

	public static final class ClosedException extends Exception {}

	public static final class Subscription {
		private final BlockingQueue<byte[]> q = new ArrayBlockingQueue<>(SUBSCRIBER_BUFFER);
		private final Channel ch;

		Subscription(Channel ch) {
			this.ch = ch;
		}

		public byte[] poll(Duration timeout) throws InterruptedException {
			return q.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
	}

	private static final class Channel {
		final String id;
		final List<Thread> workers = new ArrayList<>(2);
		// Guarded by Hub.mu.
		final Set<Subscription> subs = new HashSet<>();
		ScheduledFuture<?> teardown;

		final ReentrantLock bufMu = new ReentrantLock();
		List<ChatMessage> buffer = new ArrayList<>();

		Channel(String id) {
			this.id = id;
		}

		void push(ChatMessage m) {
			bufMu.lock();
			try {
				buffer.add(m);
			} finally {
				bufMu.unlock();
			}
		}

		List<ChatMessage> drain() {
			bufMu.lock();
			try {
				var b = buffer;
				buffer = new ArrayList<>();
				return b;
			} finally {
				bufMu.unlock();
			}
		}

		void stop() {
			workers.forEach(Thread::interrupt);
		}
	}

	private static final Logger log = LoggerFactory.getLogger(Hub.class);

	private final Resolver resolver;
	private final ObjectMapper json;
	private final AtomicLong nextId = new AtomicLong();
	private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(
			Thread.ofVirtual().name("hub-timer").factory());

	private final ReentrantLock mu = new ReentrantLock();
	private final Map<String, Channel> channels = new HashMap<>();
	private int total;
	private boolean closed;

	Hub(Resolver resolver, ObjectMapper json) {
		this.resolver = resolver;
		this.json = json;
	}

	public Subscription subscribe(String channelId) throws TooManySubscribersException, ClosedException {
		mu.lock();
		try {
			if (closed) {
				throw new ClosedException();
			}
			var ch = channels.get(channelId);
			if (total >= MAX_TOTAL || (ch != null && ch.subs.size() >= MAX_PER_CHANNEL)) {
				throw new TooManySubscribersException();
			}
			if (ch == null) {
				ch = startChannel(channelId);
				channels.put(channelId, ch);
			}
			if (ch.teardown != null) {
				ch.teardown.cancel(false);
				ch.teardown = null;
			}
			var sub = new Subscription(ch);
			ch.subs.add(sub);
			total++;
			return sub;
		} finally {
			mu.unlock();
		}
	}

	public void unsubscribe(Subscription sub) {
		mu.lock();
		try {
			var ch = sub.ch;
			if (!ch.subs.remove(sub)) {
				return;
			}
			total--;
			if (!ch.subs.isEmpty() || closed) {
				return;
			}
			ch.teardown = timers.schedule(() -> {
				mu.lock();
				try { // re-check: a subscribe may have raced the timer
					if (ch.subs.isEmpty() && channels.get(ch.id) == ch) {
						channels.remove(ch.id);
						ch.stop();
						log.info("channel torn down channel={}", ch.id);
					}
				} finally {
					mu.unlock();
				}
			}, TEARDOWN_DELAY.toMillis(), TimeUnit.MILLISECONDS);
		} finally {
			mu.unlock();
		}
	}

	public boolean inject(String channelId, ChatMessage m) {
		Channel ch;
		mu.lock();
		try {
			ch = channels.get(channelId);
		} finally {
			mu.unlock();
		}
		if (ch != null) {
			ch.push(m);
		}
		return ch != null;
	}

	// mu must be held.
	private Channel startChannel(String id) {
		var ch = new Channel(id);
		ch.workers.add(Thread.ofVirtual().name("ingest-" + id).start(() -> ingest(ch)));
		ch.workers.add(Thread.ofVirtual().name("flush-" + id).start(() -> flushLoop(ch)));
		log.info("channel started channel={}", id);
		return ch;
	}

	/**
	 * Real port: token → session URL → socket.io-client-java → CHAT into
	 * ch.push, restart with 1 s..60 s backoff on every failure. The prototype
	 * only receives /dev/chat injections, so this just waits for cancellation.
	 */
	private void ingest(Channel ch) {
		try {
			Thread.sleep(Long.MAX_VALUE);
		} catch (InterruptedException e) {
			// cancelled
		}
	}

	private void flushLoop(Channel ch) {
		long next = System.nanoTime();
		try {
			while (true) {
				next += FLUSH_INTERVAL.toNanos();
				Thread.sleep(Duration.ofNanos(Math.max(0, next - System.nanoTime())));
				try {
					flushOnce(ch);
				} catch (Exception | Error e) { // an uncaught throwable would end this channel's flushing silently
					if (e instanceof VirtualMachineError vme) {
						throw vme;
					}
					log.error("flush channel={}", ch.id, e);
				}
			}
		} catch (InterruptedException e) {
			// cancelled
		}
	}

	private List<Subscription> subscribers(Channel ch) {
		mu.lock();
		try {
			return new ArrayList<>(ch.subs);
		} finally {
			mu.unlock();
		}
	}

	private void flushOnce(Channel ch) {
		var raw = ch.drain();
		if (raw.isEmpty()) {
			return;
		}
		var subs = subscribers(ch);
		if (subs.isEmpty()) {
			return;
		}
		byte[] data;
		try {
			data = json.writeValueAsBytes(Map.of("messages", buildBatch(raw)));
		} catch (JsonProcessingException | RuntimeException e) {
			log.error("encode batch", e);
			return;
		}
		for (var s : subs) {
			s.q.offer(data); // a stuck widget must not block the others
		}
	}

	private List<Map<String, Object>> buildBatch(List<ChatMessage> raw) {
		if (raw.size() > MAX_BATCH) {
			raw = raw.subList(0, MAX_BATCH);
		}
		var seen = new HashMap<String, Resolver.Result>();
		var msgs = new ArrayList<Map<String, Object>>(raw.size());
		for (var m : raw) {
			var res = seen.computeIfAbsent(m.senderChannelId(), id -> {
				try {
					return resolver.resolve(id);
				} catch (RuntimeException e) {
					log.error("resolve sender", e);
					return new Resolver.Result(Resolver.Status.unlinked, List.of());
				}
			});
			var o = new LinkedHashMap<String, Object>();
			o.put("id", nextId.incrementAndGet());
			o.put("text", m.content());
			o.put("emojis", m.emojis() == null ? Map.of() : m.emojis());
			o.put("status", res.status());
			o.put("badge", resolver.badgeJson(res));
			o.put("nickname", m.nickname());
			msgs.add(o);
		}
		return msgs;
	}

	void onShutdown(@Observes ShutdownEvent ev) {
		stop();
	}

	/** Hub.Close: stop workers first, then "close" every subscription. */
	public void stop() {
		List<Subscription> subs = new ArrayList<>();
		List<Channel> chs;
		mu.lock();
		try {
			if (closed) {
				return;
			}
			closed = true;
			chs = new ArrayList<>(channels.values());
			for (var ch : chs) {
				if (ch.teardown != null) {
					ch.teardown.cancel(false);
				}
				subs.addAll(ch.subs);
				ch.subs.clear();
			}
			channels.clear();
			total = 0;
		} finally {
			mu.unlock();
		}
		chs.forEach(Channel::stop);
		for (var ch : chs) { // = h.wg.Wait()
			for (var t : ch.workers) {
				try {
					t.join();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
		for (var s : subs) {
			s.q.clear();
			s.q.offer(CLOSED);
		}
		timers.shutdownNow();
	}
}
