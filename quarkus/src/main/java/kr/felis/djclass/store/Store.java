package kr.felis.djclass.store;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * internal/store: a single write connection (default datasource, max-size=1) and
 * a query_only read pool (datasource "read"). writeTx is plain JDBC so the "no
 * network I/O inside, never nest" rule stays visible; no JTA.
 */
@ApplicationScoped
public class Store {
	public static final class NestedWriteException extends IllegalStateException {
		NestedWriteException() {
			super("store: nested writeTx (single write connection would deadlock)");
		}
	}

	@FunctionalInterface
	public interface TxFn<T> {
		T apply(Connection c) throws SQLException;
	}

	@FunctionalInterface
	public interface RowMapper<T> {
		T map(ResultSet rs) throws SQLException;
	}

	private static final ThreadLocal<Boolean> IN_WRITE = ThreadLocal.withInitial(() -> false);

	private final AgroalDataSource write;
	private final AgroalDataSource read;

	Store(AgroalDataSource write, @DataSource("read") AgroalDataSource read) {
		this.write = write;
		this.read = read;
	}

	public <T> T writeTx(TxFn<T> fn) {
		if (IN_WRITE.get()) {
			throw new NestedWriteException(); // = ErrNestedWrite
		}
		IN_WRITE.set(true);
		try (Connection c = write.getConnection()) {
			c.setAutoCommit(false); // BEGIN IMMEDIATE (transaction_mode=IMMEDIATE)
			try {
				T v = fn.apply(c);
				c.commit();
				return v;
			} catch (SQLException | RuntimeException e) {
				c.rollback();
				throw e;
			} finally {
				c.setAutoCommit(true);
			}
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		} finally {
			IN_WRITE.remove();
		}
	}

	public <T> List<T> query(String sql, RowMapper<T> m, Object... args) {
		try (Connection c = read.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
			for (int i = 0; i < args.length; i++) {
				ps.setObject(i + 1, args[i]);
			}
			try (ResultSet rs = ps.executeQuery()) {
				var out = new ArrayList<T>();
				while (rs.next()) {
					out.add(m.map(rs));
				}
				return out;
			}
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	public boolean exists(String sql, Object... args) {
		return !query(sql, rs -> 1, args).isEmpty();
	}

	public static int exec(Connection c, String sql, Object... args) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			for (int i = 0; i < args.length; i++) {
				ps.setObject(i + 1, args[i]);
			}
			return ps.executeUpdate();
		}
	}

	public <T> Optional<T> one(String sql, RowMapper<T> m, Object... args) {
		return query(sql, m, args).stream().findFirst();
	}
}
