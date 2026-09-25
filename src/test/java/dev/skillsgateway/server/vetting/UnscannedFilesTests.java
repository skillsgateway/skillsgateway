package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Files a pattern vetter could not read are reported once per reason, not once per file, and a
 * pass that skipped files says so in its coverage summary (GW_VETTING_0043).
 */
class UnscannedFilesTests {

    private static InMemorySnapshot snapshot(int oversizeImages, int oversizeDocs, int binaries) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("README.md", "# fine\n".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < oversizeImages; i++) {
            files.put("assets/shot-%02d.png".formatted(i), null);
        }
        for (int i = 0; i < oversizeDocs; i++) {
            files.put("docs/big-%02d.md".formatted(i), null);
        }
        for (int i = 0; i < binaries; i++) {
            files.put("assets/icon-%02d.ico".formatted(i), new byte[] {(byte) 0xff, (byte) 0xfe, 0, (byte) 0xc3});
        }
        return new InMemorySnapshot(files);
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0043"})
    void theSecretScanReportsSkippedFilesAsOneEntryPerReason() {
        Verdict verdict = new SecretScanVetter().vet(snapshot(25, 0, 3));

        assertThat(verdict.state()).isEqualTo(VerdictState.PASS);
        assertThat(verdict.findings()).hasSize(2).allSatisfy(finding -> {
            assertThat(finding.id()).isEqualTo("file-not-scanned");
            assertThat(finding.severity()).isEqualTo(Severity.INFO);
        });
        assertThat(verdict.findings().getFirst().message())
                .startsWith("25 file(s) not scanned: over the scan size limit: assets/shot-00.png, ")
                .contains("assets/shot-19.png")
                .doesNotContain("assets/shot-20.png")
                .endsWith("(+5 more)");
        assertThat(verdict.findings().get(1).message()).startsWith("3 file(s) not scanned: binary");
        assertThat(verdict.summary())
                .contains("scanned 1 text file(s)")
                .contains("28 file(s) not scanned (25 over the size limit, 3 binary)");
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0043"})
    void thePromptInjectionScanReportsSkippedInstructionFilesTheSameWay() {
        Verdict verdict = new PromptInjectionVetter().vet(snapshot(4, 2, 0));

        // Only instruction content is selected, so the oversize images are not its business.
        assertThat(verdict.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(Severity.INFO);
            assertThat(finding.message())
                    .isEqualTo("2 file(s) of instruction content not scanned: over the scan size limit:"
                            + " docs/big-00.md, docs/big-01.md");
        });
        assertThat(verdict.summary()).contains("2 file(s) not scanned (2 over the size limit)");
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0043"})
    void aSnapshotWithNothingSkippedCarriesNoEntryAndNoClause() {
        for (Vetter vetter : List.of(new SecretScanVetter(), new PromptInjectionVetter())) {
            Verdict verdict = vetter.vet(snapshot(0, 0, 0));

            assertThat(verdict.findings()).as(vetter.name()).isEmpty();
            assertThat(verdict.summary()).as(vetter.name()).doesNotContain("not scanned");
        }
    }
}
