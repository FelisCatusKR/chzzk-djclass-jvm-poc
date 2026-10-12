package kr.felis.djclass.store;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * store.WriteTx in Java. Explicit TransactionTemplate rather than @Transactional:
 * the "no network I/O inside, never nest" rule stays visible at the call site.
 */
@Component
public class Store {
	public static final class NestedWriteException extends IllegalStateException {
		NestedWriteException() {
			super("store: nested writeTx (single write connection would deadlock)");
		}
	}

	@FunctionalInterface
	public interface TxFn<T> {
		T apply(JdbcClient q);
	}

	private final JdbcClient read;
	private final JdbcClient write;
	private final TransactionTemplate tx;

	Store(@Qualifier("read") JdbcClient read, @Qualifier("writeDataSource") DataSource writeDs,
			PlatformTransactionManager tm) {
		this.read = read;
		this.write = JdbcClient.create(writeDs);
		this.tx = new TransactionTemplate(tm);
	}

	public JdbcClient read() {
		return read;
	}

	public <T> T writeTx(TxFn<T> fn) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new NestedWriteException(); // = ErrNestedWrite
		}
		return tx.execute(status -> fn.apply(write));
	}
}
