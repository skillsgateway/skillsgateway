package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.persistence.FetchLogRepository;
import dev.skillsgateway.server.vetting.RevetService;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A snapshot's fetchers report names only those who fetched it through its own marketplace
 * (GW_AUTH_0056): one commit served by two marketplaces is two different audiences.
 */
class FetchersScopeTests extends AbstractGatewayTest {

    @Autowired
    private RevetService revetService;

    @Test
    @SVCs({"SVC_GW_AUTH_0056"})
    void the_fetchers_of_a_commit_two_marketplaces_serve_are_reported_per_marketplace() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered first = registerAndIngest(uniqueName("fetchone"), upstream);
        Registered second = registerAndIngest(uniqueName("fetchtwo"), upstream);
        approve(first.snapshot().id());
        approve(second.snapshot().id());
        assertThat(first.snapshot().sha()).isEqualTo(second.snapshot().sha());

        String onlyFirst = uniqueName("onlyfirst");
        String onlySecond = uniqueName("onlysecond");
        assertThat(gitClone(
                                facadeUrl(
                                        first.marketplace().name(),
                                        tokenService.create(onlyFirst, "t").token()),
                                newWorkDir("f1"))
                        .exitCode())
                .isZero();
        assertThat(gitClone(
                                facadeUrl(
                                        second.marketplace().name(),
                                        tokenService.create(onlySecond, "t").token()),
                                newWorkDir("f2"))
                        .exitCode())
                .isZero();

        List<String> reported = revetService.affected(first.snapshot().id()).stream()
                .map(FetchLogRepository.Fetcher::principal)
                .toList();
        assertThat(reported).contains(onlyFirst).doesNotContain(onlySecond);
    }
}
