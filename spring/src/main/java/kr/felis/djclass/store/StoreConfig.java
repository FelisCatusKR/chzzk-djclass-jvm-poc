package kr.felis.djclass.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * Same layout as internal/store: one write connection (WAL, synchronous=NORMAL,
 * BEGIN IMMEDIATE) so writers queue in the pool rather than on SQLite's lock, and
 * a query_only read pool.
 */
@Configuration
public class StoreConfig {

	private static SQLiteDataSource sqlite(String path, boolean write) {
		var c = new SQLiteConfig();
		c.setBusyTimeout(5000);
		c.enforceForeignKeys(true);
		if (write) {
			c.setJournalMode(SQLiteConfig.JournalMode.WAL);
			c.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
			c.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE); // = _txlock=immediate
		}
		var ds = new SQLiteDataSource(c);
		ds.setUrl("jdbc:sqlite:" + path);
		return ds;
	}

	@Bean
	@Primary
	@FlywayDataSource
	DataSource writeDataSource(@Value("${djclass.sqlite-path}") String path) {
		var h = new HikariConfig();
		h.setPoolName("write");
		h.setDataSource(sqlite(path, true));
		h.setMaximumPoolSize(1); // = writeDB.SetMaxOpenConns(1)
		h.setMinimumIdle(1);
		h.setConnectionTimeout(30_000); // how long a writer may queue
		return new HikariDataSource(h);
	}

	@Bean
	DataSource readDataSource(@Value("${djclass.sqlite-path}") String path) {
		int n = Math.max(4, Runtime.getRuntime().availableProcessors());
		var h = new HikariConfig();
		h.setPoolName("read");
		h.setDataSource(sqlite(path, false));
		h.setMaximumPoolSize(n);
		h.setMinimumIdle(n);
		h.setConnectionInitSql("PRAGMA query_only=1"); // = _pragma=query_only(1)
		return new HikariDataSource(h);
	}

	@Bean
	@Primary
	PlatformTransactionManager transactionManager(DataSource writeDataSource) {
		return new DataSourceTransactionManager(writeDataSource);
	}

	@Bean
	@Qualifier("read")
	JdbcClient readJdbc(@Qualifier("readDataSource") DataSource ds) {
		return JdbcClient.create(ds);
	}
}
