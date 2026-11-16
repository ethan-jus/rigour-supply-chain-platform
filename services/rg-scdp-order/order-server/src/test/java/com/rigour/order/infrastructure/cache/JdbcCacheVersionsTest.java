package com.rigour.order.infrastructure.cache;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
class JdbcCacheVersionsTest {
 @Test void versionCommitsAtomicallyOncePerTransactionAndRollbackDoesNotInvalidate() {
  var ds=new DriverManagerDataSource("jdbc:h2:mem:cache-"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
  var jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE query_cache_version(tenant_id VARCHAR(64) PRIMARY KEY,revision BIGINT NOT NULL)");
  var versions=new JdbcCacheVersions(jdbc);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  tx.executeWithoutResult(s->{versions.changed("tenant-a");versions.changed("tenant-a");assertTrue(versions.dirty("tenant-a"));assertEquals(0,versions.current("tenant-a"));});
  assertEquals(1,versions.current("tenant-a"));assertFalse(versions.dirty("tenant-a"));assertEquals(0,versions.current("tenant-b"));
  tx.executeWithoutResult(s->{versions.changed("tenant-a");s.setRollbackOnly();});assertEquals(1,versions.current("tenant-a"));
  tx.executeWithoutResult(s->versions.changed("tenant-a"));assertEquals(2,versions.current("tenant-a"));
  assertThrows(IllegalStateException.class,()->versions.changed("tenant-a"));
 }
}
