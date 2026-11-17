package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.persistence.AccessToken;
import dev.skillsgateway.server.persistence.TokenRepository;
import io.github.reqstool.annotations.SVCs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The upgrade that removes gateway-hosted marketplaces, run against real PostgreSQL schemas staged
 * at each earlier version with rows the earlier schema allowed (GW_AUTH_0090).
 *
 * <p>Each case builds its own schema inside the suite's PostgreSQL, so Flyway runs the real
 * migration files from the classpath against it; nothing here is a copy of the migration. A schema
 * rather than a database because the schema is the unit Flyway migrates, and because it needs no
 * credential the application's own connection does not already hold.
 */
class HostedRemovalMigrationTests extends AbstractGatewayTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private DataSource dataSource;

    private final List<String> schemas = new ArrayList<>();

    @BeforeEach
    void clearSchemas() {
        schemas.clear();
    }

    @AfterEach
    void dropSchemas() throws SQLException {
        for (String schema : schemas) {
            try (Connection connection = dataSource.getConnection()) {
                connection.createStatement().execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private String newSchema() {
        String schema = "mig_" + SEQ.incrementAndGet() + "_" + Long.toString(System.nanoTime(), 36);
        schemas.add(schema);
        return schema;
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    /** A JdbcClient pinned to one connection whose search path is the schema; undone on close. */
    private static final class Pinned implements AutoCloseable {

        private final Connection connection;
        final JdbcClient jdbc;

        Pinned(DataSource dataSource, String schema) throws SQLException {
            this.connection = dataSource.getConnection();
            connection.createStatement().execute("SET search_path TO " + schema);
            this.jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
        }

        @Override
        public void close() throws SQLException {
            connection.createStatement().execute("RESET search_path");
            connection.close();
        }
    }

    /** Rows every schema generation allowed: upstream marketplaces with snapshots, every token kind, a ledger. */
    private void seedWithoutHosting(JdbcClient jdbc) {
        jdbc.sql("INSERT INTO marketplaces (name, url, created_at) VALUES"
                        + " ('alpha', 'https://forge.invalid/a.git', now()),"
                        + " ('beta', 'https://forge.invalid/b.git', now())")
                .update();
        jdbc.sql("INSERT INTO snapshots (marketplace_id, sha, upstream_sha, state, created_at)"
                        + " SELECT id, repeat('a', 40), repeat('b', 40), 'approved', now() FROM marketplaces"
                        + " WHERE name = 'alpha'")
                .update();
        jdbc.sql("INSERT INTO fetch_log (ts, source, marketplace, event)" + " VALUES (now(), 'test', 'alpha', 'fetch')")
                .update();
        token(jdbc, "plain", null, null, false, null, null);
        token(jdbc, "scoped", "{alpha}", null, false, null, null);
        token(jdbc, "session", null, null, true, null, null);
        token(jdbc, "machine", null, null, false, "{policy:read}", "now() + interval '1 day'");
        // Tokens that carry a push scope. "pushonly" has no fetch scope narrowing, so after the upgrade it
        // is exactly what it already was for fetch: a token that fetches every marketplace.
        token(jdbc, "pushonly", null, "{ghost}", false, null, null);
        token(jdbc, "pushscoped", "{alpha}", "{ghost}", false, null, null);
    }

    private void token(
            JdbcClient jdbc,
            String name,
            String scopes,
            String pushScopes,
            boolean session,
            String apiScopes,
            String expires) {
        jdbc.sql("INSERT INTO access_tokens (principal, name, token_hash, created_at, scopes, push_scopes,"
                        + " session_derived, api_scopes, expires_at) VALUES ('p', :name, :hash, now(),"
                        + " :scopes::text[], :push::text[], :session, :api::text[], "
                        + (expires == null ? "NULL" : expires) + ")")
                .param("name", name)
                .param("hash", "hash-" + name)
                .param("scopes", scopes)
                .param("push", pushScopes)
                .param("session", session)
                .param("api", apiScopes)
                .update();
    }

    private List<String> rows(JdbcClient jdbc, String table, String minus) {
        return jdbc.sql("SELECT (to_jsonb(t)" + minus + ")::text FROM " + table + " t ORDER BY id")
                .query(String.class)
                .list();
    }

    private Map<String, List<String>> dump(JdbcClient jdbc, boolean beforeUpgrade) {
        return Map.of(
                "marketplaces", rows(jdbc, "marketplaces", beforeUpgrade ? " - 'origin' - 'push_policy'" : ""),
                "snapshots", rows(jdbc, "snapshots", ""),
                "fetch_log", rows(jdbc, "fetch_log", ""),
                "access_tokens", rows(jdbc, "access_tokens", beforeUpgrade ? " - 'push_scopes'" : ""));
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0110"})
    void a_database_without_a_hosted_marketplace_upgrades_from_each_earlier_version_keeping_every_row()
            throws Exception {
        for (String start : List.of("1", "2")) {
            String schema = newSchema();
            flyway(schema, start).migrate();
            try (Pinned db = new Pinned(dataSource, schema)) {
                seedWithoutHosting(db.jdbc);
                Map<String, List<String>> before = dump(db.jdbc, true);

                flyway(schema, "3").migrate();

                assertThat(dump(db.jdbc, false))
                        .as("every row and every surviving column, upgraded from V" + start)
                        .isEqualTo(before);
                assertThat(columnsOf(db.jdbc, "marketplaces")).doesNotContain("origin", "push_policy");
                assertThat(columnsOf(db.jdbc, "access_tokens")).doesNotContain("push_scopes");
                assertThat(enumTypes(db.jdbc)).doesNotContain("marketplace_origin", "marketplace_push_policy");
                assertThat(db.jdbc
                                .sql("SELECT is_nullable FROM information_schema.columns WHERE table_schema ="
                                        + " current_schema() AND table_name = 'marketplaces' AND column_name = 'url'")
                                .query(String.class)
                                .single())
                        .as("nothing may lack a URL any more")
                        .isEqualTo("NO");
                assertThat(constraints(db.jdbc))
                        .doesNotContain("marketplaces_upstream_has_url", "marketplaces_hosted_is_on_demand")
                        .doesNotContain("marketplaces_live_has_url")
                        .contains("scope_lists_are_never_empty");
                assertThat(db.jdbc
                                .sql("SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c"
                                        + " JOIN pg_namespace n ON n.oid = c.connamespace"
                                        + " WHERE n.nspname = current_schema()"
                                        + " AND c.conname = 'scope_lists_are_never_empty'")
                                .query(String.class)
                                .single())
                        .doesNotContain("push_scopes");

                // Tokens still authenticate for fetch, with the fetch scopes they had.
                TokenRepository tokens = new TokenRepository(db.jdbc);
                assertThat(permits(tokens, "plain", "alpha")).isTrue();
                assertThat(permits(tokens, "scoped", "alpha")).isTrue();
                assertThat(permits(tokens, "scoped", "beta")).isFalse();
                assertThat(permits(tokens, "session", "beta")).isTrue();
                assertThat(permits(tokens, "machine", "alpha")).isFalse();
                // A token whose only grant was a push scope keeps what its fetch scope already was.
                assertThat(permits(tokens, "pushonly", "beta")).isTrue();
                assertThat(permits(tokens, "pushscoped", "alpha")).isTrue();
                assertThat(permits(tokens, "pushscoped", "beta")).isFalse();
            }
        }
    }

    private boolean permits(TokenRepository tokens, String name, String marketplace) {
        AccessToken token = tokens.findActiveByHash("hash-" + name).orElseThrow();
        return token.permitsMarketplace(marketplace);
    }

    /** What one marketplace's history contributes to each table (see {@link #history}). */
    private static final Map<String, Integer> PER_HISTORY = Map.ofEntries(
            Map.entry("marketplaces", 1),
            Map.entry("snapshots", 2),
            Map.entry("snapshot_closures", 1),
            Map.entry("snapshot_closure_members", 1),
            Map.entry("snapshot_facts", 1),
            Map.entry("snapshot_plugin_names", 1),
            Map.entry("vetting_runs", 1),
            Map.entry("vetting_verdicts", 1),
            Map.entry("vetting_findings", 1),
            Map.entry("snapshot_vetting_overrides", 1),
            Map.entry("snapshot_revocation_reversals", 1),
            Map.entry("vetting_waivers", 1),
            Map.entry("auto_approval_exemptions", 1),
            Map.entry("role_grants", 1),
            Map.entry("vetter_toggles", 1),
            Map.entry("vetting_chain_modes", 1),
            Map.entry("vetting_chain_orders", 1));

    /**
     * One marketplace and everything the schema lets hang off it: two snapshots, a closure with a
     * member, facts, a vetting run with a verdict and a finding, an override, a revocation reversal, a
     * waiver, an exemption, a grant, a vetter toggle, the chain's mode and order, and two ledger
     * entries recorded under its identity. {@link #PER_HISTORY} is what each table gains.
     */
    private long history(JdbcClient jdbc, String name, String url, boolean hosted, boolean removed) {
        long m = jdbc.sql("INSERT INTO marketplaces (name, url, created_at, origin, deleted_at, deleted_by,"
                        + " deleted_reason) VALUES (:name, :url, now(), :origin::marketplace_origin,"
                        + (removed ? " now(), 'admin', 'removed'" : " NULL, NULL, NULL")
                        + ") RETURNING id")
                .param("name", name)
                .param("url", url)
                .param("origin", hosted ? "hosted" : "upstream")
                .query(Long.class)
                .single();
        long s1 = snapshot(jdbc, m, "1", "approved");
        long s2 = snapshot(jdbc, m, "2", "held");
        long closure = jdbc.sql("INSERT INTO snapshot_closures (snapshot_id, digest, upstream_sha, transformer_version,"
                        + " created_at) VALUES (:s, 'digest', 'up', 't1', now()) RETURNING id")
                .param("s", s1)
                .query(Long.class)
                .single();
        jdbc.sql("INSERT INTO snapshot_closure_members (closure_id, plugin_name, source_type, declared_source,"
                        + " clone_url, resolved_sha, tree_sha, graft_path, object_count, inflated_bytes)"
                        + " VALUES (:c, 'p', 'github', 'o/r', 'https://forge.invalid/o/r.git', 'r', 't', '_plugins/p',"
                        + " 1, 1)")
                .param("c", closure)
                .update();
        jdbc.sql("INSERT INTO snapshot_facts (snapshot_id, built_at) VALUES (:s, now())")
                .param("s", s1)
                .update();
        jdbc.sql("INSERT INTO snapshot_plugin_names (snapshot_id, plugin_name, location) VALUES (:s, 'p', 'x')")
                .param("s", s1)
                .update();
        long run = jdbc.sql("INSERT INTO vetting_runs (snapshot_id, trigger, started_at, outcome)"
                        + " VALUES (:s, 'ingestion', now(), 'clear') RETURNING id")
                .param("s", s1)
                .query(Long.class)
                .single();
        long verdict = jdbc.sql("INSERT INTO vetting_verdicts (run_id, vetter, position, state, created_at)"
                        + " VALUES (:r, 'secret-scan', 0, 'pass', now()) RETURNING id")
                .param("r", run)
                .query(Long.class)
                .single();
        jdbc.sql("INSERT INTO vetting_findings (verdict_id, finding_id, severity, message)"
                        + " VALUES (:v, 'f1', 'low', 'a finding')")
                .param("v", verdict)
                .update();
        jdbc.sql("INSERT INTO snapshot_vetting_overrides (snapshot_id, reason, overridden_by, overridden_at)"
                        + " VALUES (:s, 'because', 'admin', now())")
                .param("s", s1)
                .update();
        jdbc.sql(
                        "INSERT INTO snapshot_revocation_reversals (snapshot_id, revocation_reason, revoked_by, revoked_at,"
                                + " reason, reversed_by, reversed_at) VALUES (:s, 'r', 'admin', now(), 'r', 'second-admin', now())")
                .param("s", s2)
                .update();
        jdbc.sql("INSERT INTO vetting_waivers (marketplace_id, rule_id, scope_kind, scope_value, justification,"
                        + " approved_by, created_at, expires_at) VALUES (:m, 'rule', 'path', 'a/b', 'j', 'admin', now(),"
                        + " now() + interval '1 day')")
                .param("m", m)
                .update();
        jdbc.sql("INSERT INTO auto_approval_exemptions (marketplace_id, reason, created_by, created_at, expires_at)"
                        + " VALUES (:m, 'r', 'admin', now(), now() + interval '1 day')")
                .param("m", m)
                .update();
        jdbc.sql("INSERT INTO role_grants (principal, role, granted_by, granted_at, marketplace_id)"
                        + " VALUES ('someone', 'approver', 'admin', now(), :m)")
                .param("m", m)
                .update();
        jdbc.sql("INSERT INTO vetter_toggles (vetter, enabled, updated_by, updated_at, marketplace_id)"
                        + " VALUES ('secret-scan', false, 'admin', now(), :m)")
                .param("m", m)
                .update();
        jdbc.sql("INSERT INTO vetting_chain_modes (marketplace_id, mode, updated_by, updated_at)"
                        + " VALUES (:m, 'run-all', 'admin', now())")
                .param("m", m)
                .update();
        jdbc.sql("INSERT INTO vetting_chain_orders (marketplace_id, vetters, updated_by, updated_at)"
                        + " VALUES (:m, '{secret-scan}', 'admin', now())")
                .param("m", m)
                .update();
        for (String event : List.of("fetch", "snapshot-approved")) {
            jdbc.sql("INSERT INTO fetch_log (ts, source, marketplace, event, marketplace_id)"
                            + " VALUES (now(), 'test', :name, :event, :m)")
                    .param("name", name)
                    .param("event", event)
                    .param("m", m)
                    .update();
        }
        return m;
    }

    private long snapshot(JdbcClient jdbc, long marketplaceId, String tag, String state) {
        return jdbc.sql("INSERT INTO snapshots (marketplace_id, sha, upstream_sha, state, created_at)"
                        + " VALUES (:m, substr(md5(:m::text || :tag), 1, 40), substr(md5(:tag || :m::text), 1, 40),"
                        + " :state::snapshot_state, now()) RETURNING id")
                .param("m", marketplaceId)
                .param("tag", tag)
                .param("state", state)
                .query(Long.class)
                .single();
    }

    private static void ledgerByName(JdbcClient jdbc, String name, int times) {
        for (int i = 0; i < times; i++) {
            jdbc.sql("INSERT INTO fetch_log (ts, source, marketplace, event) VALUES (now(), 'old', :name, 'fetch')")
                    .param("name", name)
                    .update();
        }
    }

    /** A digest of every path and byte under a directory, so "untouched" is a comparison and not a hope. */
    private static String treeDigest(Path root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted().toList()) {
                digest.update(root.relativize(path).toString().getBytes(StandardCharsets.UTF_8));
                if (Files.isRegularFile(path)) {
                    digest.update(Files.readAllBytes(path));
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String ids(List<Long> ids) {
        return ids.isEmpty() ? "NULL" : ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0110"})
    void every_hosted_marketplace_is_purged_with_its_history_and_nothing_else_is_touched() throws Exception {
        for (String start : List.of("1", "2")) {
            String schema = newSchema();
            flyway(schema, start).migrate();
            Path hostedRepository =
                    Files.createDirectories(newWorkDir("mig-hosted").resolve("hosted/livehosted.git/refs/heads"));
            Files.writeString(hostedRepository.resolve("main"), "0123456789012345678901234567890123456789\n");
            Files.writeString(hostedRepository.getParent().getParent().resolve("HEAD"), "ref: refs/heads/main\n");
            String repositoryBefore = treeDigest(hostedRepository.getParent().getParent());

            try (Pinned db = new Pinned(dataSource, schema)) {
                JdbcClient jdbc = db.jdbc;
                seedWithoutHosting(jdbc);
                long keeper = history(jdbc, "keeper", "https://forge.invalid/keeper.git", false, false);
                long liveHosted = history(jdbc, "livehosted", null, true, false);
                long goneHosted = history(jdbc, "gonehosted", null, true, true);
                long reusedOld = history(jdbc, "reused", null, true, true);
                long reusedNew = history(jdbc, "reused", "https://forge.invalid/reused.git", false, false);
                // Older ledger entries that never recorded an identity, and sightings, which are keyed by name.
                ledgerByName(jdbc, "gonehosted", 2);
                ledgerByName(jdbc, "livehosted", 1);
                ledgerByName(jdbc, "reused", 2);
                ledgerByName(jdbc, "keeper", 1);
                for (String name : List.of("gonehosted", "livehosted", "reused", "keeper")) {
                    jdbc.sql("INSERT INTO staging_ref_sightings (marketplace, ref, first_seen_at)"
                                    + " VALUES (:n, 'refs/staging/x', now())")
                            .param("n", name)
                            .update();
                }
                List<Long> purged = List.of(liveHosted, goneHosted, reusedOld);
                List<Long> survivors = jdbc.sql("SELECT id FROM marketplaces WHERE id NOT IN (" + ids(purged) + ")")
                        .query(Long.class)
                        .list();
                assertThat(survivors).contains(keeper, reusedNew).hasSize(4);
                String m = ids(survivors);
                String s = "SELECT id FROM snapshots WHERE marketplace_id IN (" + m + ")";
                String purgedSnapshots = "SELECT id FROM snapshots WHERE marketplace_id IN (" + ids(purged) + ")";
                List<Long> purgedSnapshotIds =
                        jdbc.sql(purgedSnapshots).query(Long.class).list();
                String runs = "SELECT id FROM vetting_runs WHERE snapshot_id IN (" + s + ")";
                String verdicts = "SELECT id FROM vetting_verdicts WHERE run_id IN (" + runs + ")";
                String closures = "SELECT id FROM snapshot_closures WHERE snapshot_id IN (" + s + ")";
                Map<String, String> survivorRows = new LinkedHashMap<>();
                survivorRows.put("marketplaces", "id IN (" + m + ")");
                survivorRows.put("snapshots", "marketplace_id IN (" + m + ")");
                survivorRows.put("snapshot_closures", "snapshot_id IN (" + s + ")");
                survivorRows.put("snapshot_closure_members", "closure_id IN (" + closures + ")");
                survivorRows.put("snapshot_facts", "snapshot_id IN (" + s + ")");
                survivorRows.put("snapshot_plugin_names", "snapshot_id IN (" + s + ")");
                survivorRows.put("vetting_runs", "snapshot_id IN (" + s + ")");
                survivorRows.put("vetting_verdicts", "run_id IN (" + runs + ")");
                survivorRows.put("vetting_findings", "verdict_id IN (" + verdicts + ")");
                survivorRows.put("snapshot_vetting_overrides", "snapshot_id IN (" + s + ")");
                survivorRows.put("snapshot_revocation_reversals", "snapshot_id IN (" + s + ")");
                for (String table : List.of(
                        "vetting_waivers",
                        "auto_approval_exemptions",
                        "role_grants",
                        "vetter_toggles",
                        "vetting_chain_modes",
                        "vetting_chain_orders")) {
                    survivorRows.put(table, "marketplace_id IN (" + m + ")");
                }
                // The ledger keeps entries by surviving identity, and the older entries whose name a survivor carries.
                survivorRows.put(
                        "fetch_log",
                        "marketplace_id IN (" + m + ") OR (marketplace_id IS NULL AND marketplace NOT IN"
                                + " ('gonehosted', 'livehosted'))");
                survivorRows.put("staging_ref_sightings", "marketplace IN ('keeper', 'reused')");

                Map<String, Long> totalsBefore = new LinkedHashMap<>();
                Map<String, List<String>> survivorsBefore = new LinkedHashMap<>();
                for (Map.Entry<String, String> table : survivorRows.entrySet()) {
                    totalsBefore.put(table.getKey(), count(jdbc, table.getKey(), "true"));
                    String minus = table.getKey().equals("marketplaces") ? " - 'origin' - 'push_policy'" : "";
                    survivorsBefore.put(
                            table.getKey(),
                            jdbc.sql("SELECT (to_jsonb(t)" + minus + ")::text FROM " + table.getKey() + " t WHERE "
                                            + table.getValue() + " ORDER BY 1")
                                    .query(String.class)
                                    .list());
                }
                List<String> tokensBefore = rows(jdbc, "access_tokens", " - 'push_scopes'");

                flyway(schema, "3").migrate();

                for (Map.Entry<String, String> table : survivorRows.entrySet()) {
                    String name = table.getKey();
                    assertThat(sortedRows(jdbc, name))
                            .as(
                                    "%s after the upgrade from V%s: exactly the rows that survive, byte for byte",
                                    name, start)
                            .isEqualTo(survivorsBefore.get(name));
                    long purgedRows = totalsBefore.get(name) - count(jdbc, name, "true");
                    if (PER_HISTORY.containsKey(name)) {
                        assertThat(purgedRows)
                                .as("%s rows purged from V%s", name, start)
                                .isEqualTo(3L * PER_HISTORY.get(name));
                    }
                }
                assertThat(totalsBefore.get("fetch_log") - count(jdbc, "fetch_log", "true"))
                        .as("ledger: two by identity for each of three, and three older entries under purged names"
                                + " nobody else carries")
                        .isEqualTo(3 * 2 + 3);
                assertThat(count(jdbc, "fetch_log", "marketplace = 'reused' AND marketplace_id IS NULL"))
                        .as("the older entries under a reused name cannot be told from its successor's, so stay")
                        .isEqualTo(2);
                assertThat(count(jdbc, "fetch_log", "marketplace_id = " + reusedNew))
                        .as("the successor keeps every entry of its own")
                        .isEqualTo(2);
                assertThat(jdbc.sql("SELECT marketplace FROM staging_ref_sightings ORDER BY marketplace")
                                .query(String.class)
                                .list())
                        .containsExactly("keeper", "reused");
                assertThat(rows(jdbc, "access_tokens", "")).isEqualTo(tokensBefore);

                // No row of any table that has such a column refers to a purged marketplace or snapshot.
                List<String> referencing = jdbc.sql("SELECT table_name || '.' || column_name FROM"
                                + " information_schema.columns WHERE table_schema = current_schema() AND"
                                + " column_name IN ('marketplace_id', 'snapshot_id', 'last_ingest_snapshot_id')")
                        .query(String.class)
                        .list();
                assertThat(referencing).contains("snapshots.marketplace_id", "vetting_runs.snapshot_id");
                for (String ref : referencing) {
                    String table = ref.substring(0, ref.indexOf('.'));
                    String column = ref.substring(ref.indexOf('.') + 1);
                    List<Long> bad = column.equals("marketplace_id")
                            ? purged
                            : (column.equals("snapshot_id") || column.equals("last_ingest_snapshot_id")
                                    ? purgedSnapshotIds
                                    : List.<Long>of());
                    assertThat(count(jdbc, table, column + " IN (" + ids(bad) + ")"))
                            .as("%s refers to a purged id", ref)
                            .isZero();
                }
                assertThat(permits(new TokenRepository(jdbc), "pushonly", "beta"))
                        .isTrue();
            }
            assertThat(treeDigest(hostedRepository.getParent().getParent()))
                    .as("the migration never touches a hosted repository")
                    .isEqualTo(repositoryBefore);
        }
    }

    private long count(JdbcClient jdbc, String table, String where) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + where)
                .query(Long.class)
                .single();
    }

    private List<String> sortedRows(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT to_jsonb(t)::text FROM " + table + " t ORDER BY 1")
                .query(String.class)
                .list();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0110"})
    void a_fresh_database_and_an_upgraded_one_have_the_same_schema() throws Exception {
        String fresh = newSchema();
        flyway(fresh, "3").migrate();
        String fromV1 = newSchema();
        flyway(fromV1, "1").migrate();
        String fromV2 = newSchema();
        flyway(fromV2, "1").migrate();
        flyway(fromV2, "2").migrate();
        for (String schema : List.of(fromV1, fromV2)) {
            try (Pinned db = new Pinned(dataSource, schema)) {
                seedWithoutHosting(db.jdbc);
            }
            flyway(schema, "3").migrate();
        }
        List<String> expected;
        try (Pinned db = new Pinned(dataSource, fresh)) {
            expected = signature(db.jdbc, fresh);
        }
        assertThat(expected).isNotEmpty();
        for (String upgraded : List.of(fromV1, fromV2)) {
            try (Pinned db = new Pinned(dataSource, upgraded)) {
                assertThat(signature(db.jdbc, upgraded)).isEqualTo(expected);
            }
        }
        // The schema the running application migrated for itself is the same again.
        try (Connection connection = dataSource.getConnection()) {
            String current = connection.getSchema();
            try (Pinned db = new Pinned(dataSource, current)) {
                assertThat(signature(db.jdbc, current))
                        .as("the application's own schema, less the migration history table")
                        .isEqualTo(expected);
            }
        }
    }

    private List<String> columnsOf(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = current_schema() AND table_name = :t")
                .param("t", table)
                .query(String.class)
                .list();
    }

    private List<String> enumTypes(JdbcClient jdbc) {
        return jdbc.sql("SELECT typname FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace"
                        + " WHERE n.nspname = current_schema() AND t.typtype = 'e'")
                .query(String.class)
                .list();
    }

    private List<String> constraints(JdbcClient jdbc) {
        return jdbc.sql("SELECT conname FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace"
                        + " WHERE n.nspname = current_schema()")
                .query(String.class)
                .list();
    }

    /** Columns, constraints, enum types and indexes, with the schema's own name taken out. */
    private List<String> signature(JdbcClient jdbc, String schema) {
        List<String> out = new ArrayList<>();
        jdbc.sql("SELECT table_name || '.' || column_name || ' ' || data_type || ' ' || udt_name || ' '"
                        + " || is_nullable || ' ' || coalesce(column_default, '-')"
                        + " FROM information_schema.columns WHERE table_schema = current_schema()"
                        + " AND table_name <> 'flyway_schema_history'")
                .query(String.class)
                .list()
                .forEach(s -> out.add("col " + s.replace(schema + ".", "")));
        jdbc.sql("SELECT c.conrelid::regclass::text || ' ' || c.conname || ' ' || pg_get_constraintdef(c.oid)"
                        + " FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace"
                        + " WHERE n.nspname = current_schema()"
                        + " AND c.conrelid::regclass::text NOT LIKE '%flyway_schema_history'")
                .query(String.class)
                .list()
                .forEach(s -> out.add("con " + s.replace(schema + ".", "")));
        jdbc.sql("SELECT t.typname || ' ' || string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)"
                        + " FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace"
                        + " JOIN pg_enum e ON e.enumtypid = t.oid WHERE n.nspname = current_schema()"
                        + " GROUP BY t.typname")
                .query(String.class)
                .list()
                .forEach(s -> out.add("enum " + s));
        jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema()"
                        + " AND tablename <> 'flyway_schema_history'")
                .query(String.class)
                .list()
                .forEach(s -> out.add("idx " + s.replace(schema + ".", "")));
        out.sort(String::compareTo);
        return out;
    }
}
