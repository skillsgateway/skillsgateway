package dev.skillsgateway.server.approval;

import io.github.reqstool.annotations.Requirements;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One lock per marketplace around every change to what it serves (GW_APPROVAL_0032): an approval's
 * decision and publication, a withdrawal and its rollback, re-vetting's quarantine, and the
 * reconciler's repair. Each re-reads the record inside the lock, so none acts on a state another
 * holder is changing, and the record and the served references move together.
 *
 * <p>A transaction-scoped PostgreSQL advisory lock, so it holds across every instance of the
 * gateway and is released at commit or rollback whatever happens. The two-key form keeps it apart
 * from the single-key lock {@link NameCollisionGate} takes.
 */
@Component
public class PublicationLock {

    /** The first key of the two-key form: this lock's namespace. */
    private static final int NAMESPACE = 0x50554231;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public PublicationLock(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Runs {@code work} in one transaction holding the marketplace's publication lock. */
    @Requirements({"GW_APPROVAL_0032"})
    public <T> T holding(long marketplaceId, Supplier<T> work) {
        return transactions.execute(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:namespace, :marketplace)")
                    .param("namespace", NAMESPACE)
                    .param("marketplace", Math.toIntExact(marketplaceId))
                    .query((rs, row) -> Boolean.TRUE)
                    .single();
            return work.get();
        });
    }
}
