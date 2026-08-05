package io.github.monssifechadli99.transactiq.investigation_assistant.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class OpenAiApiKeyStartupTest {

    private static final String FAKE_COMMON_API_KEY = "cycle6a-fake-common-api-key";
    private static final String QUESTION_SENTINEL = "SDK_QUESTION_SENTINEL_6A";
    private static final String EVIDENCE_SENTINEL = "SDK_EVIDENCE_SENTINEL_6A";
    private static final String RATIONALE_SENTINEL = "SDK_RATIONALE_SENTINEL_6A";
    private static final String CREDENTIAL_SENTINEL = "SDK_FAKE_CREDENTIAL_SENTINEL_6A";
    private static final String PROVIDER_PAYLOAD_SENTINEL = "SDK_PROVIDER_PAYLOAD_SENTINEL_6A";
    private static final String PROVIDER_RESPONSE_SENTINEL = "SDK_PROVIDER_RESPONSE_SENTINEL_6A";
    private static final String SDK_LOG_REJECTED_MARKER = "OPENAI_SDK_DIAGNOSTICS_REJECTED";

    @TempDir
    Path tempDirectory;

    @Test
    void missingApiKeyFailsContextBeforeAnyOpenAiRequest() throws Exception {
        assertStartupFailsWithoutOutboundRequest(Map.of());
    }

    @Test
    void blankApiKeyFailsContextBeforeAnyOpenAiRequest() throws Exception {
        assertStartupFailsWithoutOutboundRequest(Map.of("spring.ai.openai.api-key", "   "));
    }

    @Test
    void emptyEmbeddingSpecificApiKeyOverridesCommonKeyAndFailsBeforeAnyOpenAiRequest() throws Exception {
        assertStartupFailsWithoutOutboundRequest(Map.of(
                "spring.ai.openai.api-key", FAKE_COMMON_API_KEY,
                "spring.ai.openai.embedding.api-key", ""));
    }

    @Test
    void whitespaceEmbeddingSpecificApiKeyOverridesCommonKeyAndFailsBeforeAnyOpenAiRequest() throws Exception {
        assertStartupFailsWithoutOutboundRequest(Map.of(
                "spring.ai.openai.api-key", FAKE_COMMON_API_KEY,
                "spring.ai.openai.embedding.api-key", " \t "));
    }

    @Test
    void validCommonApiKeyIsUsedAsFallbackWithoutAnyStartupRequest() throws Exception {
        try (LoopbackTrap trap = LoopbackTrap.start()) {
            Map<String, Object> properties = startupProperties(trap, Map.of(
                    "spring.ai.openai.api-key", FAKE_COMMON_API_KEY));

            try (ConfigurableApplicationContext context = runApplication(properties)) {
                assertTrue(context.isActive());
                assertNotNull(context.getBean(EmbeddingPort.class));
            }

            assertEquals(0, trap.outboundRequests(), "valid startup must not make a provider request");
        }
    }

    @Test
    void validEmbeddingSpecificKeyStartsWithTheRealApplicationConfigurationAndNoCommonKey() throws Exception {
        try (LoopbackTrap trap = LoopbackTrap.start()) {
            Map<String, Object> properties = startupProperties(trap, Map.of(
                    "spring.ai.openai.embedding.api-key", "cycle6a-fake-embedding-specific-api-key"));
            properties.remove("spring.config.name");

            try (ConfigurableApplicationContext context = runApplication(properties)) {
                assertTrue(context.isActive());
                assertNotNull(context.getBean(EmbeddingPort.class));
            }

            assertEquals(0, trap.outboundRequests(), "valid startup must not make a provider request");
        }
    }

    @Test
    void openAiSdkDebugEnvironmentIsRejectedInIsolatedProcessWithoutLeakingOrCallingProvider() throws Exception {
        try (LoopbackTrap trap = LoopbackTrap.startWithResponse(PROVIDER_RESPONSE_SENTINEL)) {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    javaExecutable(),
                    "-jar",
                    createClasspathJar().toString(),
                    trap.baseUrl());
            processBuilder.environment().put("OPENAI_LOG", "debug");
            processBuilder.redirectErrorStream(true);
            Path processOutput = tempDirectory.resolve("openai-sdk-log-probe-output.txt");
            processBuilder.redirectOutput(processOutput.toFile());

            Process process = processBuilder.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            assertTrue(finished, "isolated OPENAI_LOG startup probe timed out");

            String output = Files.readString(processOutput, StandardCharsets.UTF_8);
            assertSensitiveValuesAbsent(output);
            assertEquals(0, process.exitValue(), "isolated OPENAI_LOG startup probe failed");
            assertTrue(output.contains(SDK_LOG_REJECTED_MARKER),
                    "isolated process did not report the safe diagnostic logging rejection");
            assertEquals(0, trap.outboundRequests(),
                    "unsafe SDK diagnostics must be rejected before any loopback provider request");
        }
    }

    private static void assertStartupFailsWithoutOutboundRequest(Map<String, Object> overrides) throws Exception {
        try (LoopbackTrap trap = LoopbackTrap.start()) {
            Map<String, Object> properties = startupProperties(trap, overrides);

            RuntimeException failure = assertThrows(RuntimeException.class, () -> runApplication(properties));

            assertTrue(hasMessage(failure, EmbeddingConfiguration.OPENAI_API_KEY_CONFIGURATION_ERROR),
                    () -> "unexpected startup failure: " + failure);
            assertEquals(0, trap.outboundRequests(),
                    "invalid configuration must fail before any provider request");
        }
    }

    private static ConfigurableApplicationContext runApplication(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("isolatedTestProperties", properties));
        return new SpringApplicationBuilder(KeyValidationApplication.class)
                .environment(environment)
                .web(WebApplicationType.NONE)
                .run();
    }

    private static Map<String, Object> startupProperties(
            LoopbackTrap trap,
            Map<String, Object> overrides) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.config.name", "transactiq-openai-key-startup-test-no-config");
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.main.log-startup-info", "false");
        properties.put("logging.level.root", "OFF");
        properties.put("spring.ai.model.chat", "none");
        properties.put("spring.ai.model.embedding", "openai");
        properties.put("spring.ai.openai.base-url", trap.baseUrl());
        properties.putAll(overrides);
        return properties;
    }

    private static void assertSensitiveValuesAbsent(String output) {
        assertFalse(output.contains(QUESTION_SENTINEL), "analyst question leaked from isolated process");
        assertFalse(output.contains(EVIDENCE_SENTINEL), "evidence leaked from isolated process");
        assertFalse(output.contains(RATIONALE_SENTINEL), "rationale leaked from isolated process");
        assertFalse(output.contains(CREDENTIAL_SENTINEL), "credential leaked from isolated process");
        assertFalse(output.contains(PROVIDER_PAYLOAD_SENTINEL), "provider request payload leaked from isolated process");
        assertFalse(output.contains(PROVIDER_RESPONSE_SENTINEL), "provider response payload leaked from isolated process");
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private Path createClasspathJar() throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, DiagnosticLoggingProbe.class.getName());
        attributes.put(Attributes.Name.CLASS_PATH, Arrays.stream(System.getProperty("java.class.path")
                        .split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(Path::of)
                .map(Path::toUri)
                .map(uri -> uri.toASCIIString())
                .reduce((left, right) -> left + " " + right)
                .orElseThrow());

        Path classpathJar = tempDirectory.resolve("openai-sdk-log-probe-classpath.jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(classpathJar), manifest)) {
            // The manifest supplies the child JVM's complete test runtime classpath.
        }
        return classpathJar;
    }

    private static boolean hasMessage(Throwable error, String expected) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) {
                return true;
            }
        }
        return false;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(EmbeddingConfiguration.class)
    static class KeyValidationApplication {
    }

    public static final class DiagnosticLoggingProbe {

        private DiagnosticLoggingProbe() {
        }

        public static void main(String[] arguments) {
            if (arguments.length != 1) {
                System.out.print("INVALID_PROBE_CONFIGURATION");
                System.exit(4);
            }

            String providerInput = String.join(" ",
                    QUESTION_SENTINEL,
                    EVIDENCE_SENTINEL,
                    RATIONALE_SENTINEL,
                    PROVIDER_PAYLOAD_SENTINEL);
            String[] startupArguments = {
                    "--spring.config.name=transactiq-openai-sdk-log-probe-no-config",
                     "--debug=false",
                     "--trace=false",
                     "--OPENAI_LOG=off",
                     "--spring.main.banner-mode=off",
                    "--spring.main.log-startup-info=false",
                    "--logging.level.root=OFF",
                    "--spring.ai.model.chat=none",
                    "--spring.ai.model.embedding=openai",
                    "--spring.ai.openai.api-key=" + CREDENTIAL_SENTINEL,
                    "--spring.ai.openai.base-url=" + arguments[0],
                    "--spring.ai.openai.max-retries=0",
                    "--spring.ai.openai.timeout=PT0.2S"
            };

            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(KeyValidationApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(startupArguments)) {
                context.getBean(EmbeddingPort.class).embed(providerInput);
                System.out.print("UNSAFE_OPENAI_SDK_DIAGNOSTICS_ACCEPTED");
                System.exit(2);
            } catch (RuntimeException failure) {
                if (hasMessage(failure, EmbeddingConfiguration.OPENAI_SDK_LOG_CONFIGURATION_ERROR)) {
                    System.out.print(SDK_LOG_REJECTED_MARKER);
                    return;
                }
                System.out.print("UNEXPECTED_PROBE_STARTUP_FAILURE");
                System.exit(3);
            }
        }
    }

    private record LoopbackTrap(HttpServer server, AtomicInteger requestCount) implements AutoCloseable {

        static LoopbackTrap start() throws IOException {
            return startWithResponse("");
        }

        static LoopbackTrap startWithResponse(String responseBody) throws IOException {
            AtomicInteger requests = new AtomicInteger();
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return new LoopbackTrap(server, requests);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int outboundRequests() {
            return requestCount.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
