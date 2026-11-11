package vn.edu.bookstore.order.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import vn.edu.bookstore.order.TestcontainersConfig;

import java.net.http.HttpClient;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "bookstore.catalog.read-timeout=300ms",
    "bookstore.catalog.connect-timeout=300ms",
    "bookstore.catalog.circuit-breaker.sliding-window-size=4",
    "bookstore.catalog.circuit-breaker.minimum-number-of-calls=2",
    "bookstore.catalog.circuit-breaker.failure-rate-threshold=50",
    "bookstore.catalog.circuit-breaker.wait-duration-in-open-state=60s"
})
@Import(TestcontainersConfig.class)
class CatalogCircuitBreakerTest {

    static WireMockServer CATALOG;

    @BeforeAll
    static void startWireMock() {
        CATALOG = new WireMockServer(
                WireMockConfiguration.wireMockConfig().dynamicPort()
        );
        CATALOG.start();

        CATALOG.stubFor(WireMock.get(urlMatching("/api/v1/books/.*"))
            .willReturn(aResponse()
                .withFixedDelay(1500)
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{}")));
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("bookstore.catalog.base-url", () -> CATALOG.baseUrl());
    }

    @AfterAll
    static void stop() {
        if (CATALOG != null) {
            CATALOG.stop();
        }
    }

    @LocalServerPort
    int port;

    @Test
    void timeoutRoiCauDaoMo() throws Exception {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(httpClient);

        factory.setReadTimeout(Duration.ofSeconds(2));

        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(factory)
                .build();

        String body = "{\"customerId\":88,\"lines\":[{\"isbn\":\"9780134685991\",\"quantity\":1}]}";

        // Warm-up kết nối lần đầu tới server để tránh trễ khởi động
        try {
            client.get()
                    .uri("/actuator/health")
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ignored) {
            // Bỏ qua lỗi trong quá trình khởi động kết nối
        }

        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();

            var response = client.post()
                    .uri("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((req, res) -> res);

            long ms = (System.nanoTime() - t0) / 1_000_000;

            assertThat(response.getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

            assertThat(response.getHeaders().getFirst("Retry-After"))
                    .isEqualTo("10");

            assertThat(ms)
                    .as("mỗi lần phản hồi < 1400 ms")
                    .isLessThan(1400);
        }

        assertThat(CATALOG.findAll(
                getRequestedFor(urlMatching("/api/v1/books/.*"))
        )).hasSize(2);
    }
}
