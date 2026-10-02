package dev.skillsgateway.server.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import dev.skillsgateway.server.ingestion.PluginComponents.Components;
import dev.skillsgateway.server.ingestion.PluginComponents.HookModule;
import dev.skillsgateway.server.ingestion.PluginComponents.Manifest;
import dev.skillsgateway.server.ingestion.PluginComponents.Problem;
import dev.skillsgateway.server.ingestion.PluginComponents.Site;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Hook modules in the plugin layout (GW_INGEST_0065) and hook declarations of a shape the reader
 * does not recognise (GW_VETTING_0060).
 */
class HookModulesTests {

    /** Files held in memory; a {@code null} value is a file over the size limit. */
    record ByteFiles(Map<String, byte[]> files) implements PluginComponents.Files {
        @Override
        public Collection<String> paths() {
            return files.keySet();
        }

        @Override
        public byte[] read(String path) {
            return files.get(path);
        }
    }

    private static final byte[] BINARY = {(byte) 0x7f, 'E', 'L', 'F', (byte) 0xff, (byte) 0xfe, 0};

    private static Map<String, byte[]> files(Object... pairs) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object content = pairs[i + 1];
            files.put(
                    (String) pairs[i],
                    content == null
                            ? null
                            : content instanceof byte[] bytes
                                    ? bytes
                                    : ((String) content).getBytes(StandardCharsets.UTF_8));
        }
        return files;
    }

    private static Components read(Map<String, byte[]> files) {
        return PluginComponents.read(new ByteFiles(files), "p", null, null);
    }

    private static final String MODULES_JSON = """
            {
              "modules": [
                "./register.tsx"
              ]
            }
            """;

    private static final String REGISTER = """
            import type { Register } from 'claude-code'
            import { helper } from './lib/helper'
            import { util } from '../shared/util.ts'
            import './side'
            import lodash from 'lodash'
            import { gone } from './missing'
            // on('tool.call', () => {}) and $.process
            /* on('prompt.compose')
               $.fs */
            const banner = 'https://x.example/a'; $.clock.now()
            export const register: Register = (on, options) => {
              on('session.start', async ($, e, next) => {
                await $.process.run({ command: 'ls' })
                return next(e)
              })
              on("tool.call", { tool: 'Bash' }, ($, e, next) => next(e))
            }
            """;

    @Test
    @SVCs({"SVC_GW_INGEST_0065"})
    void aModuleIsListedWithItsEventsUsesImportsAndWhatCouldNotBeScanned() {
        Components components = read(files(
                "p/hooks/hooks.json",
                MODULES_JSON,
                "p/hooks/register.tsx",
                REGISTER,
                "p/hooks/lib/helper.ts",
                "import { b } from './blob.js'\nexport const helper = ($: any) =>\n"
                        + "  $.model.call({ prompt: 'x' })\n",
                "p/hooks/lib/blob.js",
                BINARY,
                "p/shared/util.ts",
                "export const util = 1\n",
                "p/hooks/side/index.ts",
                "export {}\n$.fs.read('x')\n"));

        assertThat(components.hooks()).isEmpty();
        assertThat(components.hookProblems()).isEmpty();
        assertThat(components.hookModules()).hasSize(1);
        HookModule module = components.hookModules().getFirst();
        assertThat(module.path()).isEqualTo("p/hooks/register.tsx");
        assertThat(module.location()).isEqualTo("p/hooks/hooks.json:3");
        assertThat(module.events())
                .extracting(Site::name, Site::location)
                .containsExactly(
                        tuple("session.start", "p/hooks/register.tsx:12"),
                        tuple("tool.call", "p/hooks/register.tsx:16"));
        assertThat(module.uses())
                .extracting(Site::name, Site::location)
                .containsExactly(
                        tuple("clock", "p/hooks/register.tsx:10"),
                        tuple("process", "p/hooks/register.tsx:13"),
                        tuple("model", "p/hooks/lib/helper.ts:3"),
                        tuple("fs", "p/hooks/side/index.ts:2"));
        assertThat(module.files())
                .containsExactly(
                        "p/hooks/register.tsx", "p/hooks/lib/helper.ts", "p/shared/util.ts", "p/hooks/side/index.ts");
        assertThat(module.unscanned())
                .extracting(Problem::path)
                .containsExactlyInAnyOrder("p/hooks/register.tsx:5", "p/hooks/register.tsx:6", "p/hooks/lib/blob.js");
        assertThat(module.unscanned())
                .extracting(Problem::message)
                .anySatisfy(message -> assertThat(message).contains("'lodash'"))
                .anySatisfy(message -> assertThat(message).contains("'./missing'"))
                .anySatisfy(message -> assertThat(message).contains("binary"));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0065"})
    void modulesAreReadFromAnInlinePluginManifestAndAMarketplaceEntryPath() throws IOException {
        String marketplace = """
                {"name": "m", "plugins": [
                  {"name": "p", "source": "./p", "hooks": "./extra/hooks.json"}
                ]}
                """;
        Manifest manifest =
                Manifest.parse(".claude-plugin/marketplace.json", marketplace.getBytes(StandardCharsets.UTF_8));
        Components components = PluginComponents.read(
                new ByteFiles(files(
                        "p/.claude-plugin/plugin.json",
                                "{\n\"name\": \"p\",\n\"hooks\": {\"modules\": [\"./mods/a.ts\"]}\n}",
                        "p/extra/hooks.json", "{\"modules\": [\"../mods/b.ts\"]}",
                        "p/mods/a.ts", "on('stop', h)\n",
                        "p/mods/b.ts", "\non('session.end', h)\n")),
                "p",
                manifest,
                "/plugins/0");

        assertThat(components.hookModules())
                .extracting(HookModule::path, HookModule::location)
                .containsExactly(
                        tuple("p/mods/a.ts", "p/.claude-plugin/plugin.json:3"),
                        tuple("p/mods/b.ts", "p/extra/hooks.json:1"));
        assertThat(components.hookModules().get(1).events())
                .extracting(Site::location)
                .containsExactly("p/mods/b.ts:2");
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0065"})
    void aMissingOrOversizedModuleIsListedAsNotScanned() {
        Components components = read(
                files("p/hooks/hooks.json", "{\"modules\": [\"./nope.ts\", \"./big.ts\"]}", "p/hooks/big.ts", null));

        assertThat(components.hookModules())
                .extracting(HookModule::path)
                .containsExactly("p/hooks/nope.ts", "p/hooks/big.ts");
        assertThat(components.hookModules().get(0).unscanned()).singleElement().satisfies(problem -> {
            assertThat(problem.path()).isEqualTo("p/hooks/nope.ts");
            assertThat(problem.message()).contains("not in the snapshot");
        });
        assertThat(components.hookModules().get(1).unscanned())
                .singleElement()
                .satisfies(problem -> assertThat(problem.message()).contains("size limit"));
        assertThat(components.hookModules())
                .allSatisfy(module -> assertThat(module.files()).isEmpty());
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0065"})
    void aModulePathEscapingThePluginRootIsAProblemNotAModule() {
        Components components = read(files("p/hooks/hooks.json", "{\"modules\": [\"../../outside.ts\"]}"));

        assertThat(components.hookModules()).isEmpty();
        assertThat(components.hookProblems())
                .singleElement()
                .satisfies(problem -> assertThat(problem.message()).contains("../../outside.ts"));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0065"})
    void importsResolveWithSuffixesAndIndexFilesAndCyclesEnd() {
        Components components = read(files(
                "p/hooks/hooks.json", MODULES_JSON,
                "p/hooks/register.tsx", "import a from './a'\nexport * from \"./dir\"\n",
                "p/hooks/a.mts", "import r from './register.tsx'\n",
                "p/hooks/dir/index.js", "export const x = 1\n"));

        assertThat(components.hookModules().getFirst().files())
                .containsExactly("p/hooks/register.tsx", "p/hooks/a.mts", "p/hooks/dir/index.js");
        assertThat(components.hookModules().getFirst().unscanned()).isEmpty();
    }

    // ---- GW_VETTING_0060: shapes the reader does not recognise ---------------------------------

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"modules\": \"./register.ts\"}|modules",
                "{\"hooks\": {\"Stop\": []}, \"hookz\": {}}|hookz",
                "{\"PreToolUse\": {}}|PreToolUse",
                "{\"hooks\": {\"Stop\": \"x\"}}|Stop",
                "{\"hooks\": [], \"description\": \"d\"}|hooks"
            })
    @SVCs({"SVC_GW_VETTING_0060"})
    void anUnrecognisedKeyIsOneProblemNamingIt(String fixture) {
        String[] parts = fixture.split("\\|");
        Components components = read(files("p/hooks/hooks.json", parts[0]));

        assertThat(components.hookProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.path()).startsWith("p/hooks/hooks.json");
            assertThat(problem.message()).contains("\"" + parts[1] + "\"");
        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"description\": \"d\", \"hooks\": {\"Stop\": [{\"hooks\": [{\"type\": \"command\", \"command\": \"x\"}]}]}}",
                "{\"SomeFutureEvent\": [{\"hooks\": [{\"type\": \"command\", \"command\": \"x\"}]}]}",
                "{\"modules\": [\"./m.ts\"], \"description\": \"d\"}"
            })
    @SVCs({"SVC_GW_VETTING_0060"})
    void aRecognisedShapeIsNoProblem(String hooks) {
        Components components = read(files("p/hooks/hooks.json", hooks, "p/hooks/m.ts", "export {}\n"));

        assertThat(components.hookProblems()).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0060"})
    void anUnrecognisedKeyInAnInlinePluginManifestHooksObjectIsAProblem() {
        Components components =
                read(files("p/.claude-plugin/plugin.json", "{\"name\": \"p\", \"hooks\": {\"mods\": [\"x\"]}}"));

        assertThat(components.hookProblems())
                .singleElement()
                .satisfies(problem -> assertThat(problem.message()).contains("\"mods\""));
    }
}
